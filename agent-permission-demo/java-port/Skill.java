package cn.studykid.multiagent.ports.skill;

import java.util.List;
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
     * 两级描述（与契约 skill.schema.json 的 skillDescription 同构）。
     * purpose（1级）供路由/选型：Agent 靠它判断该不该选中本 Skill，必须一句话讲清用途；
     * usage（2级）供选中后注入上下文执行：when 描述「什么情况」，how 描述「用何种操作」。
     * 描述不是授权：usage 只能引导 declaredTools / declaredMcp 已声明的能力，
     * 授权真相只有声明列表与 {@link #riskCeiling()}，与描述冲突时按声明执行（fail-closed）。
     */
    @NonNull SkillDescription description();

    /** 两级 Skill 描述。usage 可缺省（空），但提供时条目间应互斥或有明确优先级。 */
    record SkillDescription(@NonNull String purpose, @NonNull List<Usage> usage) {

        public SkillDescription {
            usage = List.copyOf(usage);
        }

        /** 一条分场景操作指引：when = 什么情况，how = 用何种操作。 */
        record Usage(@NonNull String when, @NonNull String how) {}
    }

    /**
     * MCP server 绑定：连接级（serverId）+ 操作级（toolAllowlist）。
     * toolAllowlist 为空表示允许该 server exposedToolAllowlist 全部（仍受连接级闸门）。
     */
    record McpBinding(@NonNull String serverId, Set<String> toolAllowlist) {}
}
