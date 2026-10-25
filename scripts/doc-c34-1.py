#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十四批 C34-1 文档回写：评审前置决策 4 的落地（`cap_agent_child_ref` **存储**）。

⚠️ 决策（2026-10-25 · zhouwei）：**存储** ——
   `cap_agent_child_ref` 关系投影**必落库**；投影端口未装配且本次发布
   **带了子引用** → **fail-closed 拒绝发布**（不再是静默跳过）。
   投影仍是**投影**（非权威来源：release 里的 `childAgentRefs` 才是），
   可重建、release 永不改写。

涉及五份文档：

① **06 配置数据模型**：在「关系数据」小节后补「关系投影的存储口径」——
   投影必落库、判据是「有没有边」而不是「仓储装没装配」、
   幂等 upsert、投影不是权威来源、查询口径（出向/入向/全量）。

② **07 执行链集成设计**：补「关系投影不参与运行期授权判定」——
   明确投影**只服务查询与分析**，委派准入仍以 release 的 `childAgentRefs` 为准，
   避免读者误以为「投影表空了 = 没人依赖我」可以放行。

③ **08 安全测试与验收矩阵**：新增 **MA-T26a～MA-T26f**。

④ **09 需求追踪矩阵**：新增 **FR-047** 行 + 说明段。

⑤ **00 修订说明**：
   · 「评审前置决策」第 4 条：**待评审 → 已决策 · 已落地**；
   · 「已决策 ≠ 已落地」提示改写（四条已闭环，仅第 5 条仍未闭环）；
   · 快照标题下登记本批性质；
   · 「历批修正的设计缺陷」表新增 1 行（本批反证三处）。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C34_1_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C34-1 -->"

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


# ── ① 06：关系投影的存储口径 ─────────────────────────────────────────────
ANCHOR_06 = """### 关系数据
新增建议关系表 `cap_agent_child_ref`：`tenant_id`、`parent_agent_id`、`parent_release_id`、`child_agent_id`、`child_release_id`、`ordinal`、`required`、`created_at`。唯一约束防止同一父 release 重复声明同一子 release；复合外键/发布校验保证租户一致和引用完整。该表为发布关系投影，权威定义仍是不可变 Agent release 中的 `childAgentRefs`。"""

SEC_06 = """

### 关系投影的存储口径：投影必落库（第三十四批补充 · 评审前置决策 4）

⚠️ **本节是「评审已决策」的口径，不是本批自定的设计**（决策人：zhouwei，2026-10-25）：
`cap_agent_child_ref` **存储** —— 父子关系投影**必落库**，
不再是「装配了就写、不装配就静默跳过」。

⚠️ **为什么「静默跳过」是缺陷而不是「可选增强」**：
投影表回答的是「**谁依赖我**」——
影响分析、删除守卫、委派拓扑图都靠它。
表是空的就等于「**没人依赖我**」，
于是「被引用对象被抽走」的事故一路畅通，
而运维看到的是「**发布成功**」。
静默跳过把「关系真相缺失」伪装成了「发布成功」，这是最坏的失效形态。

| 场景 | 决策 4 之前 | 决策 4 之后 |
|---|---|---|
| 投影端口**已装配** | 边落库 | 边落库（**不变**） |
| 投影端口**未装配** + 本次发布**带了子引用** | **静默跳过**（发布成功、关系一条没落） | **拒绝发布**（`ORCHESTRATION_INVALID` + 「父子关系投影未装配」） |
| 投影端口**未装配** + 本次发布**没有子引用** | 发布成功 | 发布成功（**不变** —— 不做过度拦截） |

⚠️ **判据是「本次发布有没有边」，不是「仓储装没装配」**：
纯 LEAF Agent 的发布与关系投影**无关**，
让投影能力成为整个平台级发布的**隐形前置依赖**是错的。
⚠️ **实测证据**：把判据改成「仓储没装配就全拒」，
在 `agent-application` 全模块会红 **9 条** —— 说明判据选错的波及面远不止漏判的那一条。

⚠️ **投影仍然是投影，不是第二份真相**：
- 权威来源始终是**不可变 Agent release** 里的 `childAgentRefs`；
- 投影表**可重建**（重放发布即重建），**release 永不改写**；
- 投影写入**幂等**（`ON CONFLICT DO NOTHING`）—— 重放发布是常态，
  同一父 release 重复投影同一子 release **不产生第二行**；
  真正的「重复声明」在**发布期**就被编排解析层拒绝。

⚠️ **查询口径（三条，均按 Agent 维度而非版本维度）**：
| 口径 | 方法 | 语义 |
|---|---|---|
| 出向 | `listByParentAgent` | 该父 Agent 的**全部**边（**不限父 release**）—— 拓扑图要呈现全部边，否则图会随版本选择而「缺边」 |
| 入向（**反查**） | `listByChildAgent` | 声明了该子 Agent 的**全部**父（**不限子版本**）—— 被停用的是那个 Agent，不是某一版 |
| 全量 | `listByTenant` | 本租户的全部边（拓扑视图一次取全量，避免 N+1 与并发下的不一致快照） |"""


