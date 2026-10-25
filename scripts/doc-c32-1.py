#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十二批 C32-1 文档回写：评审前置决策 1 的落地（`SEQUENTIAL` 语义收敛）。

⚠️ 决策（2026-10-25 · zhouwei）：`SEQUENTIAL` **不纳入 P2** ——
   多子结果聚合**不由框架代劳**，语义收敛为「父 Agent 自己串行并聚合」。

涉及四份文档：

① **06 配置数据模型**：在「AgentDefinition 增补」后补「编排模式语义」小节 ——
   三个模式各自的语义，以及 `resultContractRef` 是**链接契约**的口径澄清。

② **07 执行链集成设计**：补「SEQUENTIAL 的运行期语义」小节 ——
   框架提供什么、框架**不**做什么、失败策略的实际作用面、校验点在哪。

③ **09 需求追踪矩阵**：FR-027 行改写（原始需求是「串行编排及输出聚合」，
   现在要如实标出**聚合部分经评审明确不做**）+ 说明段。

④ **00 修订说明**：
   · 「评审前置决策」第 1 条：**待落地 → 已落地**；
   · **更正第三十一批写错的一句**（原文写「父的输出契约在父这一层校验聚合结果」，
     与实现不符 —— `resultContractRef` 是链接契约，由**调用方**声明）；
   · 「未实现（下一批）」表里的 FR-027 行**删除** ——
     聚合已不是「待做」而是「**明确不做**」，继续留在「未实现」表里
     等于告诉读者这条线还开着；
   · 「历批修正的设计缺陷」表新增第三十二批行（名不副实的口径更正 + 语义钉桩）；
   · 快照标题下登记本批性质。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C32_1_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C32-1 -->"

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


# ── ① 06：编排模式语义 ─────────────────────────────────────────────────
OLD_06 = """缺省 `orchestration` 等价于 `LEAF`。字段仍受 `additionalProperties: false` 约束；应显式更新 JSON Schema，不得关闭额外字段校验来规避迁移。"""

NEW_06 = """缺省 `orchestration` 等价于 `LEAF`。字段仍受 `additionalProperties: false` 约束；应显式更新 JSON Schema，不得关闭额外字段校验来规避迁移。

### 编排模式语义（第三十二批补充 · 评审前置决策 1）

⚠️ **本节是「评审已决策」的口径，不是本批自定的设计**（决策人：zhouwei，2026-10-25）：
`SEQUENTIAL` **不纳入 P2** —— 多子结果聚合**不由框架代劳**。

| 模式 | 语义 |
|---|---|
| `LEAF` | 不发起任何委派（缺省语义） |
| `ROUTER` | 按固定版本的路由策略选择子 Agent（第十四批已落地） |
| `SEQUENTIAL` | 父 Agent 可在其声明的子 Agent 集合内**逐次委派**；**串行与聚合由父 Agent 自己完成，框架不代劳** |

⚠️ **一次委派 = 一个目标 = 一条 `agent_invocation` 记录 = 一次子执行**：
框架**不会**因为声明了多个子 Agent 就替父把子 Agent 全跑一遍再合成 ——
父 Agent 本身是**多轮执行体**，它可以在不同轮次依次委派给不同子 Agent
并把各轮结果合成一份输出，那是**父的模型行为**，不是框架的功能。
（`DelegationExecutorTest#multipleDeclaredChildrenStillRunOnePerDelegation` 钉住这一条）

⚠️ **`resultContractRef` 是「链接契约」**：由**调用方**声明、约束**被调方**的输出形状
（父子输出不同构时用它做映射）。**每一跳的契约都由调用方声明** ——
不是「被调方声明自己的输出契约」，也不是「父声明自己该收到什么再自己校验自己」。
⚠️ 由此推出：父 Agent 聚合后的输出，是它**被调用时**的返回值，
由**调用方声明的链接契约**校验（MA-T16），而不是由父自己声明的契约自校验。

⚠️ **「按输出契约聚合结果」不是框架的动作**：此前 `OrchestrationMode.SEQUENTIAL`
的 javadoc 承诺过它而实现没有 —— 名字许诺了做不到的事，
配置方就会按那个许诺来设计（以为「声明了多个子，框架会替我串起来」）。
这与第十四批之前 ROUTER「名不副实」同构，已于第三十二批更正。
⚠️ **P0-D02（SRS）的 FR-027 原文不改** —— 那是评审原始需求规格，
改写只记在变更包与 00（第三十二批）。"""


