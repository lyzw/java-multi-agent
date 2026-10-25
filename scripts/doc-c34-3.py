#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十四批 C34-3 文档回写：管理台改造的**前端**部分
（Agent / Skill / Tool / MCP 四类对象的详情页 CRUD + 关系维护 + 委派拓扑视图）。

⚠️ 本批的用户诉求（2026-10-25 · zhouwei）：
   「对 admin-console 相关的进行优化，包括 Agent / Skill / Tool / Mcp 中的详细数据的
   增删改查动作，以及相关关系的维护功能」——**本轮全量交付**。
⚠️ 后端部分见 `scripts/doc-c34-2.py`（本脚本只补**前端**那半）。

涉及六份文档：

① **00 修订说明**：
   · 「已实现」表新增 2 行（前端改造 + 反证）；
   · 「历批修正的设计缺陷」表新增 1 行（**回填不全 ⇒ 静默清空关系**）；
   · 快照标题与本批性质段落。

② **06 配置数据模型**：新增「管理台前端对平台级对象的读写面」——
   登记与修改共用同一张草稿表单；「删除 = 停用」的前端口径；
   **凭据引用不得被静默丢弃**（闭包补齐的 fail-closed）；
   **传递闭包**（MCP 型 Tool 绑定的 Server 也要进发布集合）。

③ **07 执行链集成设计**：新增「前端不参与运行期判定」——
   前端只做**形状与一致性**检查，服务端门禁才是权威。

④ **08 安全测试与验收矩阵**：新增 **MA-T28a～MA-T28f**。

⑤ **09 需求追踪矩阵**：新增 **FR-049** 行 + 说明段。

⑥ **重规划 html**：§3.5 补「3.5.2 前端落地状态」。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C34_3_BASE", str(DEFAULT_BASE)))
HTML_BASE = pathlib.Path(
    os.environ.get(
        "DOC_C34_3_HTML_BASE",
        "/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/管理后台开发计划",
    )
)

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"
FHTML = HTML_BASE / "admin-console-多级Agent重规划.html"

MARK = "<!-- C34-3 -->"
MARK_HTML = "<!-- C34-3 前端 -->"

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


def _write(path: pathlib.Path, text: str) -> None:
    """写回文件，并**追加幂等标记**。

    ⚠️ 标记必须随内容一起落盘 —— 否则脚本在中途抛错后重跑，
    已经写入的那部分会被重复追加（表格行重复 = 文档结构破损）。
    """
    if MARK not in text:
        text = text.rstrip("\n") + "\n\n" + MARK + "\n"
    path.write_text(text)


# ── ① 00：快照标题 + 本批性质段落 ───────────────────────────────────────
def patch_00_head() -> None:
    if _done(F00):
        print(f"  跳过（已回写）：{F00.name}")
        return
    text = F00.read_text()
    text = _sub_once(
        text,
        "## 实现状态快照（2026-10-25 · 代码落地第三十四批：决策 4 落地 + 管理台改造）",
        "## 实现状态快照（2026-10-25 · 代码落地第三十四批：决策 4 落地 + 管理台改造【前后端全量交付】）",
        "00 快照标题",
    )
    chunk = """
> ⚠️ **第三十四批（C34-3）是管理台改造的**前端**批次**：把后端已提供的
> 平台级写通道与关系查询端点**接到管理台上** —— Tool / Skill / MCP 三张
> **详情页**（概览 / 关系 / 三步工作流）+ 登记与修改共用同一张草稿表单
> + **委派拓扑**只读视图；并修复 Agent 详情页「**回填不全 ⇒ 静默清空关系**」
> 这一既有缺陷。
> ⚠️ **本批不改变运行期的任何判定口径**（授权 / 闭包 / 委派准入均不变）。
"""
    text = _append_after(text, "## 评审前置决策", chunk, "00 快照段落")
    _write(F00, text)
    print(f"  ✅ {F00.name}：快照标题 + 本批性质段落")


