"""配置加载与存储（单一事实源）。

Demo 用 JSON（零依赖、可运行）；Java 侧经 @ConfigurationProperties 绑定，与现有
PlatformConfigMaps 同构（见 PRD §4 注释）。
"""
from __future__ import annotations

import hashlib
import json
import os
from typing import Dict, Optional

from .model import (
    Agent, McpBinding, McpServer, ParamPolicy, RiskLevel, Skill, Tool,
)


class ConfigStore:
    def __init__(self, config_dir: str, current_authz_version: int = 2,
                 current_config_fingerprint: str = "fp-v1",
                 current_config_version: int = 1):
        self.current_authz_version = current_authz_version
        self.current_config_fingerprint = current_config_fingerprint
        self.current_config_version = current_config_version
        # 版本服务不可用（fail-closed 拒绝新调用，05 §5 / S3d）
        self.version_service_unavailable: bool = False
        self.tools: Dict[str, Tool] = {}
        self.mcp_servers: Dict[str, McpServer] = {}
        self.skills: Dict[str, Skill] = {}
        self.agents: Dict[str, Agent] = {}
        self.tenant_overrides: dict = {}
        self._load(config_dir)

    def bump_authz_version(self) -> int:
        """原子递增 authzVersion（对应 06 §1.5 DB 自增；本 Demo 单进程模拟）。"""
        self.current_authz_version += 1
        return self.current_authz_version

    def bump_config_version(self, new_fingerprint: str) -> int:
        """原子发布新配置版本（cap_config_release.config_publish_seq，06 §3.2）。"""
        self.current_config_version += 1
        self.current_config_fingerprint = new_fingerprint
        return self.current_config_version

    def _load(self, config_dir: str) -> None:
        with open(os.path.join(config_dir, "tools.json"), encoding="utf-8") as f:
            for t in json.load(f)["tools"]:
                rl = RiskLevel.resolve(t.get("riskLevel"))
                # 配置期拒绝发布：风险等级未知/非法/未登记 → 直接报错（05 §3.3 / P0-1）
                if rl == RiskLevel.UNKNOWN:
                    raise ValueError(
                        f"配置期拒绝发布：工具 {t['id']} 的风险等级 "
                        f"{t.get('riskLevel')!r} 未知/非法/未登记（须显式 READ/WRITE_PROFILE/WRITE_EXTERNAL）"
                    )
                self.tools[t["id"]] = Tool(
                    id=t["id"], name=t.get("name", t["id"]), kind=t.get("kind", "native"),
                    risk_level=rl,
                    input_schema=t.get("inputSchema", {}),
                    mcp_server_id=t.get("mcpServerId"),
                    mcp_tool_name=t.get("mcpToolName"),
                    param_policies=tuple(_policy(p) for p in t.get("paramPolicies", [])),
                    namespace=t.get("namespace", "platform"),
                )
        with open(os.path.join(config_dir, "mcp_servers.json"), encoding="utf-8") as f:
            for m in json.load(f)["mcpServers"]:
                self.mcp_servers[m["id"]] = McpServer(
                    id=m["id"], transport=m.get("transport", "http"),
                    endpoint=m.get("endpoint"), trust_tier=m.get("trustTier", "public"),
                    exposed_tool_allowlist=_opt_tuple(m.get("exposedToolAllowlist")),
                    connection_risk=RiskLevel.resolve(m.get("connectionRisk")),
                    dynamic_discovery=bool(m.get("dynamicDiscovery", True)),
                )
        with open(os.path.join(config_dir, "skills.json"), encoding="utf-8") as f:
            for s in json.load(f)["skills"]:
                self.skills[s["id"]] = Skill(
                    id=s["id"],
                    declared_tools=tuple(s.get("declaredTools", [])),
                    declared_mcp=tuple(
                        McpBinding(b["serverId"], _opt_tuple(b.get("toolAllowlist")))
                        for b in s.get("declaredMcp", [])
                    ),
                    risk_ceiling=RiskLevel.resolve(s.get("riskCeiling")),
                    param_policies=tuple(_policy(p) for p in s.get("paramPolicies", [])),
                    inherits=tuple(s.get("inherits", [])),
                    required_caps=tuple(s.get("requiredCaps", [])),
                    namespace=s.get("namespace", "platform"),
                )
        with open(os.path.join(config_dir, "agents.json"), encoding="utf-8") as f:
            for a in json.load(f)["agents"]:
                self.agents[a["id"]] = Agent(
                    id=a["id"], tenant_id=a["tenantId"], roles=tuple(a.get("roles", [])),
                    enabled_skills=tuple(a.get("enabledSkills", [])),
                    skill_bundles=tuple(a.get("skillBundles", [])),
                    capability_tags=tuple(a.get("capabilityTags", [])),
                    inherits=a.get("inherits"),
                    overrides=a.get("overrides", {}) or {},
                    version=a.get("version", 1),
                )
        to_path = os.path.join(config_dir, "tenant_overrides.json")
        if os.path.exists(to_path):
            with open(to_path, encoding="utf-8") as f:
                self.tenant_overrides = json.load(f)

    # ---- 查询辅助 ----
    def find_mcp_tool(self, server_id: str, mcp_tool_name: str) -> Tool | None:
        """按 (serverId, mcpToolName) 找到包装该 MCP 工具的 Tool 定义。"""
        for tool in self.tools.values():
            if tool.kind == "mcp" and tool.mcp_server_id == server_id \
                    and tool.mcp_tool_name == mcp_tool_name:
                return tool
        return None

    def closure_fingerprint(self, agent_id: str, skill_id: str,
                            tool_id: Optional[str] = None,
                            mcp_server_id: Optional[str] = None,
                            mcp_tool_name: Optional[str] = None) -> str:
        """有效能力闭包指纹（评审 P0-6 / 06 §4）：**仅本 Run 实际依赖的闭包**内容哈希，
        非全局配置聚合。其他 Agent/Skill/Tool/MCP 的变更**不影响**本 Run 的指纹。

        覆盖：Agent（version/启用集/覆盖） + 本次 Skill（riskCeiling/声明/绑定/继承）
        + 目标 Tool（riskLevel/inputSchema） + 目标 MCP server（endpoint/allowlist/connectionRisk）。
        """
        parts: list = []
        agent = self.agents.get(agent_id)
        if agent is not None:
            parts.append(("agent", agent.id, agent.version, agent.tenant_id,
                          sorted(agent.enabled_skills), sorted(agent.skill_bundles),
                          sorted(agent.capability_tags), json.dumps(agent.overrides, sort_keys=True)))
        skill = self.skills.get(skill_id)
        if skill is not None:
            parts.append(("skill", skill.id, skill.risk_ceiling.value,
                          sorted(skill.declared_tools),
                          [(b.server_id, sorted(b.tool_allowlist) if b.tool_allowlist else None)
                           for b in skill.declared_mcp],
                          sorted(skill.inherits), sorted(skill.required_caps)))
        if tool_id is not None:
            t = self.tools.get(tool_id)
            if t is not None:
                parts.append(("tool", t.id, t.risk_level.value,
                              json.dumps(t.input_schema, sort_keys=True), t.kind,
                              t.mcp_server_id, t.mcp_tool_name))
        if mcp_server_id is not None:
            s = self.mcp_servers.get(mcp_server_id)
            if s is not None:
                parts.append(("mcp", s.id, s.endpoint, s.trust_tier, s.connection_risk.value,
                              sorted(s.exposed_tool_allowlist) if s.exposed_tool_allowlist else None,
                              s.dynamic_discovery))
        raw = json.dumps(parts, sort_keys=True, default=str)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _opt_tuple(v):
    return tuple(v) if v is not None else None


def _policy(p: dict) -> ParamPolicy:
    return ParamPolicy(param=p["param"], op=p["op"], value=p.get("value"))
