# P3-D03 UNKNOWN 对账与防盲目重放（FR-009）

> **阶段**：P3 可靠性与安全
> **切片**：D3（依赖 D1 故障注入框架）
> **验收**：FR-009 ——「模拟"请求已到下游但响应丢失"时进入 UNKNOWN；对账前禁止盲目重放」（AT-UNKNOWN-001/002）
> **状态**：✅ 完成（2026-10-19）

---

## 1. 现状：账本是空转的

P2 已建好的 UNKNOWN 基础设施（**全部未被真实数据触达**）：

| 资产 | 位置 | 状态 |
|---|---|---|
| `ToolOperationState` 状态机 | core（UNKNOWN 只能 → RECONCILED_* / MANUAL_DISPOSED）| ✅ 已定义，**无调用方** |
| `tool_operation` / `tool_operation_attempt` 表 | V1 基线（幂等键唯一 / result_unknown 约束）| ✅ 已建表，**零写入** |
| `forbid_attempt_after_terminal` 触发器 | V1（UNKNOWN 状态禁止新增 attempt）| ✅ 已部署，**从未被真实触发** |
| `ToolExecutionException.unknownResult` + 闸门 UNKNOWN 处置 | worker | ✅ 已实现（`recordUnknown` 只记内存）|
| **账本读写（登记 / 结果 / 对账）** | — | ❌ **完全缺失** |
| **对账服务（收敛 UNKNOWN → RECONCILED_*）** | — | ❌ **完全缺失** |

**这意味着**：工具真的超时丢响应时，UNKNOWN 只存在于内存的
`ToolInvocationResult` 字段里，随请求结束蒸发 —— 无人能对账，
红线 7「UNKNOWN 须对账处置」在持久层不成立。D3 就是把账本做活。

---

## 2. 设计

### 2.1 账本端口（`RunToolLedgerPort`，ports 层）

四个操作对应状态机的全部合法迁移：

| 操作 | 迁移 | 说明 |
|---|---|---|
| `register` | PENDING → IN_FLIGHT | 调用前登记（幂等键唯一约束命中即返回既有操作——重复调用指向同一逻辑操作）|
| `recordResult` | IN_FLIGHT → SUCCEEDED / FAILED / UNKNOWN | 插 attempt 行（attempt_number 自增；UNKNOWN 强制 result_unknown=true）|
| `reconcile` | UNKNOWN → RECONCILED_SUCCEEDED / RECONCILED_ABSENT | 对账收敛，记 source + reconciled_at |
| `manualDispose` | UNKNOWN → MANUAL_DISPOSED | 人工兜底，记责任人（D5 审批切片接入）|

外加 `findUnknownOperations`（对账扫描）与 `findByIdempotencyKey`（对账查下游依据）。

**迁移一律先过 `ToolOperationState.requireTransitionTo` 状态机校验**，
DB 触发器是第二道防线（应用层拒绝非法迁移 + DB 拒绝非法迁移双保险）。

### 2.2 D-1 账本登记失败 → fail-closed 拒绝执行

闸门执行链（红线 9）本应是「账本 → … → 执行 → 账本更新」，
P2 的实现里账本这一环是空话。接入后**账本登记失败必须拒绝执行工具**：

- 无账可对的工具调用，其 UNKNOWN 无处安放 —— 等于把红线 7 的最危险场景
  （副作用已发生却无法追溯）暴露在故障最频发的时刻（账本故障常与下游故障同源）。
- 拒绝的备选方案：「账本失败降级放行 + 记日志」——日志不是账本，
  对账扫描扫不到日志，拒绝。

### 2.3 D-2 对账收敛依据：查下游幂等记录，不猜

`ReconciliationService` 对 UNKNOWN 的 `record_weakness` 操作：

1. 用幂等键（student_id + run_id + knowledge_point）查下游画像库是否已有该记录；
2. 已有 → `RECONCILED_SUCCEEDED`（副作用已发生，预算不释放——成本真实发生了）；
3. 没有 → **不得立即判定 ABSENT**：下游可能尚未落库（读己之写延迟）。
   仅当登记时间超过对账宽限期（配置，默认 5 分钟）仍无记录才 `RECONCILED_ABSENT`；
4. 宽限期内 → 保持 UNKNOWN 等下次扫描（对账是幂等的，可反复跑）。

**拒绝的备选**：拿「没有查到」直接当「没发生」——这是把最终一致性
当强一致性用，恰好制造「副作用已发生却标记 ABSENT」的 Sev-0。

### 2.4 D-3 attempt 的 state 语义与 result_unknown 的关系

attempt 行的 state 记录**本次调用**的结局（SUCCEEDED/FAILED/UNKNOWN），
操作行 state 记录**逻辑操作**的结局。同一次 UNKNOWN 调用：

- operation：IN_FLIGHT → UNKNOWN（逻辑结果未知）
- attempt：state=UNKNOWN，result_unknown=true（CHECK 约束强制）

对账收敛后**不回改历史 attempt 行**（账本是 append 事实，不是当前状态缓存）；
OPERATION 行的 reconciliation_source 记录「谁、凭什么」收敛的。

---

## 3. 闸门接入点（worker 模块）

`ToolInvocationGate` 变更（执行链补齐「账本」两环）：

