package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;

/**
 * UNKNOWN 操作查询与人工处置实现（P4-D13 / G9）。
 *
 * <p>⛔ <b>本类不存在任何把状态改回 {@code PENDING}/{@code IN_FLIGHT} 的路径</b>。
 * UNKNOWN 的定义是「副作用是否发生<b>未知</b>」，
 * 此时重放 = 可能在学生数据上重复施加副作用（Sev-0）。
 * 处置的三个出口全部是<b>终态归档</b>。
 */
@Repository
public class JdbcUnknownOperationRepository implements UnknownOperationPort {

    private static final String SQL_SELECT = """
            SELECT operation_id, run_id, tool_name, risk_level, state,
                   created_at, updated_at, reconciliation_source,
                   manual_disposed_by, manual_disposed_at
            FROM tool_operation
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;
    private final AuditPort audit;

    public JdbcUnknownOperationRepository(@NonNull DataSource dataSource,
                                          @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull Page<OperationItem> list(@NonNull String tenantId,
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
            sql.append(" AND state = ?::tool_operation_state");
            params.add(state);
        }
        if (after != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND operation_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, operation_id DESC LIMIT ?");
        params.add(limit + 1);

        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<OperationItem> rows = new ArrayList<>(limit);
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
                OperationItem last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.operationId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询操作列表失败", e);
        }
    }

    @Override
    public @Nullable OperationItem find(@NonNull String tenantId, @NonNull String operationId) {
        String sql = SQL_SELECT + " AND operation_id = ?::uuid";
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询操作失败", e);
        }
    }

    @Override
    public @NonNull DispositionResult dispose(@NonNull String tenantId,
                                             @NonNull String operationId,
                                             @NonNull Disposition disposition,
                                             @NonNull String operatorId,
                                             @Nullable String note) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                OperationItem current = lockAndRead(conn, tenantId, operationId);
                if (current == null) {
                    conn.rollback();
                    throw new UnknownOperationPort.OperationNotFoundException(operationId);
                }
                // ⛔ 非 UNKNOWN 态一律拒绝：重复处置、或已被 P3-D03 自动对账接管
                if (!"UNKNOWN".equals(current.state())) {
                    throw new NotInUnknownStateException(current.state());
                }
                Instant now = Instant.now();
                update(conn, tenantId, operationId, disposition, operatorId, note, now);
                conn.commit();

                audit.recordRequired(tenantId, operatorId, "UNKNOWN_DISPOSE",
                        "tool_operation", operationId,
                        java.util.Map.of("disposition", disposition.name(),
                                "runId", current.runId(),
                                "toolName", current.toolName()));
                return new DispositionResult(operationId, disposition.name(), operatorId, now);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("UNKNOWN 处置失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("UNKNOWN 处置失败", e);
        }
    }

    private OperationItem lockAndRead(Connection conn, String tenantId, String operationId)
            throws SQLException {
        String sql = SQL_SELECT + " AND operation_id = ?::uuid FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private void update(Connection conn, String tenantId, String operationId,
                        Disposition disposition, String operatorId, String note, Instant now)
            throws SQLException {
        // ⚠️ reconciliation_source='MANUAL' 与 P3-D03 的自动对账来源可区分，
        //    事后能回答「这条是系统判的，还是人判的」。
        String sql = """
                UPDATE tool_operation
                SET state = ?::tool_operation_state,
                    reconciled_at = ?,
                    reconciliation_source = 'MANUAL',
                    manual_disposed_by = ?,
                    manual_disposed_at = ?,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, disposition.name());
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, note == null ? operatorId : operatorId + ": " + note);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, tenantId);
            ps.setString(6, operationId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new IllegalStateException("操作更新行数异常: " + n);
            }
        }
    }

    private OperationItem read(ResultSet rs) throws SQLException {
        return new OperationItem(
                rs.getString("operation_id"),
                rs.getString("run_id"),
                rs.getString("tool_name"),
                rs.getString("risk_level"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("reconciliation_source"),
                rs.getTimestamp("manual_disposed_at") != null);
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
