# -*- coding: utf-8 -*-
"""第十六批 · 07/08/09 变更补充。"""
import pathlib

DOC_DIR = pathlib.Path('/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化')

# ── 07 执行链集成设计 ────────────────────────────────────────────────
p7 = DOC_DIR / '07_执行链集成设计.md'
s7 = p7.read_text(encoding='utf-8')

old = "4. 校验深度、剩余调用次数、剩余时间、预算、父调用状态及取消标志。"
new = ("4. 校验深度、剩余调用次数、剩余时间、预算、父调用状态及取消标志。"
       "⚠️ **第十六批**：**委派本身也必须占 Run 预算额度** —— 见下「委派的预算口径」。")
assert old in s7
s7 = s7.replace(old, new)

anchor = "### 上下文与安全\n"
i = s7.index(anchor)
section = """### 委派的预算口径（第十六批 · MA-T17）

⚠️ **此前委派是「免费动作」**：闸门的委派分支直接 `continue`，
**绕过**了工具路径的 ⑥ Run 级**金额**判定与 ⑥ **预算预留**。
一次委派会启动子 Agent、子 Agent 会调模型 —— 那是**真实成本**，却**不占任何额度**。

**为什么这是 Sev-0 级的缺口**：MA-T17 的承诺是「全调用树共享预算上限，不能重置额度」。
该承诺此前**只对工具调用成立** —— 所有工具调用都记在同一 `run_id` 下，
按 Run 聚合出的消耗就是整棵树的消耗；**但委派不在聚合里**。
而 `maxChildInvocations` 是**每父 Agent** 的（每层相乘：8 × 8 × 8 = 512 次子调用），
**不是 Run 级上限** → Run 额度本是唯一的 Run 级兜底，却恰好对委派不管用。

**执行链（第十六批）**：

```
委派发起
 ├─ ① Run 级**金额**判定（runBudget.check：剩余额度 ≥ 本次预留额）
 │     └─ 不足 → 拒绝（BUDGET_EXCEEDED）+ **零子执行**
 ├─ ② 权威预留（budget.reserve，幂等键 = 委派 invocationId）
 │     └─ 失败 → 拒绝 + **零子执行**
 ├─ ③ 执行委派（既有链路）
 └─ ④ 按结局处置预留：
       成功        → 结算
       执行失败     → 结算   （子执行已启动 = 子 Agent 跑过了 = 成本已发生）
       被拒         → 释放   （子执行从未启动 = 零成本 = 额度原样退回）
       UNKNOWN     → 既不结算也不释放（成本是否发生未知 → 留 RESERVED 待对账）
```

⚠️ **工具与委派共用同一份预算预留核心**（`reserveQuota`）—— 两份实现必然漂移，
而漂移就是「两个真相」本身。

⚠️ **「执行失败 → 结算」与工具路径的「确定失败 → 释放」刻意不同**：
工具的确定失败 = 工具没产生副作用；子执行的失败 = 子 Agent **已经运行过**。

⚠️ **预留额取「一次调用当量」—— 本批取舍，非评审冻结的定价模型**：
预算体系当前按「调用当量」计量，真实成本由 `usage_record` 独立记录（P3-D04 §6 L-5）。
一次委派的真实成本（子 Agent 的多轮模型调用）**高于**一次工具调用当量，
故本口径是**保守下界**，不是精确计价。
⚠️ **模型调用本身仍未计入 Run 额度**（根与子 Agent 的模型调用都是）——
彻底修需要冻结成本模型（token 计价），属**未闭环范围** → 已登记为缺口。

⚠️ **行为变更**：租户**未开通预算账户**时，委派从「能跑」变成「被拒」
（`NO_ACCOUNT`，fail-closed）—— 与工具调用**同口径**；此前委派完全不碰预算，
才造成「多级 Agent 在没开预算的租户上能用、单 Agent 工具路径不能用」的不一致。

"""
s7 = s7[:i] + section + s7[i:]
p7.write_text(s7, encoding='utf-8')
print("07 已更新")

