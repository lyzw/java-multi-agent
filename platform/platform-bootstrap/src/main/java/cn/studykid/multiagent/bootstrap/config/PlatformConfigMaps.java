package cn.studykid.multiagent.bootstrap.config;

import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;

/**
 * 平台级**映射型**配置的解析结果（C8 接线 / P4-D11）。
 *
 * <p>⚠️ <b>为什么不能直接用 {@code @Value}</b>：
 * {@code @Value("${agent.tool.risk-level-map}")} 期望该键是一个**标量**，
 * 而它在 yml 里是一个 **map** → Spring 抛
 * {@code Could not resolve placeholder}。
 * 绑定 map 的正确做法是用 {@code Binder}（与 {@code ConfigReadService} 同源做法）。
 *
 * <p>⚠️ <b>fail-closed</b>：映射缺失或非法 → <b>拒绝启动</b>。
 * 退回默认值等于「悄悄用了一个没人审过的风险映射」——
 * 那正是 C8 要消除的分叉（声明与实际不一致）。
 *
 * @param toolRiskLevelMap      工具名 → 风险级别（<b>镜像</b>；权威在工具代码，见
 *                              {@link ToolRiskLevelConsistencyGuard}）
 * @param approvalRequiredByRisk 风险级别 → 审批要求（只能收紧，不能放松）
 */
public record PlatformConfigMaps(
        @NonNull Map<String, ToolRiskLevel> toolRiskLevelMap,
        @NonNull Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk
) {
    public PlatformConfigMaps {
        toolRiskLevelMap = Map.copyOf(toolRiskLevelMap);
        approvalRequiredByRisk = Map.copyOf(approvalRequiredByRisk);
    }
}
