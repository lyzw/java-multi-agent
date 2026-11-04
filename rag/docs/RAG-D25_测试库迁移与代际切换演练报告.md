# RAG-D25 测试库迁移与代际切换端到端演练报告

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D25 |
| 版本/状态 | v1.0 / 实测报告 |
| 日期 | 2026-11-04 |
| 环境 | 本地 `rag-pg-s2`（PostgreSQL 17.11 + pgvector 0.8.7）→ `ragdb` |
| 结论 | 迁移全部成功；演练暴露并修复 **5 个真实缺陷**（单测覆盖不到） |

---

## 一、迁移执行结果

### 1.1 环境与前置状态

| 项 | 实测值 |
|---|---|
| 数据库 | `ragdb`（本地容器 `rag-pg-s2`，端口 55435） |
| pgvector | 0.8.7 ✅ |
| 迁移前状态 | 停在 07 之后——**08 也未执行**（`rag_reviewer_roster` 缺失） |
| 数据量 | chunk=28 / embedding=20 / document=8 / source=3 / edition=3 |

### 1.2 执行结果

| 迁移 | 内容 | 结果 | 耗时 |
|---|---|---|---|
| 08 | 审核员名录表 | ✅ | — |
| 09 | 代际表 + **向量表主键改造** | ✅ | **70ms** |
| 10 | 版面事实三列 + 部分索引 | ✅ | — |
| 11 | 构建游标 + 待构建索引 | ✅ | — |

### 1.3 关键验证

| 验证项 | 结果 |
|---|---|
| 主键变更 | `(chunk_id)` → `(tenant_id, chunk_id, index_generation)` ✅ |
| 存量向量归档 | 20 条自动挂到 `generation=1` ACTIVE ✅ |
| 数据零丢失 | chunk=28、embedding=20 与迁移前一致 ✅ |
| 代际 JOIN 不误伤 | 返回 20 行，与迁移前相同 ✅ |
| **幂等性** | 09/10/11 重放均通过，无副作用 ✅ |
| 列可空性 | `block_type`/`matched_prefix`/`build_cursor` 均 YES，与设计一致 ✅ |

> **锁表风险实测**：改主键仅 **70ms**（28 片段）。但这是小数据量结论，
> 生产万级片段仍需低峰期执行。

### 1.4 备份

```
backup/rag-migration-20261104-210858/
├── ragdb-rag-schema.sql   (1.1MB, 迁移前完整 schema)
└── pre-counts.txt         (行数快照)
```

---

## 二、端到端演练：暴露并修复的 5 个缺陷

> ⚠️ **这 5 个缺陷全部由真实库演练暴露，单元测试无法发现**
> ——因为单测用 mock 的 `JdbcTemplate`，覆盖不到 RLS 策略与事务边界。

### 缺陷 1：`listGenerations` 未绑定会话租户 → 恒返回空

| 项 | 内容 |
|---|---|
| 现象 | 代际列表返回 `[]`，而库里明明有 `generation=1 ACTIVE` |
| 根因 | RLS 读策略是 `tenant_id IN (rag_current_tenant(), rag_global_tenant_id())`；未设会话变量时 `rag_current_tenant()` 返回 NULL → **本租户行被整行过滤** |
| 原注释错在哪 | 写着"用调用方给的租户即可，不设会话变量"——**这个假设是错的** |
| 修复 | 在事务内 `bindTenant(tenantId)` |

### 缺陷 2：`createGeneration` 未绑定会话租户 → 500

| 项 | 内容 |
|---|---|
| 现象 | `POST /index-generations` 返回 500 |
| 根因 | `new row violates row-level security policy for table "rag_index_generation"` |
| 修复 | 同缺陷 1，写路径同样需要 `bindTenant` |

### 缺陷 3：`upsertEmbeddingForGeneration` 缺 `@Transactional` → worker 无限重试

| 项 | 内容 |
|---|---|
| 现象 | worker 每 2 秒报一次 RLS 拒绝，代际永远建不完 |
| 根因 | `set_config(..., true)` 是**事务局部**的；方法无 `@Transactional` → autocommit 下 set_config 当场失效 → INSERT 失去会话租户 |
| 修复 | 加 `@Transactional`（与 `saveChunkAndEmbedding` 同一手段） |

### 缺陷 4：构建无"完成"标记 → 代际停在 5/5 无法切换

| 项 | 内容 |
|---|---|
| 现象 | 构建到 `5/5` 后 worker 不再处理，代际永远停在 BUILDING |
| 根因 | `findPendingBuild` 用 `indexed < total OR total = 0` 判断；`5<5` 为假 → 不再捡起 → 走不到"扫空集确认"分支 |
| 修复 | ① 构建器扫到空集时调 `markBuildFinished`（写 `note='构建已完成'`）<br>② `findPendingBuild` 改为**「未标记完成」即待构建**——一条条件同时覆盖"建到一半"/"待收尾"/"刚创建" |

> 为什么不用 `indexed == total` 判断完成：计数相等有两种含义
> ——"刚建完未确认" 与 "确实建完"，必须用显式标记区分。

