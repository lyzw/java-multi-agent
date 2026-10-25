#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十四批 C34-2 文档回写：管理台改造的**后端**部分
（平台级对象 Tool / Skill / MCP Server 的登记与生命周期 + 关系查询 + Agent 结构化定义）。

⚠️ 本批的用户诉求（2026-10-25 · zhouwei）：
   「对 admin-console 相关的进行优化，包括 Agent / Skill / Tool / Mcp 中的详细数据的
   增删改查动作，以及相关关系的维护功能」——**本轮全量交付**。
⚠️ 用户已拍板：平台对象的写入口走**平台作用域发布端点**
   （`PLATFORM_ADMIN` 闸门 + 运维通道）。

涉及五份文档：

① **06 配置数据模型**：新增「平台级对象的登记与生命周期」——
   平台对象无 tenant_id；发布写 PLATFORM 作用域的发布头与指针，
   而那两张表的写策略只放行本租户行 → 必须走平台运维角色（BYPASSRLS）；
   申请单表支持 PLATFORM 作用域（V27 迁移，**RLS 策略一个字都不改**）；
   「删除」语义 = **停用**（DEPRECATED，单向终态），不做物理删除；
   关系维护口径。

② **07 执行链集成设计**：新增「平台级变更与运行期的关系」——
   明确平台的登记/停用**不改变**运行期的授权判定。

③ **08 安全测试与验收矩阵**：新增 **MA-T27a～MA-T27g**。

④ **09 需求追踪矩阵**：新增 **FR-048** 行 + 说明段。

⑤ **00 修订说明**：
   · 「历批修正的设计缺陷」表新增 2 行
     （① 平台级对象没有 HTTP 写入口；② 申请单表不支持 PLATFORM 作用域）；
   · 快照标题与本批性质段落；
   · 更正「运维通道不接在租户可达的 HTTP 路径上」这条**已被本批推翻**的旧口径。

⚠️ 幂等：按文件独立记账 + 文件内标记双重判定。
"""
import os
import pathlib

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C34_2_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C34-2 -->"

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


# ── ① 06：平台级对象的登记与生命周期 ─────────────────────────────────────
ANCHOR_06 = """### 输出契约 / 路由策略的生命周期（第二十五批补充）"""