# ── 08 安全测试与验收矩阵 ────────────────────────────────────────────
p8 = DOC_DIR / '08_安全测试与验收矩阵.md'
s8 = p8.read_text(encoding='utf-8')
s8 = s8.rstrip() + """

### 变更补充：委派预算计费的验收补充（第十六批）

⚠️ **本批验收项 MA-T17a～MA-T17e 均为「本批补充」**，补充的是
MA-T17（多层委派不得绕过 Run 总预算）在**委派路径**上的既有约束，不是新增约束。

| 用例 ID | 类别 | 场景 | 预期 | 证据 |
|---|---|---|---|---|
| MA-T17a | 预算 | 委派成功 | 委派**占用** Run 额度（预留 + 结算）—— 一次委派会启动子 Agent，是真实成本，不得免费 | `DelegationBudgetChargeTest#delegationConsumesRunBudget` |
| MA-T17b | 预算 | Run 级剩余额度**不足以容纳**本次委派 | 在新增子调用前拒绝（`BUDGET_EXCEEDED`），**零子执行** | `DelegationBudgetChargeTest#insufficientRunBudgetBlocksDelegation` |
| MA-T17c | 预算 | 租户级预留被拒（额度不足 / 未开通账户） | 拒绝 + **零子执行**，且拒绝原因可区分「额度不足」与「未开通账户」 | `DelegationBudgetChargeTest#tenantBudgetRejectionBlocksDelegation` |
| MA-T17d | 预算 | 委派被拒（子执行**从未启动**） | **释放**预留（零成本，额度原样退回），不得结算 | `DelegationBudgetChargeTest#rejectedDelegationReleasesQuota` |
| MA-T17e | 预算 | 委派结果 **UNKNOWN**（子执行已启动，结果不确定） | 预留**既不结算也不释放**（R-007 红线：成本可能已发生，释放等于白送额度） | `DelegationBudgetChargeTest#unknownDelegationHoldsQuota` |

⚠️ **「执行失败 → 结算」的对应用例**：`DelegationBudgetChargeTest#failedDelegationSettlesQuota`
（子 Agent 已经运行过 → 成本已发生 → 不得释放额度）。

⚠️ **反证**：`scripts/c16-1-counterproof.sh` 五处 ——
整体摘掉委派预算 → 7 条全红；单点摘掉（金额判定 / 被拒释放 / 失败结算 / UNKNOWN 释放）→ 各恰 1 条红。
"""
p8.write_text(s8 + "\n", encoding='utf-8')
print("08 已更新")

# ── 09 需求追踪矩阵 ──────────────────────────────────────────────────
p9 = DOC_DIR / '09_需求追踪矩阵.md'
s9 = p9.read_text(encoding='utf-8')
old = """| FR-030 | **多级递归按「当前执行中的 Agent」判定**（第十五批补充） | 07 §嵌套委派的执行语义（`DelegationContext`）、07 §嵌套路径的超时共享 | MA-05（第十五批 C15-1 / C15-2） | AC-021、AC-024 / MA-T05g～MA-T05j、MA-T09a～MA-T09c、MA-T10a |"""
new = old + """
| FR-031 | **委派也计入 Run 级预算**（第十六批补充） | 07 §委派的预算口径（MA-T17） | MA-05（第十六批 C16-1） | AC-024 / MA-T17、MA-T17a～MA-T17e |"""
assert old in s9
s9 = s9.replace(old, new)

old2 = """⚠️ **FR-030 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""
new2 = """⚠️ **FR-031 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
07/08 原文只规定「全调用树共享预算上限」，从未规定
**「委派本身是否占额度、占多少、按什么口径结算/释放」**
（本批取「与工具调用同一份预留核心 + 一次调用当量 + 按子 Agent 是否真的跑过区分结局」的最小方案）。
状态仍为 `Draft` —— **不得**因本批已有实现与自动化证据就标记为 `Approved`：
⚠️ 其中「**预留额如何定价**」与「**模型调用是否计入 Run 额度**」都依赖**尚未冻结的成本模型**，
已登记为缺口，待评审一并闭环。

⚠️ **FR-030 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""
assert old2 in s9
s9 = s9.replace(old2, new2)
p9.write_text(s9, encoding='utf-8')
print("09 已更新")