# ── ② 00：已实现表新增 2 行 ──────────────────────────────────────────────
ROW_FE = (
    "| **管理台前端：Tool / Skill / MCP 详情页与关系维护 + 委派拓扑"
    "（第三十四批 · C34-3 · FR-049）** | "
    "修复前，管理台对平台级对象（Tool / Skill / MCP Server）**只有目录、没有详情**："
    "「详细数据的增删改查」里除了「查」，其余三项**根本没有入口** —— "
    "改一个 Tool 的 MCP 绑定、改一个 Skill 的声明清单、停用一个 MCP Server，"
    "全都只能手写 SQL 或走带外运维通道。而关系视图同样不存在："
    "「这个 Skill 声明的 Tool 里有哪些**还不存在**」「这个 MCP Server 允许集与"
    "实际登记差多少」都得靠人对着三张表比对。"
    "现在：① **三张详情页**（ToolDetailPage / SkillDetailPage / McpDetailPage）"
    "共用 `PlatformDetailShell`（概览 / 关系 / 操作三段）与 `PlatformPublishPanel`"
    "（申请 → 审批 → 执行三步）—— 骨架共用是刻意的：三页的生命周期语义完全一致"
    "（已发布行不可变 ⇒ 只能发新版本；删除 = 停用），骨架一旦分叉语义就会悄悄漂移；"
    "② **登记与修改共用同一张草稿表单**（`new` 是保留路由段 —— 分两条路由就有两份表单，"
    "迟早漂移）；③ **关系视图**：Skill 的声明 Tool / MCP、Tool 的所属 MCP、"
    "MCP 的允许集与实际登记**分开呈现**，「声明了但对象不存在」显式列为 missing；"
    "④ **委派拓扑**只读视图（`GET /api/cap/delegations` 三端点）；"
    "⑤ Agent 详情的**关系字段完整回填** + 取不到权威定义时**绝不静默清空**。"
    "⚠️ **「删除」语义仍是停用**（物理删除不做，已发布行不可变）。"
    "⚠️ **剩余缺口**：① **闭包补齐遇带凭据引用的 MCP Server 一律拦截**"
    "（fail-closed，见 06 §管理台前端对平台级对象的读写面）—— 这不是缺陷，"
    "是「不静默丢凭据」的必然代价，但确实让「改一条 Skill 声明」变得更麻烦；"
    "② 平台级**申请队列无 UI**（`getPlatformPublishRequests` 已备好接口，未做列表页）；"
    "③ MCP 的 `command` 字段**无法回填**（目录 DTO 无此字段，V1 仅 http/sse，影响低）；"
    "④ `expectedPublishSeq` **无来源**（平台作用域暂无 pointer 端点，操作者拿不到当前序号） | "
    "`platformCatalog.test.tsx` 20 例 + `platformObjectForm.test.ts` 29 例 + "
    "`agentDefinitionBackfill.test.tsx` 12 例 + `delegationTopology.test.tsx` 8 例 + "
    "`orchestrationForm.test.tsx` 27 例 |"
)
ROW_CP = (
    "| **第三十四批前端反证（六处，逐一验证用例真的会红）** | "
    "① 摘掉「取不到权威定义 → `definitionUnavailable`」标记"
    "（回填不全 ⇒ 静默清空关系）→ **恰 3 条红**；"
    "② 把 allowlist 的 EMPTY 归一成 null（三态不可归一化）→ **恰 6 条红**"
    "（含 2 条页面级闭环 —— 纯函数两端被测、中间接缝未测是本批最大的覆盖缺口）；"
    "③ 把 403 的提示并入 400（闸门语义不可分辨）→ **恰 1 条红**；"
    "④ 摘掉「闭包补齐会丢凭据引用」的 fail-closed 阻断 → **恰 1 条红**；"
    "⑤ 在停用卡片里注入一个「删除」按钮 → **恰 1 条红**；"
    "⑥ 摘掉「MCP 型 Tool 绑定的 Server 也要纳入发布集合」这一跳 → **恰 2 条红**。"
    "**六处互相独立、可分别证伪**；脚本带「结论必须变化」的自动判定 —— "
    "改动后结论不变即判为**无效反证**并以非零码退出。"
    "⚠️ **反证 E 曾判无效并被修正**：原断言写的是 "
    "`queryByRole('button', { name: /^删除/ })`，而 antd 会给**两字中文**按钮名"
    "**插空格**（「删除」渲染成「删 除」），正则**永远匹配不上** —— "
    "那是一条**空断言**（即使真有删除按钮也照样绿）。"
    "已改为「扫全部按钮、取归一化文本」，并附证「停用入口在」 | "
    "`scripts/c34-3-counterproof.sh`（含「恢复后复跑全绿」） |"
)