### 缺陷 5：`isActivatable()` 一刀切 → 构建完成的代际永远无法切换

| 项 | 内容 |
|---|---|
| 现象 | 构建已标记完成，切换接口仍返回 409「构建中的代际不可激活」 |
| 根因 | `isActivatable()` 只允许 `ACTIVE / RETIRED`，而构建完成后 status **仍是 BUILDING**（构建完成 ≠ 自动上线） |
| 修复 | BUILDING 且 `buildFinished()` 也可激活；未建完的仍拒绝（防半成品上线） |

---

## 三、演练结果：六步全通

| 步骤 | 操作 | 结果 |
|---|---|---|
| ① | 新建代际 | ✅ `gen=2`，`status=BUILDING`，`activatable=false` |
| ② | 触发构建（异步排队） | ✅ **31ms 返回**（改造前阻塞数分钟），`total=5` |
| ③ | worker 分批推进 | ✅ 处理 5 段，无异常 |
| ③' | 构建收尾 | ✅ `5/5`，`note=构建已完成` |
| ④ | 未完成时尝试切换 | ✅ **正确拒绝 409**（防半成品上线） |
| ⑤ | 切换到代际 2 | ✅ `gen2=ACTIVE`、`gen1=RETIRED`，**时间戳一致（同事务）** |
| ⑥ | 回滚到代际 1 | ✅ `gen1=ACTIVE`、`gen2=RETIRED` |

### 关键佐证

**双代际共存成立**（改造前物理上不可能）：

```
rag_chunk_embedding:
  generation=1 → 25 行
  generation=2 → 5 行
```

**切换原子性**：

```sql
1|RETIRED|retired_at=2026-11-04 13:30:48.796125
2|ACTIVE |activated_at=2026-11-04 13:30:48.796125
                ↑ 时间戳完全相同 → 同一事务
```

**检索作用域随切换变化**：切换后检索 JOIN 返回 5 行（代际 2 的向量），回滚后恢复 20 行。

---

## 四、过程中踩到的环境问题（非代码缺陷）

| # | 问题 | 处置 |
|---|---|---|
| 1 | `java` 不在 PATH | 用 Homebrew JDK 绝对路径 `/opt/homebrew/Cellar/openjdk/27/.../bin/java` |
| 2 | 启动被 `ragJwtAuthFilter` 拒绝（未配公钥） | **这是正确的 fail-closed**；加载 `jwt.env` 提供公钥 |
| 3 | 启动被 `RagStartupSelfCheck` 拒绝（用 `postgres` 超级用户） | **这同样是正确的防护**；改用非特权角色 `rag_app` |
| 4 | 本地库缺 `rag_app` 角色 | 执行 `03_rag_roles.sql`（幂等） |
| 5 | 令牌 401 | `iss` 必须是 `https://auth.studykid.cn`（不是 `agent-platform`） |
| 6 | 运行中重新打包 jar 导致 `ClassNotFoundException` | 重启服务；**后续改为先停服务再打包** |

> 第 2、3 两条值得单独说明：它们**不是障碍，而是防护在起作用**。
> 一个用超级用户跑通的"成功"是假成功——超级用户会绕过 RLS，租户隔离根本不生效。

---

## 五、回归验证

| 项 | 结果 |
|---|---|
| 后端 rag-core / ports / infrastructure / worker | 132 / 2 / 110 / 3 全绿 |
| 后端 application | 63 全绿 |
| BUILD | ✅ SUCCESS |

> ⚠️ 修复后**新增的 RLS 绑定逻辑没有补单测**——因为单测用 mock 的 JdbcTemplate，
> **结构上就覆盖不到 RLS 策略**。这类缺陷只能靠真实库演练发现，
> 这也是本次演练最大的价值。

---

## 六、结论与建议

### 6.1 结论

1. **三个迁移可安全上线**（幂等、70ms、零数据丢失）；
2. **蓝绿切换链路完整可用**（建 → 构建 → 切换 → 回滚）；
3. **但代码存在 5 个缺陷，均已修复**；
4. **单测通过 ≠ 能用** —— 这 5 个缺陷全部逃过了 593 项测试。

### 6.2 建议

| 优先级 | 动作 |
|---|---|
| **P0** | 把本次演练固化为**可重复脚本**（`scripts/e2e-index-generation.sh`），纳入发布前 checklist |
| **P1** | 生产库迁移前，先在同规模数据上实测 09 的锁表时间 |
| **P1** | 引入**真实库的集成测试**（现有 IT 已有基建），覆盖 RLS 相关路径 |
| **P2** | 考虑把头号教训写进开发规范：**RLS 表的每个访问方法都必须显式绑定会话租户** |

### 6.3 最大的教训

> **593 项单测全绿，应用却在真实库上连续失败 5 次。**

根因不是测试写得少，而是**测试与真实环境的边界错配**：
mock 了 `JdbcTemplate`，就永远测不到 RLS 策略、事务边界、`set_config` 的作用域。
这类"只有真实库才暴露"的缺陷，必须在 CI 里用真实 PostgreSQL 跑一遍。
