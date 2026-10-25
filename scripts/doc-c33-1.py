#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十三批 C33-1 文档回写：评审前置决策 2 的落地（取消平台默认值）。

⚠️ 决策（2026-10-25 · zhouwei）：**取消平台默认值** ——
   非 LEAF 的 Agent 发布时**必须显式声明**最大深度 / 子调用数 / 超时，
   缺失即**拒绝发布**（fail-closed）；深度**不级联**（每一跳按当前 Agent
   自己的声明判定）。

涉及五份文档：

① **06 配置数据模型**：在「编排模式语义（第三十二批）」后补
   「委派限制的声明口径」小节 —— 三个限制按模式的取值、缺失时的行为、
   `null` 的含义、存量配置的迁移影响、深度不级联。

② **07 执行链集成设计**：补「委派限制在运行期的取值」小节 ——
   逐环节列出「决策 2 之前 / 之后」的差别（解析 / 领域模型 / 规范化 JSON /
   闭包指纹 / 运行期守卫），并说明深度不级联与「次数/超时仍全调用树共享」
   这两件**没有**被改变的事。

③ **08 安全测试与验收矩阵**：新增 **MA-T25a～MA-T25h**。

④ **09 需求追踪矩阵**：FR-024 行改写 + 新增 **FR-046** 行 + 说明段。

⑤ **00 修订说明**：
   · 「评审前置决策」第 2 条：**待落地 → 已落地**；
   · 「已决策 ≠ 已落地」的提示改写（三条均已落地/冻结，④ ⑤ 仍未闭环）；
   · 「未实现（下一批）」表三行状态更新：
     ① Run 级默认上限 —— 决策 2 **只覆盖 Agent 编排的三个限制**，
        Run 级默认上限**仍需单独决策**；
     ② 「最大委派深度」不级联 —— 从「未修 · 待评审」改为
        「**已决策（决策 2b）：维持现状 —— 不级联**」；
     ③ 「编排声明入指纹导致在途 Run 一次性失效」—— 追加第三十三批的新实例；
   · 「历批修正的设计缺陷」表新增 3 行（1 行修正 + 2 行**登记不修**）；
   · 第三十二批历史条目追加一条**过时更正**（它写的「默认值尚未闭环」已过时）；
   · 快照标题下登记本批性质。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C33_1_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C33-1 -->"

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


def _append_after(text: str, anchor: str, chunk: str, where: str) -> str:
    """把 chunk 追加到 anchor 所在行之后（anchor 必须唯一命中一行）。"""
    if text.count(anchor) != 1:
        raise SystemExit(f"{where} 锚点不唯一（命中 {text.count(anchor)}）：{anchor[:70]}")
    return text.replace(anchor, anchor + "\n" + chunk.rstrip("\n"), 1)


def _append_row_to_table(text: str, header_exact: str, row: str, where: str) -> str:
    """把一行追加到指定表头的表块**末尾**（表格行必须落在表格内部）。"""
    lines = text.split("\n")
    out: list[str] = []
    i = 0
    n = len(lines)
    done = False
    while i < n:
        if lines[i].strip() != header_exact:
            out.append(lines[i])
            i += 1
            continue
        j = i
        while j < n and lines[j].lstrip().startswith("|"):
            j += 1
        block = lines[i:j]
        if any(r.strip() == row.strip() for r in block):
            raise SystemExit(f"{where} 该行已存在，拒绝重复追加")
        out.extend(block)
        out.append(row)
        done = True
        i = j
    if not done:
        raise SystemExit(f"{where} 未找到表头：{header_exact}")
    return "\n".join(out)


# ── ① 06：委派限制的声明口径 ───────────────────────────────────────────
ANCHOR_06 = """⚠️ **P0-D02（SRS）的 FR-027 原文不改** —— 那是评审原始需求规格，
改写只记在变更包与 00（第三十二批）。"""

