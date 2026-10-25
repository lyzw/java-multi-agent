#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十批 C20-1 文档回写：把「委派工具 Schema 装配」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的模型/编号均为「本批补充」，**非评审冻结模型** ——
   文档里必须如实标注，不得虚报实现状态。
   ⚠️ 尤其：**根 Agent 的模型标识/提示词/普通工具表仍是 L-4 占位**，
   本批只补「委派工具」这一项触发点 —— 不得写成「根 Agent 配置已接通」。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C20-1 -->"

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


def append_block(path: pathlib.Path, block: str) -> bool:
    """在文件末尾追加 block；若本文件已回写则跳过（幂等）。"""
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text().rstrip("\n") + "\n\n" + block
    path.write_text(_mark(path, s))
    print(f"  已回写（追加到末尾）：{path.name}")
    return True


def replace_once(path: pathlib.Path, old: str, new: str) -> bool:
    """替换 old → new；若本文件已回写则跳过（幂等）。"""
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text()
    if old not in s:
        raise SystemExit(f"待替换文本未命中：{path.name} :: {old[:60]}")
    s = s.replace(old, new, 1)
    path.write_text(s)
    print(f"  已更新：{path.name}")
    return True


def finalize(path: pathlib.Path) -> None:
    """收尾：确保文件末尾有本批标记（供 replace_once 系列的幂等判定）。"""
    if path.name in _PATCHED:
        return
    s = path.read_text()
    if MARK not in s:
        path.write_text(_mark(path, s))
        print(f"  已打标记：{path.name}")


# ══════════════════════════════════════════════════════════════════
# 00 —— 快照标题 / 验证口径 / 已实现表 / 历批修正的设计缺陷
# ══════════════════════════════════════════════════════════════════
print("00_修订说明与评审决议项.md")

replace_once(
    F00,
    "## 实现状态快照（2026-10-25 · 代码落地第十九批：委派分支在两条执行链路上共用）",
    "## 实现状态快照（2026-10-25 · 代码落地第二十批：委派工具 Schema 装配 —— 让模型看得见委派能力）",
)

replace_once(
    F00,
    """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1268 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十八批基线（`9322b6f`）同口径为 **1253 例** → 本批 **+15 例**
>   （全在 `DelegationGateBranchTest` 的 `ChainParity` 嵌套类：合法委派 2 +
>   目标未声明 2 + 参数非法 2 + FAIL_FAST 2 + 上下文串了 2 + 版本漂移 2 +
>   未装配执行器 2 + 四字段逐项一致 1），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例仍成对断言**（「两条链路都放行」与「两条链路都拒绝」互相独立）：
>   拆掉任一条链路的能力只让那条链路的用例红 —— 否则「全都拒」也能让用例绿。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`9322b6f` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1268 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。""",
    """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1292 例 / 0 failures / 0 errors）**；
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
>   与主工作区同口径一致。""",
)

