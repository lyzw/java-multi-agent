#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十一批 C21-1 文档回写：把「根 Agent 按 Run 快照读真实定义」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型** ——
   文档里必须如实标注，不得虚报实现状态。
⚠️ 尤其：**根 Agent 的「模型标识 / 提示词 / 普通工具表」已从 L-4 占位
   切换为「按 Run 快照读真实定义」** —— 但「读不到即拒绝执行」这条
   fail-closed 口径必须写清，不得写成「根 Agent 配置完全接通、已可生产」。

⚠️ **落点纪律（本脚本修订版）**：表格行必须落在**表格内部**，
   否则 markdown 会把 `| a | b |` 渲染成普通段落 —— 文档「看着有内容」，
   实际排版是塌的。故 00/09 一律用「锚定表格末行 → 插到其后」的方式，
   不用「插到某个标题之前」的方式（标题前若有空行，同样会断表）。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C21-1 -->"

# ⚠️ 每个文件**独立**记账：
#    「00 已回写」不代表「09 已回写」——
#    若用一个全局标记，第二个文件会被误跳过（那是漏写，不是幂等）。
_PATCHED: set[str] = set()


def _done(path: pathlib.Path) -> bool:
    """本文件是否已回写（内存记账 + 文件内标记双重判定）。"""
    if path.name in _PATCHED:
        return True
    if MARK in path.read_text():
        _PATCHED.add(path.name)
        return True
    return False


def _mark(path: pathlib.Path, s: str) -> str:
    """在文件末尾打上本批标记（供下次运行时识别）。"""
    _PATCHED.add(path.name)
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    return s


def insert_before(path: pathlib.Path, anchor: str, block: str) -> bool:
    """在 anchor 之前插入 block；若本文件已回写则跳过（幂等）。"""
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


def insert_after_line(path: pathlib.Path, line_prefix: str, block: str) -> bool:
    """把 block 插到「以 line_prefix 开头的那一行」之后（且紧随其后，不留空行）。

    ⚠️ 这是**表格行**唯一安全的落点：插在标题前会被空行断表。
    """
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    lines = path.read_text().split("\n")
    hits = [i for i, ln in enumerate(lines) if ln.startswith(line_prefix)]
    if len(hits) != 1:
        raise SystemExit(f"锚行未唯一命中：{path.name} :: {line_prefix[:60]} (命中 {len(hits)})")
    i = hits[0]
    lines[i + 1 : i + 1] = block.rstrip("\n").split("\n")
    path.write_text(_mark(path, "\n".join(lines)))
    print(f"  已回写：{path.name}")
    return True


def replace(path: pathlib.Path, old: str, new: str) -> bool:
    """把 old 替换为 new（必须唯一命中，否则报错）。"""
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text()
    if old not in s:
        raise SystemExit(f"待替换文本未命中：{path.name} :: {old[:60]}")
    if s.count(old) != 1:
        raise SystemExit(f"待替换文本不唯一：{path.name} :: {old[:60]} (命中 {s.count(old)})")
    path.write_text(_mark(path, s.replace(old, new, 1)))
    print(f"  已回写（替换）：{path.name}")
    return True


def append(path: pathlib.Path, block: str) -> bool:
    """在文件末尾追加 block；若本文件已回写则跳过（幂等）。"""
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text().rstrip("\n") + "\n\n" + block.rstrip("\n") + "\n"
    path.write_text(_mark(path, s))
    print(f"  已回写：{path.name}")
    return True


