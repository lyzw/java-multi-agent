# RAG-D22 代际构建器异步化改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D22 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D18 §8.6（同步执行是已知限制）、RAG-D21 §六 P1 |
| 改造目标 | 代际构建从「HTTP 线程同步跑完」改为「worker 轮询 + 分批推进」，可续跑、不阻塞 |

---

## 一、改造前的问题

RAG-D18 交付的构建器虽然在功能上闭环，但 `POST /index-generations/{g}/build`
**在 HTTP 线程上一次性跑完全部片段**：

| 问题 | 后果 |
|---|---|
| 数万片段阻塞数十分钟 | 请求超时、网关断连 |
| 进程重启后进度丢失 | 只能从头再来 |
| 无持久游标 | 无法续跑 |
| 占用 HTTP 线程 | 同一实例的其他请求受影响 |

> RAG-D18 §8.6 已如实登记为「同步执行」限制，本次是补齐。

---

## 二、设计：worker 轮询 + 分批推进

```
POST /index-generations/{g}/build   ← 只做"排队"：统计总数、清零游标 → 立即返回
        ↓
rag_index_generation (BUILDING, total=N, cursor=NULL)
        ↓
IndexGenerationBuildWorker（每 2 秒）
   └─ findPendingBuild(tenant)          ← 找 BUILDING 且未建完的代际
        └─ buildBatch(tenant, gen)      ← 处理一批（batchSize=50）
             ├─ 从 build_cursor 续扫
             ├─ 嵌入 + 写入指定代际
             └─ 更新进度 + 游标（一次写完）
        ↓
下一轮继续...直到扫不到新片段 → 完成
```

### 2.1 关键设计：持久化键集游标

新增列 `build_cursor`（text），记录"上一个已处理的 chunk_id"。

**⚠️ 为什么不能用 OFFSET 或 `indexed_chunks` 当偏移**：

构建期间片段可能被撤回或新增：
- `OFFSET N` —— 前面删了行，后面整体**错位，跳过片段**；
- 用 `indexed_chunks` 当偏移 —— 语义是"已写入行数"不是"扫到哪"，同样错位。

结果都是"有些片段永远没有新代际的向量"，**且无任何报错**。

### 2.2 进度与游标必须一次写完

```java
void updateBuildState(tenantId, generation, indexedChunks, totalChunks, buildCursor)
```

**⚠️ 拆成两次写会在崩溃时留下矛盾状态**：
worker 会从"进度说有 500、游标还停在第 0 条"的状态续跑，导致重复嵌入或跳过片段。

### 2.3 「待构建」的判定

```sql
WHERE status = 'BUILDING'
  AND (indexed_chunks < total_chunks OR total_chunks = 0)
ORDER BY generation ASC
```

**⚠️ `total_chunks = 0` 必须单独放进来**：新建代际的 total 就是 0，
而 `0 < 0` 为假——不单独处理的话，**新建的代际永远不会被 worker 捡起**。

---

## 三、架构调整：仓储实现下沉

`JdbcIndexGenerationRepository` 从 `rag-management` **下沉到 `rag-infrastructure`**。

**⚠️ 为什么必须下沉**：

| 模块 | 依赖 |
|---|---|
| `rag-worker` | 依赖 `rag-core / ports / application / infrastructure` |
| `rag-worker` | **不依赖** `rag-management` |
| `rag-infrastructure` | **不依赖** `rag-management`（反向） |

worker 需要装配代际端口，而原实现位于 management → **编译不过**。
下沉到 infrastructure 后，management 与 worker 都能引用，
且与 `ChunkIndexer`（同样需要被两处共用）保持同一处理方式。

---

## 四、改动清单

### 4.1 数据库

| 文件 | 改动 |
|---|---|
| `deploy/sql/rag/11_v1_0_3_generation_build_cursor_alter.sql` | **新增**：`build_cursor` 列 + 待构建查询索引 |

### 4.2 后端

