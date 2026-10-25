#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十五批 C25-1 文档回写：把「输出契约 / 路由策略的独立生命周期迁移端点」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ **本批顺带修正一处文档口径错误**：00 文档此前写「契约 / 策略的『草稿 → 发布』
   只能通过『随 Agent 一起发布』这一条路径完成」—— 实测**不带 Agent 单独发布
   契约 / 策略是可行的**（本批 IT 已用真实 PG 钉住）。本批把该表述改正，
   并把**真正的**缺口（没有独立的**生命周期迁移**通道 / 没有草稿态）说清楚。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C25-1 -->"

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

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十四批：编排绑定承载被引用行的内容指纹）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十五批：输出契约/路由策略的独立生命周期迁移端点）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1332 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十三批基线（`c82bc36`）同口径为 **1322 例** → 本批 **+10 例**
>   （`ReferencedContentFingerprintTest` 10 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「第二道防线挡得住」与「**不该失效的别失效**」互相独立）：
>   拆掉内容指纹承载只让前者红，把内容指纹段改成无条件写入只让后者红 ——
>   否则「全都失效」也能让用例绿。
>   ⚠️ **DB 层证据**：`agent-infrastructure` 全部 IT **376 例 / 0 失败**
>   （本机真实 PG17 + RabbitMQ）。
>   ⚠️ **本批的反证覆盖六处**（见「已实现」表的「第二十四批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`c82bc36` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1332 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1345 例 / 0 failures / 0 errors / 25 skipped）**；
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

BLOCK_00_IMPL = """| **输出契约/路由策略的独立生命周期迁移端点（第二十五批 · C25-1 · FR-040）** | 修复前 `cap_result_contract`（V20）与 `cap_routing_policy`（V24）只继承了 V15 时代的「布尔式」发布标记（`published_at` 非空 = 已发布），**没有** V17 引入的 `cap_lifecycle_state` 列 —— 这两类对象**只能表达「已发布 / 未发布」两态**。后果有三：① 表达不出「已停用」（`DEPRECATED`）—— 06 §3.2 要求「被引用对象禁止物理删除 → 改 DEPRECATED」，而它们连一个「停用」的落点都没有；② 治理通道的生命周期迁移端点只接受 `type=agent`，契约 / 策略**连「申请停用」这一步都提交不了**；③ ⚠️ **V20 / V24 建表时漏挂了不可变触发器**（V15 只给 tool / skill / mcp_server / agent 四张表挂了）—— §6.1「已发布版本行不可变」对这两张表**从未生效**。现在：① **V26 迁移**给两张表增补 `lifecycle_state`（默认 `DRAFT`），回填「`published_at` 非空 → `PUBLISHED`」（回填期间临时 `NO FORCE RLS` —— 这两张表都是 FORCE RLS 的租户表，不解除 FORCE 会**静默影响 0 行**，表现为「迁移跑成功了但状态全是 DRAFT」这类最难查的假成功），并接入 V17 的 `align_cap_lifecycle_state()` 与 `forbid_published_def_mutation()`（**补上不可变触发器**）；② 治理通道的生命周期迁移类型闸门放开到 `{agent, result_contract, routing_policy}`（全是租户级对象），**平台级对象仍然拒绝**；③ **发布期引用防线**：已停用的契约 / 策略**不得被新发布引用**（新增两个稳定原因码 `RESULT_CONTRACT_DEPRECATED` / `ROUTING_POLICY_DEPRECATED`，与「未发布」分列 —— 两者排查方向相反：查发布流程 vs 查停用记录）。⚠️ **三处刻意的取舍**：① **停用不能靠 `published_at` 表达** —— 它是不可变触发器的判定依据，把它清空等于把已发布行**降级回草稿**（内容重新可改、「已发布」这个事实凭空消失），故停用必须是独立于 `published_at` 的第三个维度；② **停用只挡「新引用」，不追溯存量**（06 §3.2「存量 Run 按版本过渡」）；③ **读不到生命周期状态时不得降级成「没有停用」** —— 那会让防线「看起来在、其实不在」（`JdbcCapResultContractRepository` 因此**不吞**列缺失的异常，让它显式炸出来）。⚠️ **顺带修正一处文档口径错误**：00 文档此前写「契约 / 策略的『草稿 → 发布』只能通过『随 Agent 一起发布』这一条路径完成」—— 实测**不带 Agent 单独发布契约 / 策略是可行的**（本批 IT 已用真实 PG 钉住）。⚠️ **剩余缺口**：① 契约 / 策略**没有草稿登记通道**（`DRAFT` 态在数据层可表达，但无写入路径），故 `DRAFT → VALIDATED` 这条边当前不可达；② 运行期**不按 `DEPRECATED` 拦截**（存量按固定版本过渡，与既有 agent 口径一致）；③ Agent 自身的 `DEPRECATED` 仍**没有**发布期引用防线（既有缺口，不在本批范围） | `ContractPolicyLifecycleTest` 11 例 + `ContractPolicyLifecycleIT` 11 例（真实 PG17，含 RLS 与 V26 结构断言） |"""

