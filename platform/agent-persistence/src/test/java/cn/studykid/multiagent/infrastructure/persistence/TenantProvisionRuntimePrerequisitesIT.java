package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.core.tenant.TenantRegistry.TenantRow;

/**
 * 生产开通链「写侧显式建行」的活库实证（P0 · E2E 二次评估 2026-11-14）。
 *
 * <p><b>为什么必须连真库、且必须用 app_agent</b>：
 * {@link AdminTenantRepository#provisionTenant} 走的是业务连接池
 * （{@code app_agent}，NOBYPASSRLS）。{@code budget_account} 与
 * {@code cap_authz_version} 都是 FORCE ROW LEVEL SECURITY 表，
 * 策略带 {@code WITH CHECK (tenant_id = current_tenant_id())}：
 * <ul>
 *   <li>不绑 {@code app.tenant_id} 就 INSERT ⇒ 直接被策略拒绝，开通事务回滚；</li>
 *   <li>用库 owner（BYPASSRLS）跑本测试 ⇒ 写没写都能过，是假绿。</li>
 * </ul>
 * 只有用 NOBYPASSRLS 角色、并断言「绑定后查得到、不绑定查不到」，
 * 才能证明 {@link TenantRuntimeDefaults} 与
 * {@link AdminTenantRepository} 里的 GUC 绑定确实生效。
 *
 * <p><b>与 {@link ItTenantProvisionerIT} 的分工</b>：那边验的是 IT 夹具
 * （库 owner / BYPASSRLS），这边验的是生产开通链（NOBYPASSRLS）。
 * 两条路径形状一致但连接身份完全不同，缺任一条都会重演
 * 「IT 全绿、新租户跑不起来」。
 *
 * <p>租户用完即删（本类不造 execution_snapshot，没有不可变触发器阻挡）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class TenantProvisionRuntimePrerequisitesIT {

    private static final String SLUG = "it-runtime-prereq";

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static String tenantId;

    @BeforeAll
    static void setUp() throws SQLException {
        appPool = pool(System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"));
        adminPool = pool(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        purge();

        AdminTenantRepository repo = new AdminTenantRepository(appPool);
        TenantRow row = repo.provisionTenant(SLUG, "IT 专用租户 · 生产开通链运行前置验证");
        tenantId = row.tenantId();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        try {
            purge();
        } finally {
            if (appPool != null) {
                appPool.close();
                appPool = null;
            }
            if (adminPool != null) {
                adminPool.close();
                adminPool = null;
            }
        }
    }

    @Test
    @DisplayName("✅ NOBYPASSRLS 开通链：budget_account 与 worker 授权版本行都在，且值正确")
    void provisionWritesBothPrerequisitesUnderRls() throws SQLException {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            // ⚠️ 铁律 1：读 FORCE RLS 表前必须绑定目标租户，否则查询静默 0 行＝假绿。
            TenantSessionBinder.bind(conn, new TenantContext(tenantId, "it", java.util.Set.of(), 0L));

            assertThat(queryLong(conn,
                    "SELECT count(*) FROM budget_account WHERE tenant_id = ?::uuid "
                            + "AND currency = 'CNY' AND reserved_amount = 0 AND settled_amount = 0",
                    tenantId))
                    .as("缺预算账户 ⇒ 模型预留 NO_ACCOUNT ⇒ Run 收敛为 BUDGET_EXHAUSTED")
                    .isEqualTo(1L);
            assertThat(queryLong(conn,
                    "SELECT count(*) FROM cap_authz_version WHERE tenant_id = ?::uuid "
                            + "AND principal_id = ? AND authz_version = 1",
                    tenantId, TenantRuntimeDefaults.WORKER_PRINCIPAL_ID))
                    .as("缺 worker 服务主体授权版本行 ⇒ 六处 require() fail-closed ⇒ AUTHZ_UNAVAILABLE")
                    .isEqualTo(1L);
            conn.rollback();
        }
    }

    @Test
    @DisplayName("⚠️ 不绑租户时这两张表确实查不到（反证：不是「表没开 RLS」的假绿）")
    void unboundReadSeesNothing() throws SQLException {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.clear(conn);
            assertThat(queryLong(conn,
                    "SELECT count(*) FROM budget_account WHERE tenant_id = ?::uuid", tenantId))
                    .as("FORCE RLS 未绑定 GUC 时 SELECT 应静默 0 行；"
                            + "若这里非 0，说明连错了 BYPASSRLS 角色，本类全部结论作废")
                    .isEqualTo(0L);
            assertThat(queryLong(conn,
                    "SELECT count(*) FROM cap_authz_version WHERE tenant_id = ?::uuid",
                    tenantId))
                    .isEqualTo(0L);
            conn.rollback();
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /** 用库 owner 清理（跨租户 + BYPASSRLS），保证重复运行不残留。 */
    private static void purge() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement rows = conn.prepareStatement(
                     "DELETE FROM cap_authz_version WHERE tenant_id IN "
                             + "(SELECT tenant_id FROM tenant WHERE slug = ?)");
             PreparedStatement tenant = conn.prepareStatement(
                     "DELETE FROM tenant WHERE slug = ?")) {
            rows.setString(1, SLUG);
            rows.executeUpdate();
            tenant.setString(1, SLUG);
            tenant.executeUpdate();
        }
    }

    private static HikariDataSource pool(String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(user);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    private static long queryLong(Connection conn, String sql, String... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }
}
