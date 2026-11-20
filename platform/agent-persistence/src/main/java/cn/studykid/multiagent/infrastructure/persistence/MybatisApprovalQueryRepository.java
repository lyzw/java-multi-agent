package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ApprovalQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort.ApprovalItem;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort.ApprovalDecision;
import cn.studykid.multiagent.ports.persistence.Page;

/** ApprovalQueryPort MyBatis 实现。 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisApprovalQueryRepository implements ApprovalQueryPort {

    private final ApprovalQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisApprovalQueryRepository(@NonNull ApprovalQueryMapper mapper,
                                           @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<ApprovalItem> list(@NonNull String tenantId, @Nullable String state,
                                              @Nullable String cursor, int limit) {
        TenantScope.bind(tenantId);
        try {
            Optional<PageCursor> pc = (cursor == null || cursor.isBlank())
                    ? Optional.empty() : Optional.of(PageCursor.decode(cursor));
            String cursorId = pc.map(PageCursor::id).orElse(null);
            List<ApprovalItem> items = TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.list(tenantId, state, cursorId, limit + 1).stream()
                            .map(this::toItem).toList());
            boolean hasMore = items.size() > limit;
            List<ApprovalItem> page = items.stream().limit(limit).toList();
            String nextCursor = null;
            if (hasMore && !page.isEmpty()) {
                nextCursor = new PageCursor(page.get(page.size()-1).createdAt(),
                        page.get(page.size()-1).approvalId()).encode();
            }
            return new Page<>(page, nextCursor);
        } finally { TenantScope.clear(); }
    }

    @Override
    public @Nullable ApprovalItem find(@NonNull String tenantId, @NonNull String approvalId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.find(tenantId, approvalId).map(this::toItem).orElse(null));
        } finally { TenantScope.clear(); }
    }

    @Override
    public @NonNull ApprovalDecision decide(@NonNull String tenantId, @NonNull String approvalId,
                                              boolean approve, @NonNull String approverId,
                                              @Nullable String reason) {
        TenantScope.bind(tenantId);
        try {
            String newState = approve ? "APPROVED" : "REJECTED";
            TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> { mapper.decide(tenantId, approvalId, newState, approverId); return null; });
            return new ApprovalDecision(approvalId, newState);
        } finally { TenantScope.clear(); }
    }

    private ApprovalItem toItem(ApprovalQueryMapper.ApprovalRow r) {
        boolean expired = r.expiresAt() != null && r.expiresAt().isBefore(java.time.Instant.now());
        return new ApprovalItem(r.approvalId(), r.operationId(), r.runId(), r.state(),
                r.requirement(), r.argumentDigest(), r.targetResource(), r.approverScope(),
                r.expiresAt(), r.approverId(), r.decidedAt(), r.createdAt(), expired);
    }
}
