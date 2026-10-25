# -*- coding: utf-8 -*-
"""第十六批 · 00 文档回写：已实现表追加条目。"""
import pathlib

DOC_DIR = pathlib.Path('/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化')
p = DOC_DIR / '00_修订说明与评审决议项.md'
s = p.read_text(encoding='utf-8')

anchor = "| **第十五批反证（六处，逐一验证用例真的会红）** |"
i = s.index(anchor)
end = s.index("\n", i) + 1

rows = (
    "| **委派也计入 Run 级预算（第十六批 · C16-1 · MA-T17）** | 闸门委派分支此前**直接绕过**「Run 级金额判定 + 预算预留」→ 一次委派（启动子 Agent → 真实成本）**不占任何 Run 额度**，「全调用树共享预算上限」对委派完全失效。现在委派与工具调用**共用同一份**预算预留核心（`reserveQuota`）：委派前先做 Run 级**金额**判定（不足 → 拒绝 + **零子执行**）再 `budget.reserve`（幂等键 = 委派 `invocationId`，重复投递复用同一笔预留）。⚠️ **预留额取「一次调用当量」—— 本批取舍，非评审冻结的定价模型**：预算体系按调用当量计量，真实成本由 `usage_record` 独立记录；一次委派的真实成本（子 Agent 的多轮模型调用）**高于**一次工具调用当量，故本口径是**保守下界** | `DelegationBudgetChargeTest` 7 例（委派占额 / Run 级额度不足→零子执行 / 租户级预留被拒→零子执行 / 被拒→释放 / 执行失败→结算 / UNKNOWN→预留不动 / 重复投递→同一幂等键复用） |\n"
    "| **委派预算的结局口径按「子 Agent 是否真的跑过」区分（第十六批）** | 成功 → **结算**；执行失败（子执行已启动）→ **结算**（子 Agent 跑过了，模型调用的钱已花出去）；被拒（子执行从未启动）→ **释放**（零成本，额度原样退回）；UNKNOWN → **既不结算也不释放**（R-007 红线：成本是否发生未知，留 `RESERVED` 待对账）。⚠️ 「执行失败 → 结算」与工具路径的「确定失败 → 释放」**刻意不同**：工具的确定失败 = 工具没产生副作用；子执行的失败 = 子 Agent 已经运行过 | `DelegationBudgetChargeTest#failedDelegationSettlesQuota` / `#rejectedDelegationReleasesQuota` / `#unknownDelegationHoldsQuota` |\n"
    "| **第十六批反证（五处，逐一验证用例真的会红）** | ① 整体摘掉委派路径的预算判定与预留 → `DelegationBudgetChargeTest` 7 例**全红**；② 只摘 Run 级**金额**判定（只判方向不判量）→ **恰 1 条**红；③ 把「被拒 → 释放」改成「被拒 → 结算」→ **恰 1 条**红；④ 把「执行失败 → 结算」改成「执行失败 → 释放」→ **恰 1 条**红；⑤ 让 UNKNOWN 也释放额度 → **恰 1 条**红。**五处修复互相独立、可分别证伪** —— 这正是「每一处都有专属断言」的证据 | `scripts/c16-1-counterproof.sh`（含「恢复后复跑全绿」） |\n"
)

s = s[:end] + rows + s[end:]
p.write_text(s, encoding='utf-8')
print("已实现表已追加第十六批条目")
