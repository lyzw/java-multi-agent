package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.core.tenant.TenantRegistry.PrincipalRow;
import cn.studykid.multiagent.core.tenant.TenantRegistry.RlsVerifyResult;
import cn.studykid.multiagent.core.tenant.TenantRegistry.TenantRow;
import cn.studykid.multiagent.ports.persistence.AdminTenantPort;

/**
 * 租户管理只读/编排仓储（管理控制台 P0.5）。
 *
 * <p>⚠️ <b>tenant 表为全局表、不启用 RLS</b>（租户自身即隔离单元，见 V1__baseline_schema）。
 * 平台级读/写在此表上无需 BYPASSRLS —— 安全性完全由<b>应用层 PLATFORM_ADMIN 作用域闸门</b>保证。
 * 故本仓储对 tenant 表的操作以<b>系统身份</b>（清空租户上下文）执行，不依赖任何租户身份。
 *
 * <p>⚠️ <b>principal / principal_role / agent / tenant_config_override 均受 RLS</b>：
 * 凡涉及这些表的读取（跨租户或计数），须显式绑定<b>目标租户</b>上下文，
 * 由调用方（控制器）经 PLATFORM_ADMIN 闸门放行后，以目标租户身份读取其数据。
 * 绝不可用「清空上下文」的连接去读这些表——空上下文下 RLS 会拒绝一切（返回 0），
 * 导致计数失真。
 */
@Repository
public class AdminTenantRepository implements AdminTenantPort {

    private final DataSource dataSource;

    public AdminTenantRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── 全局 tenant 表（无 RLS，系统身份） ──────────────────

