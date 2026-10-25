#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第十八批文档回写（幂等可重跑）。"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")


def patch(name, pairs):
    p = BASE / name
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if new in s:
            print(f"[skip] {name}: 已是目标形态 -> {new[:40]}")
            continue
        if old not in s:
            raise SystemExit(f"[MISS] {name}: {old[:80]!r}")
        s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print(f"[ok] {name}")


# ══════════════════════════════════════════════════════════════
# 00_修订说明与评审决议项.md
# ══════════════════════════════════════════════════════════════
patch("00_修订说明与评审决议项.md", [
    # ① 快照标题
    (
        "## 实现状态快照（2026-10-25 · 代码落地第十七批：工具幂等键按「当前执行中的 Agent」分域）",
        "## 实现状态快照（2026-10-25 · 代码落地第十八批：CAP 链路的子 Agent 路径按固定 release 贯穿授权与准入）",
    ),
    # ② 全量验证口径
    (
        """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1247 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十六批基线（`289bd4c`）同口径为 **1228 例** → 本批 **+19 例**
>   （`IdempotencyProtocolTest` +9 / `NestedToolIdempotencyTest` +7 / `CapExecutionFlowTest` +3），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例一律成对断言**（不同 Agent 必须产生不同键、同一 Agent 重复投递必须产生同一个键）：
>   只断言「键里含 agent 字样」在「所有调用都塞进同一个键」时同样会绿 —— 那不是证据。""",
        """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1253 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十七批基线（`f745086`）同口径为 **1247 例** → 本批 **+6 例**
>   （全在 `CapExecutionFlowTest`：子 Agent 路径 4 例 + 根路径回归保护 2 例），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例仍成对断言**（「子路径可用」与「根的防线仍在」互相独立）：
>   拆掉防线只让防线那条红，拆掉可用性只让可用性那条红 —— 否则「全都拒」也能让用例绿。""",
    ),
    # ③ 「已实现」表新增两行
    (
        """| **第十七批反证（六处，逐一验证用例真的会红）** | ① 闸门不解析 Agent 引用 → **4 条**红；② 幂等键整体丢弃 Agent 维度 → **4 条**红；③ 只认执行作用域、丢掉委派上下文 → **恰 1 条**红；④ Agent 引用不带版本 → **3 条**红；⑤ CAP 链路自行派生（忽略闸门透传）→ **恰 1 条**红；⑥ 所有 Agent 共用同一个 Agent 段 → **3 条**红。**六处互相独立、可分别证伪** | `scripts/c17-1-counterproof.sh`（含「恢复后复跑全绿」） |""",
        """| **第十七批反证（六处，逐一验证用例真的会红）** | ① 闸门不解析 Agent 引用 → **4 条**红；② 幂等键整体丢弃 Agent 维度 → **4 条**红；③ 只认执行作用域、丢掉委派上下文 → **恰 1 条**红；④ Agent 引用不带版本 → **3 条**红；⑤ CAP 链路自行派生（忽略闸门透传）→ **恰 1 条**红；⑥ 所有 Agent 共用同一个 Agent 段 → **3 条**红。**六处互相独立、可分别证伪** | `scripts/c17-1-counterproof.sh`（含「恢复后复跑全绿」） |
| **CAP 链路的子 Agent 路径真正可用（第十八批 · C18-1）** | 修复前**子 Agent 的每一次工具调用都在步骤 13 被拒**（`EXEC_ADMISSION_STALE`）—— 多级路径在 CAP 链路上**整体不可用**。两个独立缺陷：① **快照复核拿错了主体**：Run 快照存的是**根 Agent** 的闭包指纹，而子路径的 Phase A 基线是**子 Agent** 的，闭包指纹含 `agent.id` → 两者是不同主体、必然不等；口径改为**按「快照自己的 Agent」比对**（快照证明的是「**根**的依赖集没变」）。② **闭包解析漂移到「当前已发布版本」**：校验器与准入各自按 `resolveCurrent` 解析，别人给子 Agent 发布新版本时在途委派会漂移并被拒 —— 现在一律按**固定 release** 解析，版本由 Phase A 一路下传 | `CapExecutionFlowTest` 6 例（子路径可用 4 + 根路径回归保护 2） |
| **第十八批反证（五处，逐一验证用例真的会红）** | ① 快照复核改回老口径（Phase A 基线 vs 快照）→ **3 条**红；② 校验器漂移回「当前已发布版本」→ **恰 1 条**红；③ 准入漂移回「当前已发布版本」→ **恰 1 条**红；④ 快照复核**整体跳过** → **恰 1 条**红（且**只有**防线那条红，可用性那条仍绿 → 证明两处修复互相独立）；⑤ Phase A 不下传固定版本 → **恰 1 条**红 | `scripts/c18-1-counterproof.sh`（含「恢复后复跑全绿」） |""",
    ),
    # ④ 「未实现（下一批）」：把「CAP 子路径不可用」那条改成「已修 + 剩余缺口」
    (
        """| **CAP 链路的子 Agent 路径整体不可用（第十七批发现 · 未修）** | 步骤 13 执行准入拿「Run 快照里的**根 Agent** 闭包指纹」去比「Phase A 的**子 Agent** 闭包指纹」，而闭包指纹**含 `agent.id`** → 两者必然不等 → `EXEC_ADMISSION_STALE` → **子 Agent 的每一次工具调用都被拒**（fail-closed，故不是越权漏洞，而是多级路径在 CAP 开关打开后不可用）。⚠️ **本批刻意不修**：修它要动的是**准入语义**（「Run 快照对子路径意味着什么」），不是幂等键；且该链路当前**默认关闭**（`config.flag.cap-authorization-enforced` 默认 `false`），生产走的是既有链路。⚠️ 正因为如此，CAP 侧只断言**幂等键**，**不**把「子 Agent 必须调用成功」固化成预期行为 | `CapExecutionFlowTest#parentAndChildUseDifferentKeys`（断言键）；准入侧无对应用例（待下一批补） |""",
        """| ✅ **CAP 链路的子 Agent 路径整体不可用（第十八批已修）** | **已落地**：① 执行准入的快照复核改为**按「快照自己的 Agent」比对**（子路径拿根的当前闭包指纹比快照；根路径复用同一执行主体的当前指纹 → 口径等价、不重复解析）；② 授权校验与执行准入**一律按固定 release** 解析闭包（`InvocationRequest.agentVersion` / `AdmissionRequest.agentVersion`，`null` = 根路径的当前已发布版本），版本由 Phase A 从执行作用域一路下传。**不放宽任何既有防线**：根的闭包漂移 → 根路径在 Phase A 步骤 7 被拒（`CONFIG_FINGERPRINT_STALE`，拦截更早）、子路径在步骤 13 被拒（`EXEC_ADMISSION_STALE`）；撤权两条路径都仍在准入被拒；子 Agent 固定 release 被就地改写 → 仍被指纹比对拒。⚠️ **剩余缺口**：① CAP 链路**默认关闭**（`config.flag.cap-authorization-enforced` 默认 `false`），生产走既有链路 → 本批修复在开关打开前**不生效**；② 子路径在 CAP 链路上的**端到端证据**（跨 `DelegationExecutor` → scope → CAP 闸门 → 工具）仍缺，目前只有 `CapExecutionFlow` 层的单元证据；③ 子路径的**审批范围绑定（MA-T18）**与**输出契约（MA-T16）**在 CAP 链路上的端到端证据待补 | `CapExecutionFlowTest#childToolCallSucceeds` / `#parentAndChildBothExecute` / `#childDoesNotDriftToNewRelease` / `#rootClosureDriftStillBlocksChildPath` / `#rootClosureDriftStillBlocksRootPath` / `#rootPathStillWorksWhenNothingChanged` |""",
    ),
    # ⑤ 「历批修正的设计缺陷」新增两条
    (
        """| **CAP 链路的子 Agent 路径被准入检查整体拒绝（第十七批发现 · 未修）** | 见「未实现（下一批）」同名条 —— 已登记，**本批不修**（要动的是准入语义） | 暂无对应用例（下一批补） |""",
        """| **CAP 链路的子 Agent 路径被准入检查整体拒绝（第十八批修 · C18-1）** | 快照复核拿「Run 快照里的**根 Agent** 闭包指纹」去比「Phase A 的**子 Agent** 闭包指纹」—— 不同主体、必然不等 → 子 Agent 的每次工具调用都被判 `EXEC_ADMISSION_STALE` → 多级路径在 CAP 链路上整体不可用（fail-closed，故非越权漏洞，而是功能面全死） | 快照复核改为**按「快照自己的 Agent」比对**。⚠️ 根路径上该口径**保持等价**（同一执行主体时复用当前指纹），并专门补了根路径的回归保护用例 | `CapExecutionFlowTest#childToolCallSucceeds`（反证：改回老口径 → 3 条红） |
| **子 Agent 的闭包解析漂移到「当前已发布版本」（第十八批修 · C18-1）** | 校验器与执行准入各自按 `resolveCurrent` 解析闭包，而子 Agent 的 release 在**委派时固定** → 别人给子 Agent 发布新版本时，在途委派漂移到新 release，表现为「工具未声明 / Skill 未启用」而**被拒**：**「发布」被变成了「打断在途执行」**，与「子 Agent 固定 release」这条不可妥协约束直接冲突 | 授权与准入**一律按固定版本**解析，版本由 Phase A 从执行作用域一路下传（`agentVersion`）。⚠️ 这条修的是**约束被违反**，不是新增设计：MA-T08 原文就要求「运行期间子 Agent 发布新版本 → 当前 Run 继续绑定原 release，不静默漂移」 | `CapExecutionFlowTest#childDoesNotDriftToNewRelease`（反证：校验器漂移 / 准入漂移 / Phase A 不下传 → 各恰 1 条红） |""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 06_配置数据模型与生命周期设计.md
# ══════════════════════════════════════════════════════════════
patch("06_配置数据模型与生命周期设计.md", [
    (
        "### 发布闭包算法",
        """### 固定 release 的贯穿口径（第十八批补充）

> ⚠️ **本节记录的是「同一个版本号必须在链路上保持一致」的边界**，不是新增配置对象。

子 Agent 的 release 在**委派时固定**（`childAgentRefs` 的固定版本引用；
`latest` 一律拒绝发布）。这个版本号此后会出现在**三处**，
三处必须**解析同一个闭包**，否则比的是三个不同的东西：

| 环节 | 解析口径 | 为什么 |
|---|---|---|
| 委派时 | `resolve(tenantId, targetId, targetVersion)` → 写入 `ExecutionScope`（含闭包指纹） | 把「这次委派授权到什么」**固化**下来 |
| 授权（Phase A 步骤 3–7） | `resolve(tenantId, agentId, **固定版本**)` | 授权依据必须与委派时固化的那一份一致 —— 否则「委派给 v1、执行时按 v2 授权」 |
| 执行准入（§5.3 步骤 13） | `resolve(tenantId, agentId, **固定版本**)` | 新鲜度校验必须重算**同一个**闭包，否则「指纹变了没有」这个判断本身就没有意义 |

⚠️ **固定版本必须一路带到底**：`PhaseA` 承载它，
经 `InvocationRequest.agentVersion` / `AdmissionRequest.agentVersion` 下传
（`null` = 单 Agent 路径 = 「当前已发布版本」）。

⚠️ **为什么不能用「当前已发布版本」**：那会让「别人给子 Agent 发布新版本」
变成「打断在途执行」—— 在途委派漂移到新 release，表现为「工具未声明 / Skill 未启用」
而被拒。**发布是治理动作，不该顺手改写在途执行的授权依据**（MA-T08 的原意）。

⚠️ **Run 快照复核按「快照自己的 Agent」**：快照存的是**根 Agent** 的闭包指纹
（Run 的依赖集固化证据），而子路径的执行主体是**子 Agent** ——
两者是不同主体，**不可互相比**。子路径下应拿**根**的当前闭包指纹比快照；
子 Agent 自己的新鲜性由上表第二、三行保证。

### 发布闭包算法""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 07_执行链集成设计.md
# ══════════════════════════════════════════════════════════════
patch("07_执行链集成设计.md", [
    (
        "### 上下文与安全",
        """### 子 Agent 路径的授权与准入口径（第十八批）

⚠️ **此前 CAP 链路的子 Agent 路径整体不可用**：子 Agent 的每一次工具调用
都在 §5.3 步骤 13 被判 `EXEC_ADMISSION_STALE`。两个独立缺陷：

① **快照复核拿错了主体** —— Run 快照存的是**根 Agent** 的闭包指纹，
子路径的 Phase A 基线是**子 Agent** 的闭包指纹，而闭包指纹**含 `agent.id`** →
两者是不同主体、必然不等 → 每次调用都被判失效。

② **闭包解析漂移到「当前已发布版本」** —— 校验器与准入各自按 `resolveCurrent`
解析，而子 Agent 的 release 在委派时**固定** → 别人给它发布新版本时，
在途委派漂移并被拒（「发布」变成了「打断在途执行」）。

**执行链（第十八批）**：

```
子 Agent 的工具调用（CAP 链路）
├─ Phase A
│   ├─ 闭包解析：按 scope 的**固定 release**（不是「当前已发布版本」）
│   ├─ 步骤 3–7 校验：校验器按**同一个固定 release** 解析
│   │   └─ 步骤 7 指纹比对：与 scope.expectedFingerprint（委派时固化的）比对
│   └─ PhaseA 携带 agentVersion（固定版本；根路径为 null）
├─ 步骤 8 幂等键：按「当前执行中的 Agent」分域（第十七批）
├─ 步骤 13 执行准入
│   ├─ 授权版本未递增？（撤权 → EXEC_ADMISSION_STALE）
│   ├─ 执行主体闭包新鲜度：按**同一个固定 release** 重算
│   └─ Run 快照复核：按「**快照自己的 Agent**」的当前闭包比对
│       ├─ 同一执行主体（根路径）→ 复用刚算出的当前指纹（不重复解析）
│       └─ 不同主体（子路径）→ 拿根的当前闭包指纹比快照
└─ 步骤 14 执行
```

⚠️ **不放宽任何既有防线**：根的闭包漂移 → 根路径在 Phase A 步骤 7 被拒
（`CONFIG_FINGERPRINT_STALE`，拦截点更早）、子路径在步骤 13 被拒
（`EXEC_ADMISSION_STALE`）；撤权两条路径都仍在准入被拒。

⚠️ **仍然待补**：子路径在 CAP 链路上的**端到端证据**
（跨 `DelegationExecutor` → scope → CAP 闸门 → 工具）与
**审批范围绑定（MA-T18）/ 输出契约（MA-T16）**的子路径端到端证据。

### 上下文与安全""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 08_安全测试与验收矩阵.md
# ══════════════════════════════════════════════════════════════
patch("08_安全测试与验收矩阵.md", [
    (
        """⚠️ **本批未覆盖的路径**：CAP 链路的**子 Agent 工具执行**本身
（步骤 13 执行准入会拒 —— 已登记为缺口，见 00「未实现」同名条）。
故 CAP 侧只断言**幂等键**，**不**把「子 Agent 必须调用成功」固化成预期行为。""",
        """⚠️ **本批未覆盖的路径**：CAP 链路的**子 Agent 工具执行**本身
（步骤 13 执行准入会拒 —— 已登记为缺口，见 00「未实现」同名条）。
故 CAP 侧只断言**幂等键**，**不**把「子 Agent 必须调用成功」固化成预期行为。

### 变更补充：固定 release 与子 Agent 路径的验收补充（第十八批）

⚠️ **本批验收项 MA-T08a～MA-T08b、MA-T15b 均为「本批补充」**，补充的是
MA-T08（运行期间子 Agent 发布新版本 → 当前 Run 继续绑定原 release，不静默漂移）
与 MA-T15（依赖漂移 → 指纹校验失败，阻止执行）在**子 Agent 路径**上的既有约束。
⚠️ MA-T08 的隐含前提是「子路径本身能跑」—— 修复前该前提**不成立**
（子 Agent 的每次调用都在步骤 13 被拒），故本批先把它立起来。

| 用例 ID | 类别 | 场景 | 预期 | 证据 |
|---|---|---|---|---|
| MA-T08a | 快照 | 子 Agent 在 CAP 链路上调用工具 | **成功执行**（此前被准入整体拒绝 → 多级路径在 CAP 链路上不可用） | `CapExecutionFlowTest#childToolCallSucceeds` |
| MA-T08b | 快照 | 子 Agent **发布新 release** 后，在途委派继续执行 | 仍绑定**原 release**（不漂移、不被拒）——「发布」不得变成「打断在途执行」 | `CapExecutionFlowTest#childDoesNotDriftToNewRelease` |
| MA-T15b | 指纹 | **根** Agent 的闭包漂移，而调用来自**子 Agent** | 拒绝（`EXEC_ADMISSION_STALE`）+ 零副作用 —— 快照证明的是「**根**的依赖集没变」，漂移仍必须阻断子路径 | `CapExecutionFlowTest#rootClosureDriftStillBlocksChildPath` |

⚠️ **回归保护（根路径）**：根的闭包漂移 → 根路径在 Phase A 步骤 7 被拒
（`CONFIG_FINGERPRINT_STALE`，拦截点比准入更早）；根路径未被改动时**照常放行**
—— 修复不得把单 Agent 链路一并打死。
证据：`CapExecutionFlowTest#rootClosureDriftStillBlocksRootPath` /
`#rootPathStillWorksWhenNothingChanged`。

⚠️ **反证**：`scripts/c18-1-counterproof.sh` 五处 ——
快照复核改回老口径 → 3 条红；校验器漂移 → 1 条红；准入漂移 → 1 条红；
**快照复核整体跳过 → 恰 1 条红且只有防线那条红**（可用性那条仍绿 → 两处修复互相独立）；
Phase A 不下传固定版本 → 1 条红。""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 09_需求追踪矩阵.md
# ══════════════════════════════════════════════════════════════
patch("09_需求追踪矩阵.md", [
    (
        "| FR-032 | **工具幂等键按「当前执行中的 Agent」分域**（第十七批补充） | 06 §工具幂等键的 Agent 维度、07 §工具幂等键的 Agent 维度 | MA-05（第十七批 C17-1） | AC-022 / MA-T10、MA-T10b～MA-T10e |",
        """| FR-032 | **工具幂等键按「当前执行中的 Agent」分域**（第十七批补充） | 06 §工具幂等键的 Agent 维度、07 §工具幂等键的 Agent 维度 | MA-05（第十七批 C17-1） | AC-022 / MA-T10、MA-T10b～MA-T10e |
| FR-033 | **子 Agent 路径按固定 release 贯穿授权与准入**（第十八批补充） | 06 §固定 release 的贯穿口径、07 §子 Agent 路径的授权与准入口径 | MA-05（第十八批 C18-1） | AC-025 / MA-T08、MA-T08a～MA-T08b、MA-T15b |""",
    ),
    (
        """⚠️ **FR-032 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：""",
        """⚠️ **FR-033 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文只规定「子 Agent 固定 release」与「运行期子 Agent 发布新版本 →
当前 Run 继续绑定原 release」，从未规定**「这个固定版本号在授权与准入链路上
如何被携带」**（本批取「由 Phase A 一路下传到校验器与准入」的最小方案）。
状态仍为 `Draft` —— **不得**因本批已有实现与自动化证据就标记为 `Approved`：
⚠️ 其中「**Run 快照对子路径意味着什么**」虽已有可工作的口径，
但「子路径的端到端证据（跨委派执行器 → CAP 闸门 → 工具）」与
「**审批范围绑定 / 输出契约在子路径上的端到端证据**」仍缺，已登记为缺口。

⚠️ **FR-032 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：""",
    ),
])

print("ALL DONE")
