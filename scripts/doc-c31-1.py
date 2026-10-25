#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十一批 C31-1 文档回写：评审前置决策 3 的**冻结**（ADR-012）。

⚠️ 本批**不改任何实现**（决策 3 冻结的是**现状**，不是新设计），
只把已经实现并被用例钉住的委派状态语义正式冻结下来。

涉及三份文档：

① **08 验收矩阵**：新增 MA-T24a～MA-T24i —— 把 ADR-012 的每条语义变成
   **可验证**的验收项（每条都指向**真实存在**的用例方法名）。
   ⚠️ 与前几批的「本批补充」不同：这一批是**评审冻结的验收项** ——
   它们钉住的是既有语义，故证据全部是**既有用例**，不需要新写用例。

② **00 修订说明**：把「评审前置决策」第 1/2/3 条标记为已决策，
   并在实现状态快照标题下登记本批性质。
   ⚠️ **「已决策」≠「已落地」**：决策 1、2 的落地分别在第三十二批、第三十三批，
   在那之前不得按已落地口径引用 —— 故明确标注「待落地」。

③ **09 需求追踪矩阵**：补 FR-045 行 + 说明段（说明它是「冻结」而非「新需求」）。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C31_1_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C31-1 -->"

_PATCHED: set[str] = set()


def _done(path: pathlib.Path) -> bool:
    key = str(path)
    if key in _PATCHED:
        return True
    if MARK in path.read_text():
        _PATCHED.add(key)
        return True
    return False