SEC_06 = """

### 委派限制的声明口径：不再有平台默认值（第三十三批补充 · 评审前置决策 2）

⚠️ **本节是「评审已决策」的口径，不是本批自定的设计**（决策人：zhouwei，2026-10-25）：
**取消平台默认值** —— 非 LEAF 的 Agent 发布时**必须显式声明**
`maxDelegationDepth` / `maxChildInvocations` / `timeoutMs`，**缺失即拒绝发布**。

| 模式 | 三个限制 | 缺失时的行为 |
|---|---|---|
| `LEAF` | **不适用**（解析为 `null`） | 正常 —— LEAF 不参与委派，限制对它无意义 |
| `ROUTER` / `SEQUENTIAL` | **必填**（三者都要，缺一不可） | **拒绝发布**（`ORCHESTRATION_INVALID`） |

⚠️ **`null` 是「不适用」，不是 0、也不是默认值**：
- 用 `0` 表达「LEAF 没有限制」会制造第二种解读 ——「0 次子调用」看起来像个真实上限；
- 用某个默认值表达同样会制造「这一跳**有**上限」的错觉。

⚠️ **规范化 JSON 只写已声明的字段**（`AgentOrchestrationParser.toJson`）：
LEAF 的规范化 JSON 里**不出现**这三个字段 —— 写进去会让「LEAF 没有限制」
与「LEAF 限制为 0」在指纹口径上长得一样。

⚠️ **判据放在领域模型，不只放在解析器**：`AgentOrchestration` 的构造器
强制「非 LEAF 必须显式声明」，因此**任何**构造路径（解析 / 指纹固化 / 测试替身）
都必须过这一关；否则「从别处 new 出来的编排」就能绕过「必须显式声明」。
⚠️ **运行期同样 fail-closed**：`DelegationGuard` 读到 `null` 即判
`ORCHESTRATION_INVALID` —— 守卫是**安全边界**，读不到上限时**绝不**当成「不限制」。

⚠️ **存量配置的迁移影响**：升级前**没有显式声明**这三个字段的
ROUTER / SEQUENTIAL 配置，**升级后将无法发布**（fail-closed）。
这是决策的直接后果，不是缺陷 —— 但运维必须知道。

⚠️ **深度不级联**（决策 2b）：每一跳按**当前 Agent 自己**声明的
`maxDelegationDepth` 判定，不取父子较小值、不逐层收敛。
这与「拓扑取自当前 Agent」自洽
（`NestedDelegationGateTest#nestedDelegationRespectsCurrentAgentDepthLimit`）。"""


def patch_06() -> None:
    if _done(F06):
        print("  跳过（已回写）：06_配置数据模型与生命周期设计.md")
        return
    s = F06.read_text()
    s = _append_after(s, ANCHOR_06, SEC_06, "06 委派限制声明口径")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F06.write_text(s)
    print("  已回写：06_配置数据模型与生命周期设计.md（委派限制声明口径）")


# ── ② 07：委派限制在运行期的取值 ───────────────────────────────────────
ANCHOR_07 = """并把「一次委派只跑一个子」钉成**可执行断言**（此前这个语义只存在于文档里）。"""

SEC_07 = """

### 委派限制在运行期的取值（第三十三批补充 · 评审前置决策 2）

⚠️ **评审已决策**（决策人：zhouwei，2026-10-25，评审前置决策 2）：
**取消平台默认值** —— 三个限制不再是「读不到就回落」，而是「**读不到就拒绝**」。

| 环节 | 决策 2 之前 | 决策 2 之后 |
|---|---|---|
| 解析（`AgentOrchestrationParser`） | 缺字段 → 回落默认值 | 缺字段 → **解析失败**（`OrchestrationConfigException`） |
| 领域模型（`AgentOrchestration`） | 三个限制是 `int` / `long`，恒有值 | 三个限制**可空**；非 LEAF 缺任一 → **构造失败** |
| 规范化 JSON（`toJson`） | 恒写出三个字段 | **只写已声明的**（LEAF 不写） |
| 闭包指纹（`ClosureFingerprint`） | 三个默认值恒定参与 | **写显式 `null`**（不省略字段 —— 省略会让「没声明」与「声明成默认值」在指纹上分不出来） |
| 运行期守卫（`DelegationGuard`） | 读到的永远是有效值 | 读到 `null` → **fail-closed**（`ORCHESTRATION_INVALID` + 告警日志），**绝不**当成「不限制」 |

⚠️ **深度不级联**（决策 2b）：守卫在**每一跳**读**当前 Agent 自己**声明的
`maxDelegationDepth` —— 不取父子较小值、不逐层收敛。
⚠️ **次数与超时仍然是「全调用树共享」**：本级声明的 `timeoutMs` 是**上界**，
有效预算 = `min(根 Run 剩余时间, 本级 timeoutMs)`；子调用次数同样不因嵌套而重置
（MA-T17）。决策 2 改的只是「这三个值**从哪来**」，**没有**改它们的封顶语义。

⚠️ **在途 Run 会一次性失效**：LEAF 编排的规范化 JSON 不再含这三个字段
→ 闭包指纹内容变化 → 绑定旧指纹的在途 Run 失效。
这与「编排声明入指纹导致在途 Run 一次性失效」是**同类已知行为**，
不是新缺陷；但**必须在发布前告知运维**。"""