# ── 00：实现状态快照 + 已实现表 ────────────────────────────────────
BLOCK_00 = """| **根 Agent 按 Run 快照读真实定义（第二十一批 · C21-1 · FR-036）** | 修复前根 Agent 的模型标识 / 提示词 / **普通工具表**是<b>硬编码占位</b>（`fake-model` + 写死的提示词 + 空工具表）。后果不是「配置不准」，而是<b>根 Agent 实际上没有任何普通工具可用</b> —— 它能看见的只有委派工具，于是「多级 Agent 可用」这句话在根这一层是假的：<b>根只能委派、不能自己干活</b>。现在：① 新增 `AgentModelConfigAssembler`（agent-worker）—— 按<b>固定版本</b>读 `agent_version.definition`，装配模型标识/提示词/温度/maxTokens/超时 + <b>该 Agent 自己闭包</b>里的工具 Schema；<b>根路径与子路径共用这一份</b>（此前子路径自己解析、根路径走占位 → 两套语义）；② 新增 `RootAgentModelConfigProvider` —— 根 Agent 身份来自 <b>Run 能力快照</b>（`snapshot.agentId()` / `agentVersion()`），按那个<b>固定 release</b>装配，委派工具与闭包工具并列；③ bootstrap 根路径改读真实配置，<b>读不到即 fail-closed</b>（拒绝执行该 Run），<b>绝不回落 `fake-model` 占位</b> —— 回落等于让「配置缺失」被伪装成「配置正常」。⚠️ <b>不放宽任何既有防线</b>：模型标识取不到 → 拒绝执行（不猜模型，与子路径同口径）；工具表只来自该 Agent 自己的闭包（工具授权面不放宽）；LEAF / 无子引用 → 不给委派工具；闭包解析失败 → 工具表为空但 Agent 仍可用（不放大成全瘫）。⚠️ <b>剩余缺口</b>：① 「根 Run 的输入载荷」仍是 L-4 占位（`runInput` 恒返回写死的题目），本批只接通<b>配置侧</b>；② 「根 Agent 的委派目标枚举」依赖 `cap_agent_def`（CAP 侧投影），而<b>双写开关默认关闭</b> → 未走 CAP 发布通道的 Agent 在生产上仍是 LEAF；③ 根路径<b>没有「子路径那样」的跨链路端到端证据</b>（本批为单元级 + 接线级） | `AgentModelConfigAssemblerTest` 8 例 + `RootAgentModelConfigProviderTest` 7 例 + `RootDelegationToolSchemaWiringTest` 7 例（重写） |
| **第二十一批反证（八处，逐一验证用例真的会红）** | ① 根路径回落 `fake-model` 占位（= 修复前）→ `RootAgentModelConfigProviderTest` **2 条**红 + `RootDelegationToolSchemaWiringTest` **2 条**红；② 根路径漂移到别的 release（违反「固定 release」）→ **4 条**红；③ 读不到定义却回落占位（fail-closed 被拆）→ **恰 1 条**红；④ 根路径不给委派工具（根→子无触发点）→ **恰 1 条**红；⑤ LEAF 也给委派工具（不该给的不给）→ **恰 1 条**红；⑥ 缺模型标识也放行（随便挑一个模型）→ **恰 1 条**红；⑦ 工具表不来自自己闭包（工具授权面被放大）→ **恰 1 条**红；⑧ 子路径自写一份解析 → 子路径用例<b>仍绿</b>（证明子路径行为可独立回退、行为不变）。**七处互相独立、可分别证伪** | `scripts/c21-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

# 00：历批修正的设计缺陷表 —— 追加一行
BLOCK_00_DEFECT = """| **根 Agent 的模型配置是硬编码占位 → 根只能委派、不能自己干活（第二十一批修 · C21-1）** | 根 Agent 的模型标识（`fake-model`）、提示词、**普通工具表**是<b>硬编码占位</b> —— 不是「不准」，而是<b>根根本没有任何普通工具</b>：模型只能看见委派工具，于是「多级 Agent 可用」在根这一层是假的（根只能委派、不能自己干活），而代码看上去齐备（子路径有真实解析，根路径走占位）。现在根与子<b>共用同一份装配实现</b>（`AgentModelConfigAssembler`），根身份取自 <b>Run 能力快照</b>的<b>固定 release</b>，工具表只来自该 Agent <b>自己闭包</b>；<b>读不到即拒绝执行该 Run</b>（fail-closed，<b>不回落占位</b> —— 回落会把「配置缺失」伪装成「配置正常」）。⚠️ 不放宽既有防线：模型标识取不到 → 拒绝执行（不猜模型）；工具授权面不放宽；LEAF → 不给委派工具 | `AgentModelConfigAssemblerTest` 8 例 + `RootAgentModelConfigProviderTest` 7 例 + `RootDelegationToolSchemaWiringTest` 7 例 |"""

# 00：快照标题 + 验证口径
OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十批：委派工具 Schema 装配 —— 让模型看得见委派能力）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十一批：根 Agent 按 Run 快照读真实定义）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1292 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十九批基线（`6edf027`）同口径为 **1268 例** → 本批 **+24 例**
>   （`DelegationToolSchemaTest`（core）6 例 + `DelegationToolSchemaTest`（application）2 例 +
>   `DelegationToolSchemaAssemblerTest` 5 例 +
>   `WorkerChildAgentModelConfigProviderTest` 5 例 +
>   `RootDelegationToolSchemaWiringTest` 6 例），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例仍成对断言**（「看得见委派工具」与「不该给的不给」互相独立）：
>   拆掉装配只让装配那条红，拆掉 fail-closed 只让 fail-closed 那条红 ——
>   否则「全都给委派工具」也能让用例绿。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`6edf027` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1292 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1308 例 / 0 failures / 0 errors / 25 skipped）**；
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

# 00：第二十批那行的「剩余缺口」已过时（C21-1 消解了它）
OLD_00_C20_ROW = "⚠️ **剩余缺口**：根 Agent 的<b>模型标识 / 提示词 / 普通工具表</b>仍是 L-4 占位（`fake-model` + 空工具表）—— 本批只补「委派工具」这一项触发点，**不得据此认为根 Agent 配置已接通**"
NEW_00_C20_ROW = "⚠️ **剩余缺口**：根 Agent 的<b>模型标识 / 提示词 / 普通工具表</b>原为 L-4 占位（`fake-model` + 空工具表）→ ✅ **已由第二十一批消解**（根 Agent 改为按 Run 能力快照的固定 release 读真实定义，读不到即拒绝执行）；本批（第二十批）只补「委派工具」这一项触发点"

# ── 06：配置数据模型 ────────────────────────────────────────────────
BLOCK_06 = """
### 根 Agent 的模型配置口径（第二十一批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06/07/08 原文从未规定
「根 Agent 的模型标识 / 提示词 / 普通工具表从哪来」。

