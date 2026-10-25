#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十六批 C26-1 文档回写：把「上下文按 token 预算裁剪」登记进 00/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ **本批不写 06**：A7 属于**运行期上下文管理**，不涉及「配置数据模型与生命周期」——
   往 06 塞内容只会稀释那一份文档的职责边界。预算的可配置项是**运行参数**
   （`agent.worker.context-token-budget`），不是 `cap_*` 配置对象。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C26-1 -->"

_PATCHED: set[str] = set()


def _done(path: pathlib.Path) -> bool:
    if path.name in _PATCHED:
        return True
    if MARK in path.read_text():
        _PATCHED.add(path.name)
        return True
    return False


def _mark(path: pathlib.Path, s: str) -> str:
    _PATCHED.add(path.name)
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    return s


def append(path: pathlib.Path, block: str) -> bool:
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text().rstrip("\n") + "\n\n" + block.rstrip("\n") + "\n"
    path.write_text(_mark(path, s))
    print(f"  已回写：{path.name}")
    return True


def _sub_once(text: str, old: str, new: str, where: str) -> str:
    if old not in text:
        raise SystemExit(f"{where} 待替换文本未命中：{old[:70]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:70]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


# ── 00 ──────────────────────────────────────────────────────────────

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十五批：输出契约/路由策略的独立生命周期迁移端点）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十六批：上下文按 token 预算裁剪）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1345 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十四批基线（`c3f2d74`）同口径为 **1332 例** → 本批 **+13 例**
>   （`ContractPolicyLifecycleTest` 11 例 + 新增两个原因码带来的
>   `DenySeverityTest` 参数化定档用例 2 例），增量与新增用例数逐条对得上。
>   ⚠️ **新增原因码会强制补一条定档断言用例**：`DenySeverity.of` 是**穷尽 switch**
>   （不设 default），新码不定档在**编译期**就红 ——
>   「原因码表新增常量却忘了定档」不会漏到线上。
>   ⚠️ **本批新增用例成对断言**（「停用挡得住新引用」与「**没停用的别误伤**」互相独立）：
>   拆掉契约的 DEPRECATED 防线只让前者红，拆掉策略的 DEPRECATED 防线只让后者红 ——
>   否则「全都拒」也能让用例绿。
>   ⚠️ **DB 层证据**：`agent-infrastructure` 全部 IT **387 例**
>   （本机真实 PG17 + RabbitMQ）。
>   ⚠️ ⚠️ **本批如实登记一处既有失败**：`GovernanceQueryIT#listFiltersBySubject`
>   在**改动前同样失败**（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与本批无关，未在本批处理。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十五批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`3b00fd2` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1345 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1361 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十五批基线（`cb60ea3`）同口径为 **1345 例** → 本批 **+16 例**
>   （`ContextBudgetTrimmerTest` 12 例 + `RunExecutionLoopTest` 的「上下文按 token 预算裁剪」
>   3 例 + `WorkerChildAgentRunnerTest` 1 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「裁了但语义不变」与「**不该裁的不裁**」互相独立）：
>   拆掉根路径裁剪只让根路径那条红，拆掉子路径裁剪只让子路径那条红 ——
>   否则「父子两套语义」也能让用例绿。
>   ⚠️ **本批无 DB 层改动**：纯运行期逻辑（agent-worker），故 IT 计数与上一批同口径
>   （`agent-infrastructure` 全部 IT **387 例**，本机真实 PG17 + RabbitMQ）。
>   ⚠️ ⚠️ **上一批如实登记的既有失败仍在**：`GovernanceQueryIT#listFiltersBySubject`
>   在改动前同样失败（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与多级 Agent 改动无关。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十六批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`f84fd20` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1361 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

BLOCK_00_IMPL = """| **上下文按 token 预算裁剪（第二十六批 · C26-1 · FR-041）** | 修复前，根循环与子执行器的消息序列都**随轮次线性增长**（每轮追加「模型意图 + 工具结果」）。单条工具输出虽有 8000 字符封顶（`ToolOutputLimiter`），但轮数一多（`maxSteps` 允许到 20），总量仍能顶爆上下文窗口 —— 那时候模型看到的不是「结果」，而是「一堆噪声 + 供应商报错」，且报错长得像供应商故障，排查方向完全被带偏。现在新增 `ContextBudgetTrimmer`（agent-worker，根/子**共用一份实现**）：① **按「轮」整轮丢弃**（一轮 = 一条 ASSISTANT + 其后连续的若干 TOOL）—— 丢弃点必须落在**轮边界**上，否则会留下「意图被丢、结果被留」的孤儿 TOOL（对模型是无意义噪声，部分供应商直接报错）；② **原始输入永不被裁**（首条 USER 消息是任务本身，裁掉它等于悄悄改了任务）—— 它本身超预算时**保留并显式告警**，绝不静默截断用户输入；③ **裁剪只影响「发给模型的内容」**，被裁掉的消息**不从累积序列里删除**（否则裁一次就永久丢历史，后续轮次再也恢复不了）；④ **裁剪必须留痕**（这是**安全要求**，不是 nicety）—— 若不告诉模型「前面调过哪些工具」，它会以为那些工具**根本没调用过** → **重复调用** → 重复副作用（重复写库 / 重复扣款 / 重复发消息）= Sev-0；留痕写明移除了几轮、约多少 token、期间调用过哪些工具，并明确「请勿重复调用上述工具」；⑤ 留痕走**系统提示**（可信指令载体）—— 混进消息序列会引入角色交替问题，而伪装成用户输入等于把系统事实交给不可信通道。⚠️ **三处刻意的取舍**：① token 口径是**粗估**不是真 tokenizer（汉字 1 字 1 token、其余 3 字符 1 token，两侧都取**偏保守**的一侧 —— 估算偏小会顶爆窗口，估算偏大只是早裁剪）；本仓没有供应商 tokenizer（D-002 未提供）；② **裁剪说明不计入消息预算**（它走系统提示，而预算的口径是「发给模型的消息序列」）—— 若把说明算进来，小预算下说明本身就会把预算吃光（实测：预算 15 token 时，一条 70 token 的说明把上下文压到只剩原始输入）；③ 默认预算 16000 是**本批自定**的保守值（本仓没有「模型上下文窗口」的权威来源），可由 `agent.worker.context-token-budget` 配置。⚠️ **剩余缺口**：① token 估算是粗估，真实口径随模型而变；② 预算**只约束消息序列**，系统提示与工具 Schema 不计入；③ 未按**模型实际上下文窗口**裁剪 | `ContextBudgetTrimmerTest` 12 例 + `RunExecutionLoopTest` 3 例 + `WorkerChildAgentRunnerTest` 1 例 |"""

BLOCK_00_CP = """| **第二十六批反证（七处，逐一验证用例真的会红）** | ① 根路径不裁剪（上下文仍会顶爆窗口）→ **恰 1 条红**（子路径仍绿）；② 子路径不裁剪（父子两套语义）→ **恰 1 条红**（根路径仍绿，证明两处独立）；③ 裁剪不留痕（重复副作用的入口）→ **3 条红**；④ 丢弃点落在轮边界之外（会留下孤立的工具结果）→ **恰 1 条红**；⑤ 原始输入也被裁掉（等于悄悄改了任务）→ **2 条红**；⑥ token 估算偏乐观（实际 token 会超出预算）→ **2 条红**；⑦ 就地改写调用方的累积序列（裁一次就永久丢历史）→ **恰 1 条红**。**七处互相独立、可分别证伪**；脚本带「结论必须变化」的自动判定 —— 改动后结论不变即判为**无效反证**并以非零码退出（编译失败同样判无效：「编译不过」不是「用例红」） | `scripts/c26-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

OLD_00_GAP = """| 根 Agent 回灌后的**上下文膨胀** | 根循环的消息序列随轮次线性增长（每轮追加「意图 + 结果」），单轮工具输出上限 8000 字符（`ToolOutputLimiter`）。`maxSteps` 限制了轮数（典型 ≤ 5），但极端情况下上下文仍可能偏大 —— 尚未引入按 token 预算的上下文裁剪 |"""

NEW_00_GAP = """| ✅ **根 Agent 回灌后的上下文膨胀（第二十六批已修 · C26-1）** | **已落地**：新增 `ContextBudgetTrimmer`（根/子共用一份实现），在**每次模型调用前**按 token 预算裁剪消息序列 —— **按「轮」整轮丢弃**（丢弃点必须落在轮边界，否则会留下孤立的工具结果）、**原始输入永不被裁**、**裁剪只影响发给模型的内容**（累积序列不删）、**裁剪必须留痕**（写明移除了几轮、期间调用过哪些工具，并提示「请勿重复调用上述工具」—— 不留痕的话模型会以为那些工具根本没调用过，从而**重复调用** → 重复副作用 = Sev-0）。⚠️ **剩余缺口**：① token 估算是**粗估**不是真 tokenizer；② 预算**只约束消息序列**，系统提示与工具 Schema 不计入；③ 默认预算值是**本批自定**，未按模型实际上下文窗口裁剪 | `ContextBudgetTrimmerTest` 12 例 + `RunExecutionLoopTest` 3 例 + `WorkerChildAgentRunnerTest` 1 例 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_GAP, NEW_00_GAP, "00")
    lines = s.split("\n")

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("| **第二十五批反证（七处，逐一验证用例真的会红）**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十五批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_00_IMPL, BLOCK_00_CP]

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 07 ──────────────────────────────────────────────────────────────

BLOCK_07 = """
### 上下文的 token 预算裁剪（第二十六批补充）

⚠️ **本节为本批补充** —— 07 原文从未规定「上下文按什么预算裁剪」。

```
每轮模型调用前
    ▼
ContextBudgetTrimmer.trim(messages, budget)
    ├─ 未超预算 → **原样返回**（不该裁的不裁）
    └─ 超预算
       ├─ 保留**首条 USER 消息**（原始输入 = 任务本身，永不裁）
       ├─ 按「轮」整轮丢弃（轮 = 一条 ASSISTANT + 其后连续的若干 TOOL）
       │    ⚠️ 丢弃点必须落在**轮边界**上
       │       否则会留下「意图被丢、结果被留」的孤儿 TOOL
       │       （对模型是无意义噪声，部分供应商直接报错）
       ├─ 保留**最近**的若干轮（最近的上下文对当前决策最相关）
       └─ 生成**裁剪留痕**（见下）
    ▼
ModelRequest
    ├─ messages = 裁剪后的序列
    └─ systemPrompt = Agent 配置的系统提示 **+ 裁剪留痕**
       ⚠️ 留痕走系统提示（可信指令载体）：
          混进消息序列会引入角色交替问题，
          而伪装成用户输入等于把系统事实交给不可信通道
```

⚠️ **裁剪只影响「发给模型的内容」，不影响我们自己的记账**：
被裁掉的消息**不从累积序列里删除** —— 否则裁一次就永久丢历史，后续轮次再也恢复不了。

⚠️ **裁剪必须留痕，这是安全要求而不是 nicety**：
若不告诉模型「前面调过哪些工具」，它会以为那些工具**根本没调用过**，
从而**重复调用** —— 那会产生**重复副作用**
（重复写库 / 重复扣款 / 重复发消息），是 Sev-0 级风险。
故留痕必须写明：移除了几轮、约多少 token、期间调用过哪些工具，
并明确「请勿重复调用上述工具」。

⚠️ **token 口径是粗估，不是真 tokenizer**（本批取舍）：
汉字 1 字 1 token、其余 3 字符 1 token，两侧都取**偏保守**的一侧 ——
估算偏小会让实际 token 超出预算（顶爆窗口），
估算偏大只是**早一点裁剪**（代价远小于前者）。
本仓**没有**供应商 tokenizer（D-002 未提供），真实口径随模型而变。

⚠️ **裁剪说明不计入消息预算**：说明走系统提示，
而预算的口径是「发给模型的消息序列」（系统提示与工具 Schema 都不计入）。
若把说明算进来，小预算下说明本身就会把预算吃光
（实测：预算 15 token 时，一条 70 token 的说明把上下文压到只剩原始输入）。

⚠️ **父子共用同一份实现**（`ContextBudgetTrimmer`）：
根循环与子执行器各写一套裁剪口径必然漂移，
而漂移落在「根裁了子不裁」这类两套语义上。
"""


# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：上下文按 token 预算裁剪的验收补充（第二十六批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「上下文按 token 预算裁剪」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T20a | 估算口径 | 汉字 / 非汉字的 token 估算 | 汉字 **1 字 1 token**；其余 **3 字符 1 token**（**保守**侧） | `ContextBudgetTrimmerTest#chineseIsEstimatedConservatively` / `#asciiIsEstimatedConservatively`（反证 F：2 条红） |
| MA-T20b | 不该裁的不裁 | 未超预算 | **原样返回**，且**不生成裁剪说明** | `ContextBudgetTrimmerTest#withinBudgetIsReturnedUntouched` / `RunExecutionLoopTest#withinBudgetIsNotTrimmed` |
| MA-T20c | 真裁 | 超预算 | 丢弃**最早**的轮次，保留**最近**的；原始输入不动 | `ContextBudgetTrimmerTest#oldestRoundsAreDroppedFirst`（反证 E：2 条红） |
| MA-T20d | 不产生畸形上下文 | 裁剪后每条 TOOL 前面必有对应的模型意图 | **无孤立 TOOL 消息** | `ContextBudgetTrimmerTest#noOrphanToolMessagesRemain`（反证 D：恰 1 条红） |
| MA-T20e | **留痕（安全）** | 裁剪发生时 | 说明里写明**移除了几轮**与**期间调用过哪些工具**，并提示「请勿重复调用上述工具」 | `ContextBudgetTrimmerTest#trimmingLeavesATrace` / `RunExecutionLoopTest#oversizedContextIsTrimmedBeforeModelCall` / `WorkerChildAgentRunnerTest#childContextIsAlsoTrimmedByTokenBudget`（反证 C：3 条红） |
| MA-T20f | 预算生效 | 裁剪后 | 估算 token 数**确实 ≤ 预算** | `ContextBudgetTrimmerTest#trimmedContextIsWithinBudget` |
| MA-T20g | 不改任务 | 原始输入本身超预算 | **保留输入**并标记「超预算」（不得静默截断用户输入） | `ContextBudgetTrimmerTest#oversizedHeadIsKeptAndFlagged`（反证 E：2 条红） |
| MA-T20h | 不丢历史 | 裁剪后 | 调用方的**累积序列不变**（裁一次就永久丢历史是最坏的失效形态） | `ContextBudgetTrimmerTest#trimmingDoesNotMutateTheCallerSequence`（反证 G：恰 1 条红） |
| MA-T20i | 根路径接线 | 根循环上下文超预算 | 发给模型的是**裁剪后**的消息，且系统提示带留痕 | `RunExecutionLoopTest#oversizedContextIsTrimmedBeforeModelCall`（反证 A：恰 1 条红） |
| MA-T20j | 子路径接线 | 子执行上下文超预算 | 与根路径**同一口径**（父子两套语义 = 漂移） | `WorkerChildAgentRunnerTest#childContextIsAlsoTrimmedByTokenBudget`（反证 B：恰 1 条红） |
| MA-T20k | fail-fast | 预算非正 | **显式拒绝**（不得静默当成「无限预算」） | `ContextBudgetTrimmerTest#nonPositiveBudgetIsRejected` |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-041 | **上下文按 token 预算裁剪**（第二十六批补充） | 07 §上下文的 token 预算裁剪 | MA-05、MA-20（第二十六批 C26-1） | AC-031 / MA-T20a、MA-T20b、MA-T20c、MA-T20d、MA-T20e、MA-T20f、MA-T20g、MA-T20h、MA-T20i、MA-T20j、MA-T20k |"

BLOCK_09_2 = """⚠️ **FR-041 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
07/08 原文从未规定「上下文按什么预算裁剪」，本批取的是
「按轮整轮丢弃 + 原始输入不裁 + 裁剪必留痕 + 粗估 token」这一组最小口径。
⚠️ 补充的是**既有约束**在长上下文下的落地 ——
「工具结果回灌」保证了模型看得见自己刚调了什么，
但没有约束「回灌到什么规模为止」；
不约束的结果是上下文随轮次线性增长直到顶爆窗口。
⚠️ **本批刻意把「留痕」写成安全要求而不是提示**：
不留痕 → 模型以为工具没调过 → 重复调用 → 重复副作用 = Sev-0。
⚠️ **剩余缺口**：① token 估算是**粗估**不是真 tokenizer（真实口径随模型而变）；
② 预算**只约束消息序列**，系统提示与工具 Schema 不计入；
③ 默认预算值是**本批自定**，未按模型实际上下文窗口裁剪。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-040 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-040 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-040 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-040 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C26-1 文档回写：")
    patch_00()
    append(F07, BLOCK_07)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。（本批不写 06：A7 属运行期上下文管理，不涉及配置数据模型与生命周期。）")


if __name__ == "__main__":
    main()
