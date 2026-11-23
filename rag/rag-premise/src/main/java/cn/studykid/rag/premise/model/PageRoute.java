package cn.studykid.rag.premise.model;

import org.jspecify.annotations.NonNull;

/**
 * 单页路由决策（D28 P1-1 契约：`PageRoute(profile, reason, confidence)`）。
 *
 * <p><b>⚠️ reason 必须可解释</b>：写入 {@code ParseTrace.routeReason} 与
 * {@code rag_ingest_job.route_trace}，让「这页为什么升级/隔离」可回溯。
 * 禁止只写 "complex" 之类的笼统标签。
 *
 * <p>⚠️ 顶层类型（不嵌套）。
 *
 * @param pageNumber  1 起物理页码
 * @param profile     路由档位
 * @param reason      机器可读原因（如 {@code formula_dense} / {@code low_confidence=0.62}）
 * @param confidence  决策置信度 [0,1]（信号越明确越高）
 * @param qualityScore 轻量通道解析质量分 [0,1]（D28 P1-2）
 */
public record PageRoute(
        int pageNumber,
        @NonNull RouteProfile profile,
        @NonNull String reason,
        double confidence,
        double qualityScore) {

    public PageRoute {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber 必须 >= 1");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空（路由必须可解释）");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 [0,1]");
        }
        if (Double.isNaN(qualityScore) || qualityScore < 0.0 || qualityScore > 1.0) {
            throw new IllegalArgumentException("qualityScore 必须在 [0,1]");
        }
    }

    /** @return 人类可读的留痕串（写入 ParseTrace.warnings / routeReason） */
    public @NonNull String traceLine() {
        return "page=" + pageNumber + " profile=" + profile
                + " reason=" + reason
                + " confidence=" + String.format(java.util.Locale.ROOT, "%.2f", confidence)
                + " quality=" + String.format(java.util.Locale.ROOT, "%.2f", qualityScore);
    }
}