def patch_00_tables() -> None:
    if _done(F00):
        print(f"  跳过（已回写）：{F00.name}")
        return
    text = F00.read_text()
    text = _append_row_to_table(
        text, "| 需求/工作包 | 落点 | 自动化证据 |", ROW_FE, "00 已实现表"
    )
    text = _append_row_to_table(
        text,
        "| 缺陷 | 现象 | 修正 |",
        ROW_CP,
        "00 历批修正表",
    )
    _write(F00, text)
    print(f"  ✅ {F00.name}：已实现表 +1 行、历批修正表 +1 行")


# ── ③ 00：历批修正表补「静默清空关系」那一行 ────────────────────────────
ROW_CLEAR = (
    "| **Agent 详情页只回填 name/description ⇒ 改一下就把关系清了**"
    "（第三十四批修 · C34-3） | "
    "Agent 详情页回填时**只填了 name 与 description**，关系字段（角色 / 启用技能 / "
    "技能包 / 能力标签 / 编排声明）**全部落空** —— 而那正是多级 Agent 的核心声明。"
    "后果：保存一次就把一份合法编排**覆盖成空**（对非 LEAF = **取消全部委派**），"
    "而用户**看不出来**（页面上那些字段本来就是空的，保存后还是空的）。"
    "根因是**取错了数据源**：管理台的 Agent 详情读的是旧体系 "
    "`agent` / `agent_version` 的 `latestDefinition`（一份自由 JSON 快照），"
    "而关系字段在**权威定义** `cap_agent_def` 里 —— 两份**不是同一份东西**。"
    "另有一处语义错位：`overrides` 回填的是 `latestDefinition` **全文**，"
    "而 `overrides` 是「**收紧覆盖**」这个字段，不是整份定义 | "
    "① **独立加载权威定义**（`GET /api/cap/agents/{id}/definition`），"
    "**完整回填**全部关系字段；"
    "② `overrides` 取自权威定义的 `overrides` 字段，**不是**旧体系快照全文；"
    "③ **取不到权威定义时绝不静默清空** —— 显式告警 + 要求用户**显式确认**才放行；"
    "④ **发布闸门抽成纯函数** `evaluatePublishGate`：提交按钮的 `disabled` 与"
    "提交函数内的提前 `return` **共用同一判据** —— 两处各写一份必然漂移。"
    "**证据**：`agentDefinitionBackfill.test.tsx` 12 例 + "
    "`scripts/c34-3-counterproof.sh` 反证 A（恰 3 条红） |"
)


def patch_00_defect() -> None:
    if _done(F00):
        print(f"  跳过（已回写）：{F00.name}")
        return
    text = F00.read_text()
    text = _append_row_to_table(
        text, "| 缺陷 | 现象 | 修正 |", ROW_CLEAR, "00 历批修正表"
    )
    _write(F00, text)
    print(f"  ✅ {F00.name}：历批修正表 +1 行（静默清空关系）")


