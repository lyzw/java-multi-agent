#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十二批 C22-1 文档回写：把「委派预留的 UNKNOWN 对账通道」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型** ——
   文档里必须如实标注，不得虚报实现状态。
⚠️ 尤其：本批修的是一个**真实的额度泄漏**（额度被占着却查无实据），
   但**不得**写成「对账已完备」—— 触发时机（调度器）与对外入口仍未接上。

⚠️ **落点纪律**：表格行必须落在**表格内部**，否则 markdown 会把 `| a | b |`
   渲染成普通段落。故 00/09 一律用「锚定表格末行 → 插到其后」的方式。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C22-1 -->"

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

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十一批：根 Agent 按 Run 快照读真实定义）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十二批：委派预留的 UNKNOWN 对账通道）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1308 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十批基线（`e5e0517`）同口径为 **1292 例** → 本批 **+16 例**
>   （`AgentModelConfigAssemblerTest` 8 例 + `RootAgentModelConfigProviderTest` 7 例 +
>   `RootDelegationToolSchemaWiringTest` 由 6 例重写为 7 例 → **+1 例**），
>   增量与新增用例数逐条对得上（8 + 7 + 1 = 16）。
>   ⚠️ **本批新增用例仍成对断言**（「看得见委派工具」与「不该给的不给」互相独立）：
>   拆掉装配只让装配那条红，拆掉 fail-closed 只让 fail-closed 那条红 ——
>   否则「全都给委派工具」也能让用例绿。
>   ⚠️ **本批的反证覆盖八处**（见「已实现」表的「第二十一批反证」行），
>   其中「子路径自写一份解析」→ 子路径用例**仍绿**，用来证明共用实现
>   **没有改变子路径行为**（子路径可独立回退）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`e5e0517` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1308 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1320 例 / 0 failures / 0 errors / 25 skipped）**；
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