old_tail = "| **第十九批反证（七处，逐一验证用例真的会红）** | ① 既有链路摘掉委派分支（= 修复前状态）→ **6 条红 + 2 条错**（且**只有 LEGACY 链路红**，CAP 链路保持绿 → 证明改动范围准确）；② 委派判定排到「未登记的工具」之后（顺序错了）→ **6 条红 + 2 条错**（同样只有 LEGACY 红）；③ 一致性校验只比 agentId 不比 version → **恰 2 条红**（且**只有版本漂移那条红**，标识不一致那条仍绿 → 证明两处校验互相独立）；④ 一致性校验整体摘掉 → **4 条红**（标识 + 版本、两条链路）；⑤ FAIL_FAST 停止语义摘掉 → **3 条红**；⑥ 既有链路绕过共用实现（本地复制一份）→ **3 条红**（且**只有 LEGACY 红** → 证明「共用」不可退化）；⑦ 未装配执行器时不再回落 → **3 条错**（NPE，说明「未登记的工具」这条防线真的在）。**七处互相独立、可分别证伪** | `scripts/c19-1-counterproof.sh`（含「恢复后复跑全绿」） |"
new_tail = old_tail + """
| **委派工具 Schema 装配 —— 让模型看得见委派能力（第二十批 · C20-1）** | 修复前委派工具<b>从未进入任何模型的工具表</b>（只出现在 `DelegationExecutor` 常量与闸门分支里）→ 运行期模型<b>物理上不可能</b>发起委派：守卫、预算、账本、契约全都齐备，却<b>没有触发点</b>。⚠️ <b>委派不是普通工具</b>：它<b>不进</b> Skill→Tool 能力闭包（进了闭包就等于给每个 Agent 都发一把委派枪），因此闭包解析<b>永远给不出</b>它 —— 必须<b>显式装配</b>。现在：① `DelegationToolSchema`（agent-core）定义 Schema 形态：只允许 `targetAgentId` + `input` 两个字段（与 `DelegationArguments` 逐字对齐）、`additionalProperties:false`、`targetAgentId` 用 **enum 枚举该 Agent 自己声明的子 Agent**、**不暴露版本**（版本由服务端按父声明解析，模型无权指定 release）；② `DelegationToolSchemaAssembler`（agent-worker）按 Agent <b>自己</b>的编排声明装配，**根路径与子路径共用这一份**（两处各写一份必然漂移，漂移落在「根能委派、子不能」这类两套语义上）；③ <b>只给「有委派能力」的 Agent</b>（LEAF / 无子引用 → 不给）；④ 读不到编排声明 / 声明非法 / 仓储异常 → <b>一律不给</b>（fail-closed：不知道有没有委派能力时宁可不给，也不凭猜测发枪）；⑤ 根路径<b>按 Run 能力快照</b>解析根 Agent 身份后装配（同一租户的不同 Run 可能由不同根 Agent 执行，按全局装配会让 A 的 Run 拿到 B 的委派目标） | `DelegationToolSchemaTest`（core）6 例 + `DelegationToolSchemaTest`（application）2 例 + `DelegationToolSchemaAssemblerTest` 5 例 + `WorkerChildAgentModelConfigProviderTest` 5 例 + `RootDelegationToolSchemaWiringTest` 6 例 |
| **第二十批反证（八处，逐一验证用例真的会红）** | ① 子路径委派工具装配整体摘掉（= 修复前）→ **1 条红 + 1 条错**；② LEAF 也装配委派工具（无委派能力却发枪）→ **恰 1 条红**；③ 目标不枚举（Schema 不限定目标范围）→ **恰 1 条红**；④ `additionalProperties` 不设 false（与闸门「多传即拒」不一致）→ **恰 1 条红**；⑤ 工具名两处各写一遍（模型可见的与闸门认得的漂移）→ **恰 1 条红**；⑥ Schema 暴露目标版本（模型可要求漂移 release）→ **恰 1 条红**；⑦ 根路径不装配委派工具（根→子没有触发点）→ **3 条红**；⑧ 装配器读不到定义也照样给委派工具（fail-closed 被拆）→ **恰 1 条红**。**八处互相独立、可分别证伪** | `scripts/c20-1-counterproof.sh`（含「恢复后复跑全绿」） |"""
replace_once(F00, old_tail, new_tail)

