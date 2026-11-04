# RAG-D23 全局桶代际构建改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D23 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D22 §7.2 待办 #1、RAG-D11（双作用域）、RAG-D02 §1.1（单向调用约定） |
| 改造目标 | 平台全局语料也能走代际构建（换嵌入模型） |

---

## 一、改造前的问题

RAG-D22 的 worker 在租户循环里**明确跳过全局哨兵**：

```java
if (RagScope.isGlobalTenant(tenant)) {
    log.warn("租户清单里的全局哨兵不走代际构建通道，已跳过：{}", tenant);
    continue;
}
```

结果：**平台语料无法换嵌入模型**——只能停机手工重导。
而全局语料是全部租户共享的，它的模型更换恰恰影响面最大。

---

## 二、为什么不能"把全局哨兵当租户传进去"

这里有一个**看起来很自然但会炸**的写法：

```java
// ❌ 错误做法
generationBuilder.buildBatch(RagScope.GLOBAL_TENANT_ID, generation);
```

两个致命问题：

| 问题 | 后果 |
|---|---|
| `set_config('app.tenant_id', <哨兵>)` | `rag_current_tenant()` **直接抛错**（V8 封堵"租户伪装成平台身份"的承重设计） |
| 应用连接无 BYPASSRLS | 即便不抛错，全局写也会被 RLS **静默滤成 0 行**——界面显示"构建完成"，实际什么都没写 |

**故通道选择必须走方法名，不能走参数值** —— 与 `ChunkIndexer#indexGlobalCorpus` 完全同款。

---

## 三、设计：双通道共用同一套流程

### 3.1 核心抽象：`ScanChannel`

```java
private record ScanChannel(
    JdbcTemplate template,        // 租户=应用连接；全局=运维连接
    TransactionTemplate tx,
    String scanSql,               // 租户=SCAN_SQL；全局=GLOBAL_SCAN_SQL
    String countSql,
    @Nullable String tenantId) {  // null = 全局桶

    void bindSession() {
        if (tenantId != null) {
            template.queryForObject("SELECT set_config('app.tenant_id', ?, true)", ...);
        }
        // ⚠️ 全局桶**刻意不设**会话租户
    }
}
```

`advanceOn(channel, tenantId, generation)` 是**通道无关的核心流程**，
租户桶与全局桶共用它——两者的唯一差异是"在哪条连接上跑"。
若各写一份，fail-closed 口径与游标推进逻辑必然漂移。

### 3.2 SQL 派生而非复制

```java
static final String GLOBAL_SCAN_SQL = SCAN_SQL.replace(
        "WHERE c.tenant_id = rag_current_tenant()",
        "WHERE c.tenant_id = rag_global_tenant_id()");
```

**⚠️ 派生是纪律，不是风格**：
两份 SQL 的唯一差异必须是**桶谓词**。而"哪些片段算合格"
（`src.ver_status='VERIFIED'` / `doc.ver_status='VERIFIED'` / `doc.retrievable`）
是必须全系统一致的治理口径。复制一份的话，将来改 fail-closed 条件只会改到租户那半，
**全局语料就会带着未校验内容进索引，且不报错**。

> 单测 `globalDiffersOnlyInBucketPredicate` 把这条钉成断言：
> 把两边的桶谓词都替换成 `BUCKET` 后，两个字符串必须**完全相等**。

### 3.3 全局写入走专属方法

```java
corpusRepository.upsertEmbeddingForGenerationInGlobalBucket(
        chunkId, vector, embedModel, generation);
```

| 特点 | 理由 |
|---|---|
| **无 tenantId 参数** | 全局写通道跑在 BYPASSRLS 的运维角色上，桶谓词写死 `rag_global_tenant_id()`。留租户参数＝留一句"把别的桶写进去"的合法调用 |
| 走 `inGlobalCorpusSession` | 运维会话窗内执行 |
| 显式 generation 参数 | 写 BUILDING 代际，不写 ACTIVE（同租户侧理由） |

---

## 四、改动清单

### 4.1 后端