def patch_07() -> None:
    if _done(F07):
        print("  跳过（已回写）：07_执行链集成设计.md")
        return
    s = F07.read_text()
    s = _append_after(s, ANCHOR_07, SEC_07, "07 委派限制运行期取值")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F07.write_text(s)
    print("  已回写：07_执行链集成设计.md（委派限制运行期取值）")


# ── ③ 08：MA-T25a～MA-T25h ──────────────────────────────────────────────
ANCHOR_08 = """| MA-T24h | **UNKNOWN 不自动处置** | 委派预留卡在 UNKNOWN / 人工给出收敛结论 | UNKNOWN **既不结算也不释放**，只统计转人工；须有**明确的收敛结论**（`RECONCILED_SUCCEEDED` / `RECONCILED_ABSENT`）才可处置；已处置的不再重复出现在反查结果里 | `DelegationBudgetReconciliationIT#terminalIsSettledButUnknownAndUnanchoredAreNot` + `#manualConclusionDisposesUnknownReservation` + `#disposedReservationsDisappearFromReverseLookup`。⚠️ **仍缺**：跨进程的接管演练（真实 Worker 崩溃 → 新 Worker 接管 → 在途记录收敛）仍需故障注入手段 |"""

SEC_08 = """

### 变更补充：取消平台默认值后的委派限制声明验收（第三十三批 · 决策 2 落地）

⚠️ **本批验收项对应的是「评审已决策并已落地」的口径**（决策人：zhouwei，2026-10-25）：
**取消平台默认值** —— 非 LEAF 必须显式声明三个委派限制，缺失即拒绝发布。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T25a | **无默认值兜底** | 非 LEAF 编排声明里**不写** `maxDelegationDepth` | **拒绝解析/发布**（fail-closed），**不得**回落到某个默认数字 | `AgentOrchestrationParserTest#nonLeafWithoutLimitsIsRejected` |
| MA-T25b | **缺一个 = 缺三个** | 三个限制里**只缺一个** | **同样拒绝** —— 少一道上限在多级场景里会指数放大（每层各自的上限相乘），所以不能只校验「三个都在」 | `AgentOrchestrationParserTest#eachMissingLimitIsRejectedIndividually` |
| MA-T25c | **判据在领域模型** | 绕过解析器、直接 `new AgentOrchestration(...)` 造一个非 LEAF 且缺限制的编排 | **同样失败** —— 校验在**构造器**里，任何构造路径（解析 / 指纹固化 / 测试替身）都过这一关 | `AgentOrchestration` 构造器 + `scripts/c33-1-counterproof.sh`（反证 A：摘掉构造器校验 → **恰好红 N 条**，其余保持绿） |
| MA-T25d | **LEAF 的限制是 `null`，不是 0** | 缺省编排（无 `orchestration` 字段）与显式 `LEAF` | 三个限制均为 `null`（**不适用**），**不得**是 0、也不得是某个默认值 | `AgentOrchestrationParserTest#leafLimitsAreNull` + `#missingOrchestrationIsLeafWithNullLimits` |
| MA-T25e | **规范化 JSON 不写未声明的字段** | LEAF 的编排被序列化为规范化 JSON | **不出现** `maxDelegationDepth` / `maxChildInvocations` / `timeoutMs` —— 写一个 0 会让「LEAF 没有限制」与「LEAF 限制为 0」在指纹口径上长得一样 | `AgentOrchestrationParser.toJson`（null 不写字段）+ `ClosureFingerprint`（写显式 `null` 而非省略） |
| MA-T25f | **契约同步必填** | 用 `agent.schema.json` 校验一份非 LEAF 编排 | `maxDelegationDepth` / `maxChildInvocations` / `timeoutMs` **三者必填**（`allOf` + `if/then`）；LEAF 不适用 | `agent.schema.json#properties.orchestration.allOf` + `scripts/c33-1-counterproof.sh`（反证 B：删掉 `allOf` → 同一份声明的校验结论由「无效」变「有效」） |
| MA-T25g | **管理台当场拒** | 管理台提交非 LEAF 编排但三个限制留空 | **当场拒**（不是提交后才被服务端门禁拒）；**LEAF 留空合法**（不适用 ≠ 缺失） | `orchestrationForm.test.tsx`（非 LEAF 缺任一限制 → 逐个被拒 / LEAF 留空合法 / `maxChildInvocations=0` 合法） |
| MA-T25h | **深度不级联** | 父声明深度 2、子声明深度 5 | **按当前 Agent 自己声明**判定（每一跳重新取当前 Agent 的限制），**不取父子较小值**、不逐层收敛 | `NestedDelegationGateTest#nestedDelegationRespectsCurrentAgentDepthLimit` + `#nestedDelegationUsesCurrentAgentDeclaration` |"""


