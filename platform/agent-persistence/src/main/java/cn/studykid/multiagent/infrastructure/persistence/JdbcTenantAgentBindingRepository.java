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
        // ⚠️ **参数必须逐个显式绑定，不能用 List.of(...)**：
        //    List.of 拒绝 null 元素，而「不固定版本」正是 pinned_version = NULL，
        //    于是 List.of(tenantId, agentId, null, ...) 直接抛 NPE ——
        //    表现为「默认绑定（不固定版本）必然 500」，且只在真发请求时才炸。
        //
        // ⚠️ **必须显式提交**：tenantConnection() 拿到的连接是
        //    setAutoCommit(false) 的事务连接，租约关闭时会**回滚**未提交的写入。
        //    漏掉 commit ⇒ 接口返回 200、审计也写了，但绑定行根本没落库
        //    （读回来还是旧值，且下一次重试又「成功」——静默失真）。
        try (TenantConnectionLease lease = tenantConnection(tenantId);
             PreparedStatement ps = lease.connection().prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            if (pinnedVersion == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, pinnedVersion);
            }
            ps.setString(4, config == null || config.isBlank() ? "{}" : config);
            ps.executeUpdate();
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("写入租户-Agent 绑定失败", e);
        }
        // ⚠️ 审计明细里的标识符必须用**豁免键**：agentId 是平台生成的不透明标识符，
        //    但 UUID 里可能撞上 8–12 位连续数字而被 PII 扫描器判成学号 → 审计写入
        //    被拒 → 整个操作 500（且取决于随机 UUID，概率性发作，极难复现）。
        //    豁免键见 JdbcAuditRepository.VALUE_SCAN_EXEMPT_KEYS。
        audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_BIND,
                "tenant_agent_binding", tenantId + ":" + agentId,
                Map.of("resourceId", agentId));
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
        // ⚠️ 同 bind：pinnedVersion 为 null 表示「清空固定版本」，
        //    用 List.of 会因 null 元素直接 NPE ⇒ 清空固定版本永远做不到。
        int updated;
        try (TenantConnectionLease lease = tenantConnection(tenantId);
             PreparedStatement ps = lease.connection().prepareStatement(sql)) {
            if (pinnedVersion == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, pinnedVersion);
            }
            ps.setString(2, config == null || config.isBlank() ? "{}" : config);
            ps.setString(3, tenantId);
            ps.setString(4, agentId);
            updated = ps.executeUpdate();
            // ⚠️ 同 bind：自持事务连接必须显式提交，否则租约关闭时回滚本次写入。
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("写入租户-Agent 绑定失败", e);
        }
        if (updated == 0) {
            throw new BindingNotFoundException(tenantId, agentId);
        }
        audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_UPDATE,
                "tenant_agent_binding", tenantId + ":" + agentId,
                Map.of("resourceId", agentId));
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
                Map.of("resourceId", agentId));
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
            int updated = ps.executeUpdate();
            // ⚠️ **必须提交**：tenantConnection() 的连接是 autocommit=false 的事务连接，
            //    租约 close() 会回滚未提交的写入。漏掉这行 ⇒ 接口报成功、审计也写了，
            //    但绑定行没落库（读回旧值），是典型的静默失真。
            //    commitQuietly 对借用外层事务的连接是 no-op，不会抢外层的提交权。
            lease.commitQuietly();
            return updated;
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
        // ⚠️ **wasNull() 只反映「最近一次读取的那一列」**，不是「上一次被读为 NULL 的列」。
        //   所以必须在读完 pinned_version 的**当下**立刻取 wasNull()；
        //   若后面又读了 tenant_id / agent_id / status，标志会被冲掉，
        //   于是「未固定版本（NULL）」会被读成 0 —— 而 0 违反表上的
        //   CHECK (pinned_version IS NULL OR pinned_version > 0)，
        //   前端把 0 原样回传给更新端点就会直接撞约束报错。
        int pinned = rs.getInt("pinned_version");
        Integer pinnedVersion = rs.wasNull() ? null : pinned;
        return new BindingItem(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("status"),
                pinnedVersion,
                rs.getString("config"),
                created.toInstant(),
                updated.toInstant());
    }
}
