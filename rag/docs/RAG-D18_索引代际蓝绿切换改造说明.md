# RAG-D18 索引代际蓝绿切换改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D18 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D13 §3.5.2、RAG-D03 §5（索引代际）、RAG-D15 §六 待办 #5 |
| 改造目标 | 换嵌入模型时的**零停机蓝绿切换**：旧代际继续服务，新代际建好再原子切换，可回滚 |

---

## 一、改造前的问题

### 1.1 现象：代际字段形同虚设

| 事实 | 位置 |
|---|---|
| `index_generation` 列存在 | `rag_chunk_embedding.index_generation`，默认 1 |
| 但**写死为 1** | `JdbcCorpusRepository` 三处 INSERT 全部硬编码 `1` |
| 检索侧**完全不看它** | `JdbcRetrievalRepository.denseSearch` 的 JOIN 无代际谓词 |
| 表注释却写着"支持蓝绿切换" | `COMMENT ON TABLE rag.rag_chunk_embedding` |

### 1.2 根因：物理上不支持双代际

```sql
CONSTRAINT rag_chunk_embedding_pkey PRIMARY KEY (chunk_id);
CONSTRAINT rag_embedding_tenant_chunk_unique UNIQUE (tenant_id, chunk_id);
```

**一个片段只能存一套向量**。换模型只能原地覆盖——
而新旧向量空间**不可比**（同一条文本在两个模型下的向量毫无关系）。

### 1.3 危害

若原地覆盖重建，重建期间检索会用"半新半旧"的混合索引算余弦相似度：
**静默产出错误分数，且没有任何报错**。这正是改造前只能"停机重导"的原因。

---

## 二、设计

### 2.1 表结构变更（`09_v1_0_3_index_generation_alter.sql`）

**① 新建 `rag_index_generation`**

| 列 | 说明 |
|---|---|
| `generation` | 代际号（从 1 递增，含已退役） |
| `embed_model` / `dimension` | 模型与维度 |
| `status` | `BUILDING` / `ACTIVE` / `RETIRED` |
| `indexed_chunks` / `total_chunks` | 构建进度 |
| 时间戳 | created / activated / retired |

**② 向量表主键改造**

```sql
-- 旧：一个片段一套向量
DROP CONSTRAINT rag_chunk_embedding_pkey;              -- (chunk_id)
DROP CONSTRAINT rag_embedding_tenant_chunk_unique;     -- (tenant_id, chunk_id)

-- 新：一个片段多代际并存
ADD CONSTRAINT rag_chunk_embedding_gen_pkey
    PRIMARY KEY (tenant_id, chunk_id, index_generation);
```

**⚠️ 为什么改主键而不是建新表**：检索要 JOIN 向量表取当前代际向量。
若每次换模型都新建一张"向量表"，检索 SQL 就得跟着改——那才是真正的灾难。
改主键后，检索只多一个谓词。

**⚠️ 不丢数据**：存量行 `generation` 均为 1，新约束下仍唯一。

**③ 存量代际记录**

为已有向量的租户建 `generation=1` 的 ACTIVE 记录，使**迁移后立即可检索**，无需重建索引。

### 2.2 状态机

```
BUILDING ──构建完成──▶ ACTIVE ──被新代际替换──▶ RETIRED
   │                     ▲                        │
   └──── 不可直接激活 ────┘                        └── 可回到 ACTIVE（回滚）
```

**⚠️ 三条硬约束**：

| 约束 | 违反后果 |
|---|---|
| **BUILDING 不可激活** | 只建了一半的代际上线 → 检索查一半空向量 → "有些片段永远召回不到"，无报错 |
| **任何时刻至多一个 ACTIVE** | 两个 ACTIVE → 检索不确定读哪套 |
| **切换在同一事务内完成** | 拆开会出现"零个 ACTIVE"中间态 → 那一瞬全站搜不到东西 |

### 2.3 检索侧：按 ACTIVE 代际过滤

```sql
JOIN rag_chunk_embedding e
     ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
   AND (
         -- 该租户有 ACTIVE 代际 → 只读它
         e.index_generation = (SELECT g.generation FROM rag_index_generation g
                                WHERE g.tenant_id = e.tenant_id AND g.status = 'ACTIVE' LIMIT 1)
         -- 该租户尚无代际记录（迁移前空库）→ 不过滤（兼容存量）
         OR NOT EXISTS (SELECT 1 FROM rag_index_generation g2
                         WHERE g2.tenant_id = e.tenant_id AND g2.status = 'ACTIVE')
       )
```

