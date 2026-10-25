#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第十九批 C19-1 文档回写：把「委派分支两条执行链路共用」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（用「标记存在则跳过」的方式），
   不会重复插入同一段内容。
⚠️ 口径：本批的模型/编号均为「本批补充」，**非评审冻结模型** ——
   文档里必须如实标注，不得虚报实现状态。
"""
import pathlib
import sys

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C19-1 -->"

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
    "## 实现状态快照（2026-10-25 · 代码落地第十八批：CAP 链路的子 Agent 路径按固定 release 贯穿授权与准入）",
    "## 实现状态快照（2026-10-25 · 代码落地第十九批：委派分支在两条执行链路上共用）",
)

replace_once(
    F00,
    """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1253 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十七批基线（`f745086`）同口径为 **1247 例** → 本批 **+6 例**
>   （全在 `CapExecutionFlowTest`：子 Agent 路径 4 例 + 根路径回归保护 2 例），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例仍成对断言**（「子路径可用」与「根的防线仍在」互相独立）：
>   拆掉防线只让防线那条红，拆掉可用性只让可用性那条红 —— 否则「全都拒」也能让用例绿。""",
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
)

# 已实现表：追加两行
old_tail = "| **第十八批反证（五处，逐一验证用例真的会红）** | ① 快照复核改回老口径（Phase A 基线 vs 快照）→ **3 条**红；② 校验器漂移回「当前已发布版本」→ **恰 1 条**红；③ 准入漂移回「当前已发布版本」→ **恰 1 条**红；④ 快照复核**整体跳过** → **恰 1 条**红（且**只有**防线那条红，可用性那条仍绿 → 证明两处修复互相独立）；⑤ Phase A 不下传固定版本 → **恰 1 条**红 | `scripts/c18-1-counterproof.sh`（含「恢复后复跑全绿」） |"
new_tail = old_tail + """
| **委派分支在两条执行链路上共用（第十九批 · C19-1）** | 修复前**委派分支只存在于 CAP 链路**：既有链路（`invokeAllLegacy`）里委派调用落到 `findHandler` → null → 被当成「**未登记的工具**」拒掉。而 `config.flag.cap-authorization-enforced` **默认 false** → 既有链路就是**生产默认路径** → 多级 Agent 在生产上**根本不可用**（同一个委派调用在两条链路上给出不同结论：CAP 链路成功、既有链路被拒）。现在两条链路**共用同一份实现**：`isDelegationCall`（判定委派调用）+ `handleDelegationCall`（FAIL_FAST 停止 / scope 与 ctx 一致性校验 / 调用委派执行器）；委派判定**必须排在「未登记的工具」判定之前**（委派不是普通工具，不注册 `ToolHandler`）。⚠️ **委派仍不经过普通工具的 Schema/授权/幂等/账本闸门**：它的授权与边界由 `DelegationGuard` 承担（逐跳授权 + 深度/次数/超时/预算），副作用由 `agent_invocation` 承担 —— 两条链路同口径 | `DelegationGateBranchTest.ChainParity` 15 例（逐条链路参数化） |
| **第十九批反证（七处，逐一验证用例真的会红）** | ① 既有链路摘掉委派分支（= 修复前状态）→ **6 条红 + 2 条错**（且**只有 LEGACY 链路红**，CAP 链路保持绿 → 证明改动范围准确）；② 委派判定排到「未登记的工具」之后（顺序错了）→ **6 条红 + 2 条错**（同样只有 LEGACY 红）；③ 一致性校验只比 agentId 不比 version → **恰 2 条红**（且**只有版本漂移那条红**，标识不一致那条仍绿 → 证明两处校验互相独立）；④ 一致性校验整体摘掉 → **4 条红**（标识 + 版本、两条链路）；⑤ FAIL_FAST 停止语义摘掉 → **3 条红**；⑥ 既有链路绕过共用实现（本地复制一份）→ **3 条红**（且**只有 LEGACY 红** → 证明「共用」不可退化）；⑦ 未装配执行器时不再回落 → **3 条错**（NPE，说明「未登记的工具」这条防线真的在）。**七处互相独立、可分别证伪** | `scripts/c19-1-counterproof.sh`（含「恢复后复跑全绿」） |"""
replace_once(F00, old_tail, new_tail)

# 「历批修正的设计缺陷」—— 在第十八批那段之后追加第十九批
if not _done(F00):
    old_defect = "| ✅ **CAP 链路的子 Agent 路径整体不可用（第十八批已修）** |"
    s = F00.read_text()
    idx = s.find(old_defect)
    if idx < 0:
        raise SystemExit("00：未找到第十八批缺陷条")
    # 找到该行的结尾（下一个 "\n|" 之前）
    end = s.find("\n|", idx + 10)
    assert end > 0
    c19_defect = """