def _sub_once(text: str, old: str, new: str, where: str) -> str:
    if old not in text:
        raise SystemExit(f"{where} 待替换文本未命中：{old[:70]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:70]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


def _append(text: str, chunk: str) -> str:
    return text.rstrip("\n") + "\n\n" + chunk.rstrip("\n") + "\n"


# ── ① 08：新增 MA-T24a～MA-T24i ────────────────────────────────────────
MA_T24_SECTION = """### 变更补充：委派调用状态机与取消语义的验收补充（第三十一批 · 决策 3 冻结 · ADR-012）

⚠️ **本批验收项是「评审冻结的验收项」** —— 与前几批的「本批补充」不同：
它们冻结的是**已经实现并被用例钉住**的语义，故每条的证据都是**既有用例**，
不需要新写用例。本节的作用是把「哪个用例钉住的是哪条语义」写清楚，
并把**仍然缺证据的部分显式暴露**（MA-T24h 的最后一句）。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T24a | **状态集合封闭** | `agent_invocation.status` 写入第七个状态 | 必须被**数据库拒绝** —— 状态集合**恰好七态**（QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / UNKNOWN），封闭不可扩展 | `MultiAgentSchemaIT`（结构断言：状态集合恰好七态）+ `MultiAgentPersistenceIT` M-2（非法状态必须被数据库拒绝） |
| MA-T24b | **终态不可覆盖** | 同一 invocationId 已是终态时再次写入终态 | **不覆盖**既有终态（第一次写入的终态是权威事实）；重放返回**既有**结论，且**不重复执行**子 Agent | `MultiAgentPersistenceIT` M-4（终态不被覆盖）+ `DelegationExecutorTest#repeatedDeliveryDoesNotRunChildTwice` |
| MA-T24c | **取消传播** | 父 Run 被取消时子 Agent 正在执行 | 子执行**停止**并落终态 `CANCELLED`（协作式）；⚠️ **取消 ≠ 撤销** —— 不承诺撤销已发出的外部副作用，已启动的 `tool_operation` 仍须进入可解释状态 | `WorkerChildAgentRunnerTest#cancelledRunStopsChildExecution` |
| MA-T24d | **探针不得炸掉 Run** | 取消探针读不到状态 / 抛异常 | **不得**把取消变成「整个 Run 崩掉」—— 探针失败按「未取消」处理并告警，子执行继续 | `WorkerChildAgentRunnerTest#cancellationProbeFailureDoesNotStopExecution` |
| MA-T24e | **确定失败 ≠ 不确定** | 子 Agent 取不到模型配置 / 预算耗尽 / 模型调用超时 | 分别落 `FAILED`（**确定**失败）与 `TIMED_OUT`；⚠️ **不得**混成 `UNKNOWN`（混了就把「确定失败」说成「结果不确定」，对账流程会误放行） | `WorkerChildAgentRunnerTest#missingModelConfigIsDeterministicFailure` + `#exhaustedBudgetTimesOut` + `#modelCallTimeoutIsCappedByRemainingBudget` |
| MA-T24f | **fencing 过期不得承认结果** | 旧世代带过期 token 提交终态 | **不承认**本次结果 → `UNKNOWN` 且**不带任何内容**（未经确认的结论不得进入父 Agent 的推理） | `DelegationExecutorTest#expiredFencingTokenMustNotAcceptResult` + `MultiAgentPersistenceIT` M-4 + `InFlightDelegationAdopterTest#staleGenerationStillCannotWriteTerminal` |
| MA-T24g | **接管三步** | Worker 崩溃后新世代接管 | 在途记录被提升世代并收敛为 `UNKNOWN`；**终态记录不被改写**（终态是权威事实，接管方没资格改）；重放拿到 `UNKNOWN` 且**零子执行**（绝不重放 = 不产生重复副作用） | `InFlightDelegationAdopterTest#adoptConvergesInFlightToUnknown` + `#terminalRecordsAreNotRewritten` + `#firstClaimAdoptsNothing` + `DelegationExecutorTest#takeoverThenReplayYieldsUnknown` |
| MA-T24i | **子执行自身不确定 → 整条委派 UNKNOWN** | 子 Agent 内部工具调用出现不确定结果 | 子执行返回 UNKNOWN → **整条委派**收敛为 UNKNOWN（不只影响那一次工具调用）；不盲目重试（MA-T13） | `DelegationExecutorTest#unknownChildResultStaysUnknown` + `DelegationGateBranchTest#unknownChildResultIsRecordedAsUnknown` |
| MA-T24h | **UNKNOWN 不自动处置** | 委派预留卡在 UNKNOWN / 人工给出收敛结论 | UNKNOWN **既不结算也不释放**，只统计转人工；须有**明确的收敛结论**（`RECONCILED_SUCCEEDED` / `RECONCILED_ABSENT`）才可处置；已处置的不再重复出现在反查结果里 | `DelegationBudgetReconciliationIT#terminalIsSettledButUnknownAndUnanchoredAreNot` + `#manualConclusionDisposesUnknownReservation` + `#disposedReservationsDisappearFromReverseLookup`。⚠️ **仍缺**：跨进程的接管演练（真实 Worker 崩溃 → 新 Worker 接管 → 在途记录收敛）仍需故障注入手段 |"""


def patch_08() -> None:
    if _done(F08):
        print("  跳过（已回写）：08_安全测试与验收矩阵.md")
        return
    s = F08.read_text()
    s = _append(s, MA_T24_SECTION + "\n\n" + MARK)
    F08.write_text(s)
    print("  已回写：08_安全测试与验收矩阵.md（MA-T24a～MA-T24i）")


# ── ② 00：评审前置决策标记 + 本批性质登记 ───────────────────────────────
OLD_DECISIONS = """## 评审前置决策
1. 是否将 `SEQUENTIAL` 纳入 P2，或仅先交付 `ROUTER`，由产品/架构在 P1 决策。
2. 最大深度、子调用数、超时和预算默认值由容量与业务风险评估后确定，不把示例值视为生产默认值。
3. `agent_invocation` 的最终状态枚举、父子取消语义、重试和 UNKNOWN 条件须在 P1 状态机/ADR 冻结。
4. `cap_agent_child_ref` 为可选关系投影设计；需由 DDL/迁移评审决定是否存储，Agent release 内容仍为权威来源。
5. P3 安全和恢复验收未通过前，多级 Agent 不得标记为生产可用。"""

NEW_DECISIONS = """## 评审前置决策

> ⚠️ **前三条已于 2026-10-25 逐条拍板**（决策人：zhouwei）：
> ① `SEQUENTIAL` **不纳入 P2**；② **取消平台默认值**，非 LEAF 必须显式声明；
> ③ 状态机 / 取消语义 / 重试 / UNKNOWN 条件**已冻结**（ADR-012）。
> ⚠️ **「已决策」≠「已落地」**：① ② 的落地分别在第三十二批、第三十三批，
> 在那之前**不得**按已落地口径引用。④ ⑤ 仍未闭环。

1. ✅ **已决策（2026-10-25）**：`SEQUENTIAL` **不纳入 P2** —— 语义收敛为「父 Agent 自己串行并聚合，**框架不代劳**」（多子结果聚合不由框架代做；父的输出契约在**父这一层**校验聚合结果）。⚠️ **待落地（第三十二批）**
2. ✅ **已决策（2026-10-25）**：**取消平台默认值** —— 非 LEAF 的 Agent 发布时**必须显式声明**最大深度 / 子调用数 / 超时，缺失即拒绝发布；深度**不级联**（每一跳按当前 Agent 自己的声明判定，与「拓扑取自当前 Agent」自洽）。⚠️ **待落地（第三十三批）**
3. ✅ **已决策（2026-10-25）**：状态枚举 / 父子取消语义 / 重试 / UNKNOWN 条件已冻结 → **ADR-012**（`docs/P0/P1/ADR-012_委派调用状态机与取消语义.md`）+ 08 §MA-T24a～MA-T24i
4. `cap_agent_child_ref` 为可选关系投影设计；需由 DDL/迁移评审决定是否存储，Agent release 内容仍为权威来源。
5. P3 安全和恢复验收未通过前，多级 Agent 不得标记为生产可用。"""

OLD_SNAPSHOT_NOTE = """> ⚠️ **第三十批（C30-1）是文档口径修正批次**：**不改任何实现**，只修交付文档自身的
> 结构与口径缺陷（断表 / 孤儿表格行 / 重复条目 / 已消解却仍写「待补」/ 表格列数不一致），
> 并把表格结构门禁（`scripts/doc-table-check.py`）升级为能拦住这些缺陷。
> 详见文末「历批修正的设计缺陷」表的第三十批行。"""

NEW_SNAPSHOT_NOTE = OLD_SNAPSHOT_NOTE + """

> ⚠️ **第三十一批（C31-1）是评审前置决策 3 的冻结批次**：**不改任何实现** ——
> 只补 ADR-012（状态枚举 / 取消语义 / 重试 / UNKNOWN 条件）与 08 §MA-T24a～MA-T24i，
> 把**已经实现并被用例钉住**的语义正式冻结。
> ⚠️ 「冻结」的意思是「以后照这份文本改」，**不是**「本次改了什么」——
> ADR-012 逐条核对过实现，**无需改动**。"""

OLD_00_MARK = "\n<!-- C30-1 -->\n"
NEW_00_MARK = "\n<!-- C30-1 -->\n\n" + MARK + "\n"


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_DECISIONS, NEW_DECISIONS, "00 评审前置决策")
    s = _sub_once(s, OLD_SNAPSHOT_NOTE, NEW_SNAPSHOT_NOTE, "00 本批性质登记")
    if OLD_00_MARK not in s:
        raise SystemExit("00 未找到 C30-1 标记，无法确定追加位置")
    s = s.replace(OLD_00_MARK, NEW_00_MARK, 1)
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md（决策标记 + 本批性质）")


