package cn.studykid.rag.infrastructure.shadow;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子比对 / 染色违例的上报桥（镜像平台 {@code AuthzShadowReporter}，D6）。
 *
 * <p>仓储在影子期（P1）调用本类上报两件事：
 * <ol>
 *   <li>{@link #recordShadowSample} —— 一次被观察的读路径样本；</li>
 *   <li>{@link #reportRlsBind} —— 一次仍走 RLS 兼容层（set_config GUC bind）的读路径；</li>
 *   <li>{@link #reportMismatch} / {@link #reportTaintViolation} —— 比对不一致或染色捕到的越权行。</li>
 * </ol>
 *
 * <p><b>阶段语义</b>：
 * <ul>
 *   <li>P1 影子期 {@code blockOnViolation = false} —— 只记日志 + 计数，<b>绝不改变请求结果</b>；</li>
 *   <li>P2 双写期翻转为 {@code true} —— 违例直接抛 {@link RagShadowViolationException}（fail-closed）。</li>
 * </ul>
 */
public final class RagShadowReporter {

    private final RagShadowMetrics metrics;
    private final boolean blockOnViolation;
    private final RagShadowSamplingPolicy sampling;

    public RagShadowReporter(@NonNull RagShadowMetrics metrics,
                             boolean blockOnViolation,
                             @Nullable RagShadowSamplingPolicy sampling) {
        this.metrics = metrics;
        this.blockOnViolation = blockOnViolation;
        this.sampling = sampling;
    }

    /** 本次查询是否要做比对 / 染色（采样决策点）。 */
    public boolean shouldEvaluate(@NonNull String resourceType, @Nullable String samplingKey) {
        return sampling == null || sampling.shouldSample(resourceType, samplingKey);
    }

    public void recordShadowSample(@NonNull String resourceType) {
        metrics.recordShadowSample(resourceType);
    }

    /**
     * 一次仍走 RLS 兼容层（set_config GUC bind）的读路径。
     *
     * <p>⚠️ 只计数，绝不阻断（即使 {@code blockOnViolation=true} 也不抛）：
     * 它是「退役 RLS 的迁移清单」条目，不是违例。
     */
    public void reportRlsBind(@NonNull String resourceType) {
        metrics.recordRlsBind(resourceType);
    }

    /** 影子比对不一致（显式谓词结果 ≠ RLS 结果）。P1 仅记录。 */
    public void reportMismatch(@NonNull String resourceType, @NonNull String detail) {
        metrics.recordMismatch(resourceType, detail);
        if (blockOnViolation) {
            throw new RagShadowViolationException("RAG_SHADOW_MISMATCH", detail);
        }
    }

    /** 染色校验捕到越权行（跨租户泄露）。P1 仅记录。 */
    public void reportTaintViolation(@NonNull String resourceType, @NonNull String rowTenantId) {
        metrics.recordTaintViolation(resourceType, rowTenantId);
        if (blockOnViolation) {
            throw new RagShadowViolationException("RAG_SHADOW_TAINT",
                    "resourceType=" + resourceType + " rowTenant=" + rowTenantId);
        }
    }

    public boolean blockOnViolation() {
        return blockOnViolation;
    }
}
