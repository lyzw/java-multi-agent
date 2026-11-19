package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort;

/**
 * 租户-Agent 绑定的 JDBC 实现。
 *
 * <p>绑定表带 FORCE RLS，所有读写都走 {@code tenantConnection(tenantId)}；
 * 平台运营代租户操作时同样显式传入 tenantId，不绕过租户隔离上下文。
 */
@Repository
public class JdbcTenantAgentBindingRepository implements TenantAgentBindingPort {

    private static final String SELECT_COLUMNS = """
            SELECT tenant_id::text, agent_id::text, status, pinned_version, config,
                   created_at, updated_at
            FROM tenant_agent_binding
            """;

    private final DataSource dataSource;
    private final AuditPort audit;

    public JdbcTenantAgentBindingRepository(@NonNull DataSource dataSource,
                                            @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull List<BindingItem> listByTenant(@NonNull String tenantId) {
        return query(SELECT_COLUMNS + " WHERE tenant_id = ?::uuid ORDER BY agent_id",
                List.of(tenantId));
    }

    @Override
    public @NonNull List<BindingItem> listByAgent(@NonNull String agentId) {
        // 绑定表带 FORCE RLS：逐租户绑定上下文查询，避免绕过租户隔离。
        List<String> tenantIds = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT tenant_id::text FROM tenant");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tenantIds.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询租户列表失败", e);
        }
        List<BindingItem> rows = new ArrayList<>();
        for (String tenantId : tenantIds) {
            rows.addAll(query(SELECT_COLUMNS
                    + " WHERE tenant_id = ?::uuid AND agent_id = ?::uuid",
                    List.of(tenantId, agentId)));
        }
        return rows;
    }

    @Override
    public @NonNull Optional<BindingItem> find(@NonNull String tenantId,
                                               @NonNull String agentId) {
        List<BindingItem> rows = query(SELECT_COLUMNS
                + " WHERE tenant_id = ?::uuid AND agent_id = ?::uuid",
                List.of(tenantId, agentId));
        return rows.stream().findFirst();
    }

    @Override
    public @NonNull BindingItem bind(@NonNull String tenantId,
                                     @NonNull String agentId,
                                     @Nullable Integer pinnedVersion,
                                     @Nullable String config,
                                     @NonNull String actorId) {
        String sql = """
                INSERT INTO tenant_agent_binding
                    (tenant_id, agent_id, status, pinned_version, config, created_at, updated_at)
                VALUES (?::uuid, ?::uuid, 'ACTIVE', ?, ?::jsonb, now(), now())
                ON CONFLICT (tenant_id, agent_id) DO UPDATE
                   SET status = 'ACTIVE',
                       pinned_version = EXCLUDED.pinned_version,
                       config = EXCLUDED.config,
                       updated_at = now()
                """;
        execute(tenantId, sql, List.of(tenantId, agentId, pinnedVersion,
                config == null || config.isBlank() ? "{}" : config));
        audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_BIND,
                "tenant_agent_binding", tenantId + ":" + agentId,
                Map.of("agentId", agentId));
        return find(tenantId, agentId).orElseThrow();
    }

    @Override
    public @NonNull BindingItem update(@NonNull String tenantId,
                                       @NonNull String agentId,
                                       @Nullable Integer pinnedVersion,
                                       @Nullable String config,
                                       @NonNull String actorId) {
        String sql = """
                UPDATE tenant_agent_binding
                   SET pinned_version = ?, config = ?::jsonb, updated_at = now()
                 WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND status = 'ACTIVE'
                """;
        int updated = execute(tenantId, sql, List.of(pinnedVersion,
                config == null || config.isBlank() ? "{}" : config, tenantId, agentId));
        if (updated == 0) {
            throw new BindingNotFoundException(tenantId, agentId);
        }
        audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_UPDATE,
                "tenant_agent_binding", tenantId + ":" + agentId,
                Map.of("agentId", agentId));
        return find(tenantId, agentId).orElseThrow();
    }

    @Override
    public void unbind(@NonNull String tenantId, @NonNull String agentId,
                       @NonNull String actorId) {
        String sql = """
                UPDATE tenant_agent_binding
                   SET status = 'SUSPENDED', updated_at = now()
                 WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND status = 'ACTIVE'
                """;
        int updated = execute(tenantId, sql, List.of(tenantId, agentId));
        if (updated == 0) {
            throw new BindingNotFoundException(tenantId, agentId);
        }
        audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_UNBIND,
                "tenant_agent_binding", tenantId + ":" + agentId,
                Map.of("agentId", agentId));
    }

    private @NonNull List<BindingItem> query(String sql, List<Object> params) {
        List<BindingItem> rows = new ArrayList<>();
        String tenantId = params.isEmpty() ? "" : String.valueOf(params.get(0));
        try (TenantConnectionLease lease = tenantConnection(tenantId);
             PreparedStatement ps = lease.connection().prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(map(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("查询租户-Agent 绑定失败", e);
        }
    }

    private int execute(String tenantId, String sql, List<Object> params) {
        try (TenantConnectionLease lease = tenantConnection(tenantId);
             PreparedStatement ps = lease.connection().prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入租户-Agent 绑定失败", e);
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", Set.of(), 0L));
    }

    private static BindingItem map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        int pinned = rs.getInt("pinned_version");
        return new BindingItem(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("status"),
                rs.wasNull() ? null : pinned,
                rs.getString("config"),
                created.toInstant(),
                updated.toInstant());
    }
}