def patch_08() -> None:
    if _done(F08):
        print("  跳过（已回写）：08_安全测试与验收矩阵.md")
        return
    s = F08.read_text()
    s = _append_after(s, ANCHOR_08, SEC_08, "08 MA-T25")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F08.write_text(s)
    print("  已回写：08_安全测试与验收矩阵.md（MA-T25a～MA-T25h）")


# ── ④ 09：FR-024 改写 + FR-046 行 + 说明段 ───────────────────────────────
OLD_FR024 = """| FR-024 | 委派深度/次数/超时/预算约束 | Run 预算与执行链 | MA-05 | AC-024 / MA-T09、T17 |"""

NEW_FR024 = """| FR-024 | 委派深度/次数/超时/预算约束（**限制值非 LEAF 必须显式声明**，第三十三批） | Run 预算与执行链、06 §委派限制的声明口径（第三十三批） | MA-05、MA-21（第三十三批 C33-1） | AC-024 / MA-T09、T17、MA-T25a～MA-T25h |"""

ANCHOR_FR045 = """| FR-045 | **委派调用状态机与取消语义的冻结**（第三十一批补充 · ADR-012） | `docs/P0/P1/ADR-012_委派调用状态机与取消语义.md`、08 §MA-T24a～MA-T24i | MA-05、MA-17（第三十一批 C31-1） | AC-034 / MA-T24a、MA-T24b、MA-T24c、MA-T24d、MA-T24e、MA-T24f、MA-T24g、MA-T24h、MA-T24i |"""

NEW_FR046 = """| FR-046 | **取消平台默认值：非 LEAF 必须显式声明委派限制**（第三十三批补充 · 评审前置决策 2） | 06 §委派限制的声明口径（第三十三批）、07 §委派限制在运行期的取值（第三十三批）、08 §MA-T25a～MA-T25h | MA-05、MA-21（第三十三批 C33-1） | AC-035 / MA-T25a、MA-T25b、MA-T25c、MA-T25d、MA-T25e、MA-T25f、MA-T25g、MA-T25h |"""

ANCHOR_FR045_NOTE = """⚠️ **FR-045 是「评审冻结」而非「新需求」**："""

NEW_FR046_NOTE = """⚠️ **FR-046 是「评审已决策并已落地」的配套条目**（决策人：zhouwei，2026-10-25）：
**取消平台默认值** —— 非 LEAF 的 Agent 发布时**必须显式声明**
`maxDelegationDepth` / `maxChildInvocations` / `timeoutMs`，**缺失即拒绝发布**（fail-closed）；
深度**不级联**（每一跳按当前 Agent 自己声明判定）。
⚠️ **本批的两个副作用必须知情**：① **存量配置将无法发布** —— 升级前未显式声明
这三个字段的 ROUTER / SEQUENTIAL 配置，升级后被 fail-closed 拒绝；
② **在途 Run 一次性失效** —— LEAF 编排的规范化 JSON 不再含这三个字段 →
闭包指纹内容变化 → 绑定旧指纹的在途 Run 失效（**同类已知行为**，非新缺陷）。
⚠️ **FR-046 只覆盖「Agent 编排的三个委派限制」** ——
**不**覆盖 Run 级的默认上限（见 00「未实现（下一批）」表的对应行：那一项**仍需单独决策**）。

""" + ANCHOR_FR045_NOTE


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    s = F09.read_text()
    s = _sub_once(s, OLD_FR024, NEW_FR024, "09 FR-024 行")
    s = _append_after(s, ANCHOR_FR045, NEW_FR046, "09 FR-046 行")
    s = _sub_once(s, ANCHOR_FR045_NOTE, NEW_FR046_NOTE, "09 FR-046 说明段")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F09.write_text(s)
    print("  已回写：09_需求追踪矩阵.md（FR-024 改写 + FR-046 行 + 说明段）")


