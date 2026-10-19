package cn.studykid.multiagent.application.config;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;

/**
 * 执行快照工厂：把「当前配置」固化为「Run 的不可变快照」。
 *
 * <p><b>这是 R-021 的处置点</b>：运行中改配置导致同一 Run 前后行为不一致。
 * 本工厂在 Run 创建时读取配置并固化，此后配置变更不影响已创建的 Run。
 *
 * <p><b>fail-closed</b>：任一必需配置缺失/非法即抛
 * {@link ConfigResolutionException}，<b>Run 创建失败</b>，不做默认值兜底。
 */
public final class RunConfigSnapshotFactory {

    private final Clock clock;

    public RunConfigSnapshotFactory(@NonNull Clock clock) {
        this.clock = clock;
    }

    /**
     * 为新 Run 创建配置快照。
     *
     * @param runId          新 Run 标识
     * @param maxCostPerRun  配置值：{@code agent.run.max-cost-per-run}
     * @param maxLlmCalls    配置值：{@code agent.run.max-llm-calls}
     * @param maxSteps       配置值：{@code agent.run.max-steps}
     * @param riskMap        配置值：{@code agent.tool.risk-level-map}
     * @param approvalByRisk 配置值：{@code agent.approval.required-by-risk}
     * @param primaryTool    本次 Run 主要调用的工具（用于确定审批要求）
     * @throws ConfigResolutionException 任一配置缺失或非法
     */
    public @NonNull RunConfigSnapshot create(
            @NonNull String runId,
            @NonNull Object maxCostPerRun,
            @NonNull Object maxLlmCalls,
            @NonNull Object maxSteps,
            @NonNull Map<String, ToolRiskLevel> riskMap,
            @NonNull Map<ToolRiskLevel, ApprovalRequirement> approvalByRisk,
            @NonNull String primaryTool) {

        BigDecimal cost = PlatformConfig.Resolver.requirePositiveDecimal(
                maxCostPerRun, PlatformConfig.KEY_MAX_COST_PER_RUN);
        int calls = PlatformConfig.Resolver.requirePositiveInt(
                maxLlmCalls, PlatformConfig.KEY_MAX_LLM_CALLS);
        int steps = PlatformConfig.Resolver.requirePositiveInt(
                maxSteps, PlatformConfig.KEY_MAX_STEPS);
        ApprovalRequirement approval = PlatformConfig.Resolver.resolveApproval(
                primaryTool, riskMap, approvalByRisk);

        return new RunConfigSnapshot(runId, cost, calls, steps, approval, clock.instant());
    }
}