| ✅ **多级 Agent 在生产默认链路上根本不可用（第十九批已修）** | **已落地**：委派分支此前**只存在于 CAP 链路**（`invokeAllCap`），既有链路（`invokeAllLegacy`）里委派调用落到 `findHandler` → null → 被当成「**未登记的工具**」拒掉（`TOOL_NOT_DECLARED`）。⚠️ **既有链路正是生产默认路径**：`config.flag.cap-authorization-enforced` 默认 `false` → 闸门走既有链路 → 于是「多级 Agent 在生产上根本不可用」，而代码看上去是齐的（委派执行器、守卫、账本、契约都在）。现在两条链路**共用** `isDelegationCall` + `handleDelegationCall`，委派判定排在「未登记的工具」判定**之前**；`invokeAllLegacy` 的签名由 `(…, agentRef)` 改为 `(…, scope, delegationContext)`（Agent 维度仍由 `resolveAgentRef` 解析 → 幂等键分域口径不变）。⚠️ **不放宽任何既有防线**：未装配委派执行器时两条链路都落回「未登记的工具」被拒（不静默放行）；scope 与 ctx 不一致时两条链路都 fail-closed 拒绝（`ORCHESTRATION_INVALID` + 零子执行）；FAIL_FAST 停止语义、目标未声明、参数非法、取消检查、Run 剩余时间封顶**全部沿用**原口径 | `DelegationGateBranchTest.ChainParity` 15 例 |"""
    s = s[:end] + c19_defect + s[end:]
    F00.write_text(s)
    print("  已追加：第十九批缺陷条")

# ⚠️ 消解第十八批的缺口①：那一批的修复只在 CAP 链路生效，
#    而 CAP 链路默认关闭 → 生产走既有链路 → 修复在生产上不起作用。
#    第十九批把委派分支补进既有链路后，生产默认路径才真正可用。
#    ⚠️ **但这只消解了「委派本身可用」**：
#    CAP 链路的授权与准入增强（固定 release 贯穿、快照复核）
#    在生产默认路径上**仍未生效** —— 那属另一条缺口，不因本批消失。
old_gap18 = "③ 子路径的**审批范围绑定（MA-T18）**与**输出契约（MA-T16）**在 CAP 链路上的端到端证据待补 | `CapExecutionFlowTest#childToolCallSucceeds` / `#parentAndChildBothExecute` / `#childDoesNotDriftToNewRelease` / `#rootClosureDriftStillBlocksChildPath` / `#rootClosureDriftStillBlocksRootPath` / `#rootPathStillWorksWhenNothingChanged` |"
new_gap18 = old_gap18 + """
| ⚠️ **第十九批已消解第十八批的缺口①**：第十八批的修复只在 CAP 链路上生效，而 CAP 链路**默认关闭** → 生产走既有链路，那批修复在生产上并不起作用。第十九批把**委派分支**补进既有链路后，生产默认路径上多级 Agent 才真正可用。⚠️ **但这只是「委派本身可用」**：CAP 链路的**授权与准入增强**（固定 release 贯穿、快照复核）在生产默认路径上**仍未生效** —— 换句话说，既有链路上的子 Agent 工具调用**仍按旧口径**授权。这是**另一条**缺口（属 CAP 链路本身的价值兑现，需评审决定是否默认开启该开关），**不因本批而消失** | — |"""
s = F00.read_text()
if old_gap18 in s and new_gap18 not in s:
    F00.write_text(s.replace(old_gap18, new_gap18, 1))
    print("  已更新：第十八批缺口①的消解说明")
finalize(F00)

# ══════════════════════════════════════════════════════════════════
# 06 —— 委派调用在两条链路上的身份口径
# ══════════════════════════════════════════════════════════════════
print("06_配置数据模型与生命周期设计.md")
block06 = MARK + """
### 委派调用在两条执行链路上的身份口径（第十九批补充）

