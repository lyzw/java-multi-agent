package cn.studykid.multiagent.application.cap;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * Agent 的收紧覆盖（§4.2「overrides 仅收紧」）—— <b>全仓库唯一解析实现</b>。
 *
 * <p>支持的形态：
 * <ul>
 *   <li>{@code removeSkills: [..]} —— 移除 Skill；</li>
 *   <li>{@code removeTools: [..]} —— 移除 Tool；</li>
 *   <li>{@code lowerRiskCeiling: "READ"} —— 全局下调风险上限；</li>
 *   <li>{@code lowerRiskCeiling: {"skill-a": "READ"}} —— 按 Skill 下调。</li>
 * </ul>
 *
 * ⚠️ <b>解析失败即抛 {@link ClosureResolutionException}</b>（fail-closed）：覆盖写错却静默忽略，等于把
 * 「本应被收紧的配置」放行。调用方（授权路径 {@link PermissionResolver}、
 * 管理面 {@link DependencyGraphService}）必须把异常当成拒绝/失败处理，
 * <b>不得</b>退化成「当没有覆盖」。
 *
 * <p>⚠️ <b>为什么抽成包级公共类</b>：授权路径与管理面图谱都要读同一份
 * {@code overrides} JSON。若两边各写一遍解析，「图谱显示与实际收紧不一致」
 * 会让运维依据错误信息做决策 —— 与 {@code McpOpAllowlistNormalizer} 同理，
 * 全仓只保留一处实现。
 */
public record AgentOverrides(Set<String> removeSkills, Set<String> removeTools,
                             @Nullable CapabilityRiskLevel globalLowerRiskCeiling,
                             Map<String, CapabilityRiskLevel> perSkillLowerRiskCeiling) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无覆盖（{@code overrides} 为 null/空白时的等价物）。 */
    public static final AgentOverrides EMPTY =
            new AgentOverrides(Set.of(), Set.of(), null, Map.of());

    public static @NonNull AgentOverrides parse(@Nullable String overridesJson) {
        if (overridesJson == null || overridesJson.isBlank()) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(overridesJson);
        } catch (RuntimeException e) {
            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                    "Agent overrides 不是合法 JSON: " + overridesJson);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return EMPTY;
        }
        if (!root.isObject()) {
            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                    "Agent overrides 结构非法: " + overridesJson);
        }
        ObjectNode node = (ObjectNode) root;
        Set<String> removeSkills = readStringSet(node.get("removeSkills"), "removeSkills");
        Set<String> removeTools = readStringSet(node.get("removeTools"), "removeTools");

        CapabilityRiskLevel global = null;
        Map<String, CapabilityRiskLevel> perSkill = new LinkedHashMap<>();
        JsonNode ceilingNode = node.get("lowerRiskCeiling");
        if (ceilingNode != null && !ceilingNode.isMissingNode() && !ceilingNode.isNull()) {
            if (ceilingNode.isTextual()) {
                global = CapabilityRiskLevel.parse(ceilingNode.asText());
                if (global.isUnknown()) {
                    throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                            "lowerRiskCeiling 取值非法: " + ceilingNode.asText());
                }
            } else if (ceilingNode.isObject()) {
                ceilingNode.properties().forEach(entry -> {
                    CapabilityRiskLevel level = CapabilityRiskLevel.parse(
                            entry.getValue().asText(null));
                    if (level.isUnknown()) {
                        throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                                "lowerRiskCeiling 取值非法: skill=" + entry.getKey());
                    }
                    perSkill.put(entry.getKey(), level);
                });
            } else {
                throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "lowerRiskCeiling 结构非法");
            }
        }
        return new AgentOverrides(removeSkills, removeTools, global, Map.copyOf(perSkill));
    }

    private static @NonNull Set<String> readStringSet(@Nullable JsonNode node,
                                                      @NonNull String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                    "Agent overrides." + field + " 结构非法");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent overrides." + field + " 含非字符串元素");
            }
            values.add(element.asText());
        }
        return Set.copyOf(values);
    }

    public boolean removesSkill(@NonNull String skillId) {
        return removeSkills.contains(skillId);
    }

    public boolean removesTool(@NonNull String toolId) {
        return removeTools.contains(toolId);
    }

    @Nullable
    public CapabilityRiskLevel lowerRiskCeilingOf(@NonNull String skillId) {
        return perSkillLowerRiskCeiling.get(skillId);
    }
}