def patch_06() -> None:
    if _done(F06):
        print("  跳过（已回写）：06_配置数据模型与生命周期设计.md")
        return
    s = F06.read_text()
    s = _append_after(s, ANCHOR_06, SEC_06, "06 关系投影存储口径")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F06.write_text(s)
    print("  已回写：06_配置数据模型与生命周期设计.md（关系投影存储口径）")


# ── ② 07：关系投影不参与运行期授权判定 ───────────────────────────────────
ANCHOR_07 = """并把「一次委派只跑一个子」钉成**可执行断言**（此前这个语义只存在于文档里）。"""

SEC_07 = """

### 关系投影不参与运行期授权判定（第三十四批补充 · 评审前置决策 4）

⚠️ **评审已决策**（决策人：zhouwei，2026-10-25，评审前置决策 4）：
`cap_agent_child_ref` **存储** —— 关系投影**必落库**；
投影端口未装配且本次发布**带了子引用** → **拒绝发布**（fail-closed）。

| 环节 | 决策 4 之前 | 决策 4 之后 |
|---|---|---|
| 发布期**写投影** | 端口未装配 → **静默跳过** | 端口未装配且**带了子引用** → **拒绝发布**（`ORCHESTRATION_INVALID`）；**没有子引用** → 照常发布 |
| 发布期**写投影**（端口已装配） | 幂等落库 | 幂等落库（**不变**） |
| 运行期**委派准入** | 以 release 的 `childAgentRefs` 为准 | 以 release 的 `childAgentRefs` 为准（**不变**） |
| 运行期**授权判定** | 与投影表无关 | 与投影表**仍然无关** |

⚠️ **必须说清的一件事：投影表**不是**运行期的判定依据**。
委派准入读的是**当前 Agent 的已发布 release** 里的 `childAgentRefs`，
不是投影表 —— 因为 release 才是权威来源，投影表是可重建的派生数据。
⚠️ **但投影表回答的是「谁依赖我」**（影响分析 / 删除守卫 / 拓扑图），
而运行期的准入判定回答的是「**此刻**这次委派合不合法」——
**两个问题不同源**，不得互相替代。
⚠️ **推论（也是本决策的真正价值）**：
投影表空 ≠ 「没人依赖我」——
决策 4 之后，「带了子引用却投影未落库」这件事**在发布期就暴露**，
不会拖到「有人抽走被引用对象」时才暴露。"""


def patch_07() -> None:
    if _done(F07):
        print("  跳过（已回写）：07_执行链集成设计.md")
        return
    s = F07.read_text()
    s = _append_after(s, ANCHOR_07, SEC_07, "07 关系投影与运行期判定")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F07.write_text(s)
    print("  已回写：07_执行链集成设计.md（关系投影与运行期判定）")


# ── ③ 08：MA-T26a～MA-T26f ──────────────────────────────────────────────
ANCHOR_08 = """| MA-T25h | **深度不级联** | 父声明深度 2、子声明深度 5 | **按当前 Agent 自己声明**判定（每一跳重新取当前 Agent 的限制），**不取父子较小值**、不逐层收敛 | `NestedDelegationGateTest#nestedDelegationRespectsCurrentAgentDepthLimit` + `#nestedDelegationUsesCurrentAgentDeclaration` |"""

