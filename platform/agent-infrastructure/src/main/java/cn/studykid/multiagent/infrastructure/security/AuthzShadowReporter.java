package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子比对 / 染色违例的<b>上报桥</b>（12 文 §4.5 / D6）。
 *
 * <p>仓储在影子期（P1）调用本类上报两件事：
 * <ol>
 *   <li>{@link #recordScopeEvaluation} —— Gate 算出的 {@link ScopeFilter}（用于离线比对，只记日志不影响请求）；</li>
 *   <li>{@link #reportMismatch} / {@link #reportTaintViolation} —— 比对不一致或染色捕到的越权行。</li>
 * </ol>
 *
 * <p><b>阶段语义</b>：
 * <ul>
 *   <li>P1 影子期 {@code blockOnViolation = false} —— 只记日志 + 计数，<b>绝不改变请求结果</b>；</li>
 *   <li>P2 双写期翻转为 {@code true} —— 违例直接抛 {@link AccessDeniedException}（fail-closed）。</li>
 * </ul>
 *
 * <p>这一翻转由配置驱动，是「双写取交集 → 应用层唯一权威」的开关点之一（13 文 §3 红线 1）。
 */
public final class AuthzShadowReporter {

    private final @NonNull AuthzMismatchMetrics metrics;
    private final boolean blockOnViolation;
    private final @Nullable AuthzSamplingPolicy sampling;

    public AuthzShadowReporter(@NonNull AuthzMismatchMetrics metrics, boolean blockOnViolation) {
        this(metrics, blockOnViolation, null);
    }

    /**
     * @param sampling 采样策略；{@code null} = 不采样（每次都做比对与染色）。P1 生产接 {@link AuthzSamplingPolicy}
     */
    public AuthzShadowReporter(@NonNull AuthzMismatchMetrics metrics,
                               boolean blockOnViolation,
                               @Nullable AuthzSamplingPolicy sampling) {
        this.metrics = metrics;
        this.blockOnViolation = blockOnViolation;
        this.sampling = sampling;
    }

    /**
     * 本次查询是否要做比对 / 染色（采样决策点）。
     *
     * <p>⚠️ <b>采样只决定「这次查询要不要看」，不决定「看到了要不要报」</b>：
     * 一旦进入被采样的查询，其中每一行的染色违例都会上报，绝不二次抽样 ——
     * 否则「AUTHZ_MISMATCH=0」会变成「抽样里没撞上」，而不是「真的没有」。
     */
    public boolean shouldEvaluate(@NonNull String resourceType, @Nullable String samplingKey) {
        return sampling == null || sampling.shouldSample(resourceType, samplingKey);
    }

    /** 当前生效的采样策略（{@code null} = 全量）。 */
    public @Nullable AuthzSamplingPolicy sampling() {
        return sampling;
    }

    /** 记录一次 Gate 决策结果（用于影子比对统计）。 */
    public void recordScopeEvaluation(@NonNull String resourceType, @NonNull ScopeFilter computed) {
        metrics.recordShadowSample(resourceType);
        if (computed instanceof ScopeFilter.Unrestricted) {
            metrics.recordUnrestricted(resourceType);
        }
    }

    /** 影子比对不一致（Gate 谓词 ≠ 实际查询绑定）。P1 仅记录。 */
    public void reportMismatch(@NonNull String resourceType, @NonNull String detail) {
        metrics.recordMismatch(resourceType, detail);
        if (blockOnViolation) {
            throw new AccessDeniedException("AUTHZ_MISMATCH", detail);
        }
    }

    /** 染色校验捕到越权行。P1 仅记录。 */
    public void reportTaintViolation(@NonNull String resourceType,
                                     @NonNull RowAttribution row,
                                     @NonNull ScopeFilter expected) {
        metrics.recordTaintViolation(resourceType, row, expected);
        if (blockOnViolation) {
            throw new AccessDeniedException("TAINT_VIOLATION",
                    "resourceType=" + resourceType + " rowTenant=" + row.tenantId());
        }
    }

    public boolean blockOnViolation() {
        return blockOnViolation;
    }

    /** 便捷：集合路径的 resourceType 命名（与仓储约定的类型字符串保持一致）。 */
    public static @NonNull String collection(@NonNull String type) {
        return type;
    }
}
