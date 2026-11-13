package cn.studykid.rag.infrastructure.shadow;

import org.springframework.beans.factory.DisposableBean;

import org.jspecify.annotations.NonNull;

/**
 * 影子观测采集器（Spring Bean，仅在 {@code rag.shadow.enabled=true} 时装配）。
 *
 * <p>通过静态门面 {@link RagShadow} 暴露给各仓储的采集点；禁用时门面返回 no-op，
 * 保证影子态默认关闭时<b>零行为变更</b>。销毁时自动注销，避免跨重启残留静态引用。
 */
public class RagShadowCollector implements DisposableBean {

    private final RagShadowMetrics metrics;
    private final RagShadowReporter reporter;

    public RagShadowCollector(@NonNull RagShadowMetrics metrics, @NonNull RagShadowReporter reporter) {
        this.metrics = metrics;
        this.reporter = reporter;
    }

    /** 由 {@code RagShadowConfiguration} 在工厂方法内调用，完成静态注册。 */
    void attach() {
        RagShadow.register(this);
    }

    @Override
    public void destroy() {
        RagShadow.unregister();
    }

    public RagShadowMetrics metrics() {
        return metrics;
    }

    public RagShadowReporter reporter() {
        return reporter;
    }

    /** 单行快照，供周报/启动日志输出。 */
    public String snapshot() {
        if (metrics instanceof InMemoryRagShadowMetrics m) {
            return m.snapshot();
        }
        return "ragShadow[samples=" + metrics.shadowSamples()
                + " rlsBind=" + metrics.rlsBindCalls()
                + " mismatches=" + metrics.mismatches()
                + " taint=" + metrics.taintViolations()
                + " rlsDependent=" + metrics.rlsDependent() + "]";
    }
}
