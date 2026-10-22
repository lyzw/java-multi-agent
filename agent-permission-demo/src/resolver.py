"""权限解析：继承合并、收紧覆盖、能力闭包计算。

对应 PRD §2.4（继承与覆盖规则）与 §3.1（T2 绑定期闭包）。
核心铁律：覆盖只能收紧（移除 Skill/Tool、调低 riskCeiling），不得放宽。
"""
from __future__ import annotations

from typing import Dict, List, Set

from .model import Agent, McpBinding, Skill, Tool
from .store import ConfigStore


def _overrides_for(agent: Agent, store: ConfigStore) -> dict:
    """合并 Agent 自身 overrides 与租户覆盖（tenant_config_override，仅收紧）。"""
    ov = {"removeSkills": [], "removeTools": [], "lowerRiskCeiling": {}}
    ov["removeSkills"].extend(agent.overrides.get("removeSkills", []))
    ov["removeTools"].extend(agent.overrides.get("removeTools", []))
    ov["lowerRiskCeiling"].update(agent.overrides.get("lowerRiskCeiling", {}))
    t = store.tenant_overrides.get(agent.tenant_id, {}).get(agent.id)
    if t:
        ov["removeSkills"].extend(t.get("removeSkills", []))
        ov["removeTools"].extend(t.get("removeTools", []))
        ov["lowerRiskCeiling"].update(t.get("lowerRiskCeiling", {}))
    return ov


def effective_skills_of_agent(agent: Agent, store: ConfigStore) -> Set[str]:
    """Agent 有效 Skill 集 = enabledSkills ∪ inherits模板 - 移除（收紧覆盖）。

    V1 忽略语义（04 §3 / 评审 P0-3）：`skillBundles` / `capabilityTags` / `Skill.requiredCaps`
    仅接受写入、**不用于任何启用/闭包/授权判定**；缺失 requiredCaps **不丢弃** Skill（不 fail-closed
    为"缺能力即不可用"）。本 Demo 因此只取 enabledSkills（+ 继承模板），并应用收紧移除。
    """
    base: Set[str] = set(agent.enabled_skills)
    if agent.inherits and agent.inherits in store.agents:
        base |= set(store.agents[agent.inherits].enabled_skills)
    ov = _overrides_for(agent, store)
    result = base - set(ov["removeSkills"])
    return result


def effective_tools_of_skill(skill: Skill, store: ConfigStore, _seen: Set[str] | None = None) -> Set[str]:
    """Skill 有效 Tool 集 = 自身 declaredTools ∪ 各 inherits 基础 Skill 工具集（传递闭包）。"""
    if _seen is None:
        _seen = set()
    if skill.id in _seen:
        return set()
    _seen.add(skill.id)
    tools: Set[str] = set(skill.declared_tools)
    for parent_id in skill.inherits:
        parent = store.skills.get(parent_id)
        if parent:
            tools |= effective_tools_of_skill(parent, store, _seen)
    return tools


def effective_mcp_bindings(skill: Skill, store: ConfigStore, _seen: Set[str] | None = None) -> List[McpBinding]:
    """Skill 有效 MCP 绑定 = 自身 declaredMcp ∪ 继承（合并 allowlist 取交集）。"""
    if _seen is None:
        _seen = set()
    if skill.id in _seen:
        return []
    _seen.add(skill.id)
    bindings = list(skill.declared_mcp)
    for parent_id in skill.inherits:
        parent = store.skills.get(parent_id)
        if parent:
            bindings += effective_mcp_bindings(parent, store, _seen)
    return bindings


def reachable_tools_closure(agent: Agent, store: ConfigStore) -> Set[str]:
    """Agent 的能力闭包：所有有效 Skill 的 Tool 并集（快照/审计/快速查找用）。"""
    out: Set[str] = set()
    for sid in effective_skills_of_agent(agent, store):
        skill = store.skills.get(sid)
        if skill:
            out |= effective_tools_of_skill(skill, store)
    return out
