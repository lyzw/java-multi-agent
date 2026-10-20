package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 审批查询与决策实现（P4-D13 / G8 · FR-014）。
 *
 * <p>⚠️ <b>为什么决定走 UPDATE 而不是 {@code ApprovalPort.grant/revoke}</b>：
 * {@code ApprovalPort} 是<b>执行侧</b>的接口，其 {@code grant} 的语义是
 * 「审批权威授予 APPROVED」，且要求调用方自带 {@code argumentDigest} /
 * {@code targetResource} 等绑定参数——治理台拿到的是<b>待批列表</b>，
 * 重新回填这些字段等于让前端传回绑定值，那正是 P3-D05 防的「审批后篡改参数」形态。
 * 故本实现从数据库读回当前行做校验与写入，不接受前端回传的绑定字段。
 *
 * <p>⚠️ <b>终态由数据库触发器兜底</b>：{@code enforce_approval_state_guard}
 * 拒绝任何从 CONSUMED/REJECTED/EXPIRED 的迁出。本实现的显式校验是
 * <b>为了让调用方拿到 409 而不是 500</b>，而非替代触发器。
 */
@Repository
public class JdbcApprovalQueryRepository implements ApprovalQueryPort {

    private static final String SQL_SELECT = """
            SELECT a.approval_id, a.operation_id, a.state, a.requirement,
                   a.argument_digest, a.target_resource, a.approver_scope,
                   a.expires_at, a.approver_id, a.decided_at, a.created_at,
                   o.run_id
            FROM approval a
            JOIN tool_operation o
              ON o.tenant_id = a.tenant_id AND o.operation_id = a.operation_id
            WHERE a.tenant_id = ?::uuid
            """;

    private final DataSource dataSource;
    private final AuditPort audit;
    private final Clock clock;

    public JdbcApprovalQueryRepository(@NonNull DataSource dataSource,
                                       @NonNull AuditPort audit,
                                       @NonNull Clock clock) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public @NonNull Page<ApprovalItem> list(@NonNull String tenantId,
                                            @Nullable String state,
                                            @Nullable String cursor,
                                            int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_SELECT);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (state != null) {
            sql.append(" AND a.state = ?::approval_state");
            params.add(state);
        }
        if (after != null) {
            sql.append(" AND (a.created_at < ? OR (a.created_at = ? AND a.approval_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY a.created_at DESC, a.approval_id DESC LIMIT ?");
        params.add(limit + 1);

        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<ApprovalItem> rows = new ArrayList<>(limit);
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(read(rs));
                    }
                }
            }
            String next = null;
            if (rows.size() > limit) {
                rows.remove(rows.size() - 1);
                ApprovalItem last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.approvalId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批列表失败", e);
        }
    }

    @Override
    public @Nullable ApprovalItem find(@NonNull String tenantId, @NonNull String approvalId) {
        String sql = SQL_SELECT + " AND a.approval_id = ?::uuid";
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, approvalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批失败", e);
        }
    }

    @Override
    public @NonNull ApprovalDecision decide(@NonNull String tenantId,
                                             @NonNull String approvalId,
                                             @NonNull boolean approve,
                                             @NonNull String approverId,
                                             @Nullable String reason) {
        if (!approve && (reason == null || reason.isBlank())) {
            // ⛔ 拒绝必须有理由：没有理由的拒绝在事后追责时等于没有决定
            throw new ApprovalDecisionException("拒绝审批必须填写理由", "PENDING");
        }
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                ApprovalItem current = lockAndRead(conn, tenantId, approvalId);
                if (current == null) {
                    // ⚠️ 不存在 / 不属于本租户 → null，交由端点层转 404（与无权限合并）
                    conn.rollback();
                    throw new ApprovalQueryPort.ApprovalNotFoundException(approvalId);
                }
                // ⛔⛔ 过期审批**不得批准**（FE-01 §P8：过期/撤销须拒绝而非静默通过）
                if (current.expired()) {
                    throw new ApprovalDecisionException(
                            "审批已过期，不得批准（expires_at=" + current.expiresAt() + "）",
                            current.state());
                }
                if (!"PENDING".equals(current.state())) {
                    throw new ApprovalDecisionException(
                            "审批当前状态为 " + current.state() + "，不可重复决定", current.state());
                }

                String newState = approve ? "APPROVED" : "REJECTED";
                update(conn, tenantId, approvalId, newState, approverId, reason);
                conn.commit();

                audit.recordRequired(tenantId, approverId,
                        approve ? AuditPort.Actions.APPROVAL_GRANT
                                : AuditPort.Actions.APPROVAL_REVOKE,
                        "approval", approvalId,
                        java.util.Map.of("operationId", current.operationId(),
                                "runId", current.runId(),
                                "targetResource", current.targetResource()));
                return new ApprovalDecision(approvalId, newState);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("审批决定失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审批决定失败", e);
        }
    }

    private ApprovalItem lockAndRead(Connection conn, String tenantId, String approvalId)
            throws SQLException {
        String sql = SQL_SELECT + " AND a.approval_id = ?::uuid FOR UPDATE OF a";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, approvalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private void update(Connection conn, String tenantId, String approvalId,
                        String newState, String approverId, String reason) throws SQLException {
        // ⚠️ CHECK (approval_decided_consistent) 要求 decided_at IS NOT NULL 一并写入
        String sql = """
                UPDATE approval
                SET state = ?::approval_state, approver_id = ?, decided_at = now()
                WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newState);
            ps.setString(2, approverId);
            ps.setString(3, tenantId);
            ps.setString(4, approvalId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new IllegalStateException("审批更新行数异常: " + n);
            }
        }
    }

    private ApprovalItem read(ResultSet rs) throws SQLException {
        Instant now = clock.instant();
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        return new ApprovalItem(
                rs.getString("approval_id"),
                rs.getString("operation_id"),
                rs.getString("run_id"),
                rs.getString("state"),
                rs.getString("requirement"),
                rs.getString("argument_digest"),
                rs.getString("target_resource"),
                rs.getString("approver_scope"),
                expiresAt,
                rs.getString("approver_id"),
                instantOrNull(rs.getTimestamp("decided_at")),
                rs.getTimestamp("created_at").toInstant(),
                // ⚠️ expired 由**服务端**算（前端时钟不可信），且只有 PENDING 才算过期：
                //    已决定/已消费的审批过期与否不改变其终态。
                "PENDING".equals(rs.getString("state")) && !expiresAt.isAfter(now));
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}