| 项 | 口径 |
|---|---|
| 根 Agent 身份 | **Run 能力快照**（`execution_capability_snapshot.agent_id` / `agent_version`）—— 不是「当前已发布版本」 |
| 配置来源 | 该 Agent **固定 release** 的 `agent_version.definition`（JSONB）：`modelRef`/`modelId`、`instructions.inlineInstructions`/`systemPrompt`、`temperature`、`maxOutputTokens`、`timeoutMs` |
| 普通工具表 | 该 Agent **自己闭包**里的 Skill→Tool 绑定（**不放宽工具授权面**） |
| 委派工具 | 显式装配（不进闭包），与闭包工具**并列** |
| 取不到模型标识 | **拒绝执行**（不猜模型）—— 与子路径**同口径** |
| 读不到快照 / 定义 | **拒绝执行该 Run**（fail-closed）—— **不回落 `fake-model` 占位** |

⚠️ **为什么「根」与「子」必须共用同一份装配实现**：
两处各写一份必然漂移，而漂移落在「根按占位配置跑、子按真实配置跑」这类
**两套语义**上 —— 那正是本项目反复消除的问题本身。
"""

BLOCK_06_2 = """
### 根 Agent 模型配置与占位的替换（第二十一批补充）

⚠️ **本节为本批补充**。L-4 占位（`fake-model` + 写死提示词 + 空工具表）已由
→「按 Run 快照读真实定义」替换。

