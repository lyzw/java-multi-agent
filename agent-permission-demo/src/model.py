"""统一配置与权限管控体系 —— 领域模型（与 platform/ 的 ToolHandler / AuthorizationService 同构）。

对应 PRD §2。所有判定遵循 fail-closed：任何缺失/异常/未知一律按"拒绝/最严"处理。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


# 级别序：用于 riskCeiling 比较（数值越大越危险）。放在类外，避免被 Enum 当作成员。
_RISK_ORDER = {
    "UNKNOWN": 0,
    "READ": 1,
    "WRITE_PROFILE": 2,
    "WRITE_EXTERNAL": 3,
}


class RiskLevel(Enum):
    """工具风险级别，对应 platform ToolRiskLevel（READ/WRITE_PROFILE/WRITE_EXTERNAL）。"""
    UNKNOWN = "UNKNOWN"
    READ = "READ"
    WRITE_PROFILE = "WRITE_PROFILE"
    WRITE_EXTERNAL = "WRITE_EXTERNAL"

    def __le__(self, other: "RiskLevel") -> bool:
        return _RISK_ORDER[self.value] <= _RISK_ORDER[other.value]

    def __lt__(self, other: "RiskLevel") -> bool:
        return _RISK_ORDER[self.value] < _RISK_ORDER[other.value]

    @classmethod
    def resolve(cls, name: Optional[str]) -> "RiskLevel":
        """未知/非法/未登记风险 → `UNKNOWN`（评审 P0-1 / 05 §3.3）。

        不再 fail-closed 降级为最严 WRITE_EXTERNAL 后放行：未知风险在配置期拒绝发布、
        运行期 `authorizationDecision=DENY(RISK_LEVEL_UNKNOWN)`。
        """
        if name is None:
            return cls.UNKNOWN
        try:
            return cls(name)
        except ValueError:
            return cls.UNKNOWN

    def default_approval(self) -> str:
        return {
            RiskLevel.UNKNOWN: "REQUIRED",
            RiskLevel.READ: "NOT_REQUIRED",
            RiskLevel.WRITE_PROFILE: "OPTIONAL",
            RiskLevel.WRITE_EXTERNAL: "REQUIRED",
        }[self]


@dataclass(frozen=True)
class ParamPolicy:
    param: str
    op: str            # in_allowlist | lte | gte | eq
    value: object = None


@dataclass(frozen=True)
class Tool:
    id: str
    name: str
    kind: str                 # native | mcp
    risk_level: RiskLevel
    input_schema: dict = field(default_factory=dict)
    mcp_server_id: Optional[str] = None
    mcp_tool_name: Optional[str] = None
    param_policies: tuple = field(default_factory=tuple)
    namespace: str = "platform"


@dataclass(frozen=True)
class McpBinding:
    server_id: str
    tool_allowlist: Optional[tuple] = None   # None = 允许该 server exposedToolAllowlist 全部（仍受连接级闸门）


@dataclass(frozen=True)
class SkillUsage:
    """一条分场景操作指引（2级）：when = 什么情况，how = 用何种操作。"""
    when: str
    how: str


@dataclass(frozen=True)
class SkillDescription:
    """两级描述（与契约 skill.schema.json 的 skillDescription 同构）。

    purpose（1级）供路由/选型；usage（2级）供选中后注入上下文执行。
    描述不是授权：usage 只能引导 declaredTools / declaredMcp 已声明的能力，
    与声明冲突时以声明为准（fail-closed）。
    """
    purpose: str
    usage: tuple = field(default_factory=tuple)   # tuple[SkillUsage]，可空


@dataclass(frozen=True)
class McpServer:
    id: str
    transport: str
    endpoint: Optional[str] = None
    trust_tier: str = "public"
    exposed_tool_allowlist: Optional[tuple] = None
    connection_risk: RiskLevel = RiskLevel.WRITE_EXTERNAL
    dynamic_discovery: bool = True


@dataclass(frozen=True)
class Skill:
    id: str
    declared_tools: tuple = field(default_factory=tuple)
    declared_mcp: tuple = field(default_factory=tuple)   # tuple[McpBinding]
    risk_ceiling: RiskLevel = RiskLevel.WRITE_EXTERNAL
    param_policies: tuple = field(default_factory=tuple)
    inherits: tuple = field(default_factory=tuple)
    required_caps: tuple = field(default_factory=tuple)
    namespace: str = "platform"
    description: Optional[SkillDescription] = None


@dataclass(frozen=True)
class Agent:
    id: str
    tenant_id: str
    roles: tuple = field(default_factory=tuple)
    enabled_skills: tuple = field(default_factory=tuple)
    skill_bundles: tuple = field(default_factory=tuple)
    capability_tags: tuple = field(default_factory=tuple)
    inherits: Optional[str] = None
    overrides: dict = field(default_factory=dict)
    version: int = 1


@dataclass(frozen=True)
class AuthContext:
    """执行上下文，对应 platform TenantContext（tenantId/principalId/roles/authzVersion）。"""
    tenant_id: str
    principal_id: str
    roles: tuple
    authz_version: int