BLOCK_00 = """| **委派预留的 UNKNOWN 对账通道（第二十二批 · C22-1 · FR-037）** | 修复前是一个<b>真实的额度泄漏</b>：委派的预算预留记在 `budget_reservation` 上，但它的 `operation_id` 是<b>委派调用标识</b>，而委派记在 `agent_invocation` 里、<b>没有</b>对应的 `tool_operation` 行 —— 而预算对账的两条查询（`findOrphaned` / `findUnknownHeld`）都用 <b>INNER JOIN `tool_operation`</b> → 委派的预留对它们<b>完全不可见</b>。后果有两层：① 卡在 `UNKNOWN` 的委派，其预留永远停在 `RESERVED`，额度被长期占用；② 更糟的是<b>它连「转人工清单」都进不去</b>（`unknownHeld` 统计恒为 0 → `needsAttention()` 恒为 false → 运维看到的是「一切正常」）；③ 闸门在「预留成功」与「结算/释放」之间崩溃的委派 → 同样是看不见的悬挂额度。现在：① 新端口 `DelegationBudgetPort` —— 反查口径为 `budget_reservation` <b>LEFT JOIN</b> `agent_invocation`，并用「工具侧无锚点」把两条通道划成<b>互补</b>而非重叠；② `JdbcDelegationBudgetRepository` —— 用 LEFT JOIN 而非 INNER JOIN，好让「连 `agent_invocation` 行都没有」的预留（委派终态提交报 `NOT_FOUND`）也能被捞出来；③ `DelegationBudgetReconciliationService` —— 处置矩阵：<b>在途 → 不动</b>（子执行可能还在跑）、<b>UNKNOWN → 不动</b>（R-007）只统计并转人工、<b>确定终态 → 结算</b>、<b>无锚点 → 不动</b>（查无实据不改账）；人工处置入口只接受「对账已收敛」的结论，且拒绝覆盖确定终态；④ `BudgetReconciliationService` 纳入委派侧统计 —— 未装配时<b>显式告警</b>，绝不静默跳过。⚠️ <b>终态委派一律「结算」而非照抄工具侧的「成功结算、其余释放」</b>：「`agent_invocation` 有记录」等价于「子执行已被调度」（记录在调度之前落下），照抄工具侧会把子 Agent 已经花掉的钱<b>白送</b>出去。⚠️ <b>剩余缺口</b>：① 对账<b>没有调度器</b>（与工具侧同款 —— 本批只提供能力，触发时机待 D7 审计链路接入）；② <b>没有对外的处置入口</b>（API / 运维命令）—— 人工收敛结论目前只能由应用层调用服务方法；③ 对账结论<b>未回写 `agent_invocation`</b>（新增状态属评审前置决策 3「状态机与取消语义」，本批不碰） | `DelegationBudgetReconciliationServiceTest` 12 例（单元级）+ `DelegationBudgetReconciliationIT` 7 例（真实 PG17，含 RLS 跨租户） |
| **第二十二批反证（十处，逐一验证用例真的会红）** | ① 反查退回「只认 `tool_operation` 锚点」（= 修复前的形状）→ DB 层 **6 条红**；② 反查用 INNER JOIN `agent_invocation`（丢掉「无锚点」那一类）→ **恰 1 条红**；③ 去掉「工具侧」排除条件（两条通道重叠）→ **恰 1 条红**；④ `UNKNOWN` 也被自动结算（R-007 被拆）→ **3 条红**；⑤ 终态委派改成释放而非结算（白送额度）→ **恰 1 条红**；⑥ 在途的也被自动结算 → **2 条红**；⑦ 拿「本来就未知」当结论也放行（越过对账直接改账）→ **恰 1 条红**；⑧ 人工结论可覆盖确定终态（已计费用被再退一次）→ **恰 1 条红**；⑨ 结算额超预留也照结（凭空多计费）→ **恰 1 条红**；⑩ `UNKNOWN` 与无锚点不计入「需人工介入」→ **恰 1 条红**。**十处互相独立、可分别证伪** | `scripts/c22-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

BLOCK_00_DEFECT = """| **委派预留的对账通道缺失 → 额度被占着却查无实据（第二十二批修 · C22-1）** | 委派的预算预留记在 `budget_reservation` 上，但 `operation_id` 是<b>委派调用标识</b>，而委派记在 `agent_invocation` 里、<b>没有</b> `tool_operation` 行 —— 预算对账的两条查询都用 <b>INNER JOIN `tool_operation`</b>，于是委派的预留<b>对它们完全不可见</b>。后果不是「处置得不够优雅」，而是<b>额度被占着却没有任何一条查询能找到它</b>，连「转人工清单」都进不去（`unknownHeld` 恒为 0 → 运维看到的是「一切正常」）。现在补上反查通道：`budget_reservation` LEFT JOIN `agent_invocation`（LEFT JOIN 是为了连「记录都没有」的那一类也能捞出来），并与工具侧通道<b>互补</b>（靠「工具侧无锚点」划分，互不重叠）。⚠️ 不放宽任何既有防线：`UNKNOWN` <b>绝不</b>自动结算或释放（R-007）；在途与无锚点<b>一律不动</b>；确定终态 → 结算（子执行已跑过，额度不得白送） | `DelegationBudgetReconciliationServiceTest` 12 例 + `DelegationBudgetReconciliationIT` 7 例 |"""

OLD_00_GAP = """| **委派预留的 UNKNOWN 对账路径缺失（第十六批发现 · 未修）** | 委派的预算预留**没有关联的 `tool_operation` 行**（委派记在 `agent_invocation`），而 `JdbcBudgetRepository.findOrphaned` 用的是 `JOIN tool_operation`（内连接）→ 委派预留**不会**被误释放（这点是安全的），但也**没有**任何自动处置路径：若一次委派的结果为 `UNKNOWN`，其预留会永久停在 `RESERVED`，额度被长期占用、无人认领。⚠️ **剩余缺口**：需要一条「按 `agent_invocation` 的 UNKNOWN 记录反查并处置预留」的对账通道 | `DelegationBudgetChargeTest#unknownDelegationHoldsQuota`（钉死「UNKNOWN 时既不结算也不释放」这一侧；对账通道待补） |"""

NEW_00_GAP = """| ✅ **委派预留的 UNKNOWN 对账路径缺失（第十六批发现 · 第二十二批已修）** | **已落地**：新增反查通道（`DelegationBudgetPort` / `JdbcDelegationBudgetRepository` / `DelegationBudgetReconciliationService`）—— 按 `agent_invocation` 反查委派侧的持有预留，并按「在途 / UNKNOWN / 确定终态 / 无锚点」四类分别处置（**UNKNOWN 仍不自动处置**，R-007 不变）。⚠️ **剩余缺口**：① 对账**没有调度器**（与工具侧同款：只提供能力，触发时机待 D7 审计链路接入）；② **没有对外的处置入口**（人工收敛结论目前只能由应用层调用服务方法）；③ 对账结论**未回写 `agent_invocation`**（新增状态属评审前置决策 3，本批不碰） | `DelegationBudgetReconciliationServiceTest` 12 例 + `DelegationBudgetReconciliationIT` 7 例 + `DelegationBudgetChargeTest#unknownDelegationHoldsQuota`（钉死「UNKNOWN 时既不结算也不释放」这一侧） |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_GAP, NEW_00_GAP, "00")
    lines = s.split("\n")

    # ① 「已实现」表末尾：插在「第二十一批反证」行之后（表内末行）
    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **第二十一批反证（八处，逐一验证用例真的会红）**")]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十一批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00.split("\n")

    # ② 「历批修正的设计缺陷」表末尾：插在 C21-1 那行之后
    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **根 Agent 的模型配置是硬编码占位")]
    if len(hit) != 1:
        raise SystemExit(f"00 C21-1 缺陷行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00_DEFECT.split("\n")

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 06 ──────────────────────────────────────────────────────────────

BLOCK_06 = """
### 委派预算预留的对账口径（第二十二批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06/07/08 原文从未规定
「委派的预算预留怎么被找到、怎么被处置」。

**反查口径**：`budget_reservation` **LEFT JOIN** `agent_invocation`
（`operation_id = invocation_id`），且**工具侧无锚点**（`NOT EXISTS tool_operation`）。

| 项 | 口径 |
|---|---|
| 为什么是 LEFT JOIN | 委派终态提交若报 `NOT_FOUND`（记录不存在），闸门判 `UNKNOWN` 并**持有**预留 —— 此时**连 `agent_invocation` 行都没有**。用 INNER JOIN 会把这一类**漏掉**，而它恰恰是最需要人工看一眼的那一类（额度被占、查无实据） |
| 为什么排除工具侧 | 工具侧的预留由 `findOrphaned` / `findUnknownHeld` 负责；两边靠「工具侧无锚点」**互补**划分 —— 否则同一笔预留会被两条通道**处置两遍** |
| 持有 ≠ 悬挂 | 持有可能是「子执行还在跑」「结果不确定」「闸门崩溃」—— 三者处置动作**完全不同**，判定在应用层做 |
| UNKNOWN 处置 | **不自动结算、不自动释放**（R-007 红线），只统计并转人工 |
| 人工处置的输入 | 只接受**对账已收敛**的结论（`RECONCILED_SUCCEEDED` → 结算 / `RECONCILED_ABSENT` → 释放） |

**处置矩阵**：

| 委派调用状态 | 含义 | 处置 |
|---|---|---|
| `QUEUED` / `RUNNING` | 子执行可能还在跑 | ⚠️ **不动** —— 动了就是凭空吞额度 |
| `UNKNOWN` | 结果不确定（R-007） | ⚠️ **不动**，只统计并转人工；须有明确的收敛结论才可处置 |
| `SUCCEEDED` / `FAILED` / `CANCELLED` / `TIMED_OUT` | 子执行已启动并结束 | **结算** —— 子执行跑过 → 成本可能已发生 |
| 无锚点（连 `agent_invocation` 行都没有） | 委派终态提交报 `NOT_FOUND` | ⚠️ **不动**，只统计；查无实据 → 不自动改账 |

⚠️ **为什么终态一律「结算」而不是照抄工具侧的「成功结算、其余释放」**：
闸门对委派的既有口径是「**子执行从未启动 → 释放；子执行已启动但失败 → 结算**」
（子 Agent 已经跑过，模型调用的钱已经花出去）。
而「委派调用有终态」这件事<b>本身就等价于「子执行已启动」</b> ——
`agent_invocation` 行是在**调度子 Agent 之前**落下的，
所以凡是**有记录行**的委派，子执行都已被调度过。
⚠️ 照抄工具侧的「其余释放」会把这笔钱**白送**出去。

⚠️ **`UNKNOWN` 也是终态枚举值，但它不是「已定论」**：
`UNKNOWN` 的语义是「不再变化、等对账给结论」——
判定「结果是否已定」必须用「**确定**终态」判据，不能用「是否终态」，
否则 `UNKNOWN` 会被误当成「已结束」而自动结算（R-007 被拆）。
"""


# ── 07 ──────────────────────────────────────────────────────────────

BLOCK_07 = """
### 委派预留的对账与处置位置（第二十二批补充）

⚠️ **本节为本批补充** —— 07 原文只规定了「委派本身要占额度」，
从未规定「这笔额度出问题时怎么找回来」。

```
闸门（ToolInvocationGate）
    │  委派前：reserve(tenantId, runId, invocationId, 当量)
    │    └─ operation_id = 委派调用标识（= agent_invocation.invocation_id）
    │  委派后：SUCCEEDED → settle / 子执行未启动 → release
    │          UNKNOWN → **既不结算也不释放**（R-007）
    ▼
预算对账（BudgetReconciliationService.reconcileAll）
    │
    ├─ 工具侧：findOrphaned / findUnknownHeld
    │     └─ INNER JOIN tool_operation → **看不见委派的预留**（修复前的漏点）
    │
    └─ 委派侧（本批新增）：DelegationBudgetReconciliationService
          ├─ scan(tenantId)
          │    └─ DelegationBudgetPort.findHeldReservations(tenantId)
          │         └─ budget_reservation LEFT JOIN agent_invocation
          │            + 排除工具侧锚点（两条通道互补，不重叠）
          ├─ settleTerminalHeld(tenantId) → 终态委派自动结算
          └─ disposeHeld(tenantId, invocationId, 收敛结论, 金额)
               └─ UNKNOWN 预留唯一的出口：**只接受对账已收敛的结论**
    ▼
统计：ReconcileStats 纳入委派侧计数
    └─ needsAttention() 覆盖「委派 UNKNOWN」与「委派无锚点」
       ⚠️ 不计入的话，运维看到的永远是「一切正常」，
          而实际上额度正被一批看不见的委派预留长期占着
```

⚠️ **与闸门不冲突**：闸门在委派返回后会自己结算同一笔预留；
两者都受「`RESERVED` → `SETTLED`」的状态前置条件保护，
先到者生效，后到者拿到 `false` —— **不会重复计费**。

⚠️ **未装配时显式告警**：委派对账通道未接入 `BudgetReconciliationService` 时，
对账会打 `WARN` 级告警并继续 —— **绝不静默跳过**：
静默跳过等于让「委派额度泄漏」这件事**永远不被发现**。

⚠️ **本批的剩余缺口（如实登记）**：对账**没有调度器**、
**没有对外处置入口**（API / 运维命令）——
与工具侧同款（只提供能力，触发时机待 D7 审计链路接入）。
"""


# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：委派预算预留对账通道的验收补充（第二十二批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「委派的预算预留必须能被对账找到」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T17f | 可发现性 | 委派预留（`agent_invocation` 为 `UNKNOWN`）能被反查出来 | 能查到，且状态为 `UNKNOWN` | `DelegationBudgetReconciliationIT#delegationReservationIsFoundByReverseLookup`（反证 A：只认 `tool_operation` 锚点 → 6 条红） |
| MA-T17g | 通道互补 | 工具侧预留**不**出现在委派通道里 | 委派通道查不到它；工具侧查询仍查得到（同一笔预留不出现两遍） | `DelegationBudgetReconciliationIT#toolSideReservationIsNotFoundByDelegationChannel`（反证 C：去掉排除条件 → 恰 1 条红） |
| MA-T17h | 无锚点可见 | 连 `agent_invocation` 行都没有的预留也能被反查 | 能查到，且**不被当成已定论** | `DelegationBudgetReconciliationIT#unanchoredReservationIsStillFound`（反证 B：改用 INNER JOIN → 恰 1 条红） |
| MA-T17i | 处置口径 | 终态委派 → 结算；`UNKNOWN` / 在途 / 无锚点 → **一律不动** | 只有终态那笔被结算；其余仍在 `RESERVED` | `DelegationBudgetReconciliationServiceTest`（M-2 / M-3 / M-4 / M-5）+ `DelegationBudgetReconciliationIT#terminalIsSettledButUnknownAndUnanchoredAreNot`（反证 D/E/F → 3/1/2 条红） |
| MA-T17j | 人工处置 | 人工收敛结论可处置 `UNKNOWN` 预留；非收敛状态拒绝；确定终态拒绝覆盖 | 结算 / 释放两条路都通；其余一律拒绝 | `DelegationBudgetReconciliationServiceTest`（M-6 / M-7 / M-8 / M-9 / M-10）（反证 G/H/I → 各恰 1 条红） |
| MA-T17k | 可见性 | `UNKNOWN` 与无锚点都计入「需人工介入」 | `needsAttention()` 为真 | `DelegationBudgetReconciliationServiceTest#needsAttentionCoversUnknownAndUnanchored`（反证 J → 恰 1 条红） |
| MA-T17l | 租户隔离 | 跨租户读不到别人的委派预留 | B 租户查不到 A 租户那笔 | `DelegationBudgetReconciliationIT#crossTenantReservationIsInvisible`（真实 PG17 + 非特权角色） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-037 | **委派预算预留的 UNKNOWN 对账通道**（第二十二批补充） | 06 §委派预算预留的对账口径、07 §委派预留的对账与处置位置 | MA-05、MA-17（第二十二批 C22-1） | AC-024 / MA-T17f、MA-T17g、MA-T17h、MA-T17i、MA-T17j、MA-T17k、MA-T17l |"

BLOCK_09_2 = """⚠️ **FR-037 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定「委派的预算预留怎么被找到、怎么被处置」
（本批取「按 `agent_invocation` 反查 + 四类分流 + 人工处置只认收敛结论」的最小方案）。
⚠️ 补充的是**既有约束**（R-007 的「`UNKNOWN` 不自动释放/结算」、
MA-T17 的「全调用树共享预算上限」）在**委派侧的额度回收**上的落地 ——
此前那笔额度被占着却查无实据，那些约束连被触发的机会都没有。
⚠️ **剩余缺口**：① 对账**没有调度器**（与工具侧同款）；
② **没有对外的处置入口**（人工收敛结论只能由应用层调用服务方法）；
③ 对账结论**未回写 `agent_invocation`** ——
新增状态属**评审前置决策 3**（状态机与取消语义），未闭环 → 本批不碰。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    # ① FR-037 表格行：插在 FR-036 表格行之后（表内末行）
    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-036 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-036 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    # ② FR-037 说明段：插在 FR-036 说明段之前（说明段按 FR 编号**降序**排列）
    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-036 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-036 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C22-1 文档回写：")
    patch_00()
    insert_before(F06, "### 发布闭包算法", BLOCK_06)
    insert_before(F07, "### 上下文与安全", BLOCK_07)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
