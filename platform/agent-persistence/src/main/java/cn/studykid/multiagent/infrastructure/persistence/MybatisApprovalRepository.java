package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ApprovalMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ApprovalMapper.ApprovalRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.ApprovalPort.ApprovalOutcome;
import cn.studykid.multiagent.ports.persistence.ApprovalPort.ApprovalView;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/** ApprovalPort MyBatis 实现（extends AuditPort，SQL 零改动搬运）。 */
// 由 ApplicationConfiguration 显式 @Bean 注册（不挂 @Repository，避免组件扫描重复）
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisApprovalRepository implements ApprovalPort {

    private final ApprovalMapper mapper;
    private final DataSource dataSource;

    public MybatisApprovalRepository(@NonNull ApprovalMapper mapper,
                                      @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull String grant(@NonNull String tenantId, @NonNull String operationId,
                                  @NonNull String argumentDigest, @NonNull String targetResource,
                                  @NonNull ApprovalRequirement requirement, @NonNull String approverScope,
                                  @NonNull Instant expiresAt, @NonNull String approverId) {
        String state = inTenantTx(tenantId, () -> {
            Optional<ApprovalRow> existing = mapper.findApproval(tenantId, operationId);
            if (existing.isPresent()) return existing.get().state();
            mapper.insertApproval(tenantId, operationId, requirement.name(), argumentDigest,
                    targetResource, approverScope, approverId, expiresAt);
            return "PENDING";
        });
        return mapper.findApproval(tenantId, operationId).map(ApprovalRow::approvalId)
                .orElseThrow(() -> new IllegalStateException("grant 后查不到"));
    }

    @Override
    public @NonNull Optional<String> request(@NonNull String tenantId, @NonNull String operationId,
                                               @NonNull String argumentDigest, @NonNull String targetResource,
                                               @NonNull ApprovalRequirement requirement,
                                               @NonNull String approverScope, @NonNull Instant expiresAt) {
        return inTenantTx(tenantId, () -> {
            Optional<ApprovalRow> existing = mapper.findApproval(tenantId, operationId);
            if (existing.isPresent()) return Optional.of(existing.get().approvalId());
            return mapper.insertApprovalExistingOp(tenantId, operationId, requirement.name(),
                    argumentDigest, targetResource, approverScope, expiresAt);
        });
    }

    @Override
    public @NonNull ApprovalOutcome consume(@NonNull String tenantId, @NonNull String operationId,
                                              @NonNull String argumentDigest, @NonNull String targetResource,
                                              @NonNull String runId) {
        return inTenantTx(tenantId, () -> {
            Optional<ApprovalRow> row = mapper.findApproval(tenantId, operationId);
            if (row.isEmpty()) return ApprovalOutcome.NO_APPROVAL;
            ApprovalRow r = row.get();
            if (r.approverId() == null) return ApprovalOutcome.NOT_APPROVED;
            if (r.expiresAt() != null && r.expiresAt().isBefore(Instant.now())) {
                mapper.expireApproval(tenantId, operationId);
                return ApprovalOutcome.EXPIRED;
            }
            mapper.consumeApproval(tenantId, operationId);
            return ApprovalOutcome.CONSUMED;
        });
    }

    @Override
    public void revoke(@NonNull String tenantId, @NonNull String operationId,
                        @NonNull String approverId, @Nullable String reason) {
        inTenantTx(tenantId, () -> {
            mapper.rejectApproval(tenantId, operationId, approverId);
            return null;
        });
    }

    @Override
    public @NonNull Optional<ApprovalView> findActive(@NonNull String tenantId,
                                                       @NonNull String operationId) {
        return inTenantTx(tenantId,
                () -> mapper.findApproval(tenantId, operationId)
                        .filter(r -> !"REJECTED".equals(r.state()) && !"CONSUMED".equals(r.state()))
                        .map(r -> new ApprovalView(r.approvalId(), r.operationId(),
                                mapOutcome(r.state()), r.requirement(), r.expiresAt(), r.approverId())));
    }

    private ApprovalOutcome mapOutcome(String state) {
        if (state == null) return ApprovalOutcome.NOT_APPROVED;
        return switch (state) {
            case "APPROVED", "CONSUMED" -> ApprovalOutcome.CONSUMED;
            case "REJECTED" -> ApprovalOutcome.REJECTED;
            case "EXPIRED" -> ApprovalOutcome.EXPIRED;
            default -> ApprovalOutcome.NOT_APPROVED;
        };
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