# ── ④ 06：管理台前端对平台级对象的读写面 ───────────────────────────────
SEC_06 = """
### 管理台前端对平台级对象的读写面（第三十四批补充 · C34-3）

⚠️ **本节为本批补充**（C34-3）：06 §平台级对象的登记与生命周期描述的是
**后端**通道；本节补**前端**这一半 —— 管理台怎么用这条通道，
以及前端**必须**替作者挡住的几个坑。

| 项 | 口径 |
|---|---|
| 登记与修改 | **共用同一张草稿表单**（`new` 是保留路由段）。分两条路由就会有两份表单，迟早漂移 —— 而「登记」与「改版」的差别只在「标识是否已存在」 |
| 三步工作流 | 前端**不**合并三步：申请 → 审批 → 执行各有各的端点与各自的按钮。把三个动作塞进一次请求，双人复核就退化成自证 |
| 「删除」语义 | **物理删除不做**（已发布行不可变，DB 触发器）。正确语义是**停用**（`PUBLISHED → DEPRECATED`，单向终态），走 `lifecycle-requests`，CAS 前置条件携带**真实**的 `expectedFrom` |
| 停用原状态 | **不得硬编码 `PUBLISHED`** —— 对象可能早已停用（迁移是单向终态）。后端目录接口**已提供** `lifecycleState`，前端须如实读取；对 `DEPRECATED` 对象直接禁用停用入口，并说明「它早就停了」，而不是让操作者走到审批才收到一个读起来像「有人跟我抢」的「原状态不符」 |
| 闸门语义可分辨 | 400（参数非法，作者能改）/ 403（不是 `PLATFORM_ADMIN`，作者改不了，得换人）/ 409（自审批或状态冲突，流程问题）/ 503（**平台运维通道未接入 = 环境问题，不是用户的错**）—— 四者**必须分开呈现**，合并成一句「操作失败」会让操作者去改自己的填法，而真因在权限或环境 |

⚠️ **前端先拒，只是体验优化，服务端门禁才是权威**：
前端在提交前做形状与一致性检查，让作者**当场**看到错误，
而不是填完理由、走到审批才被拒。

⚠️ **闭包补齐的 fail-closed：凭据引用不得被静默丢弃**：
平台级发布必须构成**自洽闭包** —— Skill 声明的每个 Tool / MCP Server、
以及 MCP 型 Tool 绑定的 Server，都必须出现在**同一次请求**的发布集合里
（否则后端以 `TOOL_NOT_DECLARED` / `MCP_NOT_BOUND` 拒）。
前端替作者补齐闭包，而补齐时有个**必须拦住的坑**：
**目录接口从不回显凭据定位符**（只给 `hasCredentialRef` 布尔，凭据材料与定位符
永不入响应，§4.2 / P5-T3）⇒ 补齐时重发出来的 Server spec **必然没有凭据引用**
⇒ 后端为它建**新版本行**时凭据引用为 null ⇒ 指针移过去后，
该 Server 在**全平台**的生效配置里**认证失效**。
一个「改 Skill 的声明清单」的操作，静默改掉了全平台的 MCP 认证 ——
与「显式授权材料被静默清空」同级。故前端 **fail-closed 拦截并点名具体 Server**，
要求改到该 Server 自己的详情页单独改版（那里能填凭据引用）。

⚠️ **传递闭包这一跳不能省**：
「Skill 声明的 Tool / 声明的 MCP」只是**第一跳**；
若被声明的 Tool 本身是 **MCP 型**，它绑定的 Server 也必须一并发布。
省掉这一跳 ⇒ 「声明了一个 MCP 型 Tool 但没声明其 Server」必被后端
`MCP_NOT_BOUND` 拒，而**被拒的那个对象根本不在用户眼前**。

⚠️ **`null` 与 `[]` 的三态在前端也必须分得开**：
`declared_mcp[].tool_allowlist` 与 `exposed_tool_allowlist` 的三态
（未配置 `null` / 显式空 `[]` / 清单）**不可归一化** ——
把「显式空」显示成「未配置」等于**权限放宽**（未配置 = 取全集）。
故三态在 UI 上真的分得开，且**页面接线**也被用例钉住
（纯函数两端被测、中间接缝未测是本批发现并补上的最大覆盖缺口）。
"""


def patch_06() -> None:
    if _done(F06):
        print(f"  跳过（已回写）：{F06.name}")
        return
    text = F06.read_text()
    anchor = "### 输出契约 / 路由策略的生命周期（第二十五批补充）"
    if text.count(anchor) != 1:
        raise SystemExit(f"06 锚点不唯一（命中 {text.count(anchor)}）")
    text = text.replace(anchor, SEC_06.strip("\n") + "\n\n" + anchor, 1)
    _write(F06, text)
    print(f"  ✅ {F06.name}：新增「管理台前端对平台级对象的读写面」")


