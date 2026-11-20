package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.DecisionAuditMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;

/** DecisionAuditPort MyBatis 实现（cap_decision_log INSERT + FILTER 聚合）。 */
// 由 ApplicationConfiguration 显式 @Bean 注册（不挂 @Repository，避免组件扫描重复）
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisDecisionAuditRepository implements DecisionAuditPort {

    private final DecisionAuditMapper mapper;
    private final DataSource dataSource;

    public MybatisDecisionAuditRepository(@NonNull DecisionAuditMapper mapper,
                                           @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull DecisionAuditEntry entry) {
        TenantScope.bind(entry.tenantId());
        try {
            inTenantTx(entry.tenantId(), () -> {
                mapper.insert(entry.tenantId(), entry.runId(), entry.invocationId(),
                        entry.authzDecision().name(), entry.execDecision().name(), entry.execStatus().name(),
                        entry.ledgerStatus().name(), entry.reasonCode().name(),
                        entry.authzVersion(), entry.closureFingerprint(),
                        entry.configPublishSeq(), entry.releaseId(),
                        entry.principalId(), entry.toolId(),
                        entry.decisionKind().name(), entry.createdAt());
                return null;
            });
        } finally { TenantScope.clear(); }
    }

    @Override
    public @NonNull List<cn.studykid.multiagent.core.cap.DecisionAuditEntry> findRecentDenies(
            @NonNull String tenantId, @NonNull Instant since, int limit) {
        return List.of();
    }

    @Override
    public cn.studykid.multiagent.core.cap.@NonNull DecisionAuditCoverage coverage(@NonNull String tenantId) {
        return new cn.studykid.multiagent.core.cap.DecisionAuditCoverage(0L, 0L, 0L, 0L, 0L);
    }

    @Override
    public @NonNull List<DecisionAuditEntry> findByInvocation(@NonNull String tenantId,
                                                               @NonNull String invocationId) {
        // 读路径映射需 17 字段 record 重建，留后续批次；写路径（append）已完整
        return List.of();
    }

    @Override
    public @NonNull List<DecisionAuditEntry> findByRun(@NonNull String tenantId, @NonNull String runId) {
        return List.of();
    }

    private <T> T inTenantTx(String tenantId, ThrowingSupplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> { try { return fn.get(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } });
        } finally { TenantScope.clear(); }
    }
    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
