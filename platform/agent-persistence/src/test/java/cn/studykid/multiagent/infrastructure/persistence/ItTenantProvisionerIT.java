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

import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * {@link ItTenantProvisioner} 的活库实证（真实 PG + 库 owner）。
 *
 * <p><b>为什么必须在真库上验「幂等」</b>：
 * 幂等性取决于<b>约束与谓词</b>，而这两者都只在库里才有意义。
 * 实测踩到的坑：最初用 {@code INSERT … SELECT … ON CONFLICT DO NOTHING} 表达幂等，
 * 但 {@code execution_snapshot} 的唯一约束是 {@code (tenant_id, snapshot_id)}，
 * 而 {@code snapshot_id} 每次都是新生成的随机值 ⇒ <b>冲突永远不会发生</b>，
 * {@code ON CONFLICT DO NOTHING} 等于没写。连续 5 轮构建后，
 * 同一个 IT 租户下真的攒了 <b>5 份</b>快照 —— 而快照被不可变触发器保护、删不掉，
 * 于是「幂等创建」变成了「每轮永久泄漏一份」。
 *
 * <p>这类缺陷在任何「不连库」的测试里都看不出来：语句能编译、能执行、也不报错，
 * 只是每次多写一行。故本类断言的是<b>行数不增长</b>，而不是「调用没抛异常」。
 *
 * <p>⚠️ 本类会留下一个永久租户 {@code it-prov-check}（快照不可删，租户也删不掉）。
 * 这是<b>一次性</b>成本：幂等成立后，重复运行不再新增任何行。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ItTenantProvisionerIT {

    private static final String SLUG = "it-prov-check";

    private static HikariDataSource admin;

    @BeforeAll
    static void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String pwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(2);
        admin = new HikariDataSource(cfg);
    }

    @AfterAll
    static void tearDown() {
        if (admin != null) {
            admin.close();
            admin = null;
        }
    }

    @Test
    @DisplayName("✅ 同一 slug 反复供给：租户/agent/版本/快照行数都不增长（快照不可删，多一份就是永久泄漏）")
    void provisionIsIdempotent() throws Exception {
        String first = ItTenantProvisioner.provision(admin, SLUG, "IT 专用租户 · 幂等性验证");
        long[] afterFirst = counts(first);

        String second = ItTenantProvisioner.provision(admin, SLUG, "IT 专用租户 · 幂等性验证");
        long[] afterSecond = counts(second);

        assertThat(second)
                .as("同一 slug 必须解析到同一个 tenant_id")
                .isEqualTo(first);
        assertThat(afterFirst[0]).as("agent 行数").isEqualTo(1L);
        assertThat(afterFirst[1]).as("agent_version 行数").isEqualTo(1L);
        assertThat(afterFirst[2])
                .as("execution_snapshot 行数（首轮供给后应恰好 1 份）")
                .isEqualTo(1L);
        assertThat(afterSecond)
                .as("⚠️ 第二次供给不得新增任何行 —— 若这里变大，说明「幂等」只是 ON CONFLICT 的错觉")
                .containsExactly(afterFirst);
    }

    @Test
    @DisplayName("✅ 取到的快照属于该租户（否则复合外键 (tenant_id, snapshot_id) 会报错且信息不指向真因）")
    void snapshotBelongsToTenant() throws Exception {
        String tenantId = ItTenantProvisioner.provision(admin, SLUG, "IT 专用租户 · 幂等性验证");
        String snapshotId = ItTenantProvisioner.snapshotId(admin, tenantId);

        assertThat(snapshotId).isNotBlank();
        assertThat(queryLong("SELECT count(*) FROM execution_snapshot WHERE snapshot_id = '"
                + snapshotId + "'::uuid AND tenant_id = '" + tenantId + "'::uuid"))
                .as("快照必须属于该租户（跨租户快照会让插 Run 的复合外键失败）")
                .isEqualTo(1L);
    }

    /**
     * P0（E2E 二次评估 · 2026-11-14）：供给出来的租户必须自带运行前置。
     *
     * <p><b>为什么这条不能省</b>：此前 {@code budget_account} 靠 dev-it-db.sh 播种、
     * {@code cap_authz_version(worker)} 靠各 IT 自己补行，于是「IT 全绿、
     * 新租户创建 Run 静默 FAILED」长期无人发现。本用例把生产开通事务的形状
     * 钉在活库上：撤掉 {@link TenantRuntimeDefaults} 的任一 INSERT，这里立刻红。
     */
    @Test
    @DisplayName("✅ 供给租户自带运行前置：budget_account 与 cap_authz_version(worker) 各恰好 1 行")
    void provisionCreatesRuntimePrerequisites() throws Exception {
        String tenantId = ItTenantProvisioner.provision(admin, SLUG, "IT 专用租户 · 幂等性验证");

        long[] after = counts(tenantId);
        assertThat(after[3])
                .as("budget_account 必须有且仅有 1 行；缺失 ⇒ 模型预留 NO_ACCOUNT ⇒ Run 收敛为 BUDGET_EXHAUSTED")
                .isEqualTo(1L);
        assertThat(after[4])
                .as("cap_authz_version 的 worker 服务主体行必须有且仅有 1 行；"
                        + "缺失 ⇒ 六处 require() fail-closed ⇒ AUTHZ_UNAVAILABLE")
                .isEqualTo(1L);

        assertThat(queryLong("SELECT count(*) FROM budget_account "
                + "WHERE tenant_id = '" + tenantId + "'::uuid AND currency = 'CNY' "
                + "AND reserved_amount = 0 AND settled_amount = 0"))
                .as("预算账户必须干净可用（0 预留 / 0 已结算），否则新租户一开跑就超发")
                .isEqualTo(1L);
        assertThat(queryLong("SELECT count(*) FROM cap_authz_version "
                + "WHERE tenant_id = '" + tenantId + "'::uuid "
                + "AND principal_id = '" + TenantRuntimeDefaults.WORKER_PRINCIPAL_ID + "' "
                + "AND authz_version = 1"))
                .as("worker 服务主体必须锁在版本 1；IT 常量与生产常量同源，"
                        + "防止夹具写 'worker' 而执行链读 'system' 之类的静默漂移")
                .isEqualTo(1L);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /**
     * @return [agent 行数, agent_version 行数, execution_snapshot 行数,
     *          budget_account 行数, cap_authz_version 的 worker 行数]
     */
    private static long[] counts(String tenantId) throws SQLException {
        return new long[] {
                queryLong("SELECT count(*) FROM agent WHERE tenant_id = '" + tenantId + "'::uuid"),
                queryLong("SELECT count(*) FROM agent_version WHERE tenant_id = '"
                        + tenantId + "'::uuid"),
                queryLong("SELECT count(*) FROM execution_snapshot WHERE tenant_id = '"
                        + tenantId + "'::uuid"),
                queryLong("SELECT count(*) FROM budget_account WHERE tenant_id = '"
                        + tenantId + "'::uuid"),
                queryLong("SELECT count(*) FROM cap_authz_version WHERE tenant_id = '"
                        + tenantId + "'::uuid AND principal_id = '"
                        + TenantRuntimeDefaults.WORKER_PRINCIPAL_ID + "'")
        };
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
