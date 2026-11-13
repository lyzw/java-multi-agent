package cn.studykid.rag.infrastructure.shadow;

import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存版影子指标（默认实现；同时也是生产实现，DEBUG 级日志输出明细）。
 *
 * <p>无 Micrometer 依赖，计数存于 JVM 内存；周报/启动自检通过 {@link #snapshot()} 读取。
 */
public class InMemoryRagShadowMetrics implements RagShadowMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryRagShadowMetrics.class);

    private final AtomicLong shadowSamples = new AtomicLong();
    private final AtomicLong rlsBindCalls = new AtomicLong();
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicLong taintViolations = new AtomicLong();

    @Override
    public void recordShadowSample(@NonNull String resourceType) {
        shadowSamples.incrementAndGet();
    }

    @Override
    public void recordRlsBind(@NonNull String resourceType) {
        rlsBindCalls.incrementAndGet();
    }

    @Override
    public void recordMismatch(@NonNull String resourceType, @NonNull String detail) {
        long n = mismatches.incrementAndGet();
        LOG.debug("[rag-shadow] mismatch resourceType={} total={} detail={}", resourceType, n, detail);
    }

    @Override
    public void recordTaintViolation(@NonNull String resourceType, @NonNull String rowTenantId) {
        long n = taintViolations.incrementAndGet();
        LOG.debug("[rag-shadow] taint resourceType={} total={} rowTenant={}", resourceType, n, rowTenantId);
    }

    @Override
    public long shadowSamples() {
        return shadowSamples.get();
    }

    @Override
    public long rlsBindCalls() {
        return rlsBindCalls.get();
    }

    @Override
    public long mismatches() {
        return mismatches.get();
    }

    @Override
    public long taintViolations() {
        return taintViolations.get();
    }

    /** 单行快照，供周报/日志输出。 */
    public String snapshot() {
        return "ragShadow[samples=" + shadowSamples.get()
                + " rlsBind=" + rlsBindCalls.get()
                + " mismatches=" + mismatches.get()
                + " taint=" + taintViolations.get()
                + " rlsDependent=" + rlsDependent() + "]";
    }
}
