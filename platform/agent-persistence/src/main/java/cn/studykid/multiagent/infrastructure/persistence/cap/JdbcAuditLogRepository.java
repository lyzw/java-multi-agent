package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.AuditLog;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantConnectionLease;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.AuditLogPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 业务审计日志 JDBC 仓储（cap_audit_log）。
 *
 * <p>⚠️ 审计写失败必须 fail-closed：调用方应与业务操作同事务，
 * 本仓储只做写入，不吞异常、不做降级 —— 无审计的高危变更比变更失败更危险。
 *
 * <p>⚠️ 本表 RLS 仅放行 INSERT + SELECT，禁止 UPDATE / DELETE（业务层只读约束）。
 */
@Repository
public class JdbcAuditLogRepository implements AuditLogPort {

    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final DataSource dataSource;

    public JdbcAuditLogRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull AuditLog record(@NonNull AuditLog audit) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(audit.tenantId()),
                conn -> {
                    String sql = """
                            INSERT INTO cap_audit_log
                                (audit_id, scope, tenant_id, resource_kind, resource_id, resource_name,
                                 event_type, from_version, to_version, from_state, to_state,
                                 before_config, after_config, diff_summary,
                                 request_id, release_id, operator_id, reason, created_at)
                            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?,
                                    ?::jsonb, ?::jsonb, ?::jsonb,
                                    ?, ?, ?, ?, COALESCE(?, now()))
                            RETURNING created_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setObject(i++, audit.auditId());
                        ps.setString(i++, audit.scope());
                        bindUuid(ps, i++, audit.tenantId());
                        ps.setString(i++, audit.resourceKind());
                        ps.setString(i++, audit.resourceId());
                        ps.setString(i++, audit.resourceName());
                        ps.setString(i++, audit.eventType());
                        setNullableInt(ps, i++, audit.fromVersion());
                        setNullableInt(ps, i++, audit.toVersion());
                        ps.setString(i++, audit.fromState());
                        ps.setString(i++, audit.toState());
                        ps.setString(i++, audit.beforeConfig());
                        ps.setString(i++, audit.afterConfig());
                        ps.setString(i++, audit.diffSummary());
                        ps.setObject(i++, audit.requestId());
                        setNullableLong(ps, i++, audit.releaseId());
                        ps.setString(i++, audit.operatorId());
                        ps.setString(i++, audit.reason());
                        ps.setTimestamp(i++, Timestamp.from(audit.createdAt()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("cap_audit_log INSERT 未返回");
                            // 时间戳由 DB 保证，用返回值
                            return new AuditLog(
                                    audit.auditId(), audit.scope(), audit.tenantId(),
                                    audit.resourceKind(), audit.resourceId(), audit.resourceName(),
                                    audit.eventType(), audit.fromVersion(), audit.toVersion(),
                                    audit.fromState(), audit.toState(),
                                    audit.beforeConfig(), audit.afterConfig(), audit.diffSummary(),
                                    audit.requestId(), audit.releaseId(),
                                    audit.operatorId(), audit.reason(),
                                    rs.getTimestamp(1).toInstant()
                            );
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<AuditLog> findById(@NonNull String scope,
                                                 @Nullable String tenantId,
                                                 @NonNull UUID auditId) {
        String sql = selectColumns() + """
                WHERE scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid AND audit_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, scope);
                bindUuid(ps, i++, tenantId);
                ps.setObject(i++, auditId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审计日志失败", e);
        }
    }

    @Override
    public @NonNull Page<AuditLog> list(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @Nullable String resourceKind,
                                         @Nullable String resourceId,
                                         @Nullable String eventType,
                                         @Nullable String cursor,
                                         int limit) {
        return queryInternal(scope, tenantId, resourceKind, resourceId, eventType, null, null, null, cursor, limit);
    }

    @Override
    public @NonNull Page<AuditLog> query(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @Nullable String eventType,
                                          @Nullable String operatorId,
                                          @Nullable String fromTime,
                                          @Nullable String toTime,
                                          @Nullable String cursor,
                                          int limit) {
        return queryInternal(scope, tenantId, null, null, eventType, operatorId, fromTime, toTime, cursor, limit);
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private Page<AuditLog> queryInternal(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @Nullable String resourceKind,
                                          @Nullable String resourceId,
                                          @Nullable String eventType,
                                          @Nullable String operatorId,
                                          @Nullable String fromTime,
                                          @Nullable String toTime,
                                          @Nullable String cursor,
                                          int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit 必须为正数");

        java.time.Instant afterTime = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split(",", 2);
                afterTime = java.time.Instant.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (Exception ignored) { }
        }

        StringBuilder sql = new StringBuilder(selectColumns())
                .append("WHERE scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid");
        List<Object> params = new ArrayList<>();
        params.add(scope);
        params.add(tenantId);

        if (resourceKind != null && !resourceKind.isBlank()) {
            sql.append(" AND resource_kind = ?");
            params.add(resourceKind);
        }
        if (resourceId != null && !resourceId.isBlank()) {
            sql.append(" AND resource_id = ?");
            params.add(resourceId);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
        if (operatorId != null && !operatorId.isBlank()) {
            sql.append(" AND operator_id = ?");
            params.add(operatorId);
        }
        if (fromTime != null && !fromTime.isBlank()) {
            sql.append(" AND created_at >= ?::timestamptz");
            params.add(fromTime);
        }
        if (toTime != null && !toTime.isBlank()) {
            sql.append(" AND created_at <= ?::timestamptz");
            params.add(toTime);
        }
        if (afterTime != null) {
            sql.append(" AND (created_at < ?::timestamptz OR (created_at = ?::timestamptz AND audit_id < ?::uuid))");
            params.add(afterTime.toString());
            params.add(afterTime.toString());
            params.add(afterId);
        }
        sql.append(" ORDER BY created_at DESC, audit_id DESC LIMIT ?");
        params.add(limit + 1);

        List<AuditLog> rows = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询业务审计日志失败", e);
        }

        String nextCursor = null;
        if (rows.size() > limit) {
            AuditLog last = rows.get(limit - 1);
            rows = new ArrayList<>(rows.subList(0, limit));
            nextCursor = last.createdAt().toString() + "," + last.auditId();
        }
        return Page.of(rows, nextCursor);
    }

    private static String selectColumns() {
        return """
                SELECT audit_id::text, scope, tenant_id::text, resource_kind, resource_id, resource_name,
                       event_type, from_version, to_version, from_state, to_state,
                       before_config::text, after_config::text, diff_summary::text,
                       request_id::text, release_id, operator_id, reason, created_at
                FROM cap_audit_log
                """;
    }

    private static AuditLog read(@NonNull ResultSet rs) throws SQLException {
        return new AuditLog(
                UUID.fromString(rs.getString("audit_id")),
                rs.getString("scope"),
                rs.getString("tenant_id"),
                rs.getString("resource_kind"),
                rs.getString("resource_id"),
                rs.getString("resource_name"),
                rs.getString("event_type"),
                (Integer) rs.getObject("from_version"),
                (Integer) rs.getObject("to_version"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("before_config"),
                rs.getString("after_config"),
                rs.getString("diff_summary"),
                rs.getString("request_id") == null ? null : UUID.fromString(rs.getString("request_id")),
                (Long) rs.getObject("release_id"),
                rs.getString("operator_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static void bindUuid(@NonNull PreparedStatement ps, int index,
                                  @Nullable String uuidText) throws SQLException {
        if (uuidText == null || uuidText.isBlank()) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, UUID.fromString(uuidText));
        }
    }

    private static void setNullableInt(@NonNull PreparedStatement ps, int index,
                                         @Nullable Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static void setNullableLong(@NonNull PreparedStatement ps, int index,
                                          @Nullable Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, value);
    }

    private TenantContext tenantContext(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    private TenantConnectionLease tenantConnection(@Nullable String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) return TenantConnectionLease.borrowed(outer);
        return TransactionalConnectionHolder.openTenantConnection(dataSource, tenantContext(tenantId));
    }
}
