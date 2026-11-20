package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.CapApprovalMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.CapApprovalMapper.ApprovalRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

/** CapApprovalPort MyBatis 实现（21 列 + CAS 状态机 6 步）。 */
// 由 ApplicationConfiguration 显式 @Bean 注册
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapApprovalRepository implements CapApprovalPort {

    private final CapApprovalMapper mapper;
    private final DataSource dataSource;

    public MybatisCapApprovalRepository(@NonNull CapApprovalMapper mapper,
                                         @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull String grant(@NonNull CapApprovalGrant g) {
        Instant now = Instant.now();
        return inTx(g.tenantId(), () -> mapper.insertPending(g.tenantId(), g.principalId(), g.toolId(),
                        g.targetResource(), g.approvalScopeHash(), g.scopeDefinition(),
                        g.effectiveArgsHash(), g.closureFingerprint(), g.authzVersion(),
                        g.approverId(), g.approverScope(), now, g.expiresAt(), now, now)
                .orElseThrow(() -> new IllegalStateException("cap_approval 插入未返回 id")));
    }

    @Override
    public @NonNull String requestHumanApproval(@NonNull CapApprovalRequest r) {
        Instant now = Instant.now();
        return inTx(r.tenantId(), () -> mapper.insertAwaitingHuman(r.tenantId(), r.principalId(),
                        r.toolId(), r.targetResource(), r.approvalScopeHash(), r.scopeDefinition(),
                        r.effectiveArgsHash(), r.closureFingerprint(), r.authzVersion(),
                        r.approverScope(), r.expiresAt(), now, now)
                .orElseThrow(() -> new IllegalStateException("cap_approval 插入未返回 id")));
    }

    @Override
    public @NonNull ReservationOutcome reserve(@NonNull String tenantId, @NonNull String approvalId,
                                                 @NonNull String invocationId, @NonNull String operationId,
                                                 @Nullable String runId, @NonNull String effectiveArgsHash,
                                                 @NonNull String closureFingerprint, long authzVersion) {
        return inTx(tenantId, () -> {
            Optional<ApprovalRow> existing = mapper.findById(tenantId, approvalId);
            if (existing.isEmpty()) return ReservationOutcome.NOT_FOUND;
            ApprovalRow a = existing.get();
            // 先判状态给出可区分结论（与 CAS 结果不矛盾）
            switch (a.approvalState()) {
                case "AWAITING_HUMAN" -> { return ReservationOutcome.AWAITING_HUMAN_DECISION; }
                case "CANCELLED" -> { return ReservationOutcome.CANCELLED; }
                case "CONSUMED" -> { return ReservationOutcome.ALREADY_CONSUMED; }
                case "INTENT_SUBMITTED" -> { return ReservationOutcome.INTENT_SUBMITTED; }
                case "RESERVED" -> {
                    return invocationId.equals(a.invocationId())
                            ? ReservationOutcome.ALREADY_RESERVED_BY_SAME
                            : ReservationOutcome.ALREADY_RESERVED_BY_OTHER;
                }
                default -> { /* PENDING 继续 CAS */ }
            }
            if (a.expiresAt() != null && a.expiresAt().isBefore(Instant.now())) {
                return ReservationOutcome.EXPIRED;
            }
            int updated = mapper.reserve(invocationId, operationId, runId, tenantId, approvalId,
                    effectiveArgsHash, closureFingerprint, authzVersion);
            if (updated > 0) return ReservationOutcome.RESERVED;
            // CAS 失败：再读一次给出原因
            return mapper.findById(tenantId, approvalId)
                    .map(x -> {
                        if (invocationId.equals(x.invocationId())) {
                            return ReservationOutcome.ALREADY_RESERVED_BY_SAME;
                        }
                        if (x.expiresAt() != null && x.expiresAt().isBefore(Instant.now())) {
                            return ReservationOutcome.EXPIRED;
                        }
                        return ReservationOutcome.ALREADY_RESERVED_BY_OTHER;
                    })
                    .orElse(ReservationOutcome.NOT_FOUND);
        });
    }

    @Override
    public boolean markIntentSubmitted(@NonNull String tenantId, @NonNull String approvalId,
                                        @NonNull String invocationId) {
        return inTx(tenantId, () -> {
            if (mapper.markIntent(tenantId, approvalId, invocationId) > 0) return true;
            return mapper.markIntentIdempotent(tenantId, approvalId, invocationId) > 0;
        });
    }

    @Override
    public boolean consume(@NonNull String tenantId, @NonNull String approvalId,
                            @NonNull String invocationId, @NonNull String terminalStatus) {
        return inTx(tenantId, () -> {
            if (mapper.consume(tenantId, approvalId, invocationId, terminalStatus) > 0) return true;
            return mapper.consumeIdempotent(tenantId, approvalId, invocationId) > 0;
        });
    }

    @Override
    public boolean cancel(@NonNull String tenantId, @NonNull String approvalId,
                           @NonNull String invocationId, @NonNull String reason) {
        return inTx(tenantId, () -> mapper.cancel(tenantId, approvalId, invocationId, reason) > 0);
    }

    @Override
    public @NonNull Optional<CapApproval> findById(@NonNull String tenantId, @NonNull String approvalId) {
        return inTx(tenantId, () -> mapper.findById(tenantId, approvalId).map(this::toDomain));
    }

    @Override
    public @NonNull List<CapApproval> findByTool(@NonNull String tenantId, @NonNull String toolId) {
        return inTx(tenantId, () -> mapper.findByTool(tenantId, toolId).stream()
                .map(this::toDomain).toList());
    }

    @Override
    public @NonNull Optional<CapApproval> findByInvocation(@NonNull String tenantId,
                                                             @NonNull String invocationId) {
        return inTx(tenantId, () -> mapper.findByInvocation(tenantId, invocationId).stream()
                .findFirst().map(this::toDomain));
    }

    private CapApproval toDomain(ApprovalRow r) {
        return new CapApproval(r.approvalId(), r.tenantId(), r.principalId(), r.runId(),
                r.invocationId(), r.operationId(), r.toolId(), r.targetResource(),
                r.approvalScopeHash(), r.scopeDefinition(), r.effectiveArgsHash(),
                r.closureFingerprint(), r.authzVersion(), r.approverId(), r.approverScope(),
                r.decidedAt(), r.expiresAt(), CapApproval.ApprovalState.valueOf(r.approvalState()),
                r.terminalStatus(), r.cancelReason(), r.createdAt(), r.updatedAt());
    }

    private <T> T inTx(String tenantId, java.util.function.Supplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L), conn -> fn.get());
        } finally {
            TenantScope.clear();
        }
    }
}
