#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十三批 C23-1 文档回写：把「委派记录补模型原始候选」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ 尤其：**「未记录」≠「没有降级」** —— 存量行不回填，
   文档必须如实写明 NULL 的语义，不得写成「降级可被完整还原」。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C23-1 -->"

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


def insert_before(path: pathlib.Path, anchor: str, block: str) -> bool:
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text()
    if anchor not in s:
        raise SystemExit(f"锚点未命中：{path.name} :: {anchor[:60]}")
    s = s.replace(anchor, block + anchor, 1)
    path.write_text(_mark(path, s))
    print(f"  已回写：{path.name}")
    return True


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
        raise SystemExit(f"{where} 待替换文本未命中：{old[:60]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:60]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


# ── 00 ──────────────────────────────────────────────────────────────

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十二批：委派预留的 UNKNOWN 对账通道）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十三批：委派记录补「模型原始候选」）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1320 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十一批基线（`bead702`）同口径为 **1308 例** → 本批 **+12 例**
>   （`DelegationBudgetReconciliationServiceTest` 12 例），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「该处置的要处置」与「**不该动的绝对不动**」互相独立）：
>   拆掉处置只让处置那条红，拆掉 R-007 只让 R-007 那条红 ——
>   否则「全都结算掉」也能让用例绿。
>   ⚠️ **本批另有一层 DB 层证据**：`DelegationBudgetReconciliationIT` 7 例
>   （本机真实 PG17 + 非特权角色真跑，含 **RLS 跨租户**用例）——
>   这条通道的价值<b>全部</b>在那条反查 SQL 上，单元级证据证不了「它在 PG 上真能跑」。
>   ⚠️ **本批的反证覆盖十处**（见「已实现」表的「第二十二批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`bead702` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1320 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1322 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十二批基线（`9b367c6`）同口径为 **1320 例** → 本批 **+2 例**
>   （`RouterDelegationTest` 新增 2 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「看得见降级」与「**不该丢的事实**」互相独立）：
>   拆掉原始候选的落库只让那条红，拆掉生效目标的落库只让另一条红 ——
>   否则「两列都写生效目标」也能让用例绿。
>   ⚠️ **本批另有一层 DB 层证据**：`MultiAgentPersistenceIT` 增 3 例
>   （本机真实 PG17 真跑，含**迁移脚本结构断言**——
>   降级索引的谓词必须排除存量行）。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十三批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`9b367c6` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1322 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

BLOCK_00 = """| **委派记录补「模型原始候选」（第二十三批 · C23-1 · FR-038）** | 修复前是一个<b>审计盲区</b>：`agent_invocation` 记录的是<b>实际生效</b>的目标（这本身是对的 —— 委派记录的职责是「谁真的被调用了」，写模型候选会让审计看到一份「并非事实」的委派）；但 `ORDERED_FALLBACK` 降级时，<b>模型给出的原始候选就此丢失</b> —— 事后无法从数据上直接回答「这次是不是发生了路由降级、模型原本想选谁」，只剩日志留痕，而日志会轮转、会丢。现在：① `AgentInvocation` 增补 `requestedTargetAgentId`（可空，**紧邻生效目标之后**），并提供<b>兼容构造器</b>（不含该列 → NULL），存量调用点不必逐个改参数；② 另提供 `routedFallback()`（原始候选 ≠ 生效目标 = 发生过降级）；③ 执行器落库时<b>两列并存</b> —— 「模型本来想要谁」与「谁真的跑了」<b>互不替代</b>；④ 新增迁移 V25（补列 + 两个部分索引）。⚠️ <b>回填口径：不回填</b>（本批最需要被审的一点）：存量行的模型原始候选在任何<b>持久化</b>位置都不存在 —— 当时只有应用日志留痕，而日志可能已轮转、已归档、已按租户擦除；据此回填 = 用<b>猜测</b>冒充事实，猜错的行会把「没降级」记成「降级过」，比空值更有害 → 存量行一律留 NULL，语义是「<b>当时未记录</b>」（≠「没有降级」）。⚠️ <b>降级索引的谓词必须显式排除 NULL</b>：`IS DISTINCT FROM` 作用在「NULL vs 生效目标」上会判为「不同」，漏掉 `IS NOT NULL` 就把<b>从未降级</b>的历史行算进降级集合 —— 恰恰制造出本列要消除的那种噪音（已用结构断言钉死）。⚠️ <b>剩余缺口</b>：① 降级**只被记录、不被解释** —— 「为什么这条会降级」仍要靠策略配置与日志；② 候选的<b>版本</b>未记录（候选从未被解析出固定 release，记下来反而是误导） | `RouterDelegationTest`（+2 例）+ `MultiAgentPersistenceIT` M-8 / M-8b / M-8c（真实 PG17） |
| **第二十三批反证（七处，逐一验证用例真的会红）** | ① 执行器不写「模型原始候选」（落库为 NULL）→ **3 条红**；② 「模型原始候选」被冒充成「生效目标」→ **2 条红**；③ 降级判定反了（相同才算降级）→ **2 条红**；④ 兼容构造器把「未记录」填成「生效目标」（存量行的 NULL 语义被破坏）→ **恰 1 条红**；⑤ 「未记录」被当成「降级过」（凭空造出降级噪音）→ **恰 1 条红**；⑥ JDBC 读列错位（落库对了、读回来错了）→ **2 条红**；⑦ 降级索引的谓词没排除存量行（历史行被算成降级）→ **恰 1 条红**。**七处互相独立、可分别证伪** | `scripts/c23-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

BLOCK_00_DEFECT = """| **路由降级在数据上看不见 → 事后无法复核「模型原本想选谁」（第二十三批修 · C23-1）** | `ORDERED_FALLBACK` 降级时，委派记录只写**实际生效**的目标 —— 这本身是对的（委派记录的职责是「谁真的被调用了」），但**模型给出的原始候选就此丢失**，只剩日志留痕；而日志会轮转、会丢、会按租户擦除 → 事后无法从数据上直接回答「这次是不是降级了、模型原本想选谁」。现在两列并存：`target_agent_id`（谁真的跑了，权威事实）+ `requested_target_agent_id`（模型本来想要谁，审计线索），另提供 `routedFallback()` 判定。⚠️ <b>存量行不回填</b>：原始候选在持久化层没有任何副本，据日志回填 = 用猜测冒充事实（猜错会把「没降级」记成「降级过」）→ 留 NULL，语义是「**当时未记录**」。⚠️ 降级索引的谓词显式排除 NULL，避免把从未降级的历史行算进降级集合 | `RouterDelegationTest`（+2 例）+ `MultiAgentPersistenceIT` M-8 / M-8b / M-8c |"""

OLD_00_GAP = """| **路由降级后「模型原始候选」未落库** | `ORDERED_FALLBACK` 降级时，委派记录（`agent_invocation`）写的是**实际生效的目标**（这才是「谁真的被调用了」的权威事实），但**模型给出的原始候选没有落库** —— 事后无法从数据上直接看出「这次发生了降级、模型原本想选谁」。当前只有日志留痕。⚠️ 剩余缺口：`agent_invocation` 无「原始候选」列，补列需迁移 + 回填口径设计 | `RouterDelegationTest#routerFallsBackInDeclaredOrder`（断言记录写生效目标）；日志留痕见 `DelegationExecutor.selectByRoutingPolicy` |"""

NEW_00_GAP = """| ✅ **路由降级后「模型原始候选」未落库（第二十三批已修）** | **已落地**：`agent_invocation` 增补 `requested_target_agent_id`（迁移 V25），与 `target_agent_id`（实际生效目标）**并存**；读侧提供 `routedFallback()`。⚠️ **回填口径：不回填** —— 存量行的原始候选在持久化层没有任何副本（当时只有日志，可能已轮转/擦除），据此回填 = 用猜测冒充事实 → 存量行留 NULL，语义是「**当时未记录**」（≠「没有降级」）。⚠️ **剩余缺口**：① 降级**只被记录、不被解释**（「为什么这条会降级」仍要靠策略配置与日志）；② 候选的**版本**未记录（候选从未被解析出固定 release，记下来反而是误导） | `RouterDelegationTest`（+2 例）+ `MultiAgentPersistenceIT` M-8 / M-8b / M-8c |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_GAP, NEW_00_GAP, "00")
    lines = s.split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **第二十二批反证（十处，逐一验证用例真的会红）**")]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十二批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00.split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **委派预留的对账通道缺失")]
    if len(hit) != 1:
        raise SystemExit(f"00 C22-1 缺陷行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00_DEFECT.split("\n")

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 06 ──────────────────────────────────────────────────────────────

BLOCK_06 = """
### 委派记录的「模型原始候选」口径（第二十三批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06/07/08 原文从未规定
「路由降级这件事要怎么被记录下来」。

`agent_invocation` 现在**并存**两列关于「目标」的字段：

| 列 | 语义 | 回答的问题 | 可空 |
|---|---|---|---|
| `target_agent_id` | **实际生效**的目标（谁真的被调用了） | 「这次到底是谁干的」 | 否 |
| `requested_target_agent_id` | **模型给出的原始候选**（路由降级<b>前</b>） | 「模型本来想选谁 / 是不是被降级了」 | 是（存量行为 NULL） |

⚠️ **为什么两列都要，不能只留一列**：
只留「生效目标」→ 降级这件事在数据上**消失**，事后无从复核；
只留「候选」→ 审计看到的是一份「**并非事实**」的委派（谁真的跑了就说不清了）。
两列**互不替代**。

⚠️ **回填口径：不回填**（本节最需要被审的一点）：

| 项 | 口径 |
|---|---|
| 为什么不能回填 | 存量行的「模型原始候选」在任何**持久化**位置都不存在 —— 当时只有应用日志留痕，而日志可能已轮转、已归档、已按租户擦除 |
| 为什么不能「据日志回填」 | 据此回填 = 用**猜测**冒充事实：猜错的行会把「没降级」记成「降级过」，比空值更有害 |
| 存量行的取值 | 一律 `NULL` |
| `NULL` 的语义 | 「**当时未记录**」—— ⚠️ **不是**「没有降级」 |
| 读侧义务 | 判定「是否降级」时必须区分「候选 ≠ 生效目标」（降级过）与「候选为 NULL」（无从判断） |

⚠️ **降级索引的谓词必须显式排除 NULL**：
`requested_target_agent_id IS DISTINCT FROM target_agent_id` 在 NULL 左侧会判为「不同」——
若不补 `IS NOT NULL`，**从未发生降级**的历史行会被算进降级集合，
恰恰制造出本列要消除的那种噪音。

⚠️ **候选的版本不记录**：候选目标在降级场景下**从未被解析出固定 release**
（生效目标的版本来自父声明对**生效目标**的引用），
把一个不存在的版本记下来反而是误导 → 本列只记 Agent 标识。
"""

# ── 07 ──────────────────────────────────────────────────────────────

BLOCK_07 = """
### 「模型原始候选」的落库位置（第二十三批补充）

⚠️ **本节为本批补充** —— 07 原文从未规定「路由降级在运行期记到哪里」。

```
路由判定（DelegationExecutor.selectByRoutingPolicy）
    │  模型候选 = command.targetAgentId()   ← 模型给出的原始候选
    │  生效目标 = effectiveTargetId
    │    ├─ 未降级：生效目标 == 模型候选
    │    └─ 降级  ：生效目标 == policy.targets().get(0)（≠ 模型候选）
    ▼
写委派记录（第 6 步，调度子 Agent 之前）
    AgentInvocation(
        targetAgentId           = effectiveTargetId,   ← 谁真的被调用了（权威事实）
        requestedTargetAgentId  = command.targetAgentId(),  ← 模型本来想要谁（审计线索）
        ...)
    ▼
agent_invocation
    · target_agent_id              NOT NULL
    · requested_target_agent_id    NULL（存量行：当时未记录）
    ▼
读侧
    AgentInvocation.routedFallback()
      = requestedTargetAgentId != null && requestedTargetAgentId != targetAgentId
      ⚠️ NULL 时返回 false —— 那只意味着「**无从判断**」，
         不得据此断言「这次没有降级」
```

⚠️ **为什么两列都要在「调度之前」就写好**：
委派记录是在**调度子 Agent 之前**落下的（`DelegationExecutor` 第 6 步插记录、
第 7 步才调度），所以记录里的「生效目标」是**决策的事实**而不是事后追认 ——
「模型原本想选谁」也必须在同一个时点落下，否则 Worker 崩溃后就无从复原。
"""

# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：委派记录「模型原始候选」的验收补充（第二十三批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「路由降级必须能从数据上看出来」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T05v | 降级可见 | `ORDERED_FALLBACK` 降级后，委派记录同时含「原始候选」与「生效目标」 | 原始候选 = 模型给出的那个；生效目标 = 降级后的那个；两者**并存** | `RouterDelegationTest#modelCandidateAndEffectiveTargetAreBothRecorded` / `#routerFallsBackInDeclaredOrder`（反证 A：3 条红；反证 B：2 条红） |
| MA-T05w | 降级判定 | 「原始候选 ≠ 生效目标」= 发生过降级 | `routedFallback()` 为真；未降级时为假 | `RouterDelegationTest#routerDelegatesAllowedTarget` / `#routerFallsBackInDeclaredOrder`（反证 C：2 条红） |
| MA-T05x | 未记录语义 | 存量行（原始候选为 NULL）的语义是「**当时未记录**」 | `requestedTargetAgentId()` 为 NULL；`routedFallback()` 为假；**不得**据此断言「没降级」 | `RouterDelegationTest#unrecordedCandidateIsNotAssertedAsNoFallback` + `MultiAgentPersistenceIT#legacyRowKeepsNullCandidate`（反证 D：恰 1 条红；反证 E：恰 1 条红） |
| MA-T05y | 落库往返 | 「原始候选」可写入、可读回，且与「生效目标」不同 | 读回的原始候选 = 写入值；生效目标**不**被它覆盖 | `MultiAgentPersistenceIT#modelCandidateRoundTripsAlongsideEffectiveTarget`（反证 F：2 条红） |
| MA-T05z | 索引口径 | 降级索引的谓词必须排除存量行（NULL） | 谓词含 `IS NOT NULL` 且含 `IS DISTINCT FROM`；该列可空 | `MultiAgentPersistenceIT#fallbackIndexExcludesLegacyRows`（反证 G：恰 1 条红） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-038 | **委派记录补「模型原始候选」**（第二十三批补充） | 06 §委派记录的「模型原始候选」口径、07 §「模型原始候选」的落库位置 | MA-05（第二十三批 C23-1） | AC-028 / MA-T05v、MA-T05w、MA-T05x、MA-T05y、MA-T05z |"

BLOCK_09_2 = """⚠️ **FR-038 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定「路由降级这件事要怎么被记录下来」
（本批取「两列并存（生效目标 + 模型原始候选）+ 存量行不回填」的最小方案）。
⚠️ 补充的是**既有约束**（MA-T05 的「目标未声明即拒绝」、MA-T15 的「编排声明固化进发布闭包」）
在**运行期审计**上的落地 ——
此前降级只留在日志里，那条约束的运行结果**没有留下任何可复核的痕迹**。
⚠️ **回填口径必须如实登记为「不回填」**：存量行的原始候选在持久化层没有任何副本，
据日志回填 = 用猜测冒充事实 → 存量行留 NULL，语义是「**当时未记录**」（≠「没有降级」）。
⚠️ **剩余缺口**：① 降级**只被记录、不被解释**（「为什么这条会降级」仍要靠策略配置与日志）；
② 候选的**版本**未记录（候选从未被解析出固定 release，记下来反而是误导）。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-037 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-037 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-037 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-037 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C23-1 文档回写：")
    patch_00()
    insert_before(F06, "### 发布闭包算法", BLOCK_06)
    insert_before(F07, "### 上下文与安全", BLOCK_07)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