SEC_06 = """
### 平台级对象的登记与生命周期：PLATFORM 作用域发布通道（第三十四批补充）

⚠️ **本节为本批补充**（用户拍板于 2026-10-25 · zhouwei）：
平台级对象（Tool / Skill / MCP Server）的写入口走
**平台作用域发布端点**（`PLATFORM_ADMIN` 闸门 + 平台运维通道）。

⚠️ **为什么平台级对象此前没有写入口**：
它们**无 `tenant_id`**（V15 明写「这三张表是 platform 级，不启用 RLS」），
而平台的**发布头**（`cap_config_release`）与**当前指针**（`cap_config_pointer`）
的写策略**只放行本租户的 TENANT 行**（V15 / V17）——
平台行**只能**由平台运维角色（BYPASSRLS）写入。
于是此前只有两条路：手写 SQL，或带外运维工具。

| 项 | 口径 |
|---|---|
| 身份闸门 | `PLATFORM_ADMIN` 角色（**403 默认拒绝**）。平台身份表 `principal` 是**租户内**的，没有跨租户主体 —— 没有这道闸门，任一租户管理员自授角色即可改全平台配置 |
| 通道闸门 | 写入走**平台运维角色连接**（BYPASSRLS）。**未接入时明确失败（503）**，**绝不回落应用角色** —— 回落后 RLS 会拒写，报错点落在 RLS 策略上，离「这个环境没配运维凭据」这一真因极远 |
| 双人复核 | 平台级变更**同样走「申请 → 审批 → 执行」三步**。把申请人与审批人两个 id 放进一次请求由调用方填写，双人复核就退化成「调用方自己声明另一个人同意了」（V18 迁移注释原话） |
| 作用域闸门 | PLATFORM 作用域的发布**只带平台级对象**；TENANT 作用域**只带租户级对象**。混装被配置期门禁拒绝（`ConfigValidationGate`），且发布头的作用域会与内容自相矛盾 |
| 版本号与指纹 | **服务端决定**：版本号 = 该对象现有最大版本 + 1；内容指纹 = `CapObjectFingerprint` 按内容计算。客户端提交这两者 = 让客户端指定「内容没变」这个证明 |
| 申请单的作用域 | `cap_config_publish_request` **第三十四批起支持 PLATFORM 作用域**（V27 迁移：`tenant_id` 可空 + `scope` 列 + 一致性 CHECK）。⚠️ **RLS 读写策略一个字都不改** —— 策略是严格相等（`tenant_id = current_tenant_id()`），平台行（`tenant_id IS NULL`）对**应用角色连接天然不可见**：租户既读不到、也写不进平台的申请单 |
| 「删除」语义 | **物理删除不做** —— 已发布行不可变（DB 层触发器）。正确语义是**停用**（`PUBLISHED → DEPRECATED`，单向终态）。管理台的「删除」按钮因此映射到停用通道 |
| 停用的边界 | 停用**只挡「新引用」**；存量引用按固定版本继续过渡（同 06 §3.2 对契约 / 策略的口径） |

⚠️ **关系维护的口径（每个对象只维护自己声明的关系）**：
| 对象 | 声明的关系 | 存储落点 |
|---|---|---|
| Skill | 声明哪些 Tool（`declared_tools`）、声明哪些 MCP Server（`declared_mcp`） | `cap_skill_def` |
| Tool | 绑定在哪个 MCP Server 的哪个远端工具（`mcp_server_id` + `mcp_tool_name`） | `cap_tool_def` |
| MCP Server | 允许哪些远端工具（`exposed_tool_allowlist`） | `cap_mcp_server_def` |

⚠️ **`declared_mcp[].tool_allowlist` 的 `null` 与 `[]` 语义不同**（§4.4）：
`null` = 未配置（取 ServerAllowedOps 全集），`[]` = 显式不允许任何操作。
**不可归一化** —— 把 `null` 归一成 `[]` 会让「没配」变成「什么都不许」，
那是**可用性事故**而不是保守收紧。

⚠️ **「MCP Server 允许集」与「实际登记在该 Server 上的 Tool」是两回事**：
前者是连接级允许集，后者是 Tool 定义绑定。两者可以不一致（允许集更宽、登记更窄），
刻意**分开呈现**，避免运维把「没登记」误读成「不允许」。

⚠️ **「声明了但对象不存在」必须如实呈现**：
`declared_tools` 里可以出现尚未登记的 id（草稿态 Skill 的正常情形）。
关系查询端点把它们**显式列为 `missing`**，而不是悄悄从清单里消失 ——
悄悄消失会让运维以为「没有这个依赖」。
"""


def patch_06() -> None:
    if _done(F06):
        print("  跳过（已回写）：06_配置数据模型与生命周期设计.md")
        return
    s = F06.read_text()
    s = _append_after(s, ANCHOR_06, SEC_06, "06 平台级对象登记与生命周期")
    # ⚠️ 更正旧口径：平台级对象不再是「仍然拒绝」
    old = """| 治理通道 | 生命周期迁移端点（申请 → 审批 → 执行，三步）的类型闸门放开到 `{agent, result_contract, routing_policy}` —— 全是**租户级**对象；平台级对象（tool / skill / mcp_server）**仍然拒绝**（其发布走平台运维通道，不经租户身份暴露） |"""
    new = """| 治理通道 | 生命周期迁移端点（申请 → 审批 → 执行，三步）的类型闸门放开到 `{agent, result_contract, routing_policy}` —— 全是**租户级**对象；平台级对象（tool / skill / mcp_server）在**租户通道**里**仍然拒绝**（其发布走平台运维通道，不经租户身份暴露）。⚠️ **第三十四批更正**：平台级对象**另有**一条独立通道（`PLATFORM_ADMIN` 闸门 + 运维通道，见本文件「平台级对象的登记与生命周期」小节）——「仍然拒绝」只对**租户通道**成立，不再是「平台级对象无生命周期通道」 |"""
    s = _sub_once(s, old, new, "06 治理通道行")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F06.write_text(s)
    print("  已回写：06_配置数据模型与生命周期设计.md（平台级对象登记与生命周期 + 旧口径更正）")


