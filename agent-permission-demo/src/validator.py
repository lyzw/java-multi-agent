"""权限校验：调用期逐跳重验（PRD §3.2 / 05 §0-§2）。

实现**统一决策状态模型（05 §0）**的 Phase A（授权）：
  authorizationDecision(ALLOW/DENY) + reasonCode
主体授权**最先**校验，再能力链 Agent→Skill→Tool/MCP + 风险 + 参数。
任一失败 → authorizationDecision=DENY；审批/预算属 Phase B（执行准入），不在此层。

对应平台：
- Agent→Skill / Skill→Tool：PermissionResolver 闭包
- 主体重授权：AuthorizationService + authzVersion（强一致读，红线 9 / 05 §5）
- MCP 拆分连接级/操作级 + 动态列举交集（fail-closed）
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

from .model import AuthContext, RiskLevel
from .resolver import (
    effective_mcp_bindings,
    effective_skills_of_agent,
    effective_tools_of_skill,
)
from .store import ConfigStore

# 校验阶段标识（用于决策日志细分）
STAGE_AGENT_SKILL = "agent_skill"
STAGE_SKILL_TOOL = "skill_tool"
STAGE_RISK_CEILING = "risk_ceiling"
STAGE_SKILL_MCP = "skill_mcp"
STAGE_MCP_OP = "mcp_tool_not_allowed"
STAGE_MCP_DYNAMIC = "mcp_dynamic_drift"
STAGE_STALE_AUTHZ = "stale_authz"
STAGE_CONFIG_STALE = "config_stale"
STAGE_RISK_UNKNOWN = "risk_unknown"
STAGE_OK = "allowed"

# 阶段 → 稳定错误码（05 §4）
REASON_CODE: Dict[str, str] = {
    STAGE_AGENT_SKILL: "SKILL_NOT_ENABLED",
    STAGE_SKILL_TOOL: "TOOL_NOT_DECLARED",
    STAGE_RISK_CEILING: "RISK_EXCEEDS_CEILING",
    STAGE_SKILL_MCP: "MCP_NOT_BOUND",
    STAGE_MCP_OP: "MCP_OP_NOT_ALLOWED",
    STAGE_MCP_DYNAMIC: "MCP_TOOL_DRIFTED",
    STAGE_STALE_AUTHZ: "AUTHZ_STALE",
    STAGE_CONFIG_STALE: "CONFIG_FINGERPRINT_STALE",
    STAGE_RISK_UNKNOWN: "RISK_LEVEL_UNKNOWN",
    STAGE_OK: "",
}


@dataclass
class AuthorizationDecision:
    """Phase A 授权结果（05 §0）。decision=ALLOW/DENY；allowed 为便捷属性。"""
    decision: str           # "ALLOW" / "DENY"
    stage: str
    reason_code: str
    reason: str
    path: List[str] = field(default_factory=list)

    @property
    def allowed(self) -> bool:
        return self.decision == "ALLOW"

    @classmethod
    def allow(cls) -> "AuthorizationDecision":
        return cls("ALLOW", STAGE_OK, "", "闭环校验通过")

    @classmethod
    def deny(cls, stage: str, reason: str) -> "AuthorizationDecision":
        return cls("DENY", stage, REASON_CODE.get(stage, "RESOURCE_UNKNOWN"), reason)


def _is_authorized(ctx: AuthContext, store: ConfigStore, action: str, resource_id: str) -> bool:
    """简化版 AuthorizationService.isAuthorized：主体角色含 AGENT_AUTHOR/TENANT_ADMIN，
    且 authzVersion 为当前版本（撤销授权会 bump 版本，陈旧 Run 即失效）。
    版本服务不可用 → fail-closed 拒绝（05 §5）。"""
    if store.version_service_unavailable:
        return False
    if ctx.authz_version != store.current_authz_version:
        return False
    if not (set(ctx.roles) & {"AGENT_AUTHOR", "TENANT_ADMIN"}):
        return False
    return True


def validate_native(ctx: AuthContext, store: ConfigStore, agent_id: str,
                    skill_id: str, tool_id: str) -> AuthorizationDecision:
    agent = store.agents.get(agent_id)
    skill = store.skills.get(skill_id)
    tool = store.tools.get(tool_id)
    if agent is None or skill is None or tool is None:
        return AuthorizationDecision.deny("unknown", "Agent/Skill/Tool 不存在")

    # [0] 风险未知（配置期应拒发布；运行期 authorizationDecision=DENY，不降级为 WRITE_EXTERNAL，05 §3.3 / P0-1）
    if tool.risk_level == RiskLevel.UNKNOWN:
        return AuthorizationDecision.deny(STAGE_RISK_UNKNOWN,
                                          "Tool 风险等级未知/非法/未登记（RISK_LEVEL_UNKNOWN）")

    # [1] 主体授权（最先，05 §2 顺序铁律）
    if not _is_authorized(ctx, store, "tool:invoke", tool_id):
        return AuthorizationDecision.deny(STAGE_STALE_AUTHZ,
                                          "authzVersion 陈旧/版本服务不可用或主体无 AGENT_AUTHOR 角色")

    # [2] Agent → Skill
    if skill_id not in effective_skills_of_agent(agent, store):
        return AuthorizationDecision.deny(STAGE_AGENT_SKILL,
                                           f"Agent {agent_id} 未启用 Skill {skill_id}")

    # [3] Skill → Tool + riskCeiling
    if tool_id not in effective_tools_of_skill(skill, store):
        return AuthorizationDecision.deny(STAGE_SKILL_TOOL,
                                           f"Skill {skill_id} 未声明 Tool {tool_id}")
    if not (tool.risk_level <= skill.risk_ceiling):
        return AuthorizationDecision.deny(STAGE_RISK_CEILING,
                                           f"Tool {tool_id} 风险 {tool.risk_level.value} 超过 Skill {skill_id} 上限 {skill.risk_ceiling.value}")
    return AuthorizationDecision.allow()


def validate_mcp(ctx: AuthContext, store: ConfigStore, agent_id: str,
                 skill_id: str, server_id: str, mcp_tool_name: str,
                 discovered_tools: Optional[List[str]] = None) -> AuthorizationDecision:
    agent = store.agents.get(agent_id)
    skill = store.skills.get(skill_id)
    server = store.mcp_servers.get(server_id)
    if agent is None or skill is None or server is None:
        return AuthorizationDecision.deny("unknown", "Agent/Skill/McpServer 不存在")

    # [1] 主体授权（最先）
    if not _is_authorized(ctx, store, "tool:invoke", f"mcp:{server_id}/{mcp_tool_name}"):
        return AuthorizationDecision.deny(STAGE_STALE_AUTHZ, "authzVersion 陈旧/版本服务不可用或主体无权限")

    # [2] Agent → Skill
    if skill_id not in effective_skills_of_agent(agent, store):
        return AuthorizationDecision.deny(STAGE_AGENT_SKILL,
                                           f"Agent {agent_id} 未启用 Skill {skill_id}")

    # [4] Skill → MCP（连接级 + 操作级）
    binding = None
    for b in effective_mcp_bindings(skill, store):
        if b.server_id == server_id:
            binding = b
            break
    if binding is None:
        return AuthorizationDecision.deny(STAGE_SKILL_MCP,
                                          f"Skill {skill_id} 未声明接入 MCP server {server_id}")

    allowed_set = binding.tool_allowlist if binding.tool_allowlist is not None \
        else server.exposed_tool_allowlist
    if allowed_set is not None and mcp_tool_name not in allowed_set:
        return AuthorizationDecision.deny(STAGE_MCP_OP,
                                          f"MCP 工具 {mcp_tool_name} 不在 Skill {skill_id} 的 toolAllowlist")

    # 动态性兜底：server 当前已不再暴露该工具 → 拒绝（动态漂移）
    if discovered_tools is not None and mcp_tool_name not in discovered_tools:
        return AuthorizationDecision.deny(STAGE_MCP_DYNAMIC,
                                          f"MCP server {server_id} 当前未暴露 {mcp_tool_name}（运行期交集为空的 fail-closed）")

    # 找到包装该 MCP 工具的 Tool，校验 riskCeiling + 主体授权
    tool = store.find_mcp_tool(server_id, mcp_tool_name)
    if tool is None:
        return AuthorizationDecision.deny("unknown", f"无 Tool 包装 MCP {server_id}/{mcp_tool_name}")
    if tool.risk_level == RiskLevel.UNKNOWN:
        return AuthorizationDecision.deny(STAGE_RISK_UNKNOWN,
                                          f"MCP 工具 {mcp_tool_name} 风险等级未知/非法/未登记（RISK_LEVEL_UNKNOWN）")
    if not (tool.risk_level <= skill.risk_ceiling):
        return AuthorizationDecision.deny(STAGE_RISK_CEILING,
                                          f"MCP 工具 {mcp_tool_name} 风险 {tool.risk_level.value} 超过 Skill 上限 {skill.risk_ceiling.value}")
    return AuthorizationDecision.allow()