    /** 列出全部租户（平台级；调用方须已通过 PLATFORM_ADMIN 闸门）。 */
    @Override
    public List<TenantRow> listTenants(@Nullable String statusFilter, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT tenant_id, slug, name, status, created_at, updated_at FROM tenant");
        List<Object> params = new ArrayList<>();
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" WHERE status = ?");
            params.add(statusFilter);
        }
        sql.append(" ORDER BY created_at DESC, tenant_id LIMIT ?");
        params.add(limit + 1);
        List<TenantRow> out = new ArrayList<>();
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapTenant(rs));
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("列出租户失败", e);
        }
        return out;
    }

    /** 查询单个租户（系统身份，tenant 表无 RLS）。 */
    @Override
    public @Nullable TenantRow findTenant(@NonNull String tenantId) {
        String sql = """
                SELECT tenant_id, slug, name, status, created_at, updated_at
                FROM tenant WHERE tenant_id = ?::uuid
                """;
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    TenantRow row = rs.next() ? mapTenant(rs) : null;
                    conn.commit();
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询租户失败", e);
        }
    }

    /** 开通新租户（仅插入 tenant 行；主体/配置由后续流程创建）。 */
    @Override
    public TenantRow provisionTenant(@NonNull String slug, @NonNull String name) {
        String sql = """
                INSERT INTO tenant (slug, name, status)
                VALUES (?, ?, 'ACTIVE')
                RETURNING tenant_id, slug, name, status, created_at, updated_at
                """;
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, slug);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("开通租户未返回行");
                    }
                    TenantRow row = mapTenant(rs);
                    conn.commit();
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("开通租户失败（slug 可能已存在或格式非法）", e);
        }
    }

    /** 租户生命周期：置状态（ACTIVE / SUSPENDED / DECOMMISSIONED）。 */
    @Override
    public @Nullable TenantRow updateStatus(@NonNull String tenantId, @NonNull String status) {
        String sql = """
                UPDATE tenant SET status = ?, updated_at = now()
                WHERE tenant_id = ?::uuid
                RETURNING tenant_id, slug, name, status, created_at, updated_at
                """;
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    TenantRow row = rs.next() ? mapTenant(rs) : null;
                    conn.commit();
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新租户状态失败", e);
        }
    }

    // ── 受 RLS 的表（须绑定目标租户上下文） ────────────────

    /** 某租户的主体数（绑定目标租户上下文）。 */
    @Override
    public long countPrincipals(@NonNull String tenantId) {
        return countBound(tenantId, "SELECT count(*) FROM principal WHERE tenant_id = ?::uuid");
    }

    /** 某租户的 Agent 定义数（绑定目标租户上下文）。 */
    @Override
    public long countAgentDefs(@NonNull String tenantId) {
        return countBound(tenantId, "SELECT count(*) FROM agent WHERE tenant_id = ?::uuid");
    }

    /** 某租户的配置覆盖数（绑定目标租户上下文）。 */
    @Override
    public long countOverrides(@NonNull String tenantId) {
        return countBound(tenantId,
                "SELECT count(*) FROM tenant_config_override WHERE tenant_id = ?::uuid");
    }

    /**
     * 列出某租户的主体及其角色（跨租户读取；调用方须已通过 PLATFORM_ADMIN 闸门）。
     *
     * <p>⚠️ 以目标租户身份绑定连接：RLS 策略 {@code tenant_id = current_tenant_id()}
     * 与显式 {@code WHERE tenant_id = ?} 双重约束，只读得到目标租户数据。
     */
    @Override
    public List<PrincipalRow> listPrincipals(@NonNull String tenantId) {
        String sql = """
                SELECT p.principal_id, p.login_name, p.display_name, p.status,
                       p.authz_version,
                       COALESCE(array_agg(pr.role ORDER BY pr.role)
                                FILTER (WHERE pr.role IS NOT NULL), '{}') AS roles
                FROM principal p
                LEFT JOIN principal_role pr
                       ON pr.tenant_id = p.tenant_id AND pr.principal_id = p.principal_id
                WHERE p.tenant_id = ?::uuid
                GROUP BY p.principal_id, p.login_name, p.display_name, p.status, p.authz_version
                ORDER BY p.login_name
                """;
        List<PrincipalRow> out = new ArrayList<>();
        try (TenantConnectionLease lease = openTenant(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PrincipalRow(
                                rs.getString("principal_id"),
                                rs.getString("login_name"),
                                rs.getString("display_name"),
                                rs.getString("status"),
                                rs.getLong("authz_version"),
                                parseRoles(rs)));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出租户主体失败", e);
        }
        return out;
    }

    /**
     * RLS 隔离校验（P0.5 · 5.4）。
     *
     * <p>以目标租户身份绑定连接后断言两点：
     * <ol>
     *   <li><b>上下文已绑定</b>：{@code current_setting('app.tenant_id')} == 目标租户；</li>
     *   <li><b>无法越界</b>：{@code SELECT count(*) FROM principal
     *        WHERE tenant_id <> 目标租户} 在 RLS 下必须为 0。</li>
     * </ol>
     * 若第 2 项为非 0，说明当前连接带 BYPASSRLS 或策略失效，隔离被破坏。
     */
    @Override
    public RlsVerifyResult verifyIsolation(@NonNull String tenantId) {
        try (TenantConnectionLease lease = openTenant(tenantId)) {
            Connection conn = lease.connection();
            String bound;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT current_setting('app.tenant_id', true)")) {
                try (ResultSet rs = ps.executeQuery()) {
                    bound = rs.next() ? rs.getString(1) : null;
                }
            }
            long leaked;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM principal WHERE tenant_id <> ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    leaked = rs.next() ? rs.getLong(1) : -1L;
                }
            }
            boolean contextBound = tenantId.equals(bound);
            boolean isolated = leaked == 0L;
            return new RlsVerifyResult(contextBound, isolated, leaked, bound);
        } catch (SQLException e) {
            throw new IllegalStateException("RLS 校验失败", e);
        }
    }

    // ── 内部 ───────────────────────────────────────────────

    private long countBound(String tenantId, String sql) {
        try (TenantConnectionLease lease = openTenant(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("计数失败", e);
        }
    }

    private static List<String> parseRoles(ResultSet rs) throws SQLException {
        Array arr = rs.getArray("roles");
        if (arr == null) {
            return List.of();
        }
        String[] raw = (String[]) arr.getArray();
        return Arrays.asList(raw);
    }

    /** 系统身份连接：清空租户上下文（供无 RLS 的全局 tenant 表操作）。 */
    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }

    /** 目标租户连接：绑定目标租户上下文（供受 RLS 的表读取）。 */
    private TenantConnectionLease openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "platform-admin", Set.of(), 0L));
        return TenantConnectionLease.open(conn);
    }

    private static TenantRow mapTenant(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new TenantRow(
                rs.getString("tenant_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("status"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant());
    }
}
