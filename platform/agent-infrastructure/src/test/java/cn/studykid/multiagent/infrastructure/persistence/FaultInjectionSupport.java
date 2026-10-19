package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * P3-D1 故障注入测试支撑（可复用于 D2–D9）。
 *
 * <p><b>设计约束（继承 P2 铁律）</b>：
 * <ul>
 *   <li>所有测试用<b>非特权角色</b> {@code app_agent} {@link #requireNonPrivileged} 守护，
 *       否则超级用户绕过 RLS 会给出「隔离失效」的虚假结论（E-3）。</li>
 *   <li>池扰动用<b>大池 + 短 maxLifetime</b> 制造连接复用与回收，
 *       与既有 {@code TransactionBoundaryIT} 的 {@code maxPoolSize=1} 互补证伪。</li>
 *   <li>{@link #rawAppConnection()} 用于<b>模拟崩溃</b>：Hikari 归还连接时会把
 *       autocommit 重置为 true，PG 下等于<b>提交</b>未提交事务，
 *       会伪造「崩溃后数据仍在」——故崩溃场景必须用裸连接。</li>
 * </ul>
 */
final class FaultInjectionSupport {

    private FaultInjectionSupport() {
    }

    static HikariDataSource appPool(int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_USER", "app_agent"));
        String pwd = System.getenv("IT_PG_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(maxPoolSize);
        // ⚠️ 短生命周期强制连接回收，制造「连接复用」扰动
        cfg.setMaxLifetime(1_000);
        return new HikariDataSource(cfg);
    }

    static HikariDataSource adminPool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }

    /** 模拟 JVM/连接崩溃的裸连接：关闭时由 PostgreSQL 服务端回滚未提交事务。 */
    static Connection rawAppConnection() throws SQLException {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");
        return DriverManager.getConnection(url, user, pwd == null ? "" : pwd);
    }

    /** E-3 守护：非特权角色运行，否则得到「隔离失效」的虚假结论。 */
    static void requireNonPrivileged(DataSource appPool) throws SQLException {
        try (Connection conn = appPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertThat(rs.next()).isTrue();
            boolean superuser = rs.getBoolean(1);
            boolean bypass = rs.getBoolean(2);
            Assertions.assertThat(superuser)
                    .as("集成测试必须用非特权角色（app_agent），超级用户绕过 RLS 会得到虚假结论")
                    .isFalse();
            Assertions.assertThat(bypass)
                    .as("集成测试必须用 NOBYPASSRLS 角色，否则 RLS 失效形同虚设")
                    .isFalse();
        }
    }

    static void bind(Connection conn, String tenantId) throws SQLException {
        TenantSessionBinder.bind(conn, new TenantContext(tenantId, "it", Set.of(), 0L));
    }

    static String tenantIdBySlug(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tenant_id::text FROM tenant WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertThat(rs.next())
                        .as("种子数据缺失租户 %s，请先执行 ./scripts/dev-postgres.sh start", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    static String snapshotIdBySlug(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT s.snapshot_id::text FROM execution_snapshot s "
                           + "JOIN tenant t ON t.tenant_id = s.tenant_id WHERE t.slug = ? LIMIT 1")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertThat(rs.next())
                        .as("种子数据缺失快照 %s，请先执行 ./scripts/dev-postgres.sh start", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    /** 为 t-b 补足 agent_version + execution_snapshot（种子只给了 t-a），使两个租户都能建 Run。 */
    static void ensureTenantSnapshot(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO agent_version (tenant_id, agent_id, version_number, definition) "
                  + "SELECT a.tenant_id, a.agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb "
                  + "FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id "
                  + "WHERE t.slug = ? AND a.name = 'agent1' "
                  + "ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO execution_snapshot "
                  + "(tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config) "
                  + "SELECT v.tenant_id, v.version_id, '{}', '{}', '{}', "
                  + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                  + "FROM agent_version v JOIN agent a ON a.agent_id = v.agent_id "
                  + "JOIN tenant t ON t.tenant_id = v.tenant_id "
                  + "WHERE t.slug = ? AND a.name = 'agent1' AND v.version_number = 1 "
                  + "ON CONFLICT DO NOTHING")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** 在租户事务内执行（autocommit off + 绑定 + 提交）。 */
    static <T> T inTenant(DataSource pool, String tenantId, TenantQuery<T> body) throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                bind(conn, tenantId);
                T result = body.apply(conn);
                conn.commit();
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 统计某租户可见的 Run 数（受 RLS 约束）。 */
    static long countRuns(DataSource pool, String tenantId) throws Exception {
        return inTenant(pool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM run WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @FunctionalInterface
    interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
