#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第十七批文档回写（幂等可重跑）。"""
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
        "## 实现状态快照（2026-10-25 · 代码落地第十六批：委派也必须计入 Run 级预算）",
        "## 实现状态快照（2026-10-25 · 代码落地第十七批：工具幂等键按「当前执行中的 Agent」分域）",
    ),
    # ② 全量验证口径
    (
        """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1228 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和，含 bootstrap 的 74 例 / 25 skipped）。
>   第十五批基线（`f362e3a`）同口径为 **1221 例** → 本批 +7 例，增量与新增用例数逐条对得上。""",
        """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1247 例 / 0 failures / 0 errors）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第十六批基线（`289bd4c`）同口径为 **1228 例** → 本批 **+19 例**
>   （`IdempotencyProtocolTest` +9 / `NestedToolIdempotencyTest` +7 / `CapExecutionFlowTest` +3），
>   增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例一律成对断言**（不同 Agent 必须产生不同键、同一 Agent 重复投递必须产生同一个键）：
>   只断言「键里含 agent 字样」在「所有调用都塞进同一个键」时同样会绿 —— 那不是证据。""",
    ),
    # ③ 「已实现」表新增三行
    (
        """| **第十六批反证（五处，逐一验证用例真的会红）** | ① 整体摘掉委派路径的预算判定与预留 → `DelegationBudgetChargeTest` 7 例**全红**；② 只摘 Run 级**金额**判定（只判方向不判量）→ **恰 1 条**红；③ 把「被拒 → 释放」改成「被拒 → 结算」→ **恰 1 条**红；④ 把「执行失败 → 结算」改成「执行失败 → 释放」→ **恰 1 条**红；⑤ 让 UNKNOWN 也释放额度 → **恰 1 条**红。**五处修复互相独立、可分别证伪** —— 这正是「每一处都有专属断言」的证据 | `scripts/c16-1-counterproof.sh`（含「恢复后复跑全绿」） |""",
        """| **第十六批反证（五处，逐一验证用例真的会红）** | ① 整体摘掉委派路径的预算判定与预留 → `DelegationBudgetChargeTest` 7 例**全红**；② 只摘 Run 级**金额**判定（只判方向不判量）→ **恰 1 条**红；③ 把「被拒 → 释放」改成「被拒 → 结算」→ **恰 1 条**红；④ 把「执行失败 → 结算」改成「执行失败 → 释放」→ **恰 1 条**红；⑤ 让 UNKNOWN 也释放额度 → **恰 1 条**红。**五处修复互相独立、可分别证伪** —— 这正是「每一处都有专属断言」的证据 | `scripts/c16-1-counterproof.sh`（含「恢复后复跑全绿」） |
| **工具幂等键按「当前执行中的 Agent」分域（第十七批 · C17-1）** | 工具幂等键的形状由 `run_id/tool_name/call_id` 改为 `run_id/[agent_ref/]tool_name/call_id`。⚠️ **agent_ref 是可省略段**：`agentRef == null`（单 Agent 路径）→ 键值与历史**逐字符一致**（在途 `tool_operation` 行不受影响；改形状会让重放变新调用 = 重复副作用）；非 null → `agent_ref = agentId@version`（版本参与：同一 `agent_id` 的不同 release 是不同的执行主体）。**格式定义只有一处**：`IdempotencyKey.agentRefOf`，执行作用域与委派上下文都从它渲染 | `IdempotencyProtocolTest` 9 例 + `NestedToolIdempotencyTest` 7 例 + `CapExecutionFlowTest` 3 例 |
| **两条执行链路共用同一份 Agent 维度（第十七批）** | 闸门按「执行作用域 → 委派上下文 → 无」解析 Agent 引用，**算一次**再透传给 CAP 执行流（既有链路与 CAP 链路同一口径）。⚠️ 只在一条链路上分域等于没修：迁移开关一关就退回「父子互相吞调用」 | `NestedToolIdempotencyTest`（既有链路）+ `CapExecutionFlowTest`（CAP 链路） |
| **第十七批反证（六处，逐一验证用例真的会红）** | ① 闸门不解析 Agent 引用 → **4 条**红；② 幂等键整体丢弃 Agent 维度 → **4 条**红；③ 只认执行作用域、丢掉委派上下文 → **恰 1 条**红；④ Agent 引用不带版本 → **3 条**红；⑤ CAP 链路自行派生（忽略闸门透传）→ **恰 1 条**红；⑥ 所有 Agent 共用同一个 Agent 段 → **3 条**红。**六处互相独立、可分别证伪** | `scripts/c17-1-counterproof.sh`（含「恢复后复跑全绿」） |""",
    ),
    # ④ 「未实现（下一批）」新增三条
    (
        """| **「跨轮次 tool call id 复用」未收敛（第十五批发现 · 未修）** |""",
        """| **CAP 链路的子 Agent 路径整体不可用（第十七批发现 · 未修）** | 步骤 13 执行准入拿「Run 快照里的**根 Agent** 闭包指纹」去比「Phase A 的**子 Agent** 闭包指纹」，而闭包指纹**含 `agent.id`** → 两者必然不等 → `EXEC_ADMISSION_STALE` → **子 Agent 的每一次工具调用都被拒**（fail-closed，故不是越权漏洞，而是多级路径在 CAP 开关打开后不可用）。⚠️ **本批刻意不修**：修它要动的是**准入语义**（「Run 快照对子路径意味着什么」），不是幂等键；且该链路当前**默认关闭**（`config.flag.cap-authorization-enforced` 默认 `false`），生产走的是既有链路。⚠️ 正因为如此，CAP 侧只断言**幂等键**，**不**把「子 Agent 必须调用成功」固化成预期行为 | `CapExecutionFlowTest#parentAndChildUseDifferentKeys`（断言键）；准入侧无对应用例（待下一批补） |
| **`record_weakness` 的键不含 Agent 维度（第十七批 · 刻意不动）** | 它记的是「某学生的某知识点」这条**业务事实**，全局唯一 —— 父 Agent 与子 Agent 记同一条，后写的那次被幂等拦下是**正确**的。⚠️ 代价：子 Agent 想**订正**同一知识点（参数相同、语义不同）也会被吞 —— 那是**既有**的幂等口径（ADR-003），非多级引入 → 只登记 | `IdempotencyProtocolTest#recordWeaknessIgnoresAgentDimension` |
| **同一 Agent 的「跨轮次 tool call id 复用」仍未收敛（第十七批 · 不可修）** | 同一 Agent 在**不同轮次**复用同一个 `toolCallId` → 键相同 → 第二次被当成幂等重放。把轮次纳入键会破坏「**重放算出同一 id**」的前提（重放必须算出同一个键，否则重放变成重复副作用）→ **不可修**，只登记。⚠️ 该问题在单 Agent 路径上同样存在，非多级引入 | `IdempotencyProtocolTest#sameAgentRepeatDeliveryStaysIdempotent`（钉住「同一 Agent 重复投递仍是同一个键」这一侧） |
| **「跨轮次 tool call id 复用」未收敛（第十五批发现 · 未修）** |""",
    ),
    # ⑤ 「历批修正的设计缺陷」新增两条
    (
        """> ⚠️ 平台默认值（`AgentOrchestration.DEFAULT_*`）是**占位**，不得当作生产默认值 ——
> 评审前置决策第 2 条未闭环前，上线前必须由容量评估覆盖。""",
        """| **工具幂等键不含 Agent 维度 → 父子/兄弟 Agent 互相吞调用（第十七批修 · C17-1）** | 键的形状是 `run_id/tool_name/call_id`，**不含「当前执行中的 Agent」**。而账本命中重复键时**复用既有 operation、不再次执行工具** —— 于是父 Agent 与子 Agent（或两个**兄弟**子 Agent）调用**同名工具 + 同名 model call id**（模型通常都叫 `call_1`）→ 算出同一个键 → 后者被当成前者的幂等重放 → **那次工具调用根本没发生**，且模型拿到的还是**另一个 Agent 的结果**。⚠️ 这与第十五批的委派侧撞号**完全孪生**（同源：键的维度不够 + 命中即复用），只是那一次发生在委派记录的 `invocationId` 上 | 键增 **可省略**的 Agent 段（`agentId@version`）；单 Agent 路径键值与历史逐字符一致（在途 `tool_operation` 行不受影响）；`IdempotencyKey.agentRefOf` 是格式的**唯一定义**；两条执行链路共用闸门算出的同一个值。`NestedToolIdempotencyTest` 7 例 + `IdempotencyProtocolTest` 9 例（反证六处：4/4/1/3/1/3 条红） |
| **CAP 链路的子 Agent 路径被准入检查整体拒绝（第十七批发现 · 未修）** | 见「未实现（下一批）」同名条 —— 已登记，**本批不修**（要动的是准入语义） | 暂无对应用例（下一批补） |

> ⚠️ 平台默认值（`AgentOrchestration.DEFAULT_*`）是**占位**，不得当作生产默认值 ——
> 评审前置决策第 2 条未闭环前，上线前必须由容量评估覆盖。""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 06_配置数据模型与生命周期设计.md
# ══════════════════════════════════════════════════════════════
patch("06_配置数据模型与生命周期设计.md", [
    (
        "### 发布闭包算法",
        """### 工具幂等键的 Agent 维度（第十七批补充）

> ⚠️ **本节记录的是「键的形状」，不是新增配置对象**。幂等键不落配置、
> 不进闭包指纹 —— 它是**运行期量**（每次调用现算）。

`tool_operation.idempotency_key` 的唯一约束是 `UNIQUE (tenant_id, idempotency_key)`
（**租户级**），而账本命中重复键时**复用既有 operation、不再次执行工具** ——
于是「键的维度够不够」直接等于「会不会吞掉一次调用」。

| 工具 | 键形状 | 说明 |
|---|---|---|
| `record_weakness` | `student_id/run_id/knowledge_point` | **不含** Agent 维度、**不含** callId：它记的是「某学生的某知识点」这条**业务事实**，全局唯一 —— 父子记同一条，后写的那次被幂等拦下是**正确**的。⚠️ 代价：子 Agent 想**订正**同一知识点也会被吞（既有 ADR-003 口径，非多级引入）→ 已登记为缺口 |
| 其他工具 | `run_id/[agent_ref/]tool_name/call_id` | `agent_ref` 是**可省略**段（第十七批）：`null` = 单 Agent 路径 → 键值与历史**逐字符一致**（在途 `tool_operation` 行不受影响）；非 null → `agent_ref = agentId@version` |

⚠️ **为什么 `agent_ref` 带版本**：同一个 `agent_id` 的不同 release 是**不同的执行主体**
（闭包、工具集都可能不同）。只写 `agent_id` 会让「同一 Agent 的新 release」
继承旧 release 的幂等记录。

⚠️ **为什么是「可省略」而不是「统一带」**：单 Agent 路径若也改键形状，
按旧键写入的在途 `tool_operation` 行就再也匹配不上 → 重放会被当成新调用 →
**重复副作用**（比吞调用更糟的失效方向）。

⚠️ **Agent 标识必须可唯一分解**：`agent_id` 含分隔符 `/` 时
`(a/b, c)` 与 `(a, b/c)` 会算出同一个键 —— 那正是本次要修的缺陷换个方向复发。
故 `ExecutionScope` / `DelegationContext` 在**构造期**就拒绝这类标识。

### 发布闭包算法""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 07_执行链集成设计.md
# ══════════════════════════════════════════════════════════════
patch("07_执行链集成设计.md", [
    (
        "### 上下文与安全",
        """### 工具幂等键的 Agent 维度（第十七批）

⚠️ **此前键里没有「谁在调用」**：形状是 `run_id/tool_name/call_id`。
而账本命中重复键时**复用既有 operation、不再次执行工具** —— 于是
**父 Agent 与子 Agent**（或两个**兄弟**子 Agent）调用
**同名工具 + 同名 model call id**（模型通常都叫 `call_1`）时算出同一个键 →
后者被当成前者的幂等重放 → **那次工具调用根本没发生**，
且模型拿到的还是**另一个 Agent 的结果**。

**执行链（第十七批）**：

```
工具调用（闸门）
├─ 解析「当前执行中的 Agent 引用」
│    执行作用域 → 委派上下文 → 无（单 Agent 路径）
│    引用格式 = agentId@version（唯一定义：IdempotencyKey.agentRefOf）
├─ 算幂等键
│    run_id / [agent_ref] / tool_name / call_id
│    ⚠️ agent_ref 可省略：单 Agent 路径的键与历史逐字符一致
└─ 两条链路共用同一个值
     既有链路（ToolInvocationGate.invokeAllLegacy）
     CAP 链路（CapExecutionFlow.invoke(..., scope, agentRef)）
```

⚠️ **为什么由闸门算一次再透传**：「当前 Agent 是谁」在两条链路上来源不同
（既有链路看委派上下文，CAP 链路看执行作用域），
各算各的必然漂移 —— 漂移出来的不是「两条路径各自的键」，
而是「同一次调用在两条链路上算出两个键」。

⚠️ **刻意不动 `record_weakness`**：它的键**不含** Agent 维度
（业务事实全局唯一，父子记同一条被幂等拦下是正确行为）。

### 上下文与安全""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 08_安全测试与验收矩阵.md
# ══════════════════════════════════════════════════════════════
patch("08_安全测试与验收矩阵.md", [
    (
        """⚠️ **反证**：`scripts/c16-1-counterproof.sh` 五处 ——
整体摘掉委派预算 → 7 条全红；单点摘掉（金额判定 / 被拒释放 / 失败结算 / UNKNOWN 释放）→ 各恰 1 条红。""",
        """⚠️ **反证**：`scripts/c16-1-counterproof.sh` 五处 ——
→ 整体摘掉委派预算 → 7 条全红；单点摘掉（金额判定 / 被拒释放 / 失败结算 / UNKNOWN 释放）→ 各恰 1 条红。

### 变更补充：工具幂等键分域的验收补充（第十七批）

⚠️ **本批验收项 MA-T10b～MA-T10e 均为「本批补充」**，补充的是
MA-T10（幂等键确定性派生）在**多级 Agent 路径**上的既有约束，不是新增约束。

| 用例 ID | 类别 | 场景 | 预期 | 证据 |
|---|---|---|---|---|
| MA-T10b | 幂等 | 父 Agent 与子 Agent 调用**同名工具 + 同名 model call id** | 产生**不同**的幂等键 —— 后者不得被当成前者的重放（那次调用根本没发生，且模型拿到的是另一个 Agent 的结果） | `NestedToolIdempotencyTest#parentAndChildProduceDifferentKeys` + `#siblingAgentsProduceDifferentKeys` |
| MA-T10c | 幂等 | **同一 Agent** 的同一次调用**重复投递** | 产生**同一个**键（幂等语义未被修复破坏 —— 键若每次都变，幂等就退化成「每次都执行一遍」） | `NestedToolIdempotencyTest#sameAgentRepeatDeliveryKeepsSameKey` + `IdempotencyProtocolTest#sameAgentRepeatDeliveryStaysIdempotent` |
| MA-T10d | 幂等 | 单 Agent 路径（无执行作用域、无委派上下文） | 键值与历史**逐字符一致** —— 在途 `tool_operation` 行按旧键写入，改形状会让重放变新调用（**重复副作用**） | `NestedToolIdempotencyTest#rootPathKeyShapeUnchanged` + `CapExecutionFlowTest#rootPathKeyShapeUnchanged` |
| MA-T10e | 幂等 | 同一 `agent_id` 的**不同 release** | 产生**不同**的键 —— 不同 release 是不同的执行主体（闭包/工具集可能不同），不区分版本会让新 release 继承旧 release 的幂等记录 | `NestedToolIdempotencyTest#differentReleasesProduceDifferentKeys` |

⚠️ **「不同 Agent 必须不同键」与「同一 Agent 必须同键」必须成对断言**：
只断言「键里含 agent 字样」在「所有调用都塞进同一个键」时同样会绿 —— 那不是证据。

⚠️ **反证**：`scripts/c17-1-counterproof.sh` 六处 ——
闸门不解析 Agent 引用 → 4 条红；幂等键整体丢弃 Agent 维度 → 4 条红；
只认执行作用域 → 恰 1 条红；不带版本 → 3 条红；
CAP 链路自行派生 → 恰 1 条红；所有 Agent 共用同一个 Agent 段 → 3 条红。

⚠️ **本批未覆盖的路径**：CAP 链路的**子 Agent 工具执行**本身
（步骤 13 执行准入会拒 —— 已登记为缺口，见 00「未实现」同名条）。
故 CAP 侧只断言**幂等键**，**不**把「子 Agent 必须调用成功」固化成预期行为。""",
    ),
])

# ══════════════════════════════════════════════════════════════
# 09_需求追踪矩阵.md
# ══════════════════════════════════════════════════════════════
patch("09_需求追踪矩阵.md", [
    (
        "| FR-031 | **委派也计入 Run 级预算**（第十六批补充） | 07 §委派的预算口径（MA-T17） | MA-05（第十六批 C16-1） | AC-024 / MA-T17、MA-T17a～MA-T17e |",
        """| FR-031 | **委派也计入 Run 级预算**（第十六批补充） | 07 §委派的预算口径（MA-T17） | MA-05（第十六批 C16-1） | AC-024 / MA-T17、MA-T17a～MA-T17e |
| FR-032 | **工具幂等键按「当前执行中的 Agent」分域**（第十七批补充） | 06 §工具幂等键的 Agent 维度、07 §工具幂等键的 Agent 维度 | MA-05（第十七批 C17-1） | AC-022 / MA-T10、MA-T10b～MA-T10e |""",
    ),
    (
        """⚠️ **FR-031 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：""",
        """⚠️ **FR-032 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文只规定「幂等键确定性派生」与「账本命中不得重复执行副作用」，
从未规定**「键是否按当前执行中的 Agent 分域」**
（本批取「键里加一段可省略的 `agent_ref = agentId@version`」的最小方案）。
状态仍为 `Draft` —— **不得**因本批已有实现与自动化证据就标记为 `Approved`：
⚠️ 其中「**`record_weakness` 这类业务事实型工具是否也要分域**」
与「**同一 Agent 跨轮次复用 call id 如何收敛**」都依赖**尚未冻结的幂等口径**，
已登记为缺口，待评审一并闭环。

⚠️ **FR-031 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：""",
    ),
])

print("ALL DONE")
