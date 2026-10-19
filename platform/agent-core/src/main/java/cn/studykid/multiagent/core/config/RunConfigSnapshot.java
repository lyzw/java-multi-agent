package cn.studykid.multiagent.core.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 执行快照中承载的<em>配置快照</em>。
 *
 * <p><b>为什么需要它（DEC-9 / R-021）</b>：成本上限、步数上限、并发上限、审批策略
 * 均为配置化（可运行时调整）。若 Run 执行过程中配置被改动，同一个 Run 的前后行为
 * 将不可解释、故障不可复现。因此：
 *
 * <blockquote>
 * <b>Run 创建时把相关配置值快照进 ExecutionSnapshot；此后改配置只影响新 Run。</b>
 * </blockquote>
 *
 * <p>本类为不可变值对象，创建后不可修改。快照中不含任何未成年人可识别信息
 * （P0-D00 §4 白名单约束），仅为数值与枚举策略。
 *
 * @param runId            所属 Run
 * @param maxCostPerRun    单 Run 成本上限（DEC-9 初值 ¥2.00）
 * @param maxLlmCalls      单 Run 模型调用上限（初值 12）
 * @param maxSteps         单 Run 最大步数（初值 20）
 * @param approvalRequired 本 Run 快照的审批要求（DEC-11）
 * @param createdAt        快照时刻
 */
public record RunConfigSnapshot(
        @NonNull String runId,
        @NonNull BigDecimal maxCostPerRun,
        int maxLlmCalls,
        int maxSteps,
        @NonNull ApprovalRequirement approvalRequired,
        @NonNull Instant createdAt
) {

    public RunConfigSnapshot {
        requirePositive(maxCostPerRun, "maxCostPerRun");
        requirePositive(maxLlmCalls, "maxLlmCalls");
        requirePositive(maxSteps, "maxSteps");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
    }

    private static void requirePositive(@NonNull BigDecimal v, @NonNull String name) {
        if (v.signum() <= 0) {
            // fail-closed：非法配置直接拒绝，不做「取默认值」的宽松放行
            throw new IllegalArgumentException(name + " 必须为正数，实际: " + v);
        }
    }

    private static void requirePositive(int v, @NonNull String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " 必须为正整数，实际: " + v);
        }
    }

    /**
     * 以审计友好的形式导出快照内容。
     * <p>不含秘密、不含学生数据，仅数值与策略枚举。
     */
    public @NonNull Map<String, Object> toAuditMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("maxCostPerRun", maxCostPerRun);
        m.put("maxLlmCalls", maxLlmCalls);
        m.put("maxSteps", maxSteps);
        m.put("approvalRequired", approvalRequired.name());
        m.put("createdAt", createdAt);
        return Collections.unmodifiableMap(m);
    }

    /** @return 是否还可再发起模型调用。 */
    public boolean allowsLlmCall(int callsSoFar) {
        return callsSoFar < maxLlmCalls;
    }

    /** @return 是否还可推进执行步。 */
    public boolean allowsStep(int stepsSoFar) {
        return stepsSoFar < maxSteps;
    }

    /** @return 该 Run 的审批要求是否需要人工审批。 */
    public boolean requiresApproval() {
        return approvalRequired == ApprovalRequirement.REQUIRED;
    }
}
