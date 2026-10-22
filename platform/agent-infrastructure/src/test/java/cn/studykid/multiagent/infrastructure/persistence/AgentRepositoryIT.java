package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

import tools.jackson.databind.ObjectMapper;

/**
 * Agent 仓储集成测试（P4-D14 / G2 · FE-01 §P18）。
 *
 * <p><b>⚠️ 必须跑在真实 PG17 上</b>：版本号自增、唯一约束兜底、RLS 隔离、
 * 审计落库都依赖数据库特性，替身验证等于没验证（D01 教训）。
 *
 * <p><b>⚠️ D01 两类测试同时覆盖</b>：
 * <ul>
 *   <li><b>行为测试</b>：被直接调用时正确（版本递增、definition 原样落库、分页、冲突）。</li>
 *   <li><b>连通性测试</b>：审计<b>真写进</b> {@code audit_record}、版本<b>真出现在</b>
 *       {@code agent_version} 与列表——否则「系统全绿但消息永不被处理」形态会重演。</li>
 * </ul>
 *
 * <p>⚠️ 用非特权角色 {@code app_agent}：超级用户绕过 RLS，会得出虚假隔离结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class AgentRepositoryIT {

    private static HikariDataSource appPool;
    private static JdbcAgentQueryRepository query;
    private static JdbcAgentManageRepository manage;
    private static String tenantA;
    private static String tenantB;

    /**
     * 关闭应用连接池（W-PERM-P0 阶段实测修正）。
     *
     * <p>⚠️ <b>必须关闭</b>：本类原先建池不关。全量构建时各 IT 类累积的
     * 连接会打满 PostgreSQL 的 {@code max_connections}（默认 100），
     * 导致<b>后续模块</b>（platform-bootstrap）的 Spring 上下文加载以
     * {@code FATAL: sorry, too many clients already} 失败 ——
     * 失败现场在别的模块，报错点离真因（本类泄漏了连接）极远，
     * 极易被误判成「装配被改坏了」。
     */
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

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);

        // ⚠️ 连通性：审计用**真实** JdbcAuditRepository，使其真写 audit_record
        AuditPort audit = new JdbcAuditRepository(appPool);
        query = new JdbcAgentQueryRepository(appPool);
        manage = new JdbcAgentManageRepository(appPool, audit);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
    }

    // ── 行为 + 连通性 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 创建 Agent 成功；返回 versionCount=0；AGENT_CREATE 审计真落库")
    void createAgentReturnsZeroVersionAndAudits() {
        String name = "agent-it-" + UUID.randomUUID();
        var item = manage.createAgent(tenantA, name, "desc", "creator-1");

        assertThat(item.agentId()).isNotBlank();
        assertThat(item.name()).isEqualTo(name);
        assertThat(item.versionCount()).isZero();
        assertThat(item.latestVersionNumber()).isZero();
        assertThat(item.latestDefinition()).isNull();

        // ⛔ 连通性：audit_record 真的出现一条 AGENT_CREATE
        assertThat(countAudit(tenantA, "AGENT_CREATE", item.agentId()))
                .as("⛔ 创建若无审计，等于无声变更（R-1 要防）")
                .isOne();
    }

    @Test
    @DisplayName("⛔ 租户内重名 → DuplicateAgentNameException（唯一约束 409 路径）")
    void duplicateNameConflict() {
        String name = "agent-dup-" + UUID.randomUUID();
        manage.createAgent(tenantA, name, null, "creator-1");
        assertThatThrownBy(() -> manage.createAgent(tenantA, name, null, "creator-2"))
                .as("⛔ 重名必须经唯一约束转 409，而非静默覆盖")
                .isInstanceOf(AgentManagePort.DuplicateAgentNameException.class);
    }

    @Test
    @DisplayName("✅ 发布版本号递增；definition 原样落库；列表/agent_version/审计三处一致")
    void publishVersionIncrementsAndLands() throws Exception {
        String name = "agent-pub-" + UUID.randomUUID();
        var agent = manage.createAgent(tenantA, name, null, "creator-1");

        String def1 = "{\"kind\":\"tutor\",\"v\":1}";
        var v1 = manage.publishVersion(tenantA, agent.agentId(), def1, "publisher-1");
        assertThat(v1.versionNumber()).isEqualTo(1);
        assertJsonEquals(def1, v1.definition());

        String def2 = "{\"kind\":\"tutor\",\"v\":2}";
        var v2 = manage.publishVersion(tenantA, agent.agentId(), def2, "publisher-1");
        assertThat(v2.versionNumber()).isEqualTo(2);
        assertJsonEquals(def2, v2.definition());

        // 列表里该 agent 的最新版本号=2、definition=def2
        var found = query.findAgent(tenantA, agent.agentId());
        assertThat(found).isNotNull();
        assertThat(found.latestVersionNumber()).isEqualTo(2);
        assertJsonEquals(def2, found.latestDefinition());
        assertThat(found.versionCount()).isEqualTo(2);

        // ⛔ 连通性 1：agent_version 真有 2 行
        assertThat(countVersions(tenantA, agent.agentId())).isEqualTo(2);
        // ⛔ 连通性 2：AGENT_PUBLISH 审计真出现 2 次
        assertThat(countAudit(tenantA, "AGENT_PUBLISH", agent.agentId())).isEqualTo(2);
    }

    @Test
    @DisplayName("⛔ 发布到不存在的 Agent → AgentNotFoundException（端点转 404）")
    void publishToUnknownAgent() {
        String ghost = UUID.randomUUID().toString();
        assertThatThrownBy(() -> manage.publishVersion(tenantA, ghost, "{}", "p-1"))
                .as("⛔ 不存在必须与跨租户同语义，避免存在性探测")
                .isInstanceOf(AgentQueryPort.AgentNotFoundException.class);
    }

    @Test
    @DisplayName("⛔ 跨租户隔离：B 的列表不含 A 创建的 Agent（且 A 能看到自己的）")
    void crossTenantIsolation() {
        String name = "agent-iso-" + UUID.randomUUID();
        var agent = manage.createAgent(tenantA, name, null, "creator-1");

        List<String> visibleToB = query.listAgents(tenantB, null, 100).items().stream()
                .map(AgentQueryPort.AgentItem::agentId).toList();
        assertThat(visibleToB)
                .as("⛔⛔ B 不得看到 A 的 Agent（RLS + 显式 tenant_id 双保险）")
                .doesNotContain(agent.agentId());

        List<String> visibleToA = query.listAgents(tenantA, null, 100).items().stream()
                .map(AgentQueryPort.AgentItem::agentId).toList();
        assertThat(visibleToA)
                .as("控制组：A 应当能看到自己刚建的 Agent")
                .contains(agent.agentId());
    }

    @Test
    @DisplayName("✅ 游标分页：limit 2 翻页不重不漏")
    void cursorPaging() {
        for (int i = 0; i < 3; i++) {
            manage.createAgent(tenantA, "agent-page-" + UUID.randomUUID(), null, "c");
        }
        Page<AgentQueryPort.AgentItem> p1 = query.listAgents(tenantA, null, 2);
        assertThat(p1.hasMore()).isTrue();
        Page<AgentQueryPort.AgentItem> p2 = query.listAgents(tenantA, p1.nextCursor(), 2);

        List<String> seen = new java.util.ArrayList<>();
        p1.items().forEach(i -> seen.add(i.agentId()));
        p2.items().forEach(i -> seen.add(i.agentId()));
        assertThat(seen)
                .as("⛔ keyset 分页不得重复或遗漏任何 Agent")
                .doesNotHaveDuplicates();
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static long countAudit(String tenant, String action, String resourceId) {
        return Long.parseLong(adminQuery(
                "SELECT count(*)::text FROM audit_record "
                        + "WHERE tenant_id='%s'::uuid AND action='%s' AND resource_id='%s'"
                        .formatted(tenant, action, resourceId)));
    }

    private static long countVersions(String tenant, String agentId) {
        return Long.parseLong(adminQuery(
                "SELECT count(*)::text FROM agent_version "
                        + "WHERE tenant_id='%s'::uuid AND agent_id='%s'::uuid"
                        .formatted(tenant, agentId)));
    }

    /**
     * definition 存为 jsonb，PG 会规范化（键序/空白），故须语义比较而非字串比较。
     */
    private static void assertJsonEquals(String expected, String actual) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(actual))
                .as("definition 作为 jsonb 会被 PG 规范化（键序/空白），须语义比较而非字串比较")
                .isEqualTo(mapper.readTree(expected));
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

    private static HikariConfig adminConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        return cfg;
    }
}