SEC_08 = """

### 变更补充：关系投影必落库后的验收（第三十四批 · 决策 4 落地）

⚠️ **本批验收项对应的是「评审已决策并已落地」的口径**（决策人：zhouwei，2026-10-25）：
`cap_agent_child_ref` **存储** —— 投影**必落库**；
投影端口未装配且本次发布**带了子引用** → **拒绝发布**（fail-closed）。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T26a | **带子引用 + 投影未装配** | 发布一个声明了子 Agent 的 Agent，但投影端口未装配 | **拒绝发布**（`ORCHESTRATION_INVALID`，消息含「父子关系投影未装配」），且**不留 Agent 行**（拒绝后不得出现半发布） | `AgentChildRefProjectionTest#projectionNotWiredRejectsPublishWithChildren` |
| MA-T26b | **不该过度拦截** | 投影端口未装配，发布**纯 LEAF** Agent | **不拒绝** —— 投影能力缺失**不得**成为整个平台级发布的隐形前置依赖 | `AgentChildRefProjectionTest#projectionNotWiredStillAllowsLeafPublish` |
| MA-T26c | **投影保真** | 投影端口已装配，父声明两个子（第二个 `required=false`） | 边按 `ordinal` 落库（声明次序保真）、`required` 保真、两侧版本号保真 | `AgentChildRefProjectionTest#projectionWritesEdgesInDeclaredOrder` |
| MA-T26d | **投影幂等** | 同一父 release 重复投影两次 | **不产生第二条边** —— 重放发布是常态，重复边会让子调用次数预算被重复计入 | `AgentChildRefProjectionTest#repeatedProjectionIsIdempotent` |
| MA-T26e | **查询口径** | 分别按「出向 / 入向反查 / 全量」查询 | 三种口径各自正确；**入向不限子版本**（被停用的是 Agent 不是某一版）；**出向不限父 release**（拓扑图不得随版本选择而缺边） | `AgentChildRefProjectionTest#relationQueriesAnswerOutboundInboundAndWholeTenant` + `MultiAgentPersistenceIT` 的 `ChildRefProjection` M-7 / M-8 / M-9 / M-10（真实 PG17） |
| MA-T26f | **反证：结论真的会变** | ① 把 fail-closed 改回「静默跳过」；② 把判据改成「仓储装没装配」；③ 把出向查询改成按父 release 过滤 | ① 恰好红 **1** 条（fail-closed 那条）；② 恰好红 **1** 条（LEAF 不该被拒那条）；③ 恰好红 **1** 条（M-9）—— **三条反证互相独立、可分别证伪** | `scripts/c34-1-counterproof.sh`（含「恢复后复跑全绿」） |"""


def patch_08() -> None:
    if _done(F08):
        print("  跳过（已回写）：08_安全测试与验收矩阵.md")
        return
    s = F08.read_text()
    s = _append_after(s, ANCHOR_08, SEC_08, "08 MA-T26")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F08.write_text(s)
    print("  已回写：08_安全测试与验收矩阵.md（MA-T26a～MA-T26f）")


# ── ④ 09：FR-047 行 + 说明段 ─────────────────────────────────────────────
ANCHOR_FR046 = """| FR-046 | **取消平台默认值：非 LEAF 必须显式声明委派限制**（第三十三批补充 · 评审前置决策 2） | 06 §委派限制的声明口径（第三十三批）、07 §委派限制在运行期的取值（第三十三批）、08 §MA-T25a～MA-T25h | MA-05、MA-21（第三十三批 C33-1） | AC-035 / MA-T25a、MA-T25b、MA-T25c、MA-T25d、MA-T25e、MA-T25f、MA-T25g、MA-T25h |"""

NEW_FR047 = """| FR-047 | **父子关系投影必落库：投影未装配且带了子引用即拒绝发布**（第三十四批补充 · 评审前置决策 4） | 06 §关系投影的存储口径（第三十四批）、07 §关系投影不参与运行期授权判定（第三十四批）、08 §MA-T26a～MA-T26f | MA-05、MA-21（第三十四批 C34-1） | AC-036 / MA-T26a、MA-T26b、MA-T26c、MA-T26d、MA-T26e、MA-T26f |"""

