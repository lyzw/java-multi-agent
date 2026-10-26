package cn.studykid.multiagent.infrastructure.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;

/**
 * 平台审计 JDBC 仓储（重规划 §5.3 / §12）。
 *
 * <p>仅追加写 {@code platform.platform_audit_event}（无 {@code tenant_id}），
 * 与租户侧 {@code audit_record} 分域。查询供 {@code /api/platform/v1/audit-events}
 * 使用，仅平台审计权限可访问。
 */
@Repository
public class JdbcPlatformAuditRepository implements PlatformAuditPort {

    private final DataSource dataSource;

    public JdbcPlatformAuditRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(@NonNull PlatformAuditEvent event) {
        String sql = """
                INSERT INTO platform.platform_audit_event
                    (id, actor_id, actor_type, action, target_type, target_id,
                     decision, reason_code, correlation_id, ip, occurred_at)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?, ?, ?::uuid, ?, ?)
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.id().toString());
            ps.setString(2, event.actorId());
            ps.setString(3, event.actorType());
            ps.setString(4, event.action());
            ps.setString(5, event.targetType());
            ps.setString(6, event.targetId());
            ps.setString(7, event.decision());
            ps.setString(8, event.reasonCode());
            ps.setString(9, event.correlationId() == null ? null : event.correlationId().toString());
            ps.setString(10, event.ip());
            ps.setTimestamp(11, Timestamp.from(event.occurredAt()));
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入平台审计事件失败", e);
        }
    }

    @Override
    public @NonNull List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, actor_id, actor_type, action, target_type, target_id, "
                        + "decision, reason_code, correlation_id, ip, occurred_at "
                        + "FROM platform.platform_audit_event WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (query.actorId() != null && !query.actorId().isBlank()) {
            sql.append(" AND actor_id = ?::uuid");
            params.add(query.actorId());
        }
        if (query.action() != null && !query.action().isBlank()) {
            sql.append(" AND action = ?");
            params.add(query.action());
        }
        if (query.targetType() != null && !query.targetType().isBlank()) {
            sql.append(" AND target_type = ?");
            params.add(query.targetType());
        }
        if (query.targetId() != null && !query.targetId().isBlank()) {
            sql.append(" AND target_id = ?::uuid");
            params.add(query.targetId());
        }
        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at <= ?");
            params.add(Timestamp.from(query.to()));
        }
        sql.append(" ORDER BY occurred_at DESC, id LIMIT ?");
        params.add(query.limit() + 1);

        List<PlatformAuditEvent> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapEvent(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("查询平台审计事件失败", e);
        }
        return out;
    }

    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }

    private static PlatformAuditEvent mapEvent(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("occurred_at");
        UUID correlation = rs.getObject("correlation_id", UUID.class);
        return new PlatformAuditEvent(
                rs.getObject("id", UUID.class),
                rs.getString("actor_id"),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("decision"),
                rs.getString("reason_code"),
                correlation,
                rs.getString("ip"),
                ts == null ? Instant.EPOCH : ts.toInstant());
    }
}
