package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 审计查询实现（P4-D13 / G10 · P3-D07 追加只读）。
 *
 * <p>⚠️ <b>本类只有读方法</b>：{@code audit_record} 上有触发器
 * {@code forbid_audit_mutation}（BEFORE DELETE OR UPDATE 直接抛异常）。
 * 「可追溯」的另一半是「不可篡改」——一旦提供删除端点，
 * 触发器会拒绝，但审计系统会退化成「谁都改不了、连合法清理也做不了」的僵局。
 * 保留期清理走 P4-D04 的受控通道，不在本端口。
 *
 * <p>⚠️ <b>分页全序</b>：{@code ORDER BY created_at DESC, audit_id DESC}。
 */
@Repository
public class JdbcAuditQueryRepository implements AuditQueryPort {

    private static final String SQL_BASE = """
            SELECT audit_id, actor_id, action, resource_type, resource_id,
                   correlation_id, created_at, detail::text
            FROM audit_record
            WHERE tenant_id = ?::uuid
            """;

    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private final DataSource dataSource;

    public JdbcAuditQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<AuditEntry> query(@NonNull String tenantId,
                                           @Nullable String action,
                                           @Nullable String resourceType,
                                           @Nullable String resourceId,
                                           @Nullable Instant from,
                                           @Nullable Instant to,
                                           @Nullable String cursor,
                                           int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_BASE);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (action != null) {
            sql.append(" AND action = ?");
            params.add(action);
        }
        if (resourceType != null) {
            sql.append(" AND resource_type = ?");
            params.add(resourceType);
        }
        if (resourceId != null) {
            sql.append(" AND resource_id = ?");
            params.add(resourceId);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(to));
        }
        if (after != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND audit_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, audit_id DESC LIMIT ?");
        params.add(limit + 1);

        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<AuditEntry> rows = new ArrayList<>(limit);
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
                AuditEntry last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.auditId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询审计失败", e);
        }
    }

    private AuditEntry read(ResultSet rs) throws SQLException {
        return new AuditEntry(
                rs.getString("audit_id"),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("correlation_id"),
                parseDetail(rs.getString("detail")));
    }

    /**
     * 解析 {@code detail}。
     *
     * <p>⚠️ 解析失败返回空 map 而非抛异常：审计查询是**只读展示**通道，
     * 一条明细解析失败不应让整页 500——那会让运维在最需要看审计的时候看不到任何东西。
     */
    private static Map<String, Object> parseDetail(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            var node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                var v = e.getValue();
                if (v.isObject() || v.isArray()) {
                    out.put(e.getKey(), v.toString());
                } else if (v.isNull()) {
                    out.put(e.getKey(), null);
                } else if (v.isBoolean()) {
                    out.put(e.getKey(), v.booleanValue());
                } else if (v.isIntegralNumber()) {
                    out.put(e.getKey(), v.longValue());
                } else if (v.isNumber()) {
                    out.put(e.getKey(), v.doubleValue());
                } else {
                    out.put(e.getKey(), v.asString());
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
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