# ── ② 07：平台级变更与运行期的关系 ───────────────────────────────────────
ANCHOR_07 = """### 关系投影不参与运行期授权判定（第三十四批补充 · 评审前置决策 4）"""

SEC_07 = """
### 平台级变更与运行期的关系（第三十四批补充）

⚠️ **本批新增的是「配置面」的写入口，不是「运行面」的判定**：
平台级对象（Tool / Skill / MCP Server）的登记、修改与停用
**不改变**运行期既有的任何判定口径。

| 环节 | 平台级登记 / 停用之后 |
|---|---|
| 工具调用的授权判定 | **不变** —— 按调用主体已解析的能力快照与闭包指纹判定 |
| Skill / Tool 的**闭包** | **不变** —— 闭包取自**已发布**的版本；新发布的内容要等指针切换后才进入闭包 |
| MCP 的固定清单 | **不变** —— 清单来自运行期采集，配置期的 `exposed_tool_allowlist` 只是**允许集**，不是清单本身 |
| 停用后的**存量引用** | **继续过渡** —— 停用只挡「新引用」；在途调用按**固定版本**继续跑（与契约 / 策略停用同款） |
| 委派准入 | **不变** —— 委派关系 ≠ 授权关系；投影表与平台对象表都不参与准入判定 |

⚠️ **必须说清的一件事**：平台的写入口接到了 HTTP 路径上，
但**身份闸门与通道闸门都没有因此放松**：
写操作仍要过 `PLATFORM_ADMIN` 角色判定（403 默认拒绝），
仍要走平台运维角色连接（未接入即 503）。
⚠️ **它改变的是「谁**能**改」而不是「改**什么**」** ——
「运维通道由带外运维工具驱动，不接在租户可达的 HTTP 路径上」
这条旧口径**已被本批推翻**（平台级对象这一支），
但它背后的**理由**（平台身份表是租户内的，没有跨租户主体）**依然成立**，
所以闸门被搬到了 HTTP 路径的**最前面**，而不是被取消。
"""

ANchor_07_OLD_NOTE = """⚠️ **必须说清的一件事：投影表**不是**运行期的判定依据**。"""

def patch_07() -> None:
    if _done(F07):
        print("  跳过（已回写）：07_执行链集成设计.md")
        return
    s = F07.read_text()
    s = _append_after(s, ANCHOR_07, SEC_07, "07 平台级变更与运行期")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F07.write_text(s)
    print("  已回写：07_执行链集成设计.md（平台级变更与运行期）")


# ── ③ 08：MA-T27a～MA-T27g ──────────────────────────────────────────────
ANCHOR_08 = """| MA-T26f | **反证：结论真的会变** | ① 把 fail-closed 改回「静默跳过」；② 把判据改成「仓储装没装配」；③ 把出向查询改成按父 release 过滤 | ① 恰好红 **1** 条（fail-closed 那条）；② 恰好红 **1** 条（LEAF 不该被拒那条）；③ 恰好红 **1** 条（M-9）—— **三条反证互相独立、可分别证伪** | `scripts/c34-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

SEC_08 = """

### 变更补充：平台级对象的登记与生命周期验收（第三十四批 · 管理台改造）

