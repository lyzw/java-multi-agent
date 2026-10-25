# -*- coding: utf-8 -*-
"""第十六批 · 00 文档回写：未实现表补缺口 + 历批修正表补条目。"""
import pathlib

DOC_DIR = pathlib.Path('/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化')
p = DOC_DIR / '00_修订说明与评审决议项.md'
s = p.read_text(encoding='utf-8')

# ── ① 未实现表：更新 MA-T17 端到端证据条 + 追加两条新缺口 ──────────
old = "| MA-T17 端到端证据 | Run 级口径已有**单元级 + DB 层 IT** 证据（`RunBudgetProbeTest` 7 例 / `JdbcRunBudgetCapIT` 4 例，本机真实 PG17 真跑，含并发争抢与**确定性交错**）；**跨进程的接管窗口演练与故障注入**仍需专门环境 |"
new = (
    "| MA-T17 端到端证据 | Run 级口径已有**单元级 + DB 层 IT** 证据（`RunBudgetProbeTest` 7 例 / `JdbcRunBudgetCapIT` 4 例，本机真实 PG17 真跑，含并发争抢与**确定性交错**）；⚠️ **第十六批补上了「委派也占额」这一块**（`DelegationBudgetChargeTest` 7 例）—— 此前「全调用树共享预算上限」**只对工具调用成立**，委派完全绕过预算；**剩余缺口**：**跨进程的接管窗口演练与故障注入**仍需专门环境 |"
)
assert old in s
s = s.replace(old, new)

anchor = "| **「跨轮次 tool call id 复用」未收敛（第十五批发现 · 未修）** |"
i = s.index(anchor)
end = s.index("\n", i) + 1
gaps = (
    "| **模型调用本身仍未计入 Run 额度（第十六批发现 · 未修）** | 根 Agent 与子 Agent 的**模型调用**（`ModelGateway.complete`）都不经过任何预算预留 —— 预算体系按「**调用当量**」计量，真实成本由 `usage_record` 独立记录（P3-D04 §6 L-5）。⚠️ **第十六批给委派补了当量预留**（一次委派 = 一次调用当量），但**委派的真实成本**（子 Agent 的多轮模型调用）**高于**一次工具调用当量 → 当量口径是**保守下界**，不是精确计价。**彻底修需要冻结成本模型**（token 计价 → 金额），那属于**未闭环范围** → 只登记为缺口，不自行推进 | `DelegationBudgetChargeTest`（当量口径）+ `RunBudgetProbeTest`（金额级判定） |\n"
    "| **委派预留的 UNKNOWN 对账路径缺失（第十六批发现 · 未修）** | 委派的预算预留**没有关联的 `tool_operation` 行**（委派记在 `agent_invocation`），而 `JdbcBudgetRepository.findOrphaned` 用的是 `JOIN tool_operation`（内连接）→ 委派预留**不会**被误释放（这点是安全的），但也**没有**任何自动处置路径：若一次委派的结果为 `UNKNOWN`，其预留会永久停在 `RESERVED`，额度被长期占用、无人认领。⚠️ **剩余缺口**：需要一条「按 `agent_invocation` 的 UNKNOWN 记录反查并处置预留」的对账通道 | `DelegationBudgetChargeTest#unknownDelegationHoldsQuota`（钉死「UNKNOWN 时既不结算也不释放」这一侧；对账通道待补） |\n"
)
s = s[:end] + gaps + s[end:]

# ── ② 历批修正的设计缺陷表：追加第十六批条目 ──────────────────────
anchor2 = "| **根 Run 的剩余时间在嵌套路径上彻底失效（第十五批修 · C15-2）** |"
i = s.index(anchor2)
end = s.index("\n", i) + 1
row = (
    "| **委派是「免费动作」→ 全调用树共享预算上限对委派完全失效（第十六批修 · C16-1 · MA-T17）** | 闸门的委派分支在 `invokeAllCap` 里直接 `continue`，**绕过**了普通工具路径的 ⑥ Run 级**金额**判定与 ⑥ **预算预留**。一次委派会启动子 Agent、子 Agent 会调模型 —— 那是**真实成本**，却**不占任何 Run 预算额度**。后果：MA-T17 的承诺「全调用树共享预算上限，不能重置额度」此前**只对工具调用成立**（所有工具调用记在同一 `run_id` 下，按 Run 聚合即整棵树的消耗 —— 但委派不在聚合里）；而 `maxChildInvocations` 是**每父 Agent** 的（每层相乘 8 × 8 × 8 = 512 次子调用），**不是 Run 级上限** → Run 额度本是唯一的 Run 级兜底，却恰好对委派不管用，「这一棵调用树最多花多少」在多级场景下是一句空话 | 第十六批：① 抽出**共用**的预算预留核心 `reserveQuota`（工具与委派同一份口径 —— 两份实现必然漂移，而漂移就是「两个真相」本身）；② 委派前先 Run 级**金额**判定（不足 → 拒绝 + **零子执行**）再 `budget.reserve`（幂等键 = 委派 `invocationId`）；③ 结局按「子 Agent 是否真的跑过」区分：成功/执行失败 → **结算**，被拒 → **释放**，UNKNOWN → **既不结算也不释放**（R-007 红线）。`DelegationBudgetChargeTest` 7 例（反证：整体摘掉 → 7 条全红；单点摘掉 → 各恰 1 条红） |\n"
)
s = s[:end] + row + s[end:]

p.write_text(s, encoding='utf-8')
print("未实现表与历批修正表已更新")