# ── ⑤ 00：决策落地 + 三行状态更新 + 历批修正登记 + 本批性质 ─────────────
OLD_DEC2 = """2. ✅ **已决策（2026-10-25）**：**取消平台默认值** —— 非 LEAF 的 Agent 发布时**必须显式声明**最大深度 / 子调用数 / 超时，缺失即拒绝发布；深度**不级联**（每一跳按当前 Agent 自己的声明判定，与「拓扑取自当前 Agent」自洽）。⚠️ **待落地（第三十三批）**"""

NEW_DEC2 = """2. ✅ **已决策（2026-10-25）· 已落地（第三十三批 · C33-1）**：**取消平台默认值** —— 非 LEAF 的 Agent 发布时**必须显式声明**最大深度 / 子调用数 / 超时，缺失即拒绝发布；深度**不级联**（每一跳按当前 Agent 自己的声明判定，与「拓扑取自当前 Agent」自洽）。⚠️ **存量影响**：升级前**没有显式声明**这三个字段的 ROUTER / SEQUENTIAL 配置，**升级后将无法发布**（fail-closed 拒绝）—— 这是决策的直接后果，不是缺陷，但必须让运维知道"""

OLD_DECISION_NOTE = """> ⚠️ **「已决策」≠「已落地」**：① ② 的落地分别在第三十二批、第三十三批，
> 在那之前**不得**按已落地口径引用。④ ⑤ 仍未闭环。"""

NEW_DECISION_NOTE = """> ⚠️ **「已决策」≠「已落地」**：① 已于第三十二批落地、② 已于第三十三批落地、
> ③ 已于第三十一批冻结 —— 在各自落地之前**不得**按已落地口径引用。
> ④ ⑤ **仍未闭环**，不得自行推进。"""

OLD_C32_NOTE_TAIL = """> ⚠️ 修复前 `OrchestrationMode.SEQUENTIAL` 的 javadoc 承诺「按输出契约聚合结果（FR-027）」，
> 而框架**没有**多子结果聚合这个动作 —— 与第十四批之前 ROUTER「名不副实」同构。"""

NEW_C32_NOTE_TAIL = OLD_C32_NOTE_TAIL + """

> ⚠️ **第三十三批（C33-1）是评审前置决策 2 的落地批次**：**取消平台默认值**。
> 三个委派限制（最大深度 / 子调用数 / 超时）从「有平台默认值」改为
> **非 LEAF 必须显式声明**，缺失即**拒绝发布**（fail-closed）；
> LEAF 的三个限制为 `null`（**不适用**，不是 0、不是默认值）。
> ⚠️ **这是本批唯一的运行时行为变更**（其余是口径与契约同步），因此有两处**必须登记**的后果：
> ① **存量配置将无法发布** —— 升级前未显式声明这三个字段的 ROUTER / SEQUENTIAL 配置，
> 升级后被 fail-closed 拒绝（**这是决策的直接后果，不是缺陷**，但必须让运维知道）；
> ② **在途 Run 一次性失效** —— LEAF 编排的规范化 JSON 不再含这三个字段
> → 闭包指纹内容变化 → 绑定旧指纹的在途 Run 失效（与「编排声明入指纹导致在途 Run 一次性失效」同类，属**已知预期行为**）。"""