# ── ⑤ 07：前端不参与运行期判定 ──────────────────────────────────────────
SEC_07 = """
### 前端不参与运行期判定（第三十四批补充 · C34-3）

⚠️ **本批管理台前端（第三十四批）不改变运行期的任何判定口径** ——
授权判定、闭包构建、委派准入**全部不变**。前端新增的逻辑只落在
「申请 → 审批 → 执行」与「生命周期迁移」两条**既有通道**上。

⚠️ **前端的三条自律**（本批落地）：
① **前端先拒只是体验优化，服务端门禁才是权威** ——
前端在提交前做形状与一致性检查，让作者**当场**看到错误；
但前端**不得**自造一套「写权限」判断来代替后端闸门
（`PLATFORM_ADMIN` 由后端 `requirePlatformAdmin()` 强制，403 默认拒绝）。
② **前端不得预填默认值** —— 非 LEAF 的最大深度 / 子调用数 / 超时
必须由作者**显式填写**（评审前置决策 2 已取消平台默认值；
前端一旦预填就成了「第二个默认值来源」，前后端各一份，迟早漂移）。
③ **委派边 ≠ 授权关系** —— 委派拓扑页是**发布关系投影**的只读视图
（边的唯一来源是发布期投影，**不可手工写**：手工写会让投影与 release
两个真相分叉），它**不是**运行期的授权依据。

⚠️ **入向委派边只是「停用风险」的提示，不是拦截**：
「谁依赖我」非空意味着停用该 Agent 会让那些父悬空 ——
但**当前没有任何自动守卫**会因此阻止停用（后端 CAS 只管对象自身状态列，
没有引用守卫）。页面措辞已如实更正为「提示」而非「守卫」——
宣称一个并不存在的拦截，比不提示更危险（运维会以为系统会拦住，实际照放行）。
"""


def patch_07() -> None:
    if _done(F07):
        print(f"  跳过（已回写）：{F07.name}")
        return
    text = F07.read_text()
    anchor = "### 关系投影不参与运行期授权判定（第三十四批补充 · 评审前置决策 4）"
    if text.count(anchor) != 1:
        raise SystemExit(f"07 锚点不唯一（命中 {text.count(anchor)}）")
    text = text.replace(anchor, SEC_07.strip("\n") + "\n\n" + anchor, 1)
    _write(F07, text)
    print(f"  ✅ {F07.name}：新增「前端不参与运行期判定」")


# ── ⑥ 08：MA-T28a～MA-T28f ──────────────────────────────────────────────
SEC_08 = """
⚠️ **管理台前端的验收（第三十四批 · C34-3 · 与上表同批交付）**：
| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T28a | **回填不全不得静默清空** | 权威定义（`cap_agent_def`）取不到时保存 | **不得**发起发布请求；必须显式告警「保存会清空关系字段」并要求用户**显式确认**才放行 | `agentDefinitionBackfill.test.tsx#authoritativeDefinition404BlocksSilentClear` + `#未确认就提交` |
| MA-T28b | **闸门判据唯一** | 提交按钮的 `disabled` 与提交函数内的提前 `return` | 必须**共用同一判据**（`evaluatePublishGate`）—— 两处各写一份必然漂移，漂移后要么「点了没反应」，要么「拦不住」 | `orchestrationForm.test.tsx#发布闸门 · evaluatePublishGate` 6 例 |
| MA-T28c | **三态不可归一化** | `toolAllowlist` / `exposedToolAllowlist` 为「显式空」（`[]`）时提交 | 提交体必须是 `[]`，**绝不**退化成 `null`（`null` = 取全集 = 权限放宽）—— 且**页面接线**也要被钉住，不只是纯函数 | `platformObjectForm.test.ts` + `platformCatalog.test.tsx#三态闭环` |
| MA-T28d | **凭据引用不得被静默丢弃** | 闭包补齐要连带重发一个**已登记凭据引用**的 MCP Server | **必须拦截**并点名该 Server（fail-closed）—— 目录接口从不回显凭据定位符，重发出来的新版本会**丢掉凭据引用**，导致全平台生效配置认证失效 | `platformCatalog.test.tsx#已登记凭据引用的 MCP Server 被闭包补齐连带重发` |
| MA-T28e | **不得有物理删除入口** | 打开任一平台级对象详情页 | 页面**不提供**任何物理删除入口（已发布行不可变，物理删除会让引用它的对象悬空）；「删除」语义 = 停用（`PUBLISHED → DEPRECATED`，单向终态） | `platformCatalog.test.tsx#页面不提供任何物理删除入口`（⚠️ 该断言曾因 antd 两字中文插空格而**空转**，已改为归一化文本比对） |
| MA-T28f | **闸门语义可分辨** | 平台级写入口返回 400 / 403 / 409 / 503 | 四者**分开呈现**：参数非法 / 权限不足（换人）/ 状态冲突（流程问题）/ **运维通道未接入（环境问题，不是用户的错）** | `platformCatalog.test.tsx#403 → 提示「权限不足（403）」` + `#400` + `#503` |
"""