> ⚠️ **本节记录的是「同一个委派调用在两条链路上必须同身份」的边界**，
> 不是新增配置对象。

⚠️ **此前委派分支只存在于 CAP 链路**（`invokeAllCap`），既有链路
（`invokeAllLegacy`）里委派调用落到 `findHandler` → null →
被当成「**未登记的工具**」拒掉（`TOOL_NOT_DECLARED`）。

⚠️ **这不是「两条链路行为略有差异」，而是「多级 Agent 在生产上不可用」**：
`config.flag.cap-authorization-enforced` 默认 `false` → 生产走既有链路。
于是同一个委派调用在两条链路上给出**不同结论**（CAP 链路成功、既有链路被拒）。

**身份口径（第十九批）**：

| 环节 | 口径 | 为什么 |
|---|---|---|
| 委派判定 | `isDelegationCall(call)`：委派执行器**已装配** 且 工具名匹配 | 未装配时落回「未登记的工具」被拒 —— **不静默放行**（否则没有委派执行器的部署会「看起来支持委派」） |
| 判定顺序 | 必须在「未登记的工具」判定**之前** | 委派不是普通工具（不注册 `ToolHandler`），先查 handler 就必然被判成「未登记」 |
| 执行作用域 | `ExecutionScope`（按哪个 release 授权） | 与 `DelegationContext`（当前 Agent 是谁）**不可互相替代** |
| 一致性校验 | 两者同时存在时，agentId **与** agentVersion 必须一致 | 不一致 = 上下文串了 → **fail-closed 拒绝**（`ORCHESTRATION_INVALID`），不猜哪个是对的 |

⚠️ **两条链路共用同一份实现**（`handleDelegationCall`）：「共用」不是洁癖 ——
两份实现必然漂移（本地复制一份就会丢掉一致性校验），
而漂移出来的差异恰好落在**越权路径**上。

"""
insert_before(F06, "### 发布闭包算法", block06)

# ══════════════════════════════════════════════════════════════════
# 07 —— 委派分支在两条链路上的执行位置
# ══════════════════════════════════════════════════════════════════
print("07_执行链集成设计.md")
block07 = MARK + """
### 委派分支在两条执行链路上的执行位置（第十九批补充）

⚠️ **此前委派分支只存在于 CAP 链路**（`invokeAllCap` 的循环体内），
既有链路（`invokeAllLegacy`）没有对应分支 → 委派调用落到 `findHandler` → null →
「**未登记的工具**」被拒。而既有链路是**生产默认路径**
（`config.flag.cap-authorization-enforced` 默认 `false`）
→ 多级 Agent 在生产上根本不可用。

**执行链（第十九批）**：