⚠️ **口径切换的三点边界**：
1. **只换配置侧**：Run 的**输入载荷**（`run.input_payload`）仍是占位 ——
   本批未接通输入侧，不得据此认为根 Agent 已能跑真实业务请求。
2. **fail-closed 优先**：读不到根 Agent 的模型配置 → **该 Run 不执行**。
   代价是「配置未接好时平台不可用」，收益是「配置缺失不会被伪装成配置正常」。
3. **委派目标依赖 CAP 侧投影**：根 Agent 的委派目标枚举读 `cap_agent_def.orchestration`，
   而<b>双写开关默认关闭</b> → 未走 CAP 发布通道的 Agent 仍被识别为 LEAF。
   这是<b>发布通道</b>的缺口，与本批的<b>读取口径</b>无关，两者不可混为一谈。
"""

# ── 07：执行链集成 ──────────────────────────────────────────────────
BLOCK_07 = """
### 根 Agent 模型配置的装配位置（第二十一批补充）

⚠️ **本节为本批补充** —— 07 原文从未规定「根 Agent 的模型配置由谁装配」。

```
Run 能力快照(execution_capability_snapshot)
    │  agent_id + agent_version（= 这个 Run 绑定的固定 release）
    ▼
AgentModelConfigAssembler ── 读 agent_version.definition（按该固定版本）
    │    · modelRef/modelId → 模型标识
    │    · instructions.inlineInstructions / systemPrompt → 提示词
    │    · temperature / maxOutputTokens / timeoutMs → 生成参数
    │    · 该 Agent 自己闭包的 Skill→Tool → 普通工具 Schema
    ▼
RootAgentModelConfigProvider ── 并列装配委派工具（显式，不进闭包）
    │
    ▼
bootstrap: RunExecutionInputs.agentConfig(tenantId, runId)
    │  ⚠️ 返回 Optional：**读不到即空**
    ▼
RunDispatchConsumer ── 空 → 该 Run 拒绝执行（fail-closed，不回落占位模型）
```

⚠️ **为什么身份必须来自 Run 能力快照**：同一租户的不同 Run 可能由不同根 Agent 执行，
按全局装配会让 A 的 Run 拿到 B 的模型与工具。

⚠️ **为什么必须绑定固定 release**：否则别人给该 Agent 发布新版本时，
在途 Run 会静默漂移到新模型 / 新提示词（MA-T08）。

⚠️ **根与子共用同一份装配实现**（`AgentModelConfigAssembler`）：
子路径此前自己解析定义，根路径走占位 —— 两套语义必然漂移。
"""

BLOCK_07_2 = """
### 根路径的 fail-closed 处置（第二十一批补充）

⚠️ **本节为本批补充**。

根路径读不到模型配置时，**不回落任何占位值**，而是**拒绝执行该 Run**：

| 情形 | 处置 | 理由 |
|---|---|---|
| Run 能力快照缺失 | 拒绝执行该 Run | 不知道根 Agent 是谁 → 无法确定它跑在哪个模型上 |
| 快照读取异常 | 拒绝执行该 Run | 异常不得被吞成「没有配置」之外的任何结论 |
| 定义缺少模型标识 | 拒绝执行该 Run | 无法确定用哪个模型 → **不猜**（与子路径同口径） |
| 定义不是合法 JSON | 拒绝执行该 Run | 配置不可解析 → 不凭空造一份配置 |
| 闭包解析失败 | **工具表为空**，Agent 仍可用 | 「一条工具边读不出来」不该被放大成「这个 Agent 全瘫」 |
| 委派工具装配器未装配 | **不给委派工具**，其余照常 | 无从判断委派能力 → 宁可不给 |