ANCHOR_FR046_NOTE = """⚠️ **FR-046 只覆盖「Agent 编排的三个委派限制」** ——"""

NEW_FR047_NOTE = """⚠️ **FR-047 是「评审已决策并已落地」的配套条目**（决策人：zhouwei，2026-10-25）：
`cap_agent_child_ref` **存储** —— 关系投影**必落库**；
投影端口未装配且本次发布**带了子引用** → **拒绝发布**（fail-closed）。
⚠️ **判据是「有没有边」而不是「仓储装没装配」**：
纯 LEAF Agent 的发布与投影无关，不做过度拦截（MA-T26b）。
⚠️ **投影仍是投影**：权威来源是 release 里的 `childAgentRefs`，
投影表可重建、release 永不改写；运行期**委派准入与授权判定都不读投影表**。
⚠️ **FR-047 覆盖的是「发布期投影的落库语义」** ——
**委派拓扑的查询能力**（出向 / 入向反查 / 全量）已随本批提供 API 端点，
但**拓扑视图的前端页面**属管理台改造批次（第三十四批后半），不在本条验收范围内。

""" + ANCHOR_FR046_NOTE


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    s = F09.read_text()
    s = _append_after(s, ANCHOR_FR046, NEW_FR047, "09 FR-047 行")
    s = _sub_once(s, ANCHOR_FR046_NOTE, NEW_FR047_NOTE, "09 FR-047 说明段")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F09.write_text(s)
    print("  已回写：09_需求追踪矩阵.md（FR-047 行 + 说明段）")


# ── ⑤ 00：决策落地 + 提示改写 + 本批性质 + 历批表登记 ────────────────────
OLD_DEC_HEAD = """> ⚠️ **前三条已于 2026-10-25 逐条拍板**（决策人：zhouwei）：
> ① `SEQUENTIAL` **不纳入 P2**；② **取消平台默认值**，非 LEAF 必须显式声明；
> ③ 状态机 / 取消语义 / 重试 / UNKNOWN 条件**已冻结**（ADR-012）。
> ⚠️ **「已决策」≠「已落地」**：① 已于第三十二批落地、② 已于第三十三批落地、
> ③ 已于第三十一批冻结 —— 在各自落地之前**不得**按已落地口径引用。
> ④ ⑤ **仍未闭环**，不得自行推进。"""

NEW_DEC_HEAD = """> ⚠️ **前四条已于 2026-10-25 逐条拍板**（决策人：zhouwei）：
> ① `SEQUENTIAL` **不纳入 P2**；② **取消平台默认值**，非 LEAF 必须显式声明；
> ③ 状态机 / 取消语义 / 重试 / UNKNOWN 条件**已冻结**（ADR-012）；
> ④ `cap_agent_child_ref` **存储**（关系投影必落库）。
> ⚠️ **「已决策」≠「已落地」**：① 已于第三十二批落地、② 已于第三十三批落地、
> ③ 已于第三十一批冻结、④ 已于第三十四批落地 —— 在各自落地之前**不得**按已落地口径引用。
> ⑤ **仍未闭环**，不得自行推进。"""

OLD_DEC4 = """4. `cap_agent_child_ref` 为可选关系投影设计；需由 DDL/迁移评审决定是否存储，Agent release 内容仍为权威来源。"""

NEW_DEC4 = """4. ✅ **已决策（2026-10-25）· 已落地（第三十四批 · C34-1）**：`cap_agent_child_ref` **存储** —— 父子关系投影**必落库**；投影端口未装配且本次发布**带了子引用** → **拒绝发布**（fail-closed，不再静默跳过）。⚠️ **判据是「有没有边」而不是「仓储装没装配」** —— 纯 LEAF Agent 的发布与投影无关，不做过度拦截。⚠️ **投影仍是投影**：权威来源始终是 release 里的 `childAgentRefs`，投影表可重建、release 永不改写"""

OLD_SNAPSHOT_TITLE = """## 实现状态快照（2026-10-25 · 代码落地第二十九批：管理台的编排声明与契约登记表单）"""