```
一次模型返回的工具调用
├─ isDelegationCall(call)?                       ← 两条链路共用
│   ├─ 否 → 普通工具路径（各链路原有序，不变）
│   └─ 是 → handleDelegationCall（两条链路共用）
│       ├─ ① FAIL_FAST 已停？→ 记拒绝（DELEGATION_NOT_ALLOWED），不再发起
│       ├─ ② scope 与 ctx 不一致？→ fail-closed 拒绝（ORCHESTRATION_INVALID）
│       │     └─ ⚠️ 零子执行
│       └─ ③ invokeDelegation
│             ├─ 取消检查（RUN_CANCELLED）
│             ├─ 参数口径（PARAM_SCHEMA_FAIL）
│             ├─ invocationId 派生（父调用参与）
│             ├─ 根 Run 剩余时间 ≤ 0？（DELEGATION_TIMEOUT）
│             ├─ Run 级预算判定 + 预留（第十六批）
│             └─ 委派执行器 → 守卫 → 子 Agent
└─ 未装配委派执行器 → 落回「未登记的工具」（TOOL_NOT_DECLARED，两条链路同口径）
```

⚠️ **委派**不经过**普通工具**的 Schema / 授权 / 幂等 / 账本闸门**：
它的授权与边界由 `DelegationGuard` 承担（逐跳授权 + 深度/次数/超时/预算），
副作用由 `agent_invocation` 承担。**两条链路同口径** ——
否则「同一个委派在两条链路上额度不同 / 深度不同」就是可利用的差异。

⚠️ **既有链路的 `invokeAllLegacy` 签名随之调整**：
由 `(tenantId, task, calls, agentRef)` 改为 `(tenantId, task, calls, scope, delegationContext)`
—— Agent 维度（幂等键分域，第十七批）仍由 `resolveAgentRef(scope, ctx)` 解析，
**键值口径不变**。

"""
insert_before(F07, "### 上下文与安全", block07)

# ══════════════════════════════════════════════════════════════════
# 08 —— 验收矩阵补充
# ══════════════════════════════════════════════════════════════════
print("08_安全测试与验收矩阵.md")
block08 = MARK + """
### 变更补充：委派分支两条执行链路一致的验收补充（第十九批）

⚠️ **本批验收项 MA-T05g～MA-T05j 均为「本批补充」**，补充的是
MA-T05（父 Agent 只能委派给声明过的子 Agent）与 MA-T11（取消后不得发起子执行）
在**两条执行链路**上的既有约束，不是新增约束。
⚠️ 补充的**前提**是「委派在生产默认链路上能跑」—— 修复前该前提**不成立**
（既有链路把委派当成「未登记的工具」拒掉，而它是生产默认路径）。

| 用例 ID | 类别 | 场景 | 预期 | 证据 |
|---|---|---|---|---|
| MA-T05g | 委派 | 合法委派，在**既有链路**（生产默认路径）上发起 | **成功执行子 Agent**（此前被当成「未登记的工具」拒掉 → 多级 Agent 在生产上不可用） | `DelegationGateBranchTest#legalDelegationSucceedsOnBothChains` |
| MA-T05h | 委派 | 目标未在父的声明内 / 委派参数非法，在**两条链路**上 | 两条链路**都拒绝**且**零子执行**（理由码逐条一致：`DELEGATION_TARGET_NOT_DECLARED` / `PARAM_SCHEMA_FAIL`） | `DelegationGateBranchTest#undeclaredTargetRejectedOnBothChains` + `#malformedArgumentsRejectedOnBothChains` |
| MA-T05i | 委派 | 执行作用域与委派上下文**串了**（agentId 不同 或 version 不同），在**两条链路**上 | 两条链路都 **fail-closed 拒绝**（`ORCHESTRATION_INVALID`）+ 零子执行 —— 不得「挑一个信的用」 | `DelegationGateBranchTest#inconsistentScopeAndContextRejectedOnBothChains` + `#versionMismatchAlsoRejectedOnBothChains` |
| MA-T05j | 委派 | 同一个委派调用在**两条链路**上 | **四字段结论逐项相同**（reasonCode / status / authorization / execution）+ 子执行次数相同 | `DelegationGateBranchTest#bothChainsProduceIdenticalConclusions` |

⚠️ **成对断言的必要性**：「两条链路都放行」与「两条链路都拒绝」都能让
「只跑一条链路」的用例绿 —— 故本批用例**逐条链路参数化**
（`@EnumSource(Chain.class)`），每条断言都必须**同时**在两条链路上成立。