⚠️ **与子路径的一致性**：子 Agent 取不到模型配置时，
`WorkerChildAgentRunner` 判**确定失败**（`RESOURCE_UNKNOWN`）；
根路径取不到时判**该 Run 失败**。两侧都是「确定失败」而非「UNKNOWN」——
因为配置缺失是可重试也无用的**确定性**结论。
"""

# 07：第二十批那段的「仍是 L-4 占位」已过时
OLD_07_STALE = """⚠️ **根 Agent 的其余模型配置仍是 L-4 占位**：
模型标识（`fake-model`）、提示词、**普通工具表**均为占位 ——
本批只补「委派工具」这一项触发点，**不得据此认为根 Agent 配置已接通**。"""
NEW_07_STALE = """⚠️ **根 Agent 的其余模型配置原为 L-4 占位**：模型标识（`fake-model`）、提示词、
**普通工具表**均为占位 → ✅ **已由第二十一批消解**（改为按 Run 能力快照的固定 release
读真实定义）；本批（第二十批）只补「委派工具」这一项触发点。"""

# ── 08：安全与验收矩阵 ──────────────────────────────────────────────
BLOCK_08 = """
### 变更补充：根 Agent 真实定义接线的验收补充（第二十一批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「根 Agent 的模型配置必须来自它的定义」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T05p | 配置来源 | 根 Agent 的模型标识 / 提示词取自其**固定 release** 的定义 | 模型标识 = 定义里的值（非 `fake-model` 占位）；提示词 = 定义里的值 | `RootAgentModelConfigProviderTest#readsRealDefinitionByFixedRelease` / `RootDelegationToolSchemaWiringTest#rootModelConfigComesFromRealDefinition` |
| MA-T05q | 固定 release | 根 Agent 的定义按**快照里的 release** 取，不取「当前已发布版本」 | 取不到该 release → 拒绝执行（不得漂移到别的版本） | `RootAgentModelConfigProviderTest#readsRealDefinitionByFixedRelease`（反证 B：漂移 → 4 条红） |
| MA-T05r | fail-closed | 读不到能力快照 / 定义 / 模型标识 | **该 Run 拒绝执行**；**不得**回落占位模型 | `RootAgentModelConfigProviderTest#missingSnapshotIsRejected` / `#missingModelIdentifierIsRejected` / `RootDelegationToolSchemaWiringTest#missingSnapshotRejectsExecution`（反证 A/C/G 各自变红） |
| MA-T05s | 工具授权面 | 根 Agent 的普通工具表只来自**它自己闭包**里的绑定 | 工具表 = 自己闭包的工具，不多不少 | `AgentModelConfigAssemblerTest#toolSchemasComeFromOwnClosure`（反证 H：硬塞工具 → 1 条红） |
| MA-T05t | 委派可见性 | 根 Agent 有委派能力 → 看得见委派工具；LEAF → 不给 | 有能力才给；LEAF 不给 | `RootAgentModelConfigProviderTest#seesDelegationTool` / `#leafGetsNoDelegationTool`（反证 D/E 各自 1 条红） |
| MA-T05u | 父子同源 | 根与子共用同一份模型配置装配实现 | 子路径行为不因共用而改变（可独立回退） | `WorkerChildAgentModelConfigProviderTest` 5 例（反证 F：子路径自写一份 → 仍绿） |
"""

# ── 09：需求追踪矩阵 ────────────────────────────────────────────────
BLOCK_09_ROW = "| FR-036 | **根 Agent 按 Run 快照读真实定义**（第二十一批补充） | 06 §根 Agent 的模型配置口径、07 §根 Agent 模型配置的装配位置 | MA-05（第二十一批 C21-1） | AC-028 / MA-T05p、MA-T05q、MA-T05r、MA-T05s、MA-T05t、MA-T05u |"

BLOCK_09_2 = """⚠️ **FR-036 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定「根 Agent 的模型标识 / 提示词 / 普通工具表从哪来」
（本批取「代码定义形态 + 按 Run 能力快照的固定 release 读取 + 根与子共用一份」
的最小方案）。
⚠️ 补充的是**既有约束**（MA-T08 的「固定 release 不漂移」、
MA-T05 的「工具表 = 自己被授权的工具」）在**根路径**上的落地 ——
此前根路径根本没有配置来源，那些约束连被触发的机会都没有。
⚠️ **剩余缺口**：① 根 Run 的**输入载荷**仍是 L-4 占位，本批只接通**配置侧**；
② 根 Agent 的委派目标枚举依赖 `cap_agent_def`（CAP 侧投影），
而<b>双写开关默认关闭</b> → 未走 CAP 发布通道的 Agent 在生产上仍是 LEAF
（属**发布通道**缺口，与本批的读取口径无关）；
③ 根路径没有「子路径那样」的跨链路端到端证据（本批为单元级 + 接线级）。"""

# 09：FR-035 的「剩余缺口①」已过时
OLD_09_FR035 = """⚠️ **剩余缺口**：① 根 Agent 的**模型标识 / 提示词 / 普通工具表**仍是 **L-4 占位**
（`fake-model` + 空工具表）→ 本批只补「委派工具」这一项触发点，
**不得据此认为根 Agent 配置已接通**；② 委派工具 Schema 的**内容模型**"""
NEW_09_FR035 = """⚠️ **剩余缺口**：① 根 Agent 的**模型标识 / 提示词 / 普通工具表**原为 **L-4 占位**
（`fake-model` + 空工具表）→ ✅ **已由第二十一批消解**（根 Agent 改为按 Run 能力快照的
固定 release 读真实定义，读不到即拒绝执行）；② 委派工具 Schema 的**内容模型**"""

# 09：FR-034 的「缺口②」已过时（触发点已由第二十批消解）
OLD_09_FR034 = """② **委派工具的 Schema 从未装配给模型**（`delegate_to_agent` 只出现在
`DelegationExecutor` 常量与闸门分支里；根 Agent 的 `AgentModelConfig` 在生产仍是
`fake-model` 占位 + 空 toolSchemas）→ 运行期模型**不可能发起委派**，
多级机制齐备但**无触发点** —— 这是比本批更大的缺口，需单独处理；"""
NEW_09_FR034 = """② **委派工具的 Schema 从未装配给模型**（`delegate_to_agent` 只出现在
`DelegationExecutor` 常量与闸门分支里；根 Agent 的 `AgentModelConfig` 在生产仍是
`fake-model` 占位 + 空 toolSchemas）→ 运行期模型**不可能发起委派**，
多级机制齐备但**无触发点** —— 这是比本批更大的缺口；
✅ 触发点已由**第二十批**消解（委派工具 Schema 装配），
根 Agent 的配置占位已由**第二十一批**消解；"""


def main() -> None:
    print("C21-1 文档回写：")

    # ── 00 ────────────────────────────────────────────────────────
    # ⚠️ 00 有 4 处改动（标题 / 验证口径 / 过时口径 / 两处插入），
    #    必须**一次性完成**再统一打标记 —— 否则第二次跑时
    #    「标题已被替换」会让幂等复跑直接报错。
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
    else:
        s = F00.read_text()

        def sub_once(text: str, old: str, new: str) -> str:
            if old not in text:
                raise SystemExit(f"00 待替换文本未命中：{old[:60]}")
            if text.count(old) != 1:
                raise SystemExit(f"00 待替换文本不唯一：{old[:60]} (命中 {text.count(old)})")
            return text.replace(old, new, 1)

        s = sub_once(s, OLD_00_TITLE, NEW_00_TITLE)
        s = sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY)
        s = sub_once(s, OLD_00_C20_ROW, NEW_00_C20_ROW)

        lines = s.split("\n")

        # ① 「已实现」表末尾：插在**第二十批反证行之后**（表内末行）
        # ⚠️ 不能插在 `### 未实现（下一批）` 之前 —— 该标题前**有空行**，
        #    插在标题前等于插在表外 → 表格被截断，行渲染成普通段落。
        hit = [i for i, ln in enumerate(lines) if ln.startswith("| **第二十批反证（八处，逐一验证用例真的会红）**")]
        if len(hit) != 1:
            raise SystemExit(f"00 「第二十批反证」行未唯一命中 (命中 {len(hit)})")
        i = hit[0]
        lines[i + 1 : i + 1] = BLOCK_00.split("\n")

        # ② 「历批修正的设计缺陷」表末尾：插在最后一行表格行之后
        hit = [i for i, ln in enumerate(lines) if ln.startswith("| **子 Agent 的闭包解析漂移到「当前已发布版本」（第十八批修 · C18-1）**")]
        if len(hit) != 1:
            raise SystemExit(f"00 历批修正表末行未唯一命中 (命中 {len(hit)})")
        i = hit[0]
        lines[i + 1 : i + 1] = BLOCK_00_DEFECT.split("\n")

        F00.write_text(_mark(F00, "\n".join(lines)))
        print("  已回写：00_修订说明与评审决议项.md")

    # ── 06 ────────────────────────────────────────────────────────
    insert_before(F06, "### 发布闭包算法", BLOCK_06 + "\n" + BLOCK_06_2)

    # ── 07：一次性完成「修过时口径 + 插入新章节」
    # ⚠️ 分两次调用会因幂等标记互相跳过（replace 先打标记 → insert_before 被跳过），
    #    故同一文件的多处改动必须在一个块里做完，最后统一打标记。
    if _done(F07):
        print("  跳过（已回写）：07_执行链集成设计.md")
    else:
        s = F07.read_text()
        if OLD_07_STALE not in s:
            raise SystemExit("07 过时口径文本未命中")
        if s.count(OLD_07_STALE) != 1:
            raise SystemExit(f"07 过时口径文本不唯一 (命中 {s.count(OLD_07_STALE)})")
        s = s.replace(OLD_07_STALE, NEW_07_STALE, 1)

        anchor = "### 上下文与安全"
        if anchor not in s:
            raise SystemExit(f"07 锚点未命中：{anchor}")
        s = s.replace(anchor, BLOCK_07 + "\n" + BLOCK_07_2 + "\n" + anchor, 1)
        F07.write_text(_mark(F07, s))
        print("  已回写：07_执行链集成设计.md")

    # ── 08 ────────────────────────────────────────────────────────
    append(F08, BLOCK_08)

    # ── 09 ────────────────────────────────────────────────────────
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
    else:
        s = F09.read_text()
        if OLD_09_FR035 not in s:
            raise SystemExit("09 FR-035 剩余缺口文本未命中")
        s = s.replace(OLD_09_FR035, NEW_09_FR035, 1)
        if OLD_09_FR034 not in s:
            raise SystemExit("09 FR-034 剩余缺口文本未命中")
        s = s.replace(OLD_09_FR034, NEW_09_FR034, 1)

        lines = s.split("\n")

        # ① FR-036 表格行：插在 FR-035 表格行之后（表内，末行）
        hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-035 |")]
        if len(hit) != 1:
            raise SystemExit(f"09 FR-035 表格行未唯一命中 (命中 {len(hit)})")
        i = hit[0]
        lines[i + 1 : i + 1] = BLOCK_09_ROW.split("\n")

        # ② FR-036 说明段：插在 FR-035 说明段之前（说明段按 FR 编号**降序**排列）
        hit = [
            i
            for i, ln in enumerate(lines)
            if ln.startswith("⚠️ **FR-035 的需求描述与验收编号均为「本批补充」**")
        ]
        if len(hit) != 1:
            raise SystemExit(f"09 FR-035 说明段未唯一命中 (命中 {len(hit)})")
        i = hit[0]
        lines[i:i] = BLOCK_09_2.split("\n") + [""]

        F09.write_text(_mark(F09, "\n".join(lines)))
        print("  已回写：09_需求追踪矩阵.md")

    print("完成。")


if __name__ == "__main__":
    main()