OLD_RUN_ROW = """| 未声明时间预算的 Run **仍不限时** | Run 级截止时间已贯通（`run.deadline_at`），但截止时间**由创建方显式声明**（`CreateRunRequest.timeoutMs`）：未声明 = `NULL` = 不限时，此时只剩各 Agent 自己声明的 `timeoutMs` 生效。**尚未引入配置侧的 Run 级默认上限**（平台默认值是占位，不得当作生产默认值）—— 该项需评审决策项 2 闭环 |"""

NEW_RUN_ROW = """| 未声明时间预算的 Run **仍不限时** | Run 级截止时间已贯通（`run.deadline_at`），但截止时间**由创建方显式声明**（`CreateRunRequest.timeoutMs`）：未声明 = `NULL` = 不限时，此时只剩各 Agent 自己声明的 `timeoutMs` 生效。**尚未引入配置侧的 Run 级默认上限**（平台默认值是占位，不得当作生产默认值）—— ⚠️ **第三十三批更正**：评审前置决策 2 已于 2026-10-25 闭环，但**只覆盖「Agent 编排的三个委派限制」**（非 LEAF 必须显式声明）；**Run 级默认上限不在其内**，仍需**单独决策**，不得默认继承决策 2 的结论 |"""

OLD_FINGERPRINT_ROW = """| **编排声明入指纹导致在途 Run 一次性失效** | 闭包指纹算法变了（增 `orchestration` 段），**变更前创建的 Run 快照指纹与新算出的不一致** → 运行期落 `CONFIG_FINGERPRINT_STALE`，在途 Run 被拒。这是**预期行为**：旧指纹根本没覆盖编排声明，那份「内容没变」的证明本就不完整，继续放行等于让「配置变了但指纹说没变」成立（MA-T15 的核心命题）。fail-closed 优先于「让在途 Run 跑完」。**上线前需评估在途 Run 存量** |"""

NEW_FINGERPRINT_ROW = """| **编排声明入指纹导致在途 Run 一次性失效** | 闭包指纹算法变了（增 `orchestration` 段），**变更前创建的 Run 快照指纹与新算出的不一致** → 运行期落 `CONFIG_FINGERPRINT_STALE`，在途 Run 被拒。这是**预期行为**：旧指纹根本没覆盖编排声明，那份「内容没变」的证明本就不完整，继续放行等于让「配置变了但指纹说没变」成立（MA-T15 的核心命题）。fail-closed 优先于「让在途 Run 跑完」。**上线前需评估在途 Run 存量**。⚠️ **第三十三批新增同类实例**：取消平台默认值后，LEAF 编排的规范化 JSON 不再含 `maxDelegationDepth` / `maxChildInvocations` / `timeoutMs` → 闭包指纹内容变化 → **在途 Run 一次性失效**。同样是**预期行为**（旧指纹里含三个占位默认值，那份证明同样不完整），同样**上线前需评估在途 Run 存量** |"""

OLD_DEPTH_ROW = """| **「最大委派深度」不级联（第十五批发现 · 未修）** |  祖先 Agent 声明的 `maxDelegationDepth` **不自动约束**后代 Agent 声明的更大上限：根声明 3、子声明 5，则深度 4 的调用在根的视角下越界、却在子的视角下合法 —— 两个视角给出两个答案。⚠️ **本批刻意不修**，理由有二：① 这是**配置一致性**问题，不是模型可利用的攻击面（模型改不了配置）；② 级联语义本身是**未闭环的设计决策**（后代声明更大值可能是刻意意图，强行取小会废掉「按层放宽」这类合理编排）→ 属未闭环范围，先登记为缺口，待评审决策项 2（最大深度与预算默认值）一并闭环。⚠️ 修复前的口径下，嵌套路径按**当前 Agent** 判定（第十五批 C15-1）—— 只影响「本级声明」这一个真相，不放松任何既有边界；**证据**：`NestedDelegationGateTest#nestedDelegationRespectsCurrentAgentDepthLimit`（钉死「按当前 Agent 的上限判定」这一侧；级联语义待评审） |"""

