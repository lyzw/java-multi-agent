package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLF4J 实现 —— 生产观测用（13 文 §5.2 验收矩阵）。
 *
 * <p>在 {@link InMemoryAuthzMismatchMetrics} 计数基础上，对 mismatch / taint 违例
 * 打 WARN 级结构化日志，便于告警订阅（高敏接口 100% 染色 + 异常告警，12 文 §7.1 R7）。
 * 生产出口门禁要求 {@code AUTHZ_MISMATCH = 0}，故任何 WARN 都应触发值守介入。
 */
public final class Slf4jAuthzMismatchMetrics implements AuthzMismatchMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(Slf4jAuthzMismatchMetrics.class);

    private final InMemoryAuthzMismatchMetrics delegate = new InMemoryAuthzMismatchMetrics();

    @Override
    public void recordShadowSample(@NonNull String resourceType) {
        delegate.recordShadowSample(resourceType);
    }

    @Override
    public void recordUnrestricted(@NonNull String resourceType) {
        delegate.recordUnrestricted(resourceType);
        LOG.debug("[authz] Unrestricted 使用 resourceType={}", resourceType);
    }

    @Override
    public void recordMismatch(@NonNull String resourceType, @NonNull String detail) {
        delegate.recordMismatch(resourceType, detail);
        LOG.warn("[authz-mismatch] resourceType={} detail={}", resourceType, detail);
    }

    @Override
    public void recordTaintViolation(@NonNull String resourceType,
                                     @NonNull RowAttribution row,
                                     @NonNull ScopeFilter expected) {
        delegate.recordTaintViolation(resourceType, row, expected);
        LOG.warn("[authz-taint] 越权行被返回 resourceType={} rowTenant={} shared={} expected={}",
                resourceType, row.tenantId(), row.shared(), expected);
    }

    @Override
    public long shadowSamples() {
        return delegate.shadowSamples();
    }

    @Override
    public long unrestrictedUsages() {
        return delegate.unrestrictedUsages();
    }

    @Override
    public long mismatches() {
        return delegate.mismatches();
    }

    @Override
    public long taintViolations() {
        return delegate.taintViolations();
    }
}