⚠️ **本批验收项对应的是「管理台改造」的口径**（用户拍板于 2026-10-25 · zhouwei）：
平台级对象（Tool / Skill / MCP Server）的写入口走
**平台作用域发布端点**（`PLATFORM_ADMIN` 闸门 + 平台运维通道）。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T27a | **默认拒绝** | 非 `PLATFORM_ADMIN` 主体提交平台级发布申请 | **403**，且**一个字节都不许写**（服务方法一次都没被调用）—— 平台身份表是租户内的，没有跨租户主体 | `PlatformCapabilityControllerTest#nonPlatformAdminIsForbidden` + `#emptyRolesAreForbidden` |
| MA-T27b | **申请单作用域** | 平台级发布申请落库 | 申请单的 `tenant_id` 为 **NULL**、`scope = PLATFORM` —— 它要经平台运维连接读写，应用角色连接根本看不见它 | `PlatformCapabilityPublishServiceTest#requestPublishUsesPlatformScope` |
| MA-T27c | **作用域不得混装** | 平台级发布 | 发布命令的 `scope = PLATFORM`、`tenantId = null`，且**不得携带任何租户级对象**（agents / resultContracts / routingPolicies 全空） | `PlatformCapabilityPublishServiceTest#platformPublishCarriesNoTenantObjects` + `#payloadHasNoTenantSlots` |
| MA-T27d | **版本号与指纹由服务端决定** | 平台对象已有 v7，客户端提交该对象的新版本 | 版本号 = **8**（现有最大版本 + 1），内容指纹由服务端计算（**不是占位值**）—— 客户端若能指定这两者，「内容没变」这个证明就是它自己说的 | `PlatformCapabilityPublishServiceTest#versionIsAssignedByServer` |
| MA-T27e | **双��复核** | 平台级申请的审批 | 申请人审批自己的申请 → **冲突拒绝**；DB 层另有 `cap_config_publish_request_two_person_ck` CHECK 兜底 | `PlatformCapabilityPublishServiceTest#selfApprovalIsRejected` + `#decideApproves` |
| MA-T27f | **失败留痕** | 平台级发布在执行期被门禁拒绝 | 申请单被标为 **FAILED** 并带失败原因 —— 「批过但没生效」的申请不能只存在于异常栈里 | `PlatformCapabilityPublishServiceTest#failedApplyIsRecorded` |
| MA-T27g | **运维通道 fail-closed** | 平台运维角色未接入 | **明确失败**（`PlatformOpsUnavailableException` → HTTP **503**），**绝不回落应用角色**；且**申请单一条都不许落** | `PlatformCapabilityPublishServiceTest#unavailableOpsChannelFailsClosed` + `PlatformCapabilityControllerTest#unavailableOpsChannelIsServiceUnavailable` |

⚠️ **关系维护的验收（第三十四批 · 与上表同批交付）**：
| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T27h | **「声明了但不存在」如实呈现** | Skill 声明了 2 个 Tool（其一未登记）与 2 个 MCP（其一未登记） | 存在的进 `tools` / `mcpServers`，**不存在的显式进 `missingTools` / `missingMcpServers`** —— 悄悄从清单里消失会让运维以为「没有这个依赖」 | `CapabilityCatalogControllerTest#skillRelationsSeparateMissingOnes` |
| MA-T27i | **「没有绑定」是合法状态** | `kind=native` 的 Tool 查关系 | `mcpServer = null`（**不是 404**）—— 404 会把「这是合法的原生工具」说成「资源不存在」 | `CapabilityCatalogControllerTest#nativeToolHasNoMcpBinding` |
| MA-T27j | **允许集与实际登记分列** | 查 MCP Server 的关系 | `exposedToolAllowlist`（允许集）与 `tools`（实际登记）**分开呈现、不合并**；`null`（未配置）≠ `[]`（显式空清单） | `CapabilityCatalogControllerTest#mcpRelationsKeepAllowlistAndToolsApart` + `PlatformCapabilityPublishServiceTest#mcpAllowlistNullAndEmptyAreDistinct` |"""


def patch_08() -> None:
    if _done(F08):
        print("  跳过（已回写）：08_安全测试与验收矩阵.md")
        return
    s = F08.read_text()
    s = _append_after(s, ANCHOR_08, SEC_08, "08 MA-T27")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F08.write_text(s)
    print("  已回写：08_安全测试与验收矩阵.md（MA-T27a～MA-T27j）")


# ── ④ 09：FR-048 行 + 说明段 ─────────────────────────────────────────────
ANCHOR_FR047 = """| FR-047 | **父子关系投影必落库：投影未装配且带了子引用即拒绝发布**（第三十四批补充 · 评审前置决策 4） | 06 §关系投影的存储口径（第三十四批）、07 §关系投影不参与运行期授权判定（第三十四批）、08 §MA-T26a～MA-T26f | MA-05、MA-21（第三十四批 C34-1） | AC-036 / MA-T26a、MA-T26b、MA-T26c、MA-T26d、MA-T26e、MA-T26f |"""

NEW_FR048 = """| FR-048 | **管理台：Agent / Skill / Tool / MCP 四类对象的详细数据增删改查 + 关系维护**（第三十四批补充 · 管理台改造） | 06 §平台级对象的登记与生命周期（第三十四批）、07 §平台级变更与运行期的关系（第三十四批）、08 §MA-T27a～MA-T27j | MA-22（第三十四批 C34-2） | AC-037 / MA-T27a、MA-T27b、MA-T27c、MA-T27d、MA-T27e、MA-T27f、MA-T27g、MA-T27h、MA-T27i、MA-T27j |"""

ANCHOR_FR047_NOTE = """⚠️ **FR-047 是「评审已决策并已落地」的配套条目**（决策人：zhouwei，2026-10-25）："""

NEW_FR048_NOTE = """⚠️ **FR-048 是「管理台改造」的配套条目**（用户拍板于 2026-10-25 · zhouwei）：
Agent / Skill / Tool / MCP 四类对象的**详细数据增删改查** + **相关关系维护**，
本轮**全量交付**。⚠️ **平台级对象的写入口**走**平台作用域发布端点**
（`PLATFORM_ADMIN` 闸门 + 平台运维通道），且**仍是「申请 → 审批 → 执行」三步**
（双人复核不得因为「平台级」而退化）。
⚠️ **「删除」语义是停用（DEPRECATED），不做物理删除** —— 已发布行不可变。
⚠️ **「关系维护」= 每个对象维护自己声明的关系**：
Skill→Tool/MCP、Tool→MCP、MCP→暴露清单；
⚠️ **`null` 与 `[]` 的三态不可归一化**（`declared_mcp[].tool_allowlist` 与
`exposed_tool_allowlist` 的语义差异见 §4.4）。
⚠️ **FR-048 只覆盖「管理台的读写面」** —— 它**不改变**运行期的任何判定口径
（授权、闭包、委派准入均不变，见 07 §平台级变更与运行期的关系）。