NEW_SNAPSHOT_TITLE = """## 实现状态快照（2026-10-25 · 代码落地第三十四批：评审前置决策 4 —— 父子关系投影必落库）"""

OLD_C33_TAIL = """> ② **在途 Run 一次性失效** —— LEAF 编排的规范化 JSON 不再含这三个字段
> → 闭包指纹内容变化 → 绑定旧指纹的在途 Run 失效（与「编排声明入指纹导致在途 Run 一次性失效」同类，属**已知预期行为**）。"""

NEW_C33_TAIL = OLD_C33_TAIL + """

> ⚠️ **第三十四批（C34-1）是评审前置决策 4 的落地批次**：`cap_agent_child_ref` **存储**。
> 父子关系投影由「**装配了就写、不装配就静默跳过**」改为「**投影必落库**」——
> 投影端口未装配且本次发布**带了子引用** → **拒绝发布**（fail-closed）。
> ⚠️ **这是本批唯一的发布期行为变更**（其余是查询能力与 API 端点），
> 其后果有两处**必须登记**：
> ① **未装配投影端口的环境将无法发布带子引用的 Agent** ——
>    这是决策的直接后果，不是缺陷，但运维必须知道（投影端口在生产拓扑里**已装配**）；
> ② **判据不得改成「仓储装没装配」** ——
>    那样会让投影能力成为整个平台级发布的**隐形前置依赖**
>    （实测：会波及 `agent-application` 全模块 9 条用例）。
> ⚠️ **投影仍是投影**：权威来源是 release 里的 `childAgentRefs`；
> 投影表可重建、release 永不改写；运行期**委派准入与授权判定都不读投影表**。"""

OLD_TABLE_HEADER = """| 缺陷 | 现象 | 修正 |"""

NEW_ROW_34 = """| ✅ **父子关系投影未装配时静默跳过（第三十四批已修 · C34-1 · 评审前置决策 4）** | 发布一个声明了子 Agent 的 Agent，而关系投影端口未装配 → `projectChildRefs` 直接 `return`，**发布成功、关系边一条没落**。后果不是「少一张表的数据」，而是**关系真相缺失**：影响分析与删除守卫问的是「谁依赖我」，表是空的就等于「没人依赖我」，于是「被引用对象被抽走」的事故一路畅通，而运维看到的是「发布成功」 | **投影必落库**（fail-closed）：先算出本次发布的**全部**边，**无边则放行**；**有边而端口未装配 → 拒绝发布**（`ORCHESTRATION_INVALID` + 「父子关系投影未装配」），拒绝后**不留 Agent 行**。⚠️ **判据是「有没有边」而不是「仓储装没装配」** —— 纯 LEAF Agent 的发布与投影无关，不做过度拦截（成对断言：MA-T26a 该拒 / MA-T26b 不该拒）。⚠️ **投影仍是投影**：权威来源是 release 里的 `childAgentRefs`，可重建、release 永不改写；运行期准入与授权判定**不读投影表**。⚠️ **配套**：端口增补出向 / 入向反查 / 全量三种查询口径（**均按 Agent 维度而非版本维度**），并提供委派拓扑 API 端点。**证据**：`AgentChildRefProjectionTest` 5 例 + `MultiAgentPersistenceIT` 的 `ChildRefProjection` M-7～M-10（真实 PG17）+ `scripts/c34-1-counterproof.sh`（三处反证，恰好各红 1 条） |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_DEC_HEAD, NEW_DEC_HEAD, "00 决策提示段")
    s = _sub_once(s, OLD_DEC4, NEW_DEC4, "00 决策第 4 条")
    s = _sub_once(s, OLD_SNAPSHOT_TITLE, NEW_SNAPSHOT_TITLE, "00 快照标题")
    s = _sub_once(s, OLD_C33_TAIL, NEW_C33_TAIL, "00 第三十三批尾注")
    s = _append_row_to_table(s, OLD_TABLE_HEADER, NEW_ROW_34, "00 历批修正表")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md（决策 4 落地 + 历批表登记）")


def main() -> None:
    print("C34-1 文档回写：评审前置决策 4（cap_agent_child_ref 存储）")
    patch_00()
    patch_06()
    patch_07()
    patch_08()
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