```
① Schema → ② 授权 → ③ 幂等键 → ④ [新] 账本登记(register, 失败即拒绝)
   → ⑤ 审批 → ⑥ 预算 → ⑦ 执行 → ⑧ [新] 账本记结果(recordResult)
```

- 登记返回既有操作（幂等键命中）→ 直接复用，不新开操作行；
- 执行成功/确定失败/UNKNOWN 分别映射 recordResult 三种结局；
- UNKNOWN 处置不变（停止后续工具、不释放预算），但现在**留了账**。

## 4. 已知限制

| # | 限制 | 归属 |
|---|---|---|
| L-1 | Run 层状态装配未接线：UNKNOWN 后 Run 仍停留 RUNNING，依赖主循环装配切片把 `ExecutionOutcome.UNKNOWN_RESULT` 映射为 Run 终止动作（转对账队列） | Worker 主循环装配 |
| L-2 | 对账扫描无调度器：`findUnknownOperations` 可被调用但无定时触发 | D7 审计链路 / 运维装配 |
| L-3 | `reconcile` 的下游查询接口针对 record_weakness 定制（查画像库）；通用工具对账须各工具提供 `reconcile` 钩子 | 工具 SPI 演进 |
| L-4 | MANUAL_DISPOSED 仅落账（责任人/时间），审批与人工界面属 D5/E-2 | D5 |

---

## 5. 测试与反证计划（`ToolLedgerReconciliationIT`）

| 用例 | 证明什么 |
|---|---|
| T-1 未知结果 → operation=UNKNOWN + attempt(result_unknown=true) | 账本真实落账（FR-009 前半）|
| T-2 UNKNOWN 后新增 attempt → **真实触发** `forbid_attempt_after_terminal` 异常 | 防盲目重放（FR-009 后半，DB 级）|
| T-3 状态机拒绝 UNKNOWN → FAILED / → SUCCEEDED 直接迁移 | 应用层拒绝 + DB 是第二道防线 |
| T-4 对账收敛 → RECONCILED_SUCCEEDED（记 source/time）；收敛后新增 attempt 仍被拒 | 对账闭环 + 终态不可再试 |
| T-5 同幂等键重复登记 → 命中同一逻辑操作，不新建 | 幂等（FR-003 在账本层的体现）|
| T-6 闸门：账本登记失败 → 工具不执行（fail-closed）| D-1 决策 |

**反证计划**：
1. T-2 反证 —— 把触发器条件里 UNKNOWN 分支去掉 → T-2 应红；
2. T-4 反证 —— reconcile 不检查当前状态为 UNKNOWN（可对任意状态收敛）→ T-3 应红；
3. T-6 反证 —— 闸门把登记失败降级为「记日志放行」→ T-6 应红。

---

## 6. 测试证据（实现后回填）

| 用例 | 结果 | 环境 |
|---|---|---|
| T-1 未知结果落账（operation=UNKNOWN + attempt.result_unknown） | ✅ | PG17 / `app_agent` 非特权 |
| T-2 UNKNOWN 后新增 attempt 被 DB 触发器拒绝 | ✅ | 同上 |
| T-3 状态机拒绝 UNKNOWN→FAILED / →SUCCEEDED / reconcile 目标非法 | ✅ | 同上 |
| T-4 对账收敛 RECONCILED_SUCCEEDED（查到下游幂等记录）+ 终态后 attempt 仍拒 | ✅ | 同上 |
| T-4b 宽限期后无下游记录 → RECONCILED_ABSENT | ✅ | 同上 |
| T-5 同幂等键重复登记命中同一逻辑操作 | ✅ | 同上 |
| T-6 闸门账本登记失败 → fail-closed 拒绝执行 | ✅ | 单测 |
| 全量 `mvn clean verify` + `*IT` | ✅ BUILD SUCCESS；infra IT 37→**43**；@Test 311→**318** | 同上 |

**反证记录**：
1. T-2 反证 —— DB 触发器移除 UNKNOWN 分支 → T-2 红 ✅（⚠️ 第一次注入因 docker heredoc 静默失败而假阴性，改为单命令注入后成立）
2. F-2 反证 —— `inTenantScope` 成功路径 rollback（原实现）→ T-4 红；改 commit 后绿 ✅（天然反证）
3. T-6 反证由替身 `registerFails` 开关直接构造故障 → 断言工具执行数为 0 ✅

## 7. 实现中的重大发现：F-2（对账测试的副产品）

**`StudentDataRepository.inTenantScope` 成功路径无条件 `rollback()`（P2-D4 引入）——
`insertWeakness` 的写入从未落库，核心业务工具 `record_weakness` 实为空操作。**

- 为何 P2-D4 全绿：写入后若有断言，也是**同一连接内读未提交数据**（可见）——
  测试在同一事务里自证，跨连接即假阴性；
- D3 的 T-4 之所以抓住它：对账查询 `reconcileWeakness` 走**独立连接**（跨连接），
  「写入后对账查得到」这个 FR-009 的核心命题天然要求跨连接验证；
- 修复：成功路径 `commit()`（只读方法 commit 是 no-op，无害）；
- **教训**：验证写入的测试必须在**另一个连接**上断言可见性，
  同事务自证不是落库证明。
