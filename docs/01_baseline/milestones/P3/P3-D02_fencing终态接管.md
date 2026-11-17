# P3-D02 Worker Fencing 终态接管（FR-006）

> **阶段**：P3 可靠性与安全
> **切片**：D2（依赖 D1 故障注入框架）
> **验收**：FR-006 ——「租约失效 Worker 无法提交结果；新世代能安全接管」（AT-LEASE-001/002）
> **状态**：✅ 完成（2026-10-19）

---

## 1. 现状盘点：五道既有防线

P2 已建立的 fencing 防线（`JdbcRunClaimRepository` + DB 触发器）：

| # | 防线 | 位置 | 拦截什么 |
|---|---|---|---|
| 1 | 接管即递增 token | `claimTasks`/`claimSpecificRun` 的 `fencing_token = fencing_token + 1` | 旧世代 token 立即失效 |
| 2 | 提交 WHERE 校验 | `commitResult` 的 `AND fencing_token = ? AND state = 'RUNNING'` | 旧世代提交影响 0 行 |
| 3 | 续租 WHERE 校验 | `renewLease` 的 `AND lease_owner = ? AND fencing_token = ?` | 旧世代续租影响 0 行 |
| 4 | DB 触发器（回退） | `enforce_fencing_on_write`：`NEW.token < OLD.token` 拒绝 | 直接 UPDATE 回退 token |
| 5 | DB 触发器（终态） | `enforce_run_state_transition`：终态不可迁出 | 终态 Run 被改写 |

`RunClaimFencingIT` 已覆盖**串行**场景（旧世代提交/续租被拒、SKIP LOCKED 防重复领取）。

**D2 的增量不是重写防线，而是两件事**：
1. 修复一个被测试替身掩盖的**真实缺陷**（§2）；
2. 用**故障注入 + 并发交错**证明防线在竞态下依然成立（§3）—— 串行测试通过 ≠ 竞态安全。

---

## 2. F-1 续租身份不一致（真实缺陷，本次修复）

### 现象

`RunExecutionLoop` 每步循环后调 `renewLease(tenantId, runId, currentWorker(task), token, lease)`，
其中：

```java
// RunExecutionLoop.java（修复前）
private static String currentWorker(RunClaimPort.LeasedTask task) {
    return "worker-" + task.fencingToken();   // ← 从 task 现场拼出来的身份
}
```

而领取时写入 `lease_owner` 的是**另一个身份**——调用方传入的 `workerId`
（`RunDispatchConsumer.consume(payload, workerId)`，生产环境是 Worker 实例标识如主机名）。

**两者不相等 → 续租 WHERE `lease_owner = 'worker-42'` 永远匹配 `lease_owner = 'w-1'` 失败
→ 影响行数 0 → 执行循环误判「已被接管」→ `SKIPPED_STALE_GENERATION`。**

### 影响面

- **单步 Run（无工具调用）不受影响**：`commitResult` 不校验 `lease_owner`，只校验 token。
- **多步 Run（有工具调用，第二步起）全部被误杀**：每个需要工具的 Run 都会在第二步
  续租失败而放弃 —— 而 `record_weakness` 恰恰是有副作用的工具，这是核心业务路径。
- 同时引入**安全反转**：本应「接管后新世代独占」，实际变成「谁也干不成活」——
  可靠性缺陷伪装成可用性缺陷，监控上表现为大量 `SKIPPED_STALE_GENERATION`。

### 为何既有测试没抓住

`RunExecutionLoopTest.FakeClaimPort.renewLease` 返回字段 `renewSucceeds`（默认 true），
**完全不看传入的 workerId**。测试替身把「身份必须一致」这一契约抹掉了。

这正是 P2-D5 教训的又一次重演：**测试替身必须让契约可证伪**。
修复后的替身会校验 workerId 与 claim 时一致，不一致即续租失败。

### 修复方案

**workerId 是 Worker 实例的身份，应由构造注入，而不是从 task 现场拼凑**：

```java
public RunExecutionLoop(ModelGateway, RunClaimPort, ToolInvocationGate,
                        Duration leaseDuration, String workerId)
```

- 续租、（后续切片的）步骤写入都使用同一身份；
- `LeasedTask` 保持纯数据（runId/token/attempt/leaseExpiry），不携带身份 ——
  身份的源头只有一个，避免两处各自演化再度分叉；
- 拒绝的备选方案：把 workerId 塞进 `LeasedTask`（领取时回填）——
  数据record承载执行者身份，会让「task 从哪来」与「谁在执行」两个概念耦合。

---

## 3. 要证明的：三个故障注入用例（`FencingTakeoverIT`）

串行测试已由 `RunClaimFencingIT` 覆盖。D2 用 D1 框架证明**竞态与崩溃**下成立：

### FI-LEASE-002 并发接管竞态（AT-LEASE-001 的并发版）

**注入**：两线程同时操作同一 Run——
A 持 token N（租约已过期），B 尝试接管；A 的 `commitResult(N)` 与 B 的 `claimSpecificRun` 交错。

**断言**：**恰好一个赢**。
- 若 B 先锁行（FOR UPDATE）：A 的 UPDATE 等 B 提交后重估 WHERE（READ COMMITTED），
  token 已变 N+1 → A 影响 0 行；