def patch_06() -> None:
    if _done(F06):
        print("  跳过（已回写）：06_配置数据模型与生命周期设计.md")
        return
    s = F06.read_text()
    s = _sub_once(s, OLD_06, NEW_06, "06 编排模式语义")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F06.write_text(s)
    print("  已回写：06_配置数据模型与生命周期设计.md（编排模式语义）")


# ── ② 07：SEQUENTIAL 的运行期语义 ──────────────────────────────────────
SEC_07 = """### SEQUENTIAL 的运行期语义：框架不代劳多子结果聚合（第三十二批补充）

⚠️ **评审已决策**（决策人：zhouwei，2026-10-25，评审前置决策 1）：
`SEQUENTIAL` **不纳入 P2**，多子结果聚合**不由框架代劳**。

**框架提供三样，其余是父 Agent 的模型行为**：

| 框架提供 | 说明 |
|---|---|
| 子 Agent 集合 | 委派拓扑：目标必须落在父声明的 `childAgentRefs` 内（MA-T05） |
| 逐跳重新授权 | 每一跳独立判定授权（FR-025）—— 委派关系 ≠ 授权关系 |
| 逐跳资源封顶 | 深度 / 次数 / 超时 / 预算（MA-T09、MA-T17），逐跳下传**有效**值（被根 Run 剩余封顶） |

**框架不做的事**（第三十二批明确）：

- ❌ **不代为串行执行**多个子 Agent；
- ❌ **不代为聚合**多个子 Agent 的输出。

⚠️ **一次委派 = 一个目标 = 一条 `agent_invocation` 记录 = 一次子执行**。
父 Agent 想要「依次跑多个子、把结果合成一份」，那是它**多轮**执行体自己的行为：
第一轮委派给子 A、拿到结果，第二轮委派给子 B —— 框架对每一轮都是
「一次委派 = 一个目标」，判据、记录、授权、预算都按**单跳**走。
（`DelegationExecutorTest#multipleDeclaredChildrenStillRunOnePerDelegation` 钉住这一条）

⚠️ **失败策略的实际作用面**：`FAIL_FAST` / `CONTINUE_ON_ERROR` 作用在
**同一轮**的多个工具调用之间（某个委派失败后，是否继续执行本轮剩下的委派），
失败结果**照常回灌**给父 Agent，**不由框架聚合**。
⚠️ 此前 `DelegationFailurePolicy` 的 javadoc 写的是
「失败结果按输出契约聚合返回父 Agent」—— 框架**没有**这个动作，已更正。

⚠️ **校验点**：父聚合出的输出，是它**被调用时**的返回值，
由**调用方声明的链接契约**（`resultContractRef`）校验（MA-T16）。

⚠️ 此前 `OrchestrationMode.SEQUENTIAL` 的 javadoc 写着
「按 childAgentRefs 声明顺序依次执行子 Agent，**并按输出契约聚合结果（FR-027）**」——
框架**没有**多子结果聚合这个动作。第三十二批已更正口径，
并把「一次委派只跑一个子」钉成**可执行断言**（此前这个语义只存在于文档里）。"""


def patch_07() -> None:
    if _done(F07):
        print("  跳过（已回写）：07_执行链集成设计.md")
        return
    s = F07.read_text()
    anchor = "### 状态与失败语义\n"
    if anchor not in s:
        raise SystemExit("07 未找到锚点小节「状态与失败语义」")
    s = s.replace(anchor, SEC_07 + "\n" + anchor, 1)
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F07.write_text(s)
    print("  已回写：07_执行链集成设计.md（SEQUENTIAL 运行期语义）")


# ── ③ 09：FR-027 行 + 说明段 ───────────────────────────────────────────
OLD_FR027 = """| FR-027 | 串行编排及输出聚合 | orchestration/result contract | MA-06、MA-08 | AC-028 / MA-T16 |"""