**⚠️ 用按行解析的子查询而非单一参数**：查询作用域是「本租户 ∪ 全局哨兵」，
两个租户可能处于不同代际（全局已切到 3、本租户还在 2）。

> 初版曾用 `COALESCE(子查询, e.index_generation)` 做兜底，但它在
> "有多代际且该租户无 ACTIVE 记录"时会匹配**所有**代际。已改为 `NOT EXISTS` 写法。

### 2.4 写入侧：写入 ACTIVE 代际

三处 INSERT（片段行 / 向量行 / 全局桶）统一用：

```sql
COALESCE((SELECT g.generation FROM rag_index_generation g
           WHERE g.tenant_id = <bucket> AND g.status = 'ACTIVE' LIMIT 1), 1)
```

| 决策 | 理由 |
|---|---|
| **写 ACTIVE 而非写死 1** | 写死 1 意味着换模型后新向量仍进代际 1，与旧模型向量混在同一个空间——不可比，静默出错 |
| **缺省回落 1 而非报错** | 迁移前空库无代际记录。回落 1 与旧行为一致；报错会让迁移完成前所有入库失败 |
| **在 SQL 里取而非查一遍再传参** | "先查后写"在并发切换时读写不一致（查出 2、写入时已是 3）；子查询由语句执行时刻快照决定 |

`ON CONFLICT` 同步改为 `(tenant_id, chunk_id, index_generation)`——与新主键匹配。

---

## 三、后端改动

| 文件 | 改动 |
|---|---|
| `deploy/sql/rag/09_v1_0_3_index_generation_alter.sql` | **新增**：代际表 + 主键改造 + RLS 策略 + 存量代际记录 |
| `rag-core/.../indexing/IndexGeneration.java` | **新增**：领域模型（状态机、`isActivatable`、`progressPercent`） |
| `rag-ports/.../IndexGenerationPort.java` | **新增**：端口（解析/列表/创建/激活/进度） |
| `rag-management/.../infrastructure/JdbcIndexGenerationRepository.java` | **新增**：JDBC 实现（激活事务性、幂等） |
| `rag-management/.../infrastructure/RagManagementConfig.java` | + 代际端口 bean |
| `rag-management/.../api/RagManagementController.java` | + 3 个端点；代际端口可选注入（未装配返回 501） |
| `rag-management/.../api/ManagementDtos.java` | + `IndexGenerationResponse` / `CreateIndexGenerationRequest` |
| `rag-infrastructure/.../persistence/JdbcCorpusRepository.java` | 3 处写入改为 ACTIVE 代际；`ON CONFLICT` 对齐新主键 |
| `rag-infrastructure/.../persistence/JdbcRetrievalRepository.java` | denseSearch 加代际过滤 |

### 新增端点