""" + ANCHOR_FR047_NOTE


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    s = F09.read_text()
    s = _append_after(s, ANCHOR_FR047, NEW_FR048, "09 FR-048 行")
    s = _sub_once(s, ANCHOR_FR047_NOTE, NEW_FR048_NOTE, "09 FR-048 说明段")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F09.write_text(s)
    print("  已回写：09_需求追踪矩阵.md（FR-048 行 + 说明段）")


# ── ⑤ 00：历批表登记 + 本批性质 + 快照标题 ───────────────────────────────
OLD_TABLE_HEADER = """| 缺陷 | 现象 | 修正 |"""

NEW_ROW_34_2_A = """| ✅ **平台级对象没有可达的写入口（第三十四批已修 · C34-2）** | Tool / Skill / MCP Server 是**平台级对象**（无 `tenant_id`），其发布必须写 PLATFORM 作用域的发布头（`cap_config_release`）与当前指针（`cap_config_pointer`）—— 而那两张表的**写策略只放行本租户的 TENANT 行**（V15 / V17），平台行**只能**由平台运维角色（BYPASSRLS）写入。于是管理台的 Tool / Skill / MCP 页此前是**只读目录**，变更只能靠手写 SQL 或带外运维工具（P6 登记限制 7 的「平台级对象」这一支） | 新增**平台作用域发布端点**（`/api/cap/platform/*`）：**身份闸门** = `PLATFORM_ADMIN` 角色（**403 默认拒绝**，平台身份表是租户内的、没有跨租户主体）；**通道闸门** = 平台运维角色连接（**未接入即 503，绝不回落应用角色**）；**仍是「申请 → 审批 → 执行」三步**（双人复核不因「平台级」而退化）。配套：V27 迁移让 `cap_config_publish_request` 支持 PLATFORM 作用域（`tenant_id` 可空 + `scope` 列 + 一致性 CHECK，**RLS 策略一个字都不改** —— 平台行对应用角色连接天然不可见）；Tool / Skill / MCP 三个发布槽位进 `ConfigPublishPayload`；新增关系查询端点与 Agent 结构化定义端点。⚠️ **「删除」语义 = 停用**（`PUBLISHED → DEPRECATED`，单向终态），**不做物理删除** —— 已发布行不可变（DB 触发器）。**证据**：`PlatformCapabilityPublishServiceTest` 15 例 + `PlatformCapabilityControllerTest` 8 例 + `CapabilityCatalogControllerTest` 6 例 |"""

NEW_ROW_34_2_B = """| ✅ **申请单表不支持 PLATFORM 作用域（第三十四批已修 · C34-2）** | `cap_config_publish_request.tenant_id` 是 `UUID NOT NULL`，而平台级变更**没有租户** → PLATFORM 作用域的申请单**存不进去**。于是「平台级变更也要走申请单」这条纪律在存储层**无法落地**，只剩两个坏选择：① 平台变更绕过申请单（双人复核退化成「调用方自己声明另一个人同意了」= 自证，V18 迁移注释的原话）；② 给平台申请单编一个**假 tenant_id**（污染租户语义，且租户会看到平台变更方案） | V27 迁移：`tenant_id` 改为**可空**、新增 `scope` 列、加 CHECK 保证「scope 与 tenant_id 一致」。⚠️ **RLS 读写策略一个字都不改** —— 策略是严格相等（`tenant_id = current_tenant_id()`），平台行（`tenant_id IS NULL`）对**应用角色连接天然不可见**：租户既读不到、也写不进平台的申请单；平台申请单只能经**平台运维角色（BYPASSRLS）连接**读写。端口侧用 `tenantId == null` 表达「PLATFORM 作用域」，SQL 用 `tenant_id IS NOT DISTINCT FROM ?::uuid`（`= NULL` 在 SQL 里恒为 NULL，会让平台申请单**永远查不到**）。**证据**：`PlatformCapabilityPublishServiceTest#requestPublishUsesPlatformScope`（断言申请单 `tenant_id` 为 NULL、`scope = PLATFORM`） |"""

OLD_SNAPSHOT_TITLE = """## 实现状态快照（2026-10-25 · 代码落地第三十四批：评审前置决策 4 —— 父子关系投影必落库）"""