if not _done(F00):
    old_defect = "| ✅ **多级 Agent 在生产默认链路上根本不可用（第十九批已修）** |"
    s = F00.read_text()
    idx = s.find(old_defect)
    if idx < 0:
        raise SystemExit("00：未找到第十九批缺陷条")
    end = s.find("\n|", idx + 10)
    assert end > 0
    c20_defect = """
| ✅ **多级 Agent 没有触发点：模型看不见委派工具（第二十批已修）** | **已落地**：委派工具此前<b>从未进入任何模型的工具表</b> —— 它只出现在 `DelegationExecutor` 的常量与闸门分支里，而闸门是「执行期」的东西；模型是「发起期」的，它<b>看不见</b>就<b>不会发起</b>。后果：守卫、预算、账本、输出契约、逐跳重新授权**全都齐备**，多级 Agent 在生产上却<b>一次委派都发不出去</b>。现在由 `DelegationToolSchema`（形态）+ `DelegationToolSchemaAssembler`（装配）**显式**把它装进模型可见的工具表，**根路径与子路径共用同一份实现**。⚠️ **不放宽任何既有防线**：Schema 只是<b>提示</b>，不是授权 —— 真正的授权判定仍在闸门（`DelegationGuard`：目标未声明 → `DELEGATION_TARGET_NOT_DECLARED`），两者都成立才算合法（<b>委派关系 ≠ 授权关系</b>）；LEAF / 无子引用 → 不给；读不到编排声明 / 声明非法 / 仓储异常 → <b>一律不给</b>（fail-closed）；Schema 的参数口径与 `DelegationArguments` <b>逐字对齐</b>（否则模型按 Schema 合法发出的参数会被闸门拒 —— 两套语义，模型永远学不会）；目标用 enum 限定在自身声明内且<b>不暴露版本</b>（模型无权指定 release）。⚠️ **剩余缺口**：根 Agent 的<b>模型标识 / 提示词 / 普通工具表</b>仍是 L-4 占位（`fake-model` + 空工具表）—— 本批只补「委派工具」这一项触发点，**不得据此认为根 Agent 配置已接通** | `DelegationToolSchemaAssemblerTest` 5 例 + `WorkerChildAgentModelConfigProviderTest` 5 例 + `RootDelegationToolSchemaWiringTest` 6 例 + `DelegationToolSchemaTest` 8 例 |"""
    s = s[:end] + c20_defect + s[end:]
    F00.write_text(s)
    print("  已追加：第二十批缺陷条")
finalize(F00)

# ══════════════════════════════════════════════════════════════════
# 06 —— 委派工具 Schema 的形态与装配口径
# ══════════════════════════════════════════════════════════════════
print("06_配置数据模型与生命周期设计.md")
block06 = MARK + """
### 委派工具的 Schema 形态与装配口径（第二十批补充）

> ⚠️ **本节记录的是「委派工具在模型侧长什么样、由谁装配」的边界**，
> 不是新增配置对象。

⚠️ **委派不是普通工具**：它<b>不进</b> Skill→Tool 能力闭包
（进了闭包就等于给每个 Agent 都发一把委派枪），
因此闭包解析<b>永远给不出</b>它 —— 必须<b>显式装配</b>。

**形态（`DelegationToolSchema`）**：

| 环节 | 口径 | 为什么 |
|---|---|---|
| 工具名 | `delegate_to_agent`（**唯一定义**在 `DelegationToolSchema.TOOL_NAME`；闸门常量引用它） | 两处各写一遍必然漂移；漂移的后果是「模型看得见的委派工具」与「闸门认得的委派调用」对不上，模型发起的委派被当成「未登记的工具」拒绝 |
| 参数 | 只允许 `targetAgentId` + `input` | 必须与 `DelegationArguments.parse` **逐字对齐** —— 否则模型按 Schema 合法发出的参数被闸门拒（两套语义，模型永远学不会正确调用） |
| 多传字段 | `additionalProperties:false` | 模型必须自己看见「多传不被允许」，而不是发出后才被拒 |
| 目标范围 | `targetAgentId` 用 **enum 枚举该 Agent 自己声明的子 Agent** | 可用性（模型不必猜标识）+ 边界（不给模型试探越界目标的机会）。⚠️ **这只是提示，不是授权** |
| 版本 | **不暴露** | 目标版本由服务端按父声明解析；模型若能指定版本，就能要求漂移到别的 release（与「固定 release」直接冲突） |

⚠️ **Schema ≠ 授权**：真正的授权判定在闸门（`DelegationGuard`：
目标未声明 → `DELEGATION_TARGET_NOT_DECLARED`；深度/次数/超时/预算逐跳判定）。
Schema 只是<b>提示</b>，两者都成立才算合法 —— **委派关系 ≠ 授权关系**。

**装配口径（`DelegationToolSchemaAssembler`）**：

| 情形 | 是否装配 | 为什么 |
|---|---|---|
| 该 Agent 声明了子 Agent | ✅ 装配 | 有委派能力 |
| LEAF / 无子引用 | ❌ 不装配 | 无委派能力就别发枪 —— 否则模型反复尝试必然被拒的调用（白烧模型调用） |
| 读不到编排声明 / 声明非法 / 仓储异常 | ❌ 不装配 | **fail-closed**：不知道有没有委派能力时<b>宁可不给</b>，也不凭猜测发枪 |

⚠️ **根路径与子路径共用这一份装配器**：两处各写一份必然漂移，
而漂移的后果是「根能委派、子不能」（或反之）—— 那正是「两套语义」本身。

⚠️ **根路径按 Run 装配**：根 Agent 的身份来自 **Run 能力快照**
（`snapshot.agentId()` / `agentVersion()`）——
同一租户的不同 Run 可能由不同根 Agent 执行，
按全局装配会让 A 的 Run 拿到 B 的委派目标。

"""
insert_before(F06, "### 发布闭包算法", block06)

