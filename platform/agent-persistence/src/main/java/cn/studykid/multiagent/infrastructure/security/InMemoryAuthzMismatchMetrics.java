package cn.studykid.multiagent.infrastructure.security;

import java.util.concurrent.atomic.AtomicLong;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.jspecify.annotations.NonNull;

/**
 * 内存实现 —— 单测与本地验证用（13 文 §5.2 验收矩阵）。
 *
 * <p>线程安全（原子计数），可被并发查询路径共享。
 */
public final class InMemoryAuthzMismatchMetrics implements AuthzMismatchMetrics {

    private final AtomicLong shadowSamples = new AtomicLong();
    private final AtomicLong unrestrictedUsages = new AtomicLong();
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicLong taintViolations = new AtomicLong();

    @Override
    public void recordShadowSample(@NonNull String resourceType) {
        shadowSamples.incrementAndGet();
    }

    @Override
    public void recordUnrestricted(@NonNull String resourceType) {
        unrestrictedUsages.incrementAndGet();
    }

    @Override
    public void recordMismatch(@NonNull String resourceType, @NonNull String detail) {
        mismatches.incrementAndGet();
    }

    @Override
    public void recordTaintViolation(@NonNull String resourceType,
                                     @NonNull RowAttribution row,
                                     @NonNull ScopeFilter expected) {
        taintViolations.incrementAndGet();
    }

    @Override
    public long shadowSamples() {
        return shadowSamples.get();
    }

    @Override
    public long unrestrictedUsages() {
        return unrestrictedUsages.get();
    }

    @Override
    public long mismatches() {
        return mismatches.get();
    }

    @Override
    public long taintViolations() {
        return taintViolations.get();
    }
}