NEW_SNAPSHOT_TITLE = """## 实现状态快照（2026-10-25 · 代码落地第三十四批：决策 4 落地 + 管理台改造）"""

OLD_C34_1_TAIL = """> ⚠️ **投影仍是投影**：权威来源是 release 里的 `childAgentRefs`；
> 投影表可重建、release 永不改写；运行期**委派准入与授权判定都不读投影表**。"""

NEW_C34_1_TAIL = OLD_C34_1_TAIL + """

> ⚠️ **第三十四批后半（C34-2）是管理台改造批次**：Agent / Skill / Tool / MCP 四类对象的
> **详细数据增删改查** + **相关关系维护**，本轮**全量交付**。
> ⚠️ **这是本批唯一的写入口扩展**：平台级对象（Tool / Skill / MCP Server）此前**没有**
> 可达的 HTTP 写入口（P6 登记限制 7 的「平台级对象」这一支）——
> 现在接上了，但**两道闸门都没有放松**：
> ① **身份闸门** = `PLATFORM_ADMIN` 角色（**403 默认拒绝**）；
> ② **通道闸门** = 平台运维角色（BYPASSRLS）连接，**未接入即 503，绝不回落应用角色**。
> ⚠️ **平台级变更仍走「申请 → 审批 → 执行」三步** —— 双人复核不得因为「平台级」而退化
> （把两个 id 放进一次请求，双人复核就退化成「调用方自己声明另一个人同意了」）。
> ⚠️ **本批推翻了「运维通道不接在租户可达的 HTTP 路径上」这条旧口径**
> （平台级对象这一支），但它背后的**理由依然成立** ——
> 所以闸门被搬到 HTTP 路径的**最前面**，而不是被取消。
> ⚠️ **「删除」语义是停用**（`PUBLISHED → DEPRECATED`），**不做物理删除**。
> ⚠️ **本批不改变运行期的任何判定口径**（授权 / 闭包 / 委派准入均不变）。"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_SNAPSHOT_TITLE, NEW_SNAPSHOT_TITLE, "00 快照标题")
    s = _sub_once(s, OLD_C34_1_TAIL, NEW_C34_1_TAIL, "00 第三十四批前半尾注")
    s = _append_row_to_table(s, OLD_TABLE_HEADER, NEW_ROW_34_2_A, "00 历批修正表 A")
    s = _append_row_to_table(s, OLD_TABLE_HEADER, NEW_ROW_34_2_B, "00 历批修正表 B")
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md（历批表 2 行 + 本批性质）")


def main() -> None:
    print("C34-2 文档回写：管理台改造（后端）")
    patch_00()
    patch_06()
    patch_07()
    patch_08()
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