# ══════════════════════════════════════════════════════════════════
# 07 —— 委派工具在模型侧的装配位置
# ══════════════════════════════════════════════════════════════════
print("07_执行链集成设计.md")
block07 = MARK + """
### 委派工具在模型侧的装配位置（第二十批补充）

⚠️ **此前委派工具从未进入任何模型的工具表**：
它只出现在 `DelegationExecutor` 的常量与闸门分支里 ——
闸门是「执行期」的东西，而模型是「发起期」的：
模型<b>看不见</b>委派工具，就<b>不会发起</b>委派。
后果是守卫、预算、账本、输出契约、逐跳重新授权**全都齐备**，
多级 Agent 在生产上却**一次委派都发不出去**。

**装配链（第二十批）**：

```
模型发起前：装配模型可见的工具表
├─ 子 Agent 路径
│   └─ WorkerChildAgentModelConfigProvider.toolSchemas
│       ├─ 闭包工具（按子 Agent 自己的闭包）
│       └─ 委派工具 ← DelegationToolSchemaAssembler（按该 Agent 自己的编排声明）
└─ 根 Agent 路径
    └─ RunExecutionInputs.agentConfig(tenantId, runId)
        ├─ 根 Agent 身份 ← Run 能力快照（snapshot.agentId / agentVersion）
        └─ 委派工具 ← DelegationToolSchemaAssembler（同一份实现）
```

⚠️ **委派工具与闭包工具并列**：它不属于闭包，但同样出现在模型可见的工具表里。

⚠️ **「装配了 Schema」不等于「委派会被放行」**：
闸门的判定顺序不变（取消 → 参数口径 → 根 Run 剩余时间 → Run 级预算 →
守卫的逐跳判定 → 目标资源授权）。Schema 只解决「模型看得见」，
**不解决**「模型看得见就一定合法」。

⚠️ **根 Agent 的其余模型配置仍是 L-4 占位**：
模型标识（`fake-model`）、提示词、**普通工具表**均为占位 ——
本批只补「委派工具」这一项触发点，**不得据此认为根 Agent 配置已接通**。

"""
insert_before(F07, "### 上下文与安全", block07)