NEW_DEPTH_ROW = """| ✅ **「最大委派深度」不级联（第十五批发现 · 第三十三批已决策 · 维持现状）** |  祖先 Agent 声明的 `maxDelegationDepth` **不自动约束**后代 Agent 声明的更大上限：根声明 3、子声明 5，则深度 4 的调用在根的视角下越界、却在子的视角下合法 —— 两个视角给出两个答案。⚠️ **第十五批刻意不修**，理由有二：① 这是**配置一致性**问题，不是模型可利用的攻击面（模型改不了配置）；② 级联语义本身是**未闭环的设计决策**（后代声明更大值可能是刻意意图，强行取小会废掉「按层放宽」这类合理编排）。⚠️ **第三十三批：评审前置决策 2b 已拍板（2026-10-25）—— 深度「不级联」维持现状**（每一跳按当前 Agent 自己声明判定，与「拓扑取自当前 Agent」自洽），**不改为级联**。本行从「未修 · 待评审」转为「**已决策 · 维持现状**」；⚠️ **两个视角给出两个答案这一现象仍在**（那是「不级联」的必然结果，不是缺陷），需要跨 Agent 拓扑一致性的运维手段兜住。**证据**：`NestedDelegationGateTest#nestedDelegationRespectsCurrentAgentDepthLimit`（钉死「按当前 Agent 的上限判定」这一侧） |"""

OLD_C29_TAIL = """⚠️ **数值字段留空即不写入** —— 默认值口径属**评审前置决策 2**（最大深度与预算默认值，**尚未闭环**），前端一旦预填默认值就等于替评审先定了一套口径，且会成为「第二个默认值来源」（前后端各一份，迟早漂移）；故前端只提供输入、不提供默认。"""

NEW_C29_TAIL = OLD_C29_TAIL + """ ⚠️ **第三十三批更正**：「尚未闭环」已过时 —— 评审前置决策 2 已于 2026-10-25 闭环并落地（**取消平台默认值**，非 LEAF 必须显式声明）。本条「前端只提供输入、不提供默认」的结论**依然成立**（前端仍然不预填默认值），但「留空」的含义已变：现在是「**非 LEAF 留空 = 非法**」，而不再是「留空 = 服务端兜底」。"""

NEW_DEFECT_ROW_1 = """| **平台默认值让「没想过上限」变成「有上限」（第三十三批修 · C33-1 · 决策 2）** | `AgentOrchestration` 原本有三个常量 `DEFAULT_MAX_DELEGATION_DEPTH=2` / `DEFAULT_MAX_CHILD_INVOCATIONS=8` / `DEFAULT_TIMEOUT_MS=60000`，解析时缺字段就回落到它们。⚠️ **这三个数字从未经过容量与业务风险评估** —— 它们只是示例值；但以「默认值」的形式存在，就会被配置方**默默继承**，然后在没人评审的情况下成为生产口径。NFR-012 允许「使用**经过批准**的平台默认值」，而这三个值**从未被批准过** —— 批准的前提是「有评估」，而不是「有个数字」 | 评审前置决策 2 已拍板（2026-10-25）：**取消平台默认值**。① **三个常量删除**（不留「兼容入口」—— 留了就等于默认值还在）；② 三个字段**可空化**（`@Nullable Integer` / `@Nullable Long`），`null` = **不适用**（LEAF），**不是 0、不是默认值**；③ **判据放在领域模型构造器**（`AgentOrchestration`）而非只放在解析器 —— 任何构造路径（解析 / 指纹固化 / 测试替身）都必须过这一关，否则「从别处 new 出来的编排」就能绕过「必须显式声明」；④ **运行期守卫 fail-closed**（`DelegationGuard`：读到 `null` 即 `ORCHESTRATION_INVALID` + 告警，**绝不**当成「不限制」）；⑤ **契约同步必填**（`agent.schema.json` 用 `allOf` + `if/then` 表达「非 LEAF 必填」）；⑥ **管理台同步当场拒**（`orchestrationForm.ts`）；⑦ **存量 fixture 迁移 40 处**（`scripts/c33-1-fixture-migration.py`）—— 否则那些用例会因「编排声明非法」而红，而**不是因为它本来要测的那件事**。**证据**：`AgentOrchestrationParserTest#nonLeafWithoutLimitsIsRejected` + `#eachMissingLimitIsRejectedIndividually` + `#leafLimitsAreNull` + `#missingOrchestrationIsLeafWithNullLimits` + `scripts/c33-1-counterproof.sh`（A：摘掉构造器校验 → **恰好红 N 条**；B：删掉 schema 的 `allOf` → 同一份声明的校验结论由「无效」变「有效」） |"""