BLOCK_00_CP = """| **第二十五批反证（七处，逐一验证用例真的会红）** | ① 发布期不再拒绝引用「已停用的输出契约」（停用不是防线）→ **恰 1 条红**；② 发布期不再拒绝引用「已停用的路由策略」→ **恰 1 条红**；③ 生命周期迁移端点退回「只接受 agent」→ **5 条红**；④ 持久层不认识 `result_contract` 这个对象类型（独立停用在数据层无法执行）→ **5 条红**；⑤ DB 层不挂不可变触发器（§6.1 对契约从未生效）→ **2 条红**；⑥ DB 层没有 `lifecycle_state` 列（停用无处落地）→ **恰 1 条红**（且**应用层用例保持绿**，证明反证范围准确 —— 它不依赖 DB）；⑦ 仓储读不到 `lifecycle_state`（已停用这个事实读不出来）→ **恰 1 条红**。**七处互相独立、可分别证伪**；脚本带「结论必须变化」的自动判定 —— 改动后结论不变即判为**无效反证**并以非零码退出（编译失败同样判无效：「编译不过」不是「用例红」） | `scripts/c25-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

OLD_00_CONTRACT_ROW = """| 输出契约的登记流程 | ✅ **第十一批已接入**：`cap_result_contract` 随 `ConfigPublishCommand.resultContracts` 在**同一发布事务**内登记（与 Agent 同批次、同发布头、同 CAS、同审计），内容指纹由服务端计算，发布期校验「引用的契约必须已发布」。**剩余缺口**：契约行本身**没有独立的生命周期迁移接口**（草稿 → 发布只能通过「随 Agent 一起发布」这一条路径完成），且 `cap_result_contract` 的 **DB 层 IT 待补**（需外部 PG） |"""

NEW_00_CONTRACT_ROW = """| 输出契约的登记流程 | ✅ **第十一批已接入**：`cap_result_contract` 随 `ConfigPublishCommand.resultContracts` 在**同一发布事务**内登记（与 Agent 同批次、同发布头、同 CAS、同审计），内容指纹由服务端计算，发布期校验「引用的契约必须已发布」。✅ **第二十五批消解两项**：① 契约行**已有独立的生命周期迁移接口**（`type=result_contract`，走「申请 → 审批 → 执行」三步），且 `cap_result_contract` 的 **DB 层 IT 已补齐**（`ContractPolicyLifecycleIT` 11 例 + `MultiAgentPersistenceIT` 的 `ResultContractRows` 3 例，本机真实 PG17）；② ⚠️ **口径更正**：此前记的「草稿 → 发布只能通过『随 Agent 一起发布』完成」**与实现不符** —— **不带 Agent 单独登记并发布一份契约是可行的**（本批 IT 已钉住：独立登记 → 独立发布 → 契约进发布头清单）。**真正的剩余缺口**是：契约**没有草稿态的写入通道**（`DRAFT` 在数据层可表达，但无写入路径），故 `DRAFT → VALIDATED` 这条边当前不可达 |"""

OLD_00_POLICY_ROW = """| **路由策略没有独立的生命周期迁移接口** | 与输出契约同款缺口：策略行的「草稿 → 发布」只能通过「随 Agent 一起发布」这一条路径完成（`ConfigPublishCommand.routingPolicies`），**没有**独立的登记 / 生命周期迁移端点。⚠️ 后果：想单独改一份策略，必须**连着一个 Agent 一起发布**（哪怕 Agent 本身没变） | `ConfigPublishWorkflowService.requestPublish`（唯一登记入口） |"""

NEW_00_POLICY_ROW = """| ✅ **路由策略没有独立的生命周期迁移接口（第二十五批已修 · C25-1）** | **已落地**：① **V26 迁移**给 `cap_routing_policy` 增补 `lifecycle_state`（默认 `DRAFT`，回填「`published_at` 非空 → `PUBLISHED`」），并接入 V17 的对齐触发器与**不可变触发器**（此前 V24 建表时漏挂，§6.1「已发布版本行不可变」对策略**从未生效**）；② 治理通道的生命周期迁移端点放开到 `type=routing_policy`（走「申请 → 审批 → 执行」三步，CAS 前置条件与 `agent` 同口径）；③ **发布期引用防线**：已停用的策略**不得被新发布引用**（`ROUTING_POLICY_DEPRECATED`，与「未发布」分列）。⚠️ **口径更正**：此前记的「策略的『草稿 → 发布』只能通过『随 Agent 一起发布』完成」**与实现不符** —— **不带 Agent 单独登记并发布一份策略是可行的**。⚠️ **剩余缺口**：① 策略**没有草稿态的写入通道**（`DRAFT → VALIDATED` 当前不可达）；② 运行期不按 `DEPRECATED` 拦截（存量按固定版本过渡）；③ Agent 自身的 `DEPRECATED` 仍无发布期引用防线（既有缺口） | `ContractPolicyLifecycleTest` 11 例 + `ContractPolicyLifecycleIT` 11 例 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_CONTRACT_ROW, NEW_00_CONTRACT_ROW, "00")
    s = _sub_once(s, OLD_00_POLICY_ROW, NEW_00_POLICY_ROW, "00")
    lines = s.split("\n")

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("| **第二十四批反证（六处，逐一验证用例真的会红）**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十四批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_00_IMPL, BLOCK_00_CP]

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 06 ──────────────────────────────────────────────────────────────

BLOCK_06 = """
### 输出契约 / 路由策略的生命周期（第二十五批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06 §3 的状态机原文只覆盖
「Tool / Skill / MCP Server / Agent」四类对象，
从未规定「输出契约行 / 路由策略行」是否也有生命周期。

| 项 | 口径 |
|---|---|
| 为何这两张表此前没有生命周期 | `cap_result_contract`（V20）与 `cap_routing_policy`（V24）建表时只继承了 V15 时代的 `published_at`（布尔式），**没有** V17 引入的 `cap_lifecycle_state` 列 → 只能表达「已发布 / 未发布」两态 |
| 为何不能靠 `published_at` 表达停用 | `published_at` 是**不可变触发器的判定依据**（非空 = 已发布 = 内容冻结）。要「停用」就得把它清空 —— 那等于把已发布行**降级回草稿**：内容重新可改、「已发布」这个事实凭空消失。故停用必须是**独立于 `published_at` 的第三个维度** |
| 数据层落点 | V26 迁移：`lifecycle_state cap_lifecycle_state NOT NULL DEFAULT 'DRAFT'`；回填「`published_at` 非空 → `PUBLISHED`」；接入 `align_cap_lifecycle_state()`（绑定两者）与 `forbid_published_def_mutation()`（**内容冻结**，并放行 `PUBLISHED → DEPRECATED` 的**纯元数据**迁移） |
| 治理通道 | 生命周期迁移端点（申请 → 审批 → 执行，三步）的类型闸门放开到 `{agent, result_contract, routing_policy}` —— 全是**租户级**对象；平台级对象（tool / skill / mcp_server）**仍然拒绝**（其发布走平台运维通道，不经租户身份暴露） |
| 停用的效力 | **只挡「新引用」**：新发布不得引用已停用的契约 / 策略（`RESULT_CONTRACT_DEPRECATED` / `ROUTING_POLICY_DEPRECATED`）；**存量引用按固定版本继续过渡**（06 §3.2） |
| 为何「未发布」与「已停用」必须分列原因码 | 两者都表现为「引用不可用」，但排查方向**相反**：前者查发布流程（还没生效），后者查停用记录（曾经生效、现被显式停用）。混用会让运维去查「为什么没发布」，而真因是「上周有人把它停了」 |
| 顺带补上的一处更硬的缺口 | V20 / V24 建表时**漏挂了不可变触发器** —— §6.1「已发布版本行不可变」对这两张表**从未生效**（运维直接 UPDATE 可改写已发布契约）。V26 补上 |

⚠️ **回填期间必须临时 `NO FORCE ROW LEVEL SECURITY`**：
这两张表都是 FORCE RLS 的租户表，迁移角色若是 owner 也**同样受策略约束**；
未解除 FORCE 时 UPDATE 影响 0 行 → 回填**静默不生效**，
表现为「迁移跑成功了，但状态全是 DRAFT」—— 最难查的一类假成功。
异常路径必须把 FORCE 状态复原（否则留下「租户表不再受 RLS 保护」的残局）。

⚠️ **读不到生命周期状态时不得降级成「没有停用」**：
仓储读不出 `lifecycle_state` 时**不吞异常**，让数据层失败显式炸出来 ——
否则发布期引用校验读不到「已停用」这个事实，停用防线会**看起来在、其实不在**。
"""

# ── 07 ──────────────────────────────────────────────────────────────

BLOCK_07 = """
### 生命周期迁移端点与委派链路的关系（第二十五批补充）

⚠️ **本节为本批补充** —— 07 原文从未规定「治理动作与委派链路如何分工」。

```
治理通道（三步：申请 → 审批 → 执行）
    │  LIFECYCLE_TRANSITION
    │    ├─ type=agent           → 停用 / 回退该 Agent 版本行
    │    ├─ type=result_contract → **停用输出契约（第二十五批新增）**
    │    └─ type=routing_policy  → **停用路由策略（第二十五批新增）**
    ▼
CAS 迁移（期望原状态作为前置条件）
    └─ PUBLISHED → DEPRECATED = 纯元数据迁移（内容一字未改）
       ⚠️ DEPRECATED 是**终态**：不可迁出（停用必须是单向的）
    ▼
发布期引用防线（AgentPublishClosureValidator）
    ├─ 引用未发布 → RESULT_CONTRACT_NOT_PUBLISHED / ROUTING_POLICY_NOT_PUBLISHED
    └─ 引用已停用 → RESULT_CONTRACT_DEPRECATED / ROUTING_POLICY_DEPRECATED
       ⚠️ 这一道防线**只在发布期**生效
    ▼
运行期（委派链路）
    └─ **不按 DEPRECATED 拦截**：按固定版本引用取用，存量 Run 继续过渡
       （06 §3.2「存量 Run 按版本过渡」；与既有 agent 口径一致）
```

⚠️ **为什么发布期拦截、运行期不拦截**：
停用的是「**新发布不得再引用它**」这一条治理事实，
而不是「这个对象从此刻起消失」——
后者会让所有已发布并引用它的 Agent 在下一次委派时集体 fail-closed，
那既不是 06 §3.2 说的「存量按版本过渡」，也远超「停用」本来的意图。

⚠️ **停用是单向的**：`DEPRECATED` 是终态。
若允许迁回 `PUBLISHED`，「停用」就只是个可撤销的告示牌 ——
任何人停用完就能立刻恢复，而中间那一段时间里新发布被挡住的事实没人能复核。
"""

# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：输出契约 / 路由策略生命周期的验收补充（第二十五批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「输出契约行 / 路由策略行也有生命周期」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T16p | 独立迁移 | 输出契约 `PUBLISHED → DEPRECATED` | 迁移**成功**，且内容一字未改（指纹不变） | `ContractPolicyLifecycleTest#resultContractCanBeDeprecatedIndependently` / `#deprecationDoesNotChangeContent`（反证 C：5 条红） |
| MA-T15j | 独立迁移 | 路由策略 `PUBLISHED → DEPRECATED` | 迁移**成功**，且内容一字未改 | `ContractPolicyLifecycleTest#routingPolicyCanBeDeprecatedIndependently`（反证 C：5 条红） |
| MA-T16q | 闸门不得放宽 | 平台级对象类型（tool / skill / mcp_server）经租户通道做生命周期迁移 | **显式拒绝**，且**不留申请单** | `ContractPolicyLifecycleTest#platformObjectTypesAreStillRejected`（反证 C：5 条红中该条仍绿） |
| MA-T16r | 并发 | 期望原状态与实际不符 | **冲突**（不静默覆盖），状态不变 | `ContractPolicyLifecycleTest#staleExpectedStateIsRejected`（反证 C：5 条红） |
| MA-T16s | 单向性 | `DEPRECATED → PUBLISHED` | **拒绝**（停用是终态） | `ContractPolicyLifecycleTest#deprecationIsTerminal`（反证 C：5 条红） |
| MA-T16t | 防线 | 新发布引用**已停用**的契约 | **拒绝**，原因码 `RESULT_CONTRACT_DEPRECATED` | `ContractPolicyLifecycleTest#deprecatedResultContractCannotBeReferenced`（反证 A：恰 1 条红） |
| MA-T15k | 防线 | 新发布引用**已停用**的策略 | **拒绝**，原因码 `ROUTING_POLICY_DEPRECATED` | `ContractPolicyLifecycleTest#deprecatedRoutingPolicyCannotBeReferenced`（反证 B：恰 1 条红） |
| MA-T16u | 不误伤 | 引用**未停用**的契约 / 策略 | **通过**（成对断言：防线不得变成「全都拒」） | `ContractPolicyLifecycleTest#activeContractAndPolicyRemainReferenceable`（反证 A/B：该条仍绿） |
| MA-T16v | 语义区分 | 「未记录生命周期状态」≠「已停用」 | 存量行口径不变，不被误伤 | `ContractPolicyLifecycleTest#missingLifecycleStateIsNotDeprecation` |
| MA-T16w | 原因码 | 「未发布」与「已停用」 | **两个不同的原因码**（排查方向相反） | `ContractPolicyLifecycleTest#unpublishedAndDeprecatedCarryDistinctReasonCodes` |
| MA-T16x | DB 结构 | V26 迁移 | 两张表都有 `lifecycle_state` 列（默认 `DRAFT`）+ 对齐触发器 + **不可变触发器** | `ContractPolicyLifecycleIT#bothTablesHaveLifecycleStateColumn` / `#bothTablesHaveAlignmentAndImmutabilityTriggers`（反证 E：2 条红、反证 F：恰 1 条红） |
| MA-T16y | 数据层防线 | 已发布契约被**就地改写**（运维直接 UPDATE） | **被拒**（§6.1 对契约首次真正生效） | `ContractPolicyLifecycleIT#deprecatedContractIsImmutableAndTerminal`（反证 E：2 条红） |
| MA-T16z | 租户隔离 | 跨租户停用 | **改不到别人的行**（RLS） | `ContractPolicyLifecycleIT#crossTenantDeprecationDoesNotLeak`（反证 D：5 条红） |
| MA-T15l | 独立登记 | **不带 Agent** 单独登记并发布一份契约 | 契约落库为已发布，且**进发布头清单** | `ContractPolicyLifecycleIT#contractCanBeRegisteredAndPublishedWithoutAnyAgent` |
| MA-T16aa | 数据来源 | 仓储读得到 `lifecycle_state` | 读回 `DEPRECATED`（防线的事实来源不得读空） | `ContractPolicyLifecycleIT#repositoryCarriesLifecycleState`（反证 G：恰 1 条红） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-040 | **输出契约 / 路由策略的独立生命周期迁移端点**（第二十五批补充） | 06 §输出契约/路由策略的生命周期、07 §生命周期迁移端点与委派链路的关系 | MA-05、MA-15、MA-16（第二十五批 C25-1） | AC-030 / MA-T16p、MA-T16q、MA-T16r、MA-T16s、MA-T16t、MA-T16u、MA-T16v、MA-T16w、MA-T16x、MA-T16y、MA-T16z、MA-T16aa、MA-T15j、MA-T15k、MA-T15l |"

BLOCK_09_2 = """⚠️ **FR-040 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定「输出契约行 / 路由策略行也有生命周期」，也从未规定
「停用只挡新引用、不追溯存量」这条边界（本批取最小方案）。
⚠️ 补充的是**既有约束**（MA-T15 的「编排声明固化进发布闭包」、
MA-T16 的「子输出按声明 Schema 校验」）在**被引用对象可被停用**后的落地 ——
此前那条约束只覆盖「已发布」，没有覆盖「已停用」。
⚠️ **本批顺带修正一处文档口径错误**：此前 00 文档记的
「契约 / 策略的『草稿 → 发布』只能通过『随 Agent 一起发布』完成」**与实现不符**
—— 不带 Agent 单独登记并发布是可行的（`ContractPolicyLifecycleIT` 已用真实 PG 钉住）。
⚠️ **剩余缺口**：① 契约 / 策略**没有草稿态的写入通道**（`DRAFT → VALIDATED` 不可达）；
② 运行期不按 `DEPRECATED` 拦截（存量按固定版本过渡）；③ Agent 自身的 `DEPRECATED`
仍无发布期引用防线。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-039 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-039 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-039 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-039 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C25-1 文档回写：")
    patch_00()
    append(F06, BLOCK_06)
    append(F07, BLOCK_07)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