def patch_08() -> None:
    if _done(F08):
        print(f"  跳过（已回写）：{F08.name}")
        return
    text = F08.read_text()
    anchor = "<!-- C34-2 -->"
    if text.count(anchor) != 1:
        raise SystemExit(f"08 锚点不唯一（命中 {text.count(anchor)}）")
    text = text.replace(anchor, SEC_08.strip("\n") + "\n\n" + anchor, 1)
    _write(F08, text)
    print(f"  ✅ {F08.name}：新增 MA-T28a～MA-T28f")


# ── ⑦ 09：FR-049 行 + 说明段 ────────────────────────────────────────────
ROW_FR049 = (
    "| FR-049 | **管理台前端：四类对象的详情页 CRUD + 关系维护 + 委派拓扑**"
    "（第三十四批补充 · C34-3 · 管理台改造前端） | "
    "06 §管理台前端对平台级对象的读写面（第三十四批）、"
    "07 §前端不参与运行期判定（第三十四批）、08 §MA-T28a～MA-T28f | "
    "MA-22（第三十四批 C34-3） | "
    "AC-038 / MA-T28a、MA-T28b、MA-T28c、MA-T28d、MA-T28e、MA-T28f |"
)
SEC_09 = """
⚠️ **FR-049 是 FR-048 的**前端**配套条目**（C34-3）：
FR-048 覆盖「管理台的读写面」，FR-049 覆盖「**管理台前端把这条面接出来**」——
三张平台级对象的详情页（登记 / 改版 / 停用）+ 关系视图 + 委派拓扑视图，
外加 Agent 详情页「**关系字段完整回填 + 取不到权威定义时绝不静默清空**」的修复。
⚠️ **FR-049 只覆盖「前端接线」** —— 它**不改变**运行期的任何判定口径
（授权 / 闭包 / 委派准入均不变，见 07 §前端不参与运行期判定）。
⚠️ **前端先拒只是体验优化，服务端门禁才是权威** ——
`PLATFORM_ADMIN` 闸门、双人复核、版本号与指纹的服务端决定、
CAS 基线的比对，全部仍在后端，前端**不得**自造假的替代判断。
⚠️ **两条必须记在前端口径里的 fail-closed**：
① 闭包补齐时**凭据引用不得被静默丢弃**（目录接口从不回显凭据定位符，
重发会丢掉凭据 ⇒ 全平台认证失效）—— 故带凭据的 Server 一律**拦截并点名**；
② 「显式空」（`[]`）**绝不**退化成「未配置」（`null`）——
那是**权限放宽**，不是保守收紧。
"""


def patch_09() -> None:
    if _done(F09):
        print(f"  跳过（已回写）：{F09.name}")
        return
    text = F09.read_text()
    text = _append_row_to_table(
        text,
        "| 需求 ID | 需求描述 | 设计落点 | 实现工作包 | 验收用例 |",
        ROW_FR049,
        "09 需求追踪表",
    )
    anchor = "<!-- C34-2 -->"
    if text.count(anchor) != 1:
        raise SystemExit(f"09 锚点不唯一（命中 {text.count(anchor)}）")
    text = text.replace(anchor, SEC_09.strip("\n") + "\n\n" + anchor, 1)
    _write(F09, text)
    print(f"  ✅ {F09.name}：新增 FR-049 行 + 说明段")


