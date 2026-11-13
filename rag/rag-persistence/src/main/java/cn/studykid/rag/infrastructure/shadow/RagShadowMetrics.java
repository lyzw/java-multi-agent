package cn.studykid.rag.infrastructure.shadow;

import org.jspecify.annotations.NonNull;

/**
 * RAG 影子观察期（M2）观测指标。
 *
 * <p>与平台侧 D6 同构：应用层显式谓词与 RLS 同时生效，任何两者不一致都必须被量化。
 * 但 RAG 侧在 M3 已完成「显式谓词为唯一权威、GUC bind 降级为兼容层」
 * （见改造计划 RAG-3/RAG-4），因此 RAG 的 {@code rlsDependent} 口径 =
 * 仍依赖 RLS 兼容层（{@code set_config} 站点）的数量，
 * 由 {@link cn.studykid.rag.core.scope.RlsDependencyProbe} 提供权威计数。
 */
public interface RagShadowMetrics {

    /** 一次影子比对样本（每个被观察的读路径）。 */
    void recordShadowSample(@NonNull String resourceType);

    /** 一次仍走 RLS 兼容层（set_config GUC bind）的读路径。 */
    void recordRlsBind(@NonNull String resourceType);

    /** 影子比对不一致（显式谓词结果 ≠ RLS 结果）。仅记录，P1 不阻断。 */
    void recordMismatch(@NonNull String resourceType, @NonNull String detail);

    /** 染色校验捕到越权行（跨租户泄露）。 */
    void recordTaintViolation(@NonNull String resourceType, @NonNull String rowTenantId);

    long shadowSamples();

    long rlsBindCalls();

    long mismatches();

    long taintViolations();

    /**
     * 当前仍依赖 RLS 兜底的总站点数（来自 RlsDependencyProbe 静态预期）。
     * M4 逐批退役后递减，最终归 0 —— 这是 M2 出口门禁要读的量。
     */
    default long rlsDependent() {
        return cn.studykid.rag.core.scope.RlsDependencyProbe.expectedTotal();
    }
}