| 文件 | 改动 |
|---|---|
| `rag-core/.../indexing/IndexGeneration.java` | + `buildCursor` 字段（兼容构造器）+ `needsBuild()` |
| `rag-ports/.../IndexGenerationPort.java` | + `updateBuildState`、`findPendingBuild` |
| `rag-infrastructure/.../indexing/JdbcIndexGenerationRepository.java` | **从 management 移入**；实现游标读写与待构建查询 |
| `rag-infrastructure/.../indexing/IndexGenerationBuilder.java` | + `prepare()`（只排队）、`buildBatch()`（推进一批）；`build()` 保留给测试 |
| `rag-worker/.../IndexGenerationBuildWorker.java` | **新增**：`@Scheduled` 轮询器 |
| `rag-worker/.../WorkerInfraConfig.java` | + 代际端口与构建器 bean |
| `rag-management/.../api/RagManagementController.java` | build 端点改为**只排队、立即返回** |
| `rag-management/.../infrastructure/RagManagementConfig.java` | 引用下沉后的类 |

### 4.3 前端

| 文件 | 改动 |
|---|---|
| `RagReindexPage` | 提示从"构建完成"改为**"已排队，可稍后刷新查看进度"** |

> ⚠️ 这点必须改：异步后端点返回时进度仍是 0，若还提示"构建完成"就是**说谎**。

---

## 五、worker 轮询的三条纪律

```java
@Scheduled(fixedDelayString = "${rag.generation.build.poll-interval-ms:2000}", initialDelay = 10000)
```

| 纪律 | 理由 |
|---|---|
| **`fixedDelay` 而非 `fixedRate`** | 一批可能跑数秒到数分钟；fixedRate 会让下一轮追赶上一轮，堆积并发线程 |
| **异常绝不让调度线程死掉** | 单批失败只记日志、下一轮重试。否则一次数据库抖动会**永久停止所有代际构建**（且表面无异常） |
| **租户清单由配置显式给出** | worker 以非特权角色连接，代际表受 RLS FORCE；跨租户扫描会"看起来在跑、实际永远领不到"（与 `IngestJobConsumer` 同口径） |

### 关于多实例并发（如实说明）

多 worker 实例可能同时捡到同一个代际。当前**不做分布式锁**，依赖幂等：

- `upsertEmbeddingForGeneration` 是 `ON CONFLICT DO UPDATE`，重复嵌入只是浪费算力；
- 代价是算力浪费，收益是不引入额外中间件（与 RAG-D02「零新增中间件」一致）。

> 若后续 worker 多副本且算力敏感，应改为**按代际号取模分片**，而不是加锁。

---

## 六、验证结果

| 验证项 | 结果 |
|---|---|
| 后端 9 模块编译 | ✅ BUILD SUCCESS |
| `IndexGenerationBuilderTest` | ✅ **10 项全绿**（+3 项异步分批） |
| rag-core / ports / application | 132 / 2 / 63 |
| rag-infrastructure | **102**（改造前 99，+3） |
| rag-worker | **3**（新增，轮询器装配验证） |
| rag-api / management / bootstrap | 6 / 236 / 41 |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ 44 文件 / 460 用例全绿 |

### 新增测试（3 项）

| 测试 | 守护的语义 |
|---|---|
| `prepareOnlyMarks` | **prepare 阶段绝不执行嵌入**（只排队） |
| `prepareRejectsActive` | prepare 与 buildBatch 的状态守卫一致 |
| `buildBatchRejectsNonBuilding` | buildBatch 同样拒绝非 BUILDING |

---

## 七、上线顺序与边界

### 7.1 上线顺序

```
1. 执行 09_v1_0_3_index_generation_alter.sql   （若未执行）
2. 执行 10_v1_0_3_layout_facts_alter.sql       （若未执行）
3. 执行 11_v1_0_3_generation_build_cursor_alter.sql
4. 部署应用（bootstrap + worker）
```

**⚠️ 必须同时部署 worker**：bootstrap 只负责"排队"，没有 worker 就永远不推进。

### 7.2 未做（如实登记）

| # | 项 | 说明 |
|---|---|---|
| 1 | **全局桶代际构建** | worker 明确跳过全局哨兵；平台语料换模型目前无路径 |
| 2 | **构建完成的主动通知** | 靠前端手动刷新；无 SSE/WebSocket 推送 |
| 3 | **卡住的构建** | 若 worker 长期宕机，代际会一直停在 BUILDING 且进度不动，无告警 |
| 4 | **多实例并发保护** | 依赖幂等，不做分布式锁（见 §五） |

> 第 3 项建议后续加：连续 N 轮进度不变则记录告警日志——
> 否则"构建卡住"与"构建很慢"在界面上无法区分。