# ── ⑧ 重规划 html：§3.5.2 前端落地状态 ─────────────────────────────────
HTML_SEC = """
  <h3>3.5.2 管理台前端的落地状态（第三十四批 C34-3）</h3>
  <div class="callout c-info">
    <strong>📌 本节只登记<strong>前端</strong>这一半</strong>：
    后端能力见 §3.5.1（第三十四批 C34-2）。
    前端<strong>不改变运行期的任何判定口径</strong> ——
    授权 / 闭包 / 委派准入均不变。
  </div>

  <table>
    <tr><th>批次项</th><th>本批状态</th><th>说明</th></tr>
    <tr><td><strong>平台级对象详情页（Tool / Skill / MCP）</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td>三页共用 <code>PlatformDetailShell</code>（概览 / 关系 / 操作）与
        <code>PlatformPublishPanel</code>（申请 → 审批 → 执行）。
        <strong>登记与修改共用同一张草稿表单</strong>（<code>new</code> 是保留路由段）。</td></tr>
    <tr><td><strong>「删除」= 停用</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td>物理删除<strong>不做</strong>（已发布行不可变）；停用走
        <code>lifecycle-requests</code>，CAS 原状态<strong>如实读取</strong>
        <code>lifecycleState</code>（不硬编码 <code>PUBLISHED</code>）。</td></tr>
    <tr><td><strong>关系维护视图</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td>Skill 的声明 Tool / MCP、Tool 的所属 MCP、MCP 的<strong>允许集与实际登记分开呈现</strong>；
        「声明了但对象不存在」显式列为 missing（<code>error</code> 级）。</td></tr>
    <tr><td><strong>委派拓扑视图</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td><code>GET /api/cap/delegations</code> 三端点的只读视图。
        ⚠️ 入向边非空只是<strong>停用风险的提示</strong>，<strong>不是拦截</strong> ——
        当前没有任何自动守卫会因此阻止停用。</td></tr>
    <tr><td><strong>Agent 详情的关系字段回填修复</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td>此前只回填 name/description ⇒ 关系字段落空 ⇒
        <strong>保存一次就把合法编排覆盖成空</strong>。现在独立加载权威定义
        <code>cap_agent_def</code> 并<strong>完整回填</strong>；
        <code>overrides</code> 取自权威定义的该字段（不是旧体系快照全文）；
        取不到时<strong>绝不静默清空</strong>（显式告警 + 显式确认）。</td></tr>
    <tr><td><strong>闭包补齐的 fail-closed</strong></td>
      <td><span class="badge b-done">已落地</span></td>
      <td>带凭据引用的 MCP Server <strong>一律拦截并点名</strong> ——
        目录接口从不回显凭据定位符，重发会<strong>丢掉凭据引用</strong>（全平台认证失效）；
        并补齐<strong>传递闭包</strong>（MCP 型 Tool 绑定的 Server 也要进发布集合）。</td></tr>
    <tr><td><strong>平台级申请队列列表页</strong></td>
      <td><span class="badge b-plan">未做</span></td>
      <td>接口已备好（<code>getPlatformPublishRequests</code>），未做列表 UI。</td></tr>
  </table>

  <div class="callout c-warn">
    <strong>⚠️ 本批修掉的一个<strong>空断言</strong>：</strong>
    「页面不提供任何物理删除入口」原先写的是
    <code>queryByRole('button', { name: /^删除/ })</code> —— antd 会给
    <strong>两字中文</strong>按钮名<strong>插空格</strong>（「删除」渲染成「删 除」），
    正则<strong>永远匹配不上</strong>：即使真有删除按钮，那条用例也照样绿。
    已改为「扫全部按钮、取归一化文本」，并附证「停用入口在」。
    <strong>空断言比没有断言更危险</strong> —— 它让人以为这条性质被守着。
  </div>
"""


def patch_html() -> None:
    if MARK_HTML in FHTML.read_text():
        print(f"  跳过（已回写）：{FHTML.name}")
        return
    text = FHTML.read_text()
    anchor = "  <div class=\"callout c-warn\">\n    <strong>⚠️ 一条被本批推翻的旧口径：</strong>"
    if text.count(anchor) != 1:
        raise SystemExit(f"html 锚点不唯一（命中 {text.count(anchor)}）")
    text = text.replace(anchor, HTML_SEC.strip("\n") + "\n\n" + anchor, 1)
    text = text.replace("</section>\n\n<section id=\"s4\">", MARK_HTML + "\n</section>\n\n<section id=\"s4\">", 1)
    FHTML.write_text(text)
    print(f"  ✅ {FHTML.name}：新增 §3.5.2 前端落地状态")


def main() -> None:
    print("════════ 第三十四批 · C34-3 文档回写（前端） ════════")
    patch_00_head()
    patch_00_tables()
    patch_00_defect()
    patch_06()
    patch_07()
    patch_08()
    patch_09()
    patch_html()
    print("✅ C34-3 文档回写完成")


if __name__ == "__main__":
    main()
