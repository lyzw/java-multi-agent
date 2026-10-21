package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort.ChangeItem;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;

/**
 * 高危配置变更审批仓储集成测试（P4-D16 / C7）。
 *
 * <p><b>⚠️ 必须跑在真实 PG17 上</b>：状态机触发器、双人复核 CHECK、RLS 隔离、
 * apply 写入 config_override 都依赖数据库特性，替身验证等于没验证。
 *
 * <p><b>⚠️ 用非特权角色 app_agent</b>：超级用户绕过 RLS，会得出虚假隔离结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ConfigChangeRepositoryIT {

    private static HikariDataSource appPool;
    private static ConfigChangePort changes;
    private static String tenantA;
    private static String tenantB;

    private static final String REASON = "试点期收紧步数上限以控制成本";
    private static final String KEY = "agent.run.max-steps";

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);

        AuditPort audit = new JdbcAuditRepository(appPool);
        ConfigOverridePort overrides = new JdbcConfigOverrideRepository(appPool, audit);
        changes = new JdbcConfigChangeRepository(appPool, audit, overrides);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
    }

    @BeforeEach
    void clean() {
        adminExec("DELETE FROM config_change_request WHERE tenant_id = '"
                + tenantA + "'::uuid OR tenant_id = '" + tenantB + "'::uuid");
        adminExec("DELETE FROM tenant_config_override WHERE tenant_id = '"
                + tenantA + "'::uuid OR tenant_id = '" + tenantB + "'::uuid");
    }

    @Test
    @DisplayName("✅ C7-1 提交申请 → PENDING")
    void requestCreatesPending() {
        ChangeItem item = changes.request(tenantA, KEY, "12", REASON, "requester-a");
        assertThat(item.state()).isEqualTo(ConfigChangePort.ChangeState.PENDING);
        assertThat(item.requesterId()).isEqualTo("requester-a");
    }

    @Test
    @DisplayName("✅ C7-2 自审被拒（双人复核）：申请人=审批人 → SelfApprovalException")
    void selfApprovalRejected() {
        changes.request(tenantA, KEY, "12", REASON, "requester-a");
        assertThatThrownBy(() -> changes.decide(tenantA, latestChangeId(tenantA),
                ConfigChangePort.Decision.APPROVE, "ok", "requester-a"))
                .isInstanceOf(ConfigChangePort.SelfApprovalException.class);
    }

    @Test
    @DisplayName("✅ C7-3 不同审批人 → APPROVED")
    void approveByOther() {
        changes.request(tenantA, KEY, "12", REASON, "requester-a");
        ChangeItem decided = changes.decide(tenantA, latestChangeId(tenantA),
                ConfigChangePort.Decision.APPROVE, "ok", "approver-b");
        assertThat(decided.state()).isEqualTo(ConfigChangePort.ChangeState.APPROVED);
    }

    @Test
    @DisplayName("✅ C7-4 应用已批准变更 → APPLIED 且真正写入 config_override")
    void applyWritesOverride() {
        changes.request(tenantA, KEY, "12", REASON, "requester-a");
        String id = latestChangeId(tenantA);
        changes.decide(tenantA, id, ConfigChangePort.Decision.APPROVE, "ok", "approver-b");
        ChangeItem applied = changes.apply(tenantA, id, "approver-b");
        assertThat(applied.state()).isEqualTo(ConfigChangePort.ChangeState.APPLIED);

        long rows = appQueryAsTenant(tenantA,
                "SELECT count(*) FROM tenant_config_override WHERE config_key = '" + KEY + "'");
        assertThat(rows).as("⛔ apply 未真正写入 config_override = 配置未生效").isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("✅ C7-5 非 APPROVED 不可应用 → IllegalStateConflictException")
    void applyBeforeApprovalRejected() {
        changes.request(tenantA, KEY, "12", REASON, "requester-a");
        String id = latestChangeId(tenantA);
        assertThatThrownBy(() -> changes.apply(tenantA, id, "approver-b"))
                .isInstanceOf(ConfigChangePort.IllegalStateConflictException.class);
    }

    @Test
    @DisplayName("✅ C7-6 跨租户隔离：B 看不到 A 的变更申请")
    void crossTenantIsolation() {
        changes.request(tenantA, KEY, "12", REASON, "requester-a");
        long visibleToB = appQueryAsTenant(tenantB,
                "SELECT count(*) FROM config_change_request");
        assertThat(visibleToB).isZero();
    }

    // ── 辅助 ──

    private static String latestChangeId(String tenantId) {
        return adminQuery("SELECT change_id::text FROM config_change_request "
                + "WHERE tenant_id = '" + tenantId + "'::uuid ORDER BY requested_at DESC LIMIT 1");
    }

    private static String adminQuery(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    private static long appQueryAsTenant(String tenantId, String sql) {
        try (Connection conn = appPool.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("按租户查询失败: " + sql, e);
        }
    }

    private static void adminExec(String sql) {
        try (HikariDataSource p = new HikariDataSource(adminConfig());
             Connection conn = p.getConnection();
             var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    private static HikariConfig adminConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        cfg.setMaximumPoolSize(1);
        return cfg;
    }
}
