package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 平台租户管理 JDBC 仓储（重规划 §6 / §8）。
 *
 * <p>⚠️ <b>平台域表无 RLS</b>：{@code platform.tenant} / {@code tenant_quota} /
 * {@code tenant_capability_grant} 由 {@code platform_ops} 角色（或超管）管理，
 * 应用层以<b>系统身份</b>（清空租户上下文）连接，不依赖任何租户身份。
 *
 * <p>⚠️ <b>同步 {@code public.tenant}</b>：业务租户行供 RLS 与共享执行基础设施识别租户，
 * 故开通/状态迁移在<b>同一事务</b>内同时更新 {@code platform.tenant.lifecycle_state}
 * 与 {@code public.tenant.status}（镜像），保证运行时再校验与 RLS 可用。
 * 单连接 + 失败回滚，避免半初始化租户（重规划 §6.1）。
 */
@Repository
public class JdbcPlatformTenantRepository implements PlatformTenantPort {

    private final DataSource dataSource;

    public JdbcPlatformTenantRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── 列表 / 读取（系统身份，platform schema 无 RLS） ──────────

    @Override
    public List<PlatformTenantRow> listTenants(@Nullable String statusFilter, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id AS tenant_id, slug, name, lifecycle_state, plan, "
                        + "effective_policy_version, tenant_authz_version, created_at, updated_at "
                        + "FROM platform.tenant");
        List<Object> params = new ArrayList<>();
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" WHERE lifecycle_state = ?");
            params.add(statusFilter);
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT ?");
        params.add(limit + 1);

        List<PlatformTenantRow> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapTenant(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("列出租户失败", e);
        }
        return out;
    }

    @Override
    public @Nullable PlatformTenantRow findTenant(@NonNull String tenantId) {
        String sql = """
                SELECT id AS tenant_id, slug, name, lifecycle_state, plan,
                       effective_policy_version, tenant_authz_version, created_at, updated_at
                FROM platform.tenant WHERE id = ?::uuid
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                PlatformTenantRow row = rs.next() ? mapTenant(rs) : null;
                conn.commit();
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询租户失败", e);
        }
    }

    @Override
    public @Nullable QuotaRow findQuota(@NonNull String tenantId) {
        String sql = """
                SELECT agent_limit, run_concurrency_limit, budget_limit
                FROM platform.tenant_quota WHERE tenant_id = ?::uuid
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                QuotaRow row = rs.next()
                        ? new QuotaRow(rs.getInt("agent_limit"),
                                rs.getInt("run_concurrency_limit"),
                                rs.getBigDecimal("budget_limit"))
                        : null;
                conn.commit();
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询配额失败", e);
        }
    }

    // ── 写操作（同事务：platform.tenant + public.tenant + quota） ──

    @Override
    public PlatformTenantRow provisionTenant(@NonNull String slug, @NonNull String name,
                                             @Nullable String plan) {
        String insertPlatform = """
                INSERT INTO platform.tenant (slug, name, plan, status, lifecycle_state)
                VALUES (?, ?, ?, 'PENDING_SETUP', 'PENDING_SETUP')
                RETURNING id AS tenant_id, created_at, updated_at
                """;
        try (Connection conn = openSystem()) {
            try {
                UUID tenantId;
                Instant created;
                Instant updated;
                try (PreparedStatement ps = conn.prepareStatement(insertPlatform)) {
                    ps.setString(1, slug);
                    ps.setString(2, name);
                    ps.setString(3, plan);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("开通租户未返回行");
                        }
                        tenantId = rs.getObject("tenant_id", UUID.class);
                        created = rs.getTimestamp("created_at").toInstant();
                        updated = rs.getTimestamp("updated_at").toInstant();
                    }
                }
                // 同步业务租户行（供 RLS / 执行基础设施识别租户）
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tenant (tenant_id, slug, name, status) "
                                + "VALUES (?::uuid, ?, ?, 'PENDING_SETUP')")) {
                    ps.setString(1, tenantId.toString());
                    ps.setString(2, slug);
                    ps.setString(3, name);
                    ps.executeUpdate();
                }
                // 默认配额
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO platform.tenant_quota "
                                + "(tenant_id, agent_limit, run_concurrency_limit) "
                                + "VALUES (?::uuid, 50, 10)")) {
                    ps.setString(1, tenantId.toString());
                    ps.executeUpdate();
                }
                conn.commit();
                return new PlatformTenantRow(tenantId.toString(), slug, name,
                        TenantLifecycle.PENDING_SETUP, plan, 1L, 1L, created, updated);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("开通租户失败（slug 可能已存在或格式非法）", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("开通租户失败", e);
        }
    }

    @Override
    public @Nullable PlatformTenantRow transitionLifecycle(@NonNull String tenantId,
                                                          @NonNull String newState) {
        String sql = """
                UPDATE platform.tenant
                SET lifecycle_state = ?, status = ?, updated_at = now()
                WHERE id = ?::uuid
                RETURNING id AS tenant_id, slug, name, lifecycle_state, plan,
                          effective_policy_version, tenant_authz_version, created_at, updated_at
                """;
        try (Connection conn = openSystem()) {
            try {
                PlatformTenantRow row;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, newState);
                    ps.setString(2, newState);
                    ps.setString(3, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return null;
                        }
                        row = mapTenant(rs);
                    }
                }
                // 镜像到业务租户行（best-effort：业务行应已存在）
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tenant SET status = ?, updated_at = now() "
                                + "WHERE tenant_id = ?::uuid")) {
                    ps.setString(1, newState);
                    ps.setString(2, tenantId);
                    ps.executeUpdate();
                }
                conn.commit();
                return row;
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("迁移租户生命周期失败", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("迁移租户生命周期失败", e);
        }
    }

    @Override
    public @Nullable QuotaRow updateQuota(@NonNull String tenantId, @NonNull QuotaRow quota) {
        String sql = """
                INSERT INTO platform.tenant_quota
                    (tenant_id, agent_limit, run_concurrency_limit, budget_limit)
                VALUES (?::uuid, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    agent_limit = EXCLUDED.agent_limit,
                    run_concurrency_limit = EXCLUDED.run_concurrency_limit,
                    budget_limit = EXCLUDED.budget_limit
                RETURNING agent_limit, run_concurrency_limit, budget_limit
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setInt(2, quota.agentLimit());
            ps.setInt(3, quota.runConcurrencyLimit());
            ps.setBigDecimal(4, quota.budgetLimit());
            try (ResultSet rs = ps.executeQuery()) {
                QuotaRow row = rs.next()
                        ? new QuotaRow(rs.getInt("agent_limit"),
                                rs.getInt("run_concurrency_limit"),
                                rs.getBigDecimal("budget_limit"))
                        : null;
                conn.commit();
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新配额失败", e);
        }
    }

    @Override
    public CapabilityGrantRow grantCapability(@NonNull String tenantId,
                                             @NonNull String resourceCode,
                                             @NonNull String scope) {
        String sql = """
                INSERT INTO platform.tenant_capability_grant
                    (tenant_id, resource_code, scope, status, version)
                VALUES (?::uuid, ?, ?, 'ACTIVE', 1)
                ON CONFLICT (tenant_id, resource_code) DO UPDATE SET
                    scope = EXCLUDED.scope, status = 'ACTIVE', version = tenant_capability_grant.version + 1
                RETURNING id, tenant_id, resource_code, scope, status, version
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, resourceCode);
            ps.setString(3, scope);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("授予能力未返回行");
                }
                CapabilityGrantRow row = mapGrant(rs);
                conn.commit();
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("授予租户能力失败", e);
        }
    }

    @Override
    public boolean revokeCapability(@NonNull String tenantId, @NonNull String resourceCode) {
        String sql = """
                UPDATE platform.tenant_capability_grant
                SET status = 'REVOKED', version = version + 1
                WHERE tenant_id = ?::uuid AND resource_code = ? AND status = 'ACTIVE'
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, resourceCode);
            int n = ps.executeUpdate();
            conn.commit();
            return n > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("撤销租户能力失败", e);
        }
    }

    @Override
    public List<CapabilityGrantRow> listCapabilities(@NonNull String tenantId) {
        String sql = """
                SELECT id, tenant_id, resource_code, scope, status, version
                FROM platform.tenant_capability_grant WHERE tenant_id = ?::uuid
                ORDER BY resource_code
                """;
        List<CapabilityGrantRow> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapGrant(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("列出租户能力失败", e);
        }
        return out;
    }

    // ── 内部 ───────────────────────────────────────────────

    /** 系统身份连接：清空租户上下文（供无 RLS 的 platform 域与全局 tenant 表）。 */
    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }

    private static PlatformTenantRow mapTenant(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new PlatformTenantRow(
                rs.getObject("tenant_id", UUID.class).toString(),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("lifecycle_state"),
                rs.getString("plan"),
                rs.getLong("effective_policy_version"),
                rs.getLong("tenant_authz_version"),
                created == null ? Instant.EPOCH : created.toInstant(),
                updated == null ? Instant.EPOCH : updated.toInstant());
    }

    private static CapabilityGrantRow mapGrant(ResultSet rs) throws SQLException {
        return new CapabilityGrantRow(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("tenant_id", UUID.class).toString(),
                rs.getString("resource_code"),
                rs.getString("scope"),
                rs.getString("status"),
                rs.getLong("version"));
    }
}