# ── ③ 09：补 FR-045 行 + 说明段 ───────────────────────────────────────
OLD_FR044 = """| FR-044 | **管理台前端：编排声明与契约登记表单**（第二十九批补充） | 00 §实现状态快照（第二十九批）、08 §MA-T23a～MA-T23h | MA-05、MA-16（第二十九批 C29-1） | AC-033 / MA-T23a、MA-T23b、MA-T23c、MA-T23d、MA-T23e、MA-T23f、MA-T23g、MA-T23h |"""

NEW_FR044 = OLD_FR044 + """
| FR-045 | **委派调用状态机与取消语义的冻结**（第三十一批补充 · ADR-012） | `docs/P0/P1/ADR-012_委派调用状态机与取消语义.md`、08 §MA-T24a～MA-T24i | MA-05、MA-17（第三十一批 C31-1） | AC-034 / MA-T24a、MA-T24b、MA-T24c、MA-T24d、MA-T24e、MA-T24f、MA-T24g、MA-T24h、MA-T24i |"""

OLD_FR044_NOTE_ANCHOR = """⚠️ **FR-033 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""

NEW_FR045_NOTE = """⚠️ **FR-045 是「评审冻结」而非「新需求」**：它冻结的是**已经实现并被用例钉住**的
委派状态语义 —— 七态封闭集合 / 终态不可覆盖 / 协作式取消（取消 ≠ 撤销）/
墙钟超时 / 幂等重放不重复执行 / 终态写入三态可区分 / 四类 UNKNOWN /
接管三步（提升世代 + 收敛 UNKNOWN + 绝不重放）/ 预算处置矩阵。
落地物是 ADR-012 与 08 §MA-T24a～MA-T24i，**不改任何实现**。
⚠️ 决策 3 的原文要求「须在 P1 状态机/ADR 冻结」—— 本条即为该冻结的凭据；
但「多子结果聚合（FR-027）」与「平台默认值」**不在本条范围内**
（分别由评审前置决策 1、2 拍板，落地见第三十二批、第三十三批）。
⚠️ 仍缺：**跨进程的接管演练**（真实 Worker 崩溃 → 新 Worker 接管 → 在途记录收敛）
需故障注入手段，本条不声称已具备。

""" + OLD_FR044_NOTE_ANCHOR


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    s = F09.read_text()
    s = _sub_once(s, OLD_FR044, NEW_FR044, "09 FR-045 行")
    s = _sub_once(s, OLD_FR044_NOTE_ANCHOR, NEW_FR045_NOTE, "09 FR-045 说明段")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F09.write_text(s)
    print("  已回写：09_需求追踪矩阵.md（FR-045 + 说明段）")


def main() -> None:
    print("C31-1 文档回写（决策 3 冻结 · ADR-012）：")
    patch_00()
    patch_08()
    patch_09()
    print("完成。（00 决策标记 + 本批性质 / 08 MA-T24a～MA-T24i / 09 FR-045。）")


if __name__ == "__main__":
    main()