NEW_DEFECT_ROW_2 = """| **契约说「引用是对象」，实现要「`id@version` 字符串」（第三十三批登记 · 未修）** | `common.schema.json#/$defs/fixedVersionRef` 把固定版本引用定义为**对象** `{id, version}`（`routingPolicyRef` / `resultContractRef` 都指向它），而 `AgentOrchestrationParser.parsePinnedRef` 要求的是**字符串** `"id@version"`（形如 `policy-a@1`）。后果：按 schema 构造的客户端提交会被服务端**拒绝**（`routingPolicyRef 必须是字符串引用`）。⚠️ 散文口径（06 §编排绑定承载什么 / 00 多处）与**实现**都是 `id@version` —— 只有 schema 是对象形；而 `childAgentRefs` 的元素（`childAgentRef`）在 schema 与实现里**都是对象**形 ⇒ 实现自身也不自洽 | **本批只登记不修**：改 schema 的形状会动到**对外契约**（`fixedVersionRef` 还被 `routing-policy.schema.json` 的描述引用），改实现的形状则是**另一个议题**（策略引用与契约引用要不要也改成对象形）。两者都需要**单独拍板**，不在评审前置决策 2 的范围内。⚠️ 本批**没有**改动引用的形状 —— 只加了「非 LEAF 必填三个限制」的 `allOf` |"""

NEW_DEFECT_ROW_3 = """| **限制值有上界，但后端不校验上界（第三十三批登记 · 未修）** | `agent.schema.json` 给三个限制定了上界（深度 ≤ 8、子调用次数 ≤ 64、超时 ≤ 3600000 毫秒），而 `AgentOrchestration` 构造器**只校验下界**（深度 ≥ 1、次数 ≥ 0、超时 ≥ 1）。后果：一份「深度 999」的配置能通过后端发布期校验，与契约声明的上界**不一致** | **本批只登记不修**：上界该取多少本身是**容量口径**，需要业务与容量评估；决策 2 只冻结了「**不得有默认值**」与「**必须显式声明**」，**没有**冻结上界数值。本批**没有**擅自加上界 —— 加上一个未经评估的数字，与它们当初作为「示例默认值」被取消是同一种错误 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_DEC2, NEW_DEC2, "00 评审前置决策 2")
    s = _sub_once(s, OLD_DECISION_NOTE, NEW_DECISION_NOTE, "00 已决策≠已落地 提示")
    s = _sub_once(s, OLD_C32_NOTE_TAIL, NEW_C32_NOTE_TAIL, "00 本批性质登记")
    s = _sub_once(s, OLD_RUN_ROW, NEW_RUN_ROW, "00 未实现表 · Run 级默认上限行")
    s = _sub_once(s, OLD_FINGERPRINT_ROW, NEW_FINGERPRINT_ROW, "00 未实现表 · 指纹失效行")
    s = _sub_once(s, OLD_DEPTH_ROW, NEW_DEPTH_ROW, "00 未实现表 · 深度不级联行")
    s = _sub_once(s, OLD_C29_TAIL, NEW_C29_TAIL, "00 C29-1 历史条目过时更正")
    s = _append_row_to_table(s, "| 缺陷 | 现象 | 修正 |", NEW_DEFECT_ROW_1,
                              "00 历批修正表 · 主修正行")
    s = _append_row_to_table(s, "| 缺陷 | 现象 | 修正 |", NEW_DEFECT_ROW_2,
                              "00 历批修正表 · 登记行 A")
    s = _append_row_to_table(s, "| 缺陷 | 现象 | 修正 |", NEW_DEFECT_ROW_3,
                              "00 历批修正表 · 登记行 B")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md（决策落地 + 三行状态更新 + 三行登记）")


def main() -> None:
    print("C33-1 文档回写（决策 2 落地 · 取消平台默认值）：")
    patch_00()
    patch_06()
    patch_07()
    patch_08()
    patch_09()
    print("完成。（00 决策落地 + 未实现表三行状态更新 + 历批修正三行登记 / "
          "06 委派限制声明口径 / 07 委派限制运行期取值 / 08 MA-T25a～MA-T25h / "
          "09 FR-024 改写 + FR-046。）")


if __name__ == "__main__":
    main()