- 若 A 先锁行提交终态：B 的 claim WHERE `state='RUNNING'` 重估失败 → B 领取 0 行。
- 两个都成功 = fencing 失效（Sev-0）；两个都失败 = 消息丢失（Sev-1）。

### FI-LEASE-003 终态接管拒绝（AT-LEASE-002）

**注入**：Run 已至终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT 各测），
且 `lease_expires_at` 已过期（模拟租约记录残留）。

**断言**：claim **不得接管**（`state='RUNNING'` 条件）+ 触发器兜底
（终态不可迁出）。终态 Run 被「复活」意味着结果被覆盖，违反红线 5。

### FI-LEASE-004 崩溃后接管 + 旧世代复活

**注入**：旧世代领租约后「崩溃」（不做任何清理）；租约过期；
新世代接管成功且 token 严格 +1（不硬编码 ==1，见 MEMORY 铁律）；旧世代复活后带旧 token：
`commitResult` / `renewLease` / 直接 UPDATE 回退 token 三条路**全部被拒**。

**断言**：新世代独占写入权；旧世代三条路 0 行或异常；attempt 同步递增。

### 多步续租真实化（F-1 回归证明）

修复后的 `RunExecutionLoop` 用**真实 DB** 走多步执行：
第 1 步有工具调用 → 续租必须成功（`lease_owner` 匹配）→ 第 2 步继续 → 正常提交。
修复前此用例必失败（续租 0 行）—— 先写测试看它红，再修代码看它绿。

---

## 4. 决策与理由

| # | 决策 | 理由 | 拒绝的备选 |
|---|---|---|---|
| D-1 | workerId 构造注入而非从 task 拼凑 | 身份单一来源；「task 从哪来」与「谁在执行」解耦 | workerId 回填进 LeasedTask（概念耦合） |
| D-2 | 并发竞态用双线程交错测试，不用单线程模拟 | FOR UPDATE + READ COMMITTED 的互斥语义只有真实并发能证伪；单线程顺序调用无法暴露锁重估行为 | 单线程先 commit 再 claim（测不出竞态窗口） |
| D-3 | 终态接管拒绝靠 claim 的 state 条件 + 触发器双保险，不加新触发器 | 两道既有防线已覆盖；新增 `enforce_no_takeover_on_terminal` 会与 `enforce_run_state_transition` 职责重叠 | 新增第三道触发器（冗余） |
| D-4 | attempt 与 token 同步递增的断言写「后 = 前 + 1」 | token 起点不保证是 1（MEMORY 铁律：不硬编码 ==1） | 断言 token == 1 |

---

## 5. 已知限制（有意记录，后续切片处理）

| # | 限制 | 影响 | 归属 |
|---|---|---|---|
| L-1 | `run_step` / `tool_operation` 表有 `fencing_token` 列但**无触发器守卫** —— 旧世代理论上可直接插入带旧 token 的步骤行 | 当前无写入路径（P2 未实现步骤持久化），不构成现实漏洞；但 D6/D7 实现写入时**必须**同步加 `enforce_fencing_on_step` 类触发器，否则红线 5 在步骤层面失效 | D6 取消竞态 / D7 审计链路 |
| L-2 | `claimTasks`（批量领取）尚无生产调用方，Worker 主循环当前只走 `claimSpecificRun`（MQ 指定领取） | 批量领取的接管语义未经真实负载验证 | Worker 主循环装配时 |
| L-3 | 接管依赖 `now()` 与 `lease_expires_at` 的时钟一致性（单 PG 实例无此问题）；多实例部署时若时钟漂移 > 租约粒度，可能出现双主 | 当前单实例；P4 多实例部署前须引入 DB 时间源或租约仲裁 | P4 |
| L-4 | 执行循环与消费链路尚未在 bootstrap 装配（`RunDispatchConsumer.consume` 领取后未接执行） | 端到端「接管后新世代真正继续执行」的验证依赖装配完成 | Worker 主循环装配切片 |

---

## 6. 测试证据（实现后回填）

| 用例 | 结果 | 环境 |
|---|---|---|
| FI-LEASE-002 并发接管竞态 | ✅ 2 线程交错，恰好一个赢 | PG17 `agent-pg17` / `app_agent` 非特权 |
| FI-LEASE-003 终态接管拒绝（4 终态 × 2 断言） | ✅ 8 项断言全过 | 同上 |
| FI-LEASE-004 崩溃接管 + 旧世代复活三路被拒 | ✅ token = 前+1；复活三路全拒 | 同上 |
| F-1 回归：续租身份一致性（`renewUsesConstructedWorkerId` + workerId 空白 fail-fast） | ✅ 单测替身记录并断言续租身份 = 构造 workerId | 单测（FakeClaimPort 已强化为记录身份） |
| `RunExecutionLoopTest` 全量 | ✅ 15 项 | 单测 |
| 全量 `mvn clean verify` + `*IT` | ✅ BUILD SUCCESS；infra IT 34→37；@Test 306→311 | 同上 |

**反证记录**：
1. F-1 反证——将 `RunExecutionLoop` 还原为 `"worker-" + token` 拼凑 → `renewUsesConstructedWorkerId` 立即红 ✅
2. FI-LEASE-002 反证——把 claim 的 `fencing_token = fencing_token + 1` 改为不递增 → 竞态测试红（**双胜 a=true b=true**，正是 fencing 失效形态），FI-LEASE-004 的 token 递增断言同红 ✅
