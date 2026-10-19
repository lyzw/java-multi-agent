# P3 文档索引 — 可靠性与安全

> 阶段：P3（可靠性与安全，8–13 周，P1-10 重估）　**状态**：🟢 进行中（D1 ✅ D2 ✅ D3 ✅ D4 ✅）
> 总纲：[P3-01 执行计划](P3-01_执行计划.md)

## 切片进度

| 文档 | 主题 | 状态 | FR / 威胁 |
|---|---|---|---|
| [P3-D1_故障注入框架.md](P3-D1_故障注入框架.md) | 故障注入框架 + 核心不变量对账 | ✅ 完成（5/5 全绿） | 基础（FI-RLS/ATOMIC/IDEM/LEASE）|
| [P3-D02_fencing终态接管.md](P3-D02_fencing终态接管.md) | Worker fencing 终态接管（含 F-1 缺陷修复）| ✅ 完成 | FR-006 / T-2 |
| [P3-D03_UNKNOWN对账与防盲目重放.md](P3-D03_UNKNOWN对账与防盲目重放.md) | 账本落账 / 对账收敛 / 防盲目重放（含 F-2 落库缺陷修复）| ✅ 完成 | FR-009 / T-4 |
| [P3-D04_预算预留并发与对账.md](P3-D04_预算预留并发与对账.md) | 并发预留不超发 / 重复结算不重复计费（含 F-3 预算链路空转、F-4 配置静态状态修复）| ✅ 完成（17 IT 全绿 + 6 项反证）| FR-012 / T-5 / D-2 |
| P3-D05 | 审批消费原子协调 + 崩溃恢复 | ⬜ 待写 | FR-013 / E-2 / R-1 |
| P3-D06 | 取消竞态 | ⬜ 待写 | FR-011 |
| P3-D07 | 审计链路 | ⬜ 待写 | R-1 / R-2 / R-3 |
| P3-D08 | 限流 | ⬜ 待写 | D-2 |
| P3-D09 | Prompt 注入红队回归 | ⬜ 待写 | FR-016 |
| P3-D10 | SSE 实时推送 + 可观测性指标 | ⬜ 待写 | FR-014(部分) / FR-017(指标) |
| P3-D11 | 业务验收评测集 + 无编造出处 + 威胁模型复审 | ⬜ 待写 | FR-018/020 / I-5 |

## 每日状态（摘自 P3-01 §3）

| 片 | 设计文档 | 代码产出 | 依赖 |
|---|---|---|---|
| D1 | ✅ P3-D1 | FaultInjectionSupport + 4 个 FI 用例 | 无 |
| D2 | ✅ P3-D02 | FencingTakeoverIT + F-1 修复（RunExecutionLoop workerId）| D1 |
| D3 | ✅ P3-D03 | ToolLedgerReconciliationIT + 对账服务 + F-2 修复（inTenantScope commit）| D1 |
| D4 | ✅ P3-D04 | BudgetPort / JdbcBudgetRepository / BudgetReconciliationService + V4 迁移 + 闸门接入 + F-3/F-4 修复 | D1 |
| D5–D11 | ⬜ | ⬜ | 均依赖 D1 |