| 方法 | 路径 | 授权 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/management/index-generations` | 读上下文 | 列出代际 |
| `POST` | `/api/v1/management/index-generations` | **写上下文**（平台档需票据） | 新建（BUILDING） |
| `POST` | `/api/v1/management/index-generations/{generation}/activate` | **写上下文** | 切换/回滚 |

> 激活时目标为 BUILDING → **409**（状态不允许，不是参数错）。

---

## 四、前端改动

`RagReindexPage` 从"后端待接入"占位改为真蓝绿交互：

| 区域 | 内容 |
|---|---|
| 当前服务代际 | 代际号 / 模型 / 维度 / 已索引片段 / "参与检索"标签 |
| 代际列表 | 状态标签（绿=ACTIVE / 蓝=RETIRED / 处理中=BUILDING）、构建进度条、操作列 |
| 操作 | **BUILDING 置灰**（tooltip "构建中的代际不可激活"）；ACTIVE 显示"当前服务中"；RETIRED 显示"回滚"按钮 + Popconfirm |
| 数据修补 | 保留原"补齐缺失向量"，并明确区分："这是修补不是换模型" |

**⚠️ 501 特殊处理**：后端未装配代际能力时返回 501，界面显示
"这是部署缺口，不是请求错误"——避免运维去排查一个不存在的问题。

---

## 五、验证结果

| 验证项 | 结果 |
|---|---|
| 后端多模块编译（8 模块） | ✅ SUCCESS |
| `IndexGenerationTest` | ✅ **8 项全绿** |
| rag-core | 127 项（改造前 119，+8） |
| rag-api / infrastructure / management / bootstrap | 6 / 63 / 236 / 41 全绿 |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ 44 文件 / 460 用例全绿 |

### 新增测试覆盖（8 项）

| 分组 | 测试 | 守护的语义 |
|---|---|---|
| 可激活性 | `buildingIsNotActivatable` | **BUILDING 不可激活**（防半成品上线） |
| | `retiredIsActivatable` | RETIRED 可激活（回滚基础） |
| | `activeIsActivatableAndRetrievable` | ACTIVE 幂等切换 |
| 进度 | `computesPercent` | 进度计算正确 |
| | `zeroTotalDoesNotDivideByZero` | 总数为 0 不除零 |
| 不变式 | `generationMustBePositive` | 代际号 >= 1 |
| | `indexedCannotExceedTotal` | 进度自洽 |
| | `embedModelRequired` | 模型标识非空 |

---

## 六、上线顺序与风险

### 6.1 上线顺序（**不可颠倒**）

```
1. 执行 09_v1_0_3_index_generation_alter.sql
2. 部署应用
```

**⚠️ 顺序理由**：应用新增了
`AND e.index_generation = (SELECT ... FROM rag_index_generation ...)` 谓词。
若表未迁移，这个子查询会因**表不存在**而让**所有检索报错**——那是全站不可用。

### 6.2 迁移风险

| 风险 | 处置 |
|---|---|
| **改主键是 DDL 重锁表** | 向量表在万级以内锁表时间极短；但**大表需在低峰期执行**，必要时用 `CREATE INDEX CONCURRENTLY` 思路分步 |
| 主键变更期间并发写入 | 建议迁移时暂停入库任务（worker） |
| 回滚脚本会删多代际向量 | 脚本已注明"仅在确认只有一个代际时可用" |

### 6.3 构建器（已交付，见 §八）

| # | 项 | 状态 |
|---|---|---|
| 1 | **代际构建器** | ✅ 已交付（`IndexGenerationBuilder`，见 §八） |
| 2 | **构建进度更新** | ✅ 已交付（每批写完后更新 `indexed_chunks`） |
| 3 | **退役代际清理** | ⛔ 未做：无删除端点，多代际会持续占用存储 |
| 4 | **维度变更的前置校验** | ⛔ 未做：新代际维度与 `embedding` 列不符时，写入在 DB 层报错 |

---

## 七、改动文件清单

```
deploy/sql/rag/09_v1_0_3_index_generation_alter.sql                (新增)
rag/rag-core/src/main/java/cn/studykid/rag/core/indexing/IndexGeneration.java   (新增)
rag/rag-ports/src/main/java/cn/studykid/rag/ports/IndexGenerationPort.java      (新增)
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcIndexGenerationRepository.java  (新增)
rag/rag-core/src/test/java/cn/studykid/rag/core/IndexGenerationTest.java        (新增)

rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java
rag/rag-management/src/main/java/cn/studykid/rag/management/api/ManagementDtos.java
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/RagManagementConfig.java
rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java
rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcRetrievalRepository.java

admin-console/src/api/ragTypes.ts
admin-console/src/api/ragClient.ts
admin-console/src/pages/rag/RagReindexPage.tsx

# ---- 代际构建器（§八）----
rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/indexing/IndexGenerationBuilder.java  (新增)
rag/rag-infrastructure/src/test/java/cn/studykid/rag/infrastructure/indexing/IndexGenerationBuilderTest.java  (新增)
rag/rag-ports/src/main/java/cn/studykid/rag/ports/CorpusRepository.java
    + upsertEmbeddingForGeneration（显式代际写入）
rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java
    + upsertEmbeddingForGeneration 实现
rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java
    + POST /index-generations/{generation}/build
    + BuildGenerationResponse
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/RagManagementConfig.java
    + IndexGenerationBuilder bean（@ConditionalOnBean）
