package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort;

/**
 * 租户-Agent 绑定仓储集成测试（全局 Agent 多对多绑定）。
 *
 * <p><b>⚠️ 必须跑在真实 PG 上</b>：RLS 隔离、唯一约束、{@code wasNull()} 语义
 * 都依赖 JDBC/PG 的真实行为，替身验证等于没验证。
 *
 * <p>⚠️ 用非特权角色 {@code app_agent}：超级用户绕过 RLS，会得出虚假隔离结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class TenantAgentBindingRepositoryIT {

    private static DruidDataSource appPool;
    private static JdbcTenantAgentBindingRepository bindings;
    private static JdbcAgentManageRepository agents;
    private static String tenantA;
    private static String tenantB;

    @AfterAll
    static void closeAppPool() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaxActive(4);
        appPool = cfg;

        AuditPort audit = new JdbcAuditRepository(appPool);
        bindings = new JdbcTenantAgentBindingRepository(appPool, audit);
        agents = new JdbcAgentManageRepository(appPool, audit);

        // ⚠️ **必须用 it- 专用租户，不得用种子租户**：
        //    静态守卫 SeedTenantWriteArchitectureTest 禁止「连库的 IT 类」在种子租户上写
        //    业务行（常驻运行实例会抢走它们造的数据，断言变成竞态；且快照不可删会永久累积）。
        //    本类要写绑定行，故走 FaultInjectionSupport 的 it- 专用租户供给器。
        tenantA = FaultInjectionSupport.provisionItTenant("it-bind-a").tenantId();
        tenantB = FaultInjectionSupport.provisionItTenant("it-bind-b").tenantId();
    }

    @Test
    @DisplayName("✅ 未固定版本时 pinnedVersion 必须是 null —— 不能读成 0")
    void unpinnedBindingReportsNullPinnedVersion() {
        String name = "bind-unpinned-" + UUID.randomUUID();
        String agentId = agents.createAgent(tenantA, name, "desc", "it").agentId();

        var item = bindings.find(tenantA, agentId).orElseThrow();

        // ⛔ 反证点：曾因 JDBC wasNull() 读错列（被后续 getString 冲掉）而返回 0。
        //    0 违反表上的 CHECK (pinned_version IS NULL OR pinned_version > 0)，
        //    前端把 0 原样回传给更新端点就会直接撞约束报错。
        assertThat(item.pinnedVersion())
                .as("⛔ 未固定版本必须回 null；读成 0 会让前端回传 0 撞 CHECK 约束")
                .isNull();
    }

    @Test
    @DisplayName("✅ 固定版本往返一致：写入 3 → 读回 3 → 清空 → 读回 null")
    void pinnedVersionRoundTrip() {
        String name = "bind-pinned-" + UUID.randomUUID();
        String agentId = agents.createAgent(tenantA, name, "desc", "it").agentId();

        var pinned = bindings.update(tenantA, agentId, 3, "{\"k\":1}", "it");
        assertThat(pinned.pinnedVersion()).isEqualTo(3);
        // ⚠️ config 是 jsonb 列：PG 会把它规范化成带空格的文本（{"k": 1}），
        //    不能按字面量断言原文，否则断言的是「PG 的 jsonb 序列化格式」而非业务语义。
        assertThat(pinned.config().replace(" ", "")).isEqualTo("{\"k\":1}");

        var cleared = bindings.update(tenantA, agentId, null, "{}", "it");
        assertThat(cleared.pinnedVersion())
                .as("⛔ 清空固定版本后必须回 null，而不是残留上一次的值")
                .isNull();
    }

    @Test
    @DisplayName("✅ 一个全局 Agent 可绑定多个租户（多对多）")
    void sameAgentBindsMultipleTenants() {
        String name = "bind-multi-" + UUID.randomUUID();
        String agentId = agents.createAgent(tenantA, name, "desc", "it").agentId();

        bindings.bind(tenantB, agentId, null, "{}", "it");

        assertThat(bindings.find(tenantA, agentId)).isPresent();
        assertThat(bindings.find(tenantB, agentId))
                .as("⛔ 全局 Agent 必须能被第二个租户绑定，否则退回「从属」模型")
                .isPresent();

        var byAgent = bindings.listByAgent(agentId);
        assertThat(byAgent).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("✅ 解绑是逻辑停用：status=SUSPENDED 且行仍在（保留审计痕迹）")
    void unbindIsLogicalSuspend() {
        String name = "bind-unbind-" + UUID.randomUUID();
        String agentId = agents.createAgent(tenantA, name, "desc", "it").agentId();

        bindings.unbind(tenantA, agentId, "it");

        var after = bindings.find(tenantA, agentId).orElseThrow();
        assertThat(after.status()).isEqualTo("SUSPENDED");
        assertThat(after.agentId()).isEqualTo(agentId);
    }

    @Test
    @DisplayName("⛔ 租户隔离：租户 B 看不到租户 A 的绑定行")
    void crossTenantBindingInvisible() {
        String name = "bind-iso-" + UUID.randomUUID();
        String agentId = agents.createAgent(tenantA, name, "desc", "it").agentId();

        assertThat(bindings.find(tenantB, agentId))
                .as("⛔ B 租户不得看到 A 租户的绑定行（RLS 隔离不得被绑定表绕开）")
                .isEmpty();
    }

    /**
     * 确定性反证：agentId 形似学号时，绑定写操作<b>不得</b>因审计 PII 扫描被拒。
     *
     * <p>⚠️ 为什么必须构造固定 UUID：审计明细里的 {@code agentId} 若用随机 UUID，
     * 只有当它恰好含 8–12 位连续数字时才撞上「学号」模式 ⇒ 缺陷<b>概率性发作</b>，
     * 测试大多数时候是绿的，撤掉修复点也看不出红 —— 等于没有守卫。
     * 这里固定一个含 10 位连续数字的 UUID，让触发变成确定性的。
     */
    @Test
    @DisplayName("✅ agentId 形似学号时绑定写操作仍成功（审计 PII 扫描不得误伤标识符）")
    void bindingWriteSurvivesStudentIdLikeAgentId() {
        String trickyAgentId = "12345678-1111-2222-3333-444455556666";
        // ⚠️ 夹具必须幂等可重跑：上轮若红在断言之前，夹具行会留在库里，
        //    下一轮再插就会撞主键/唯一约束（且 status 可能已是 SUSPENDED，
        //    导致 UPDATE 命中 0 行 → 误报成 BindingNotFoundException）。
        adminExec("INSERT INTO agent (agent_id, name, description) VALUES ('" + trickyAgentId
                + "', 'bind-tricky-" + UUID.randomUUID() + "', 'PIPL 扫描反证夹具')"
                + " ON CONFLICT (agent_id) DO NOTHING");
        adminExec("INSERT INTO tenant_agent_binding (tenant_id, agent_id, status) VALUES ('"
                + tenantA + "'::uuid, '" + trickyAgentId + "'::uuid, 'ACTIVE')"
                + " ON CONFLICT (tenant_id, agent_id) DO UPDATE SET status = 'ACTIVE'");

        // 撤掉「审计明细用豁免键」这一处修复 → 这里会抛
        // 「审计明细检出可识别信息（学号），已拒绝写入」
        bindings.unbind(tenantA, trickyAgentId, "it");

        assertThat(bindings.find(tenantA, trickyAgentId).orElseThrow().status())
                .isEqualTo("SUSPENDED");
    }

    private static void adminExec(String sql) {
        try (DruidDataSource adminPool = adminConfig();
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    private static DruidDataSource adminConfig() {
        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaxActive(1);
        return cfg;
    }
}
