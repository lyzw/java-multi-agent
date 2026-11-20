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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;

import org.springframework.beans.factory.annotation.Autowired;

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
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
public class JdbcAuditQueryRepository implements AuditQueryPort {

    private static final String SQL_BASE = """
            SELECT tenant_id, audit_id, actor_id, action, resource_type, resource_id,
                   correlation_id, created_at, detail::text
            FROM audit_record
            WHERE tenant_id = ?::uuid
            """;

    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // audit_record 为租户隔离实体（policy-mapping.yaml: TENANT_ONLY + FORCE RLS）。
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcAuditQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcAuditQueryRepository(@NonNull DataSource dataSource,
                             @Nullable ScopedDataAccess scoped,
                             @Nullable AuthzShadowReporter reporter,
                             boolean shadowEnabled,
                             @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
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
                // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                shadowCompare("audit_record", tenantId, true);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                        String rowTenant = rs.getString("tenant_id");
                        shadowTaint("audit_record", tenantId, rowTenant);
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

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}