# ══════════════════════════════════════════════════════════════════
# 08 —— 验收矩阵补充
# ══════════════════════════════════════════════════════════════════
print("08_安全测试与验收矩阵.md")
block08 = MARK + """
### 变更补充：委派工具 Schema 装配的验收补充（第二十批）

⚠️ **本批验收项 MA-T05k～MA-T05n 均为「本批补充」**，补充的是
MA-T05（父 Agent 只能委派给声明过的子 Agent）在**模型可见性**上的既有约束，
不是新增约束。
⚠️ 补充的**前提**是「模型看得见委派工具」—— 修复前该前提**不成立**
（委派工具从未进入任何模型的工具表 → 运行期模型物理上不可能发起委派）。

| 用例 ID | 类别 | 场景 | 预期 | 证据 |
|---|---|---|---|---|
| MA-T05k | 委派 | 有子声明的 Agent，其**子路径**装配模型配置 | 模型**看得见**委派工具（否则运行期不可能发起委派 —— 机制齐备但没有触发点） | `WorkerChildAgentModelConfigProviderTest#delegationToolIsVisibleToModel` |
| MA-T05l | 委派 | 根 Agent 的**根路径**装配模型配置 | 模型**看得见**委派工具，且**按该 Run 的根 Agent** 装配（不串号） | `RootDelegationToolSchemaWiringTest#rootPathSeesDelegationTool` + `#delegationToolFollowsRunRootAgent` |
| MA-T05m | 委派 | LEAF / 无子引用 / 读不到编排声明 / 声明非法 / 仓储异常 | **一律不装配**委派工具 —— 无委派能力就别发枪；fail-closed：不知道就不给 | `WorkerChildAgentModelConfigProviderTest#leafAgentGetsNoDelegationTool` + `#missingAgentDefsYieldsNoDelegationTool` + `#invalidOrchestrationYieldsNoDelegationTool` + `DelegationToolSchemaAssemblerTest`（5 例） |
| MA-T05n | 委派 | Schema 的参数口径与闸门的一致性 | 字段集**逐字一致**（`targetAgentId` + `input`）、`additionalProperties:false`、目标用 enum 限定、**不暴露版本**；且工具名与闸门常量**同源** | `DelegationToolSchemaTest`（core）6 例 + `DelegationToolSchemaTest`（application）2 例 |

⚠️ **「看得见」与「不该给的不给」必须成对断言**：
只断言「看得见委派工具」的话，「全都给委派工具」也会让用例绿 ——
而那等于给每个 Agent 发一把委派枪。

⚠️ **Schema 不是授权**：目标未声明时闸门仍必须拒绝
（`DELEGATION_TARGET_NOT_DECLARED`）——
证据：`DelegationGateBranchTest#undeclaredTargetRejectedOnBothChains`
（第十九批）证明「枚举只是提示、闸门才是判定」这一层仍然成立。

⚠️ **反证**：`scripts/c20-1-counterproof.sh` 八处 ——
子路径装配整体摘掉 → 1 红 + 1 错；LEAF 也装配 → 恰 1 红；
目标不枚举 → 恰 1 红；`additionalProperties` 不设 false → 恰 1 红；
工具名两处各写 → 恰 1 红；暴露目标版本 → 恰 1 红；
根路径不装配 → 3 红；fail-closed 被拆 → 恰 1 红。

"""
append_block(F08, block08)

# ══════════════════════════════════════════════════════════════════
# 09 —— 需求追踪矩阵
# ══════════════════════════════════════════════════════════════════
print("09_需求追踪矩阵.md")
old_fr = "| FR-034 | **委派分支在两条执行链路上共用**（第十九批补充） | 06 §委派调用在两条链路上的身份口径、07 §委派分支在两条执行链路上的执行位置 | MA-05（第十九批 C19-1） | AC-026 / MA-T05、MA-T05g～MA-T05j |"
new_fr = old_fr + """
| FR-035 | **委派工具 Schema 装配 —— 让模型看得见委派能力**（第二十批补充） | 06 §委派工具的 Schema 形态与装配口径、07 §委派工具在模型侧的装配位置 | MA-05（第二十批 C20-1） | AC-027 / MA-T05、MA-T05k～MA-T05n |"""
replace_once(F09, old_fr, new_fr)

if not _done(F09):
    old_note = """⚠️ **FR-034 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""
    s = F09.read_text()
    idx = s.find(old_note)
    assert idx >= 0
    end = s.find("\n\n", idx)
    assert end > 0
    c20_note = """

⚠️ **FR-035 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定**「委派工具在模型侧长什么样、由谁装配」**
（本批取「代码定义形态 + 按 Agent 自己的编排声明显式装配 + 根/子共用一份」的最小方案）。
⚠️ 补充的是**既有约束**（MA-T05 的目标未声明拒绝）在**模型可见性**上的落地 ——
此前模型根本看不见委派工具，那条约束连被触发的机会都没有。
⚠️ **剩余缺口**：① 根 Agent 的**模型标识 / 提示词 / 普通工具表**仍是 **L-4 占位**
（`fake-model` + 空工具表）→ 本批只补「委派工具」这一项触发点，
**不得据此认为根 Agent 配置已接通**；② 委派工具 Schema 的**内容模型**
（描述措辞、是否按编排模式定制、`input` 的结构化程度）尚未评审冻结，
本批取的是**最小可用形态**；③ 根路径的委派工具装配依赖 Run 能力快照，
**读不到快照时 fail-closed 不装配**（宁可不给）——
该场景在生产上意味着「该 Run 无法发起委派」，需评审确认这是否是期望行为。"""
    s = s[:end] + c20_note + s[end:]
    F09.write_text(s)
    print("  已追加：FR-035 口径说明")
finalize(F09)

print("\n✅ 文档回写完成")
