# RAG-D19 版面事实持久化改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D19 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D13 §3.2.4（片段校对）、RAG-D04 §3.1（角色判定表）、RAG-D15 §六 待办 #4 |
| 改造目标 | 把角色判定的**版面事实**持久化，让校对员能看到"系统依据什么下的结论" |

---

## 一、改造前的问题

### 1.1 现象：判定依据在落库时被丢弃

角色判定是**基于版面事实的确定性规则**（RAG-D04 §3.1）：

| 版面事实 | 判定 |
|---|---|
| 在答案区 + 有题号 | `EXERCISE_ANSWER` |
| 在答案区 + 无题号 | `EXAMPLE_SOLUTION` |
| 块类型 `ANSWER` | `EXERCISE_ANSWER` |
| 块首命中「解：/答：/Answer:」等前缀 | 有题号=答案 / 无题号=例题解答 |
| 其余 | `EXPLANATION` |

这些事实在 `LayoutBlock` 里**全都有**（`inAnswerZone` / `type` / `exerciseNo`），
但切分产出 `Chunk` 时**只保留了最终 `role`**，三个事实被丢弃。

### 1.2 危害：校对员无从判断

`rag_chunk` 表里只有 `role` 一列。校对员看到"这条被判成讲解"时：

- **不知道**系统是因为"不在答案区"还是"没命中前缀"才这么判；
- 只能**凭感觉改**，改完也无从复核；
- 而误判为讲解的最坏后果是**泄题**（答案在讲解模式下被召回）。

> 改造前片段校对页只能显示页码/题号/章节路径——**恰恰缺了判定依据本身**。

---

## 二、设计

### 2.1 领域模型：`LayoutFacts`

```java
record LayoutFacts(
    boolean inAnswerZone,     // 是否位于答案区
    @Nullable String blockType,      // BlockType 枚举名
    @Nullable String matchedPrefix)  // 块首命中的前缀原文（如「解:」）
```

**三条设计决策**：

| 决策 | 理由 |
|---|---|
| **存快照而非每次重算** | 解析器版本升级后同一片段重算结果可能不同。存快照才能解释"历史上为什么那样判"；重算会抹掉这段历史 |
| **`matchedPrefix` 存命中的具体词**，而非布尔 | 校对员要能一眼看到"是哪个前缀让系统判成答案的"。布尔只说"命中了"，说不出"命中什么" |
| **三态而非两态** | `blockType == null` 表示**未采集**，与"确认不在答案区"是两件事。混为一谈会误导校对员（把"没信息"读成"系统确认过"） |

### 2.2 采集点：切分时同步记录

`IngestionPipeline` 在生成 `Chunk` 时采集：

```java
LayoutFacts facts = new LayoutFacts(
        block.inAnswerZone(),
        block.type() == null ? null : block.type().name(),
        matchedSolutionPrefix(block.text()));
```

**⚠️ `matchedSolutionPrefix` 单独立方法而不是内联**：
判定与记录必须用**同一份正则**（`SOLUTION_PREFIX`）。
若采集时另写一个正则，判定改了采集没改，校对页就会展示一个与真实判定依据不符的前缀
——**那比不显示更糟**。

### 2.3 数据库：三列 + 部分索引

```sql
ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS in_answer_zone boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS block_type text,
    ADD COLUMN IF NOT EXISTS matched_prefix text;

-- 校对页按「在答案区」筛的查询索引
CREATE INDEX rag_chunk_answer_zone_idx
    ON rag.rag_chunk (tenant_id, document_id, in_answer_zone)
    WHERE in_answer_zone = true;
```

**⚠️ 三列可空 / 有默认值**：存量片段没有这些信息。
界面显示"该片段早于版面事实采集，无判定依据"——**不伪造一个「不在答案区」**。

---

## 三、改动清单

### 3.1 后端