```

---

## 八、代际构建器（RAG-D13 §3.5.2 的「绿」）

### 8.1 为什么需要它

§二 建立的代际表与切换 API 只是"骨架"：新建的代际是 **BUILDING 空壳**，
没有任何东西把片段重新嵌入进去。没有构建器，点"新建代际"后进度永远停在 0。

### 8.2 与 `ChunkIndexer` 的分工（**语义完全不同，不可混用**）

| | `ChunkIndexer` | `IndexGenerationBuilder` |
|---|---|---|
| 选哪些片段 | **缺**向量或词法列的 | **全部**合格片段（不管有没有向量） |
| 写哪个代际 | ACTIVE（日常入库） | **指定的 BUILDING 代际** |
| 何时用 | 补数据缺口 | **换模型** |

> 用错会导致：拿 `ChunkIndexer` 换模型 → 新向量写进 ACTIVE 与旧向量混在一个空间，
> 余弦相似度给出无意义分数且**无任何报错**。

### 8.3 关键设计

**① 只允许构建 BUILDING 代际**

```java
requireBuildingStatus(tenantId, generation);
// ACTIVE  → 原地重建（正是蓝绿要避免的）
// RETIRED → 污染回滚目标（切回去得到半新半旧的索引）
// 两者都不报错，只会让切换/回滚在事后变成事故 → 直接抛 IllegalStateException
```

**② fail-closed 口径与 `ChunkIndexer` 逐字一致**

`SCAN_SQL` 与 `PENDING_SQL` 的**唯一差异是不含「缺向量」条件**，
但 `src.ver_status='VERIFIED'` / `doc.ver_status='VERIFIED'` / `doc.retrievable`
三项**完全相同**。否则会出现「日常索引不进未校验语料，换模型却进了」的口径裂缝。
> 单测 `scanSqlKeepsFailClosedPredicates` 把这条钉成断言。

**③ 进度分批更新**

| 策略 | 问题 |
|---|---|
| 逐条更新 | N 次额外写 |
| 只在最后更新 | 中途崩溃时进度停在 0，运维无法判断"跑到哪了" |
| **每批更新（采用）** | 写放大可控，中断后进度可信 |

**④ 不重切词法列**

`text_jieba` 与嵌入模型无关。换模型动它只会让词法索引短暂不可用。

**⑤ 新增端口方法 `upsertEmbeddingForGeneration`**

原 `upsertEmbedding` 语义是「写进当前 ACTIVE 代际」——那是日常入库的正确行为，
但构建器要写 BUILDING 代际。故新增显式代际参数的方法：
**代际号由调用方给出，仓储层不猜**（猜错就是写进错误的空间）。

### 8.4 新增端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/management/index-generations/{generation}/build` | 触发构建（同步执行） |

> 目标非 BUILDING → **409**（状态冲突，不是参数错）。

### 8.5 验证

| 验证项 | 结果 |
|---|---|
| `IndexGenerationBuilderTest` | ✅ **7 项全绿** |
| rag-infrastructure | 99 项（改造前 92，+7） |
| 后端 8 模块 | 全 SUCCESS |
| 前端 `tsc` / 测试 | ✅ 0 error / 460 全绿 |

新增测试覆盖：

| 分组 | 测试 | 守护的语义 |
|---|---|---|
| 状态守卫 | `rejectsActive` | ACTIVE 拒绝构建（防原地重建） |
| | `rejectsRetired` | RETIRED 拒绝构建（防污染回滚目标） |
| | `rejectsMissing` | 代际不存在明确报错 |
| 单片段写入 | `writesToGivenGeneration` | **写入的是指定代际而非 ACTIVE** |
| | `doesNotTouchJiebaColumns` | 不重切词法列 |
| fail-closed | `scanSqlKeepsFailClosedPredicates` | 三项治理条件与日常索引一致 |
| | `scanSqlDoesNotFilterByExistingEmbedding` | 无「缺向量」条件（换模型要全量重算） |

### 8.6 仍需注意

| 项 | 说明 |
|---|---|
| **同步执行** | `build` 端点同步跑完该租户全部片段。数万片段会阻塞数十分钟——生产应放在 worker 后台线程或分批调用 |
| **无并发保护** | 两个请求同时 build 同一代际会重复嵌入（幂等但浪费算力）。未加分布式锁 |
| **全局桶未纳入** | 构建器只处理租户桶；全局语料代际构建需另走运维通道（同 `ChunkIndexer.indexGlobalCorpus` 的模式） |
| **退役代际未清理** | 多代际会持续占用存储，无删除端点 |
