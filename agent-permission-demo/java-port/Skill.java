package cn.studykid.multiagent.ports.skill;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ToolRiskLevel;

/**
 * Skill 端口（新增抽象层，对应 PRD §2.2）。
 *
 * <p>职责：作为「能力包」，声明它可用的 Tool 与 MCP server（连接级 + 操作级 allowlist），
 * 并设定可驱动 Tool 的 {@link ToolRiskLevel} 上限（riskCeiling），防止高危工具经低危 Skill 透传。
 *
 * <p>Skill 不感知 Agent，依赖单向向下（Agent → Skill → {Tool, MCP}）。
 * Native Tool 与 MCP-backed Tool 统一实现现有 {@code ToolHandler} 端口，
 * 因此经 Skill 触发的任何工具最终都落入 {@code ToolInvocationGate}（ADR-011 禁止绕过）。
 */
public interface Skill {

    @NonNull String skillId();

    /** 引用的 Tool id（单一事实源，非复制）。 */
    @NonNull Set<String> declaredTools();

    /** 引用的 MCP server 绑定（连接级 + 操作级 allowlist）。 */
    @NonNull Set<McpBinding> declaredMcp();

    /** 该 Skill 可驱动 Tool 的最高风险；超过即拒（防高危透传）。 */
    @NonNull ToolRiskLevel riskCeiling();

    /** 启用该 Skill 所需的能力标签（Agent 须持有）。 */
    @NonNull Set<String> requiredCaps();

    /** 基础 Skill id（工具集合并，传递闭包）。 */
    @NonNull Set<String> inherits();

    /**
     * MCP server 绑定：连接级（serverId）+ 操作级（toolAllowlist）。
     * toolAllowlist 为空表示允许该 server exposedToolAllowlist 全部（仍受连接级闸门）。
     */
    record McpBinding(@NonNull String serverId, Set<String> toolAllowlist) {}
}