NEW_FR027 = """| FR-027 | 串行编排（**输出聚合部分经评审决策 1 明确不做**，第三十二批） | orchestration/result contract、07 §SEQUENTIAL 的运行期语义（第三十二批） | MA-06、MA-08 | AC-028 / MA-T16 |"""

OLD_FR033_ANCHOR = """⚠️ **FR-033 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""

NEW_FR027_NOTE = """⚠️ **FR-027 的原始需求是「串行编排及输出聚合」**（P0-D02 SRS：
「`SEQUENTIAL` 按确定顺序执行并按契约聚合；失败策略明确，输出通过父 Agent Schema」）。
评审已于 2026-10-25 决策（评审前置决策 1）：**`SEQUENTIAL` 不纳入 P2** ——
**多子结果聚合不由框架代劳**，语义收敛为「父 Agent 自己串行并聚合」。
⚠️ **P0-D02 原文不改**（那是评审原始需求规格），改写只记在变更包与 00。
⚠️ 「输出通过父 Agent Schema」这一条的**准确口径**：由**调用方声明的链接契约**
（`resultContractRef`）校验被调方的输出（MA-T16），**不是**父自己声明、自己校验自己。
失败策略（`FAIL_FAST` / `CONTINUE_ON_ERROR`）与输出契约校验**本身已实现**，
缺的从来不是「合成」，而是「**谁负责合成**」这个口径。

""" + OLD_FR033_ANCHOR


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    s = F09.read_text()
    s = _sub_once(s, OLD_FR027, NEW_FR027, "09 FR-027 行")
    s = _sub_once(s, OLD_FR033_ANCHOR, NEW_FR027_NOTE, "09 FR-027 说明段")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F09.write_text(s)
    print("  已回写：09_需求追踪矩阵.md（FR-027 + 说明段）")


# ── ④ 00：决策落地 + FR-027 行迁移 + 历批修正登记 + 批次说明 ─────────────
OLD_DEC1 = """1. ✅ **已决策（2026-10-25）**：`SEQUENTIAL` **不纳入 P2** —— 语义收敛为「父 Agent 自己串行并聚合，**框架不代劳**」（多子结果聚合不由框架代做；父的输出契约在**父这一层**校验聚合结果）。⚠️ **待落地（第三十二批）**"""

NEW_DEC1 = """1. ✅ **已决策（2026-10-25）· 已落地（第三十二批 · C32-1）**：`SEQUENTIAL` **不纳入 P2** —— 语义收敛为「父 Agent 自己串行并聚合，**框架不代劳**」（多子结果聚合不由框架代做）。⚠️ **口径更正**：第三十一批此处写的是「父的输出契约在**父这一层**校验聚合结果」，**与实现不符** —— `resultContractRef` 是**链接契约**，由**调用方**声明、约束**被调方**的输出；父聚合出的输出是它**被调用时**的返回值，由**调用方声明的契约**校验（MA-T16）"""

OLD_FR027_ROW_00 = """| FR-027 `SEQUENTIAL` 串行聚合 | **多子结果聚合**（把多个子 Agent 的输出合成一份交父）未实现：当前一次委派只对应一个目标。⚠️ **本批未做，且是刻意不做**：`SEQUENTIAL` 是否纳入 P2 属**未闭环的评审前置决策**（决策项 1；MA-08 明确「验收后决定是否开放 `SEQUENTIAL`」），未冻结的决策不自行推进；多子结果聚合待决策闭环后再实施。（失败策略本身已生效，缺的是**多子结果的合成**） |
"""

OLD_00_MARK = "\n<!-- C31-1 -->\n"
NEW_00_MARK = "\n<!-- C31-1 -->\n\n" + MARK + "\n"

OLD_SNAPSHOT_NOTE_C31 = """> ⚠️ **第三十一批（C31-1）是评审前置决策 3 的冻结批次**：**不改任何实现** ——
> 只补 ADR-012（状态枚举 / 取消语义 / 重试 / UNKNOWN 条件）与 08 §MA-T24a～MA-T24i，
> 把**已经实现并被用例钉住**的语义正式冻结。
> ⚠️ 「冻结」的意思是「以后照这份文本改」，**不是**「本次改了什么」——
> ADR-012 逐条核对过实现，**无需改动**。"""

NEW_SNAPSHOT_NOTE_C31 = OLD_SNAPSHOT_NOTE_C31 + """