| 文件 | 改动 |
|---|---|
| `rag-ports/.../CorpusRepository.java` | + `upsertEmbeddingForGenerationInGlobalBucket` |
| `rag-infrastructure/.../JdbcCorpusRepository.java` | 实现上述方法（运维会话窗 + 代际参数） |
| `rag-infrastructure/.../IndexGenerationBuilder.java` | + `ScanChannel`、`GLOBAL_SCAN_SQL`/`GLOBAL_COUNT_SQL`（派生）、`buildGlobalBatch`、`prepareGlobal`；`advanceOn` 抽为通道无关核心；构造器 + `opsSession` |
| `rag-worker/.../IndexGenerationBuildWorker.java` | 全局哨兵不再跳过，改调 `buildGlobalOnce()` |
| `rag-worker/.../WorkerInfraConfig.java` | 构建器 bean 注入 `ObjectProvider<RagOpsSessionPort>` |
| `rag-management/.../RagManagementConfig.java` | 同上 |
| `rag-management/.../RagManagementController.java` | build 端点：全局档走 `prepareGlobal` |

### 4.2 无数据库变更

代际表已支持全局桶（`scope` 列与 CHECK 约束在 RAG-D18 的 09 迁移中已就绪）。

### 4.3 无前端变更

前端 `RagReindexPage` 已按 `ctx.scope()` 透明支持全局档，无需改动。

---

## 五、验证结果

| 验证项 | 结果 |
|---|---|
| 后端 9 模块 | ✅ 全 SUCCESS |
| `IndexGenerationBuilderTest` | ✅ **13 项全绿**（+3 项全局桶） |
| rag-infrastructure | **105**（改造前 102，+3） |
| rag-core / ports / application / worker / api / management / bootstrap | 132 / 2 / 63 / 3 / 6 / 236 / 41 |
| 前端 `tsc` / 测试 | ✅ 0 error / 460 全绿 |

### 新增测试（3 项）

| 测试 | 守护的语义 |
|---|---|
| `globalSqlIsDerivedNotCopied` | 全局 SQL 含全局谓词、**不含**租户谓词，且三项治理条件齐备 |
| `globalDiffersOnlyInBucketPredicate` | **除桶谓词外两 SQL 完全一致**（fail-closed 口径不漂移） |
| `missingOpsSessionFailsLoudly` | 运维通道缺席时抛 `RagOpsUnavailableException`，**不静默回落** |

---

## 六、上线与边界

### 6.1 前置条件

全局桶代际构建需要**运维数据源**（`rag_ops`，`rag.ops.enabled=true`）。
未接入时：

- worker 记一条错误日志并继续处理租户桶（**可控降级**，不拖垮 worker）；
- HTTP 端点返回 503 语义的 `RagOpsUnavailableException`。

### 6.2 租户清单必须包含全局哨兵

worker 靠 `rag.indexer.tenants` 驱动。要让全局桶被处理，
清单里需**显式包含全局哨兵租户 ID**（与 `ReindexRunner` 的口径一致）。

> ⚠️ 这是配置项而非代码行为——若清单里没有哨兵，全局桶不会被构建，且**不会报错**。
> 上线时需确认配置。

### 6.3 未做（如实登记）

| # | 项 | 说明 |
|---|---|---|
| 1 | **全局桶的端到端实测** | 单测覆盖了 SQL 派生与通道选择，但未在真实 `rag_ops` 通道上跑通"创建→构建→切换" |
| 2 | **构建卡住告警** | RAG-D22 遗留项，仍未做（"卡住"与"很慢"界面上无法区分） |
| 3 | **多实例并发保护** | 仍依赖幂等，不做分布式锁 |

> 第 1 项是本轮最需要补的验证：运维通道的 `inGlobalCorpusSession` 涉及
> 会话窗与角色切换，单测无法覆盖其真实行为，**必须在测试库跑一遍**。

---

## 七、待办进度

| # | 待办 | 状态 |
|---|---|---|
| 1 版本五轴落库 | ✅ RAG-D16 |
| 2 召回漏斗计数 | ✅ RAG-D17 |
| 3 门禁阈值下发 | ✅ RAG-D20 |
| 4 版面事实持久化 | ✅ RAG-D19 |
| 5 索引代际蓝绿切换 | ✅ RAG-D18 |
| 6 上传前 dry-run 预览 | ⛔ 未做（优先级最低） |
| 7 字典类型扩展 | ✅ RAG-D20 |
| 8 知识点本体查询 | ✅ RAG-D20 |
| **9 代际构建异步化** | ✅ RAG-D22 |
| **10 全局桶代际构建** | ✅ 本文档 |