| 文件 | 改动 |
|---|---|
| `deploy/sql/rag/10_v1_0_3_layout_facts_alter.sql` | **新增**：三列 + 部分索引 |
| `rag-core/.../chunk/LayoutFacts.java` | **新增**：领域模型（`isEmpty` / `describe` / 归一） |
| `rag-core/.../chunk/Chunk.java` | + `layoutFacts` 字段（旧 9 参构造器保留） |
| `rag-application/.../ingest/IngestionPipeline.java` | 切分时采集；新增 `matchedSolutionPrefix` |
| `rag-infrastructure/.../JdbcCorpusRepository.java` | INSERT 写入三列 + `ON CONFLICT` 同步更新 |
| `rag-management/.../ports/CorpusManagementPort.java` | `ChunkSummary` + 三字段 |
| `rag-management/.../JdbcCorpusManagementRepository.java` | 2 处 SELECT + 2 处映射 |
| `rag-management/.../api/ManagementDtos.java` | `ChunkResponse` + 三字段 |

> 全部向下兼容：`Chunk` 保留 9 参构造器，`ChunkSummary` 只在内部使用。

### 3.2 前端

| 文件 | 改动 |
|---|---|
| `src/api/ragTypes.ts` | `RagChunk` + `blockType` / `inAnswerZone` / `matchedPrefix`（可选） |
| `src/pages/rag/RagChunkFixPage.tsx` | 版面事实表从静态占位改为**真实判据** |

校对页新增三行，且**区分"未采集"与"否"**：

| 字段 | 有值时的展示 | 未采集时的展示 |
|---|---|---|
| 是否在答案区 | `在答案区`（红）/ `不在答案区` | `未采集`（灰） |
| 块类型 | `PARAGRAPH` 等 Tag | `未采集` |
| 块首前缀命中 | `「解:」`（橙 Tag） | `未命中 / 未采集` |

---

## 四、验证结果

| 验证项 | 结果 |
|---|---|
| 后端 8 模块 | ✅ 全 SUCCESS |
| `LayoutFactsTest` | ✅ **5 项全绿** |
| rag-core | 132 项（改造前 127，+5） |
| rag-api / infra / management / bootstrap | 6 / 99 / 236 / 41 全绿 |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ 44 文件 / 460 用例全绿 |

### 新增测试覆盖（5 项）

| 测试 | 守护的语义 |
|---|---|
| `allNullIsEmpty` | 全空即为未采集，描述文案为"无判定依据" |
| `anyFactMakesItNonEmpty` | 任一事实非空即非未采集 |
| `describesAnswerZone` | 答案区片段描述完整（含前缀） |
| `describesPlainBody` | 正文片段如实描述（不显示"命中前缀"） |
| `blankBecomesNull` | 空白串归一为 null，避免界面显示空标签 |

---

## 五、上线与边界

### 5.1 上线顺序

```
1. 执行 10_v1_0_3_layout_facts_alter.sql
2. 部署应用
```

**⚠️** 应用写入会带这三列；若表未迁移，入库会因列不存在而失败。

### 5.2 未做（如实登记）

| # | 项 | 说明 |
|---|---|---|
| 1 | **存量片段未回填** | 旧片段 `block_type` 为 NULL，显示"未采集"。回填需重新解析原文档（重跑 ingestion），代价大且可能改变现有角色 |
| 2 | **校对页无「按答案区」筛选** | 后端索引已建，但前端筛选器未接 `in_answer_zone=true`（可作为"疑似误判"视图的入口） |
| 3 | **`UpdateChunkRoleRequest` 未记录判据变更历史** | 重判只改 `role`，不记录"从什么角色改到什么、依据哪条判据"——审计留痕只在 `INGEST` 动作里 |

> 第 2 项改动很小（前端加个筛选参数 + 后端加个查询条件），可作为校对效率的直接提升。
> 第 3 项若要完整审计链路，需新增 `rag_chunk_role_history` 表。