> ⚠️ **第三十二批（C32-1）是评审前置决策 1 的落地批次**：**不改任何行为**，
> 只把 `SEQUENTIAL` 的语义口径收敛到与实现一致，并把语义**钉成可执行断言**。
> ⚠️ 修复前 `OrchestrationMode.SEQUENTIAL` 的 javadoc 承诺「按输出契约聚合结果（FR-027）」，
> 而框架**没有**多子结果聚合这个动作 —— 与第十四批之前 ROUTER「名不副实」同构。"""

NEW_DEFECT_ROW = """| **SEQUENTIAL「名不副实」：声明了框架做不到的聚合（第三十二批修 · C32-1 · 决策 1）** | `OrchestrationMode.SEQUENTIAL` 的 javadoc 承诺「按 childAgentRefs 声明顺序依次执行子 Agent，**并按输出契约聚合结果（FR-027）**」，而框架**没有**多子结果聚合这个动作 —— 一次委派只对应**一个**目标（`DelegationArguments` 是单目标字段）、只落**一条** `agent_invocation` 记录。后果与第十四批之前 ROUTER **同构**：名字许诺了做不到的事，配置方就会按那个许诺来设计（以为「声明了多个子，框架会替我串起来」） | 评审前置决策 1 已拍板（2026-10-25）：`SEQUENTIAL` **不纳入 P2**，语义收敛为「父 Agent 自己串行并聚合，**框架不代劳**」。① **口径更正**：`OrchestrationMode` / `DelegationFailurePolicy` / `CapAgentChildRef.ordinal` 与两个 schema 的描述全部改成与实现一致；② **语义钉成可执行断言**：新增 `DelegationExecutorTest#multipleDeclaredChildrenStillRunOnePerDelegation`（父声明**两个**子时，一次委派仍只执行被选中的那一个、只落一条记录，且**没被选中的那个绝不被捎上执行**）—— 此前这个语义只存在于文档里，没有可执行的事实；③ 顺带更正第三十一批文档的不准确表述：`resultContractRef` 是**链接契约**（由**调用方**声明、约束**被调方**的输出），不是「父自己的输出契约」。⚠️ FR-027 的「未实现」行已从「未实现（下一批）」表**移除** —— 聚合已不是「待做」而是「**明确不做**」，继续留在「未实现」表里等于告诉读者这条线还开着。**证据**：`DelegationExecutorTest#multipleDeclaredChildrenStillRunOnePerDelegation` + `scripts/c32-1-counterproof.sh`（A：注入「代劳聚合」→ **恰好红 1 条**；B：不实承诺措辞 0 → 1） """


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


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_DEC1, NEW_DEC1, "00 评审前置决策 1")
    s = _sub_once(s, OLD_FR027_ROW_00, "", "00 删除 FR-027 未实现行")
    s = _sub_once(s, OLD_SNAPSHOT_NOTE_C31, NEW_SNAPSHOT_NOTE_C31, "00 本批性质登记")
    s = _append_row_to_table(s, "| 缺陷 | 现象 | 修正 |", NEW_DEFECT_ROW,
                              "00 历批修正表登记")
    if OLD_00_MARK not in s:
        raise SystemExit("00 未找到 C31-1 标记，无法确定追加位置")
    s = s.replace(OLD_00_MARK, NEW_00_MARK, 1)
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md（决策落地 + FR-027 行迁移 + 登记）")


def main() -> None:
    print("C32-1 文档回写（决策 1 落地 · SEQUENTIAL 语义收敛）：")
    patch_00()
    patch_06()
    patch_07()
    patch_09()
    print("完成。（00 决策落地 + FR-027 行迁移 + 历批修正登记 / 06 编排模式语义 / "
          "07 SEQUENTIAL 运行期语义 / 09 FR-027 改写。）")


if __name__ == "__main__":
    main()