⚠️ **未装配委派执行器**：两条链路都以「未登记的工具」被拒（`TOOL_NOT_DECLARED`），
不得静默放行 —— 该场景的用例**成对**（先断言「装配时委派真的跑通」，
再断言「未装配时被拒」），否则「委派在两条链路上根本没跑起来」也会让用例绿。
证据：`DelegationGateBranchTest#missingExecutorRejectedOnBothChains`。

⚠️ **反证**：`scripts/c19-1-counterproof.sh` 七处 ——
既有链路摘掉委派分支 → 6 条红 + 2 条错（**只有 LEGACY 红**）；
委派判定排到「未登记的工具」之后 → 6 条红 + 2 条错（同样只有 LEGACY 红）；
一致性校验只比 agentId → 恰 2 条红（**只有版本漂移那条红**）；
一致性校验整体摘掉 → 4 条红；FAIL_FAST 停止摘掉 → 3 条红；
既有链路绕过共用实现 → 3 条红（**只有 LEGACY 红**）；
未装配执行器不再回落 → 3 条错。

"""
append_block(F08, block08)

# ══════════════════════════════════════════════════════════════════
# 09 —— 需求追踪矩阵
# ══════════════════════════════════════════════════════════════════
print("09_需求追踪矩阵.md")
old_fr = "| FR-033 | **子 Agent 路径按固定 release 贯穿授权与准入**（第十八批补充） | 06 §固定 release 的贯穿口径、07 §子 Agent 路径的授权与准入口径 | MA-05（第十八批 C18-1） | AC-025 / MA-T08、MA-T08a～MA-T08b、MA-T15b |"
new_fr = old_fr + """
| FR-034 | **委派分支在两条执行链路上共用**（第十九批补充） | 06 §委派调用在两条链路上的身份口径、07 §委派分支在两条执行链路上的执行位置 | MA-05（第十九批 C19-1） | AC-026 / MA-T05、MA-T05g～MA-T05j |"""
replace_once(F09, old_fr, new_fr)

# FR-034 的口径说明，追加在 FR-033 说明之后
if not _done(F09):
    old_note = """⚠️ **FR-033 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项："""
    s = F09.read_text()
    idx = s.find(old_note)
    assert idx >= 0
    # 找到 FR-033 说明段的结尾（下一个空行前的段末）
    end = s.find("\n\n", idx)
    assert end > 0
    c19_note = """

⚠️ **FR-034 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定**「委派分支在哪几条执行链路上存在」**
（本批取「两条链路共用同一份实现、判定排在『未登记的工具』之前」的最小方案）。
⚠️ 补充的是**既有约束**（MA-T05 / MA-T11 的目标未声明拒绝、参数非法拒绝、
取消后不发起子执行）在**生产默认链路**上的落地 —— 此前那条链路根本没走到委派分支。
⚠️ **剩余缺口**：① 委派分支的**跨链路端到端证据**（跨 `DelegationExecutor`
→ scope → 闸门 → 子 Agent → 回灌）仍缺，当前是**闸门层**的链路一致性证据；
② **委派工具的 Schema 从未装配给模型**（`delegate_to_agent` 只出现在
`DelegationExecutor` 常量与闸门分支里；根 Agent 的 `AgentModelConfig` 在生产仍是
`fake-model` 占位 + 空 toolSchemas）→ 运行期模型**不可能发起委派**，
多级机制齐备但**无触发点** —— 这是比本批更大的缺口，需单独处理；
③ CAP 链路默认关闭，子路径在 CAP 链路上的端到端证据（审批范围绑定 MA-T18 /
输出契约 MA-T16）待补。"""
    s = s[:end] + c19_note + s[end:]
    F09.write_text(s)
    print("  已追加：FR-034 口径说明")
finalize(F09)

print("\n✅ 文档回写完成")
