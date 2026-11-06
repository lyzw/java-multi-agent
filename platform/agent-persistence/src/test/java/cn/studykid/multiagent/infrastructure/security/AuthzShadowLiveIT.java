package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentQueryRepository;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 影子态 <b>活库实证</b>：在真实 {@code agent_dev} 上跑一遍影子比对与逐行染色（13 文 §5.2 出口门禁）。
 *
 * <p><b>为什么必须有这个测试</b>：P1 的出口条件是「影子期 ≥2 周 {@code AUTHZ_MISMATCH = 0}」。
 * 在它之前，D2–D7 的单测可以全绿而<b>影子机制一次都没跑过</b>（历史上正是这样：
 * 装配缺失导致 {@code scoped} 恒 {@code null}）。一个「从没运行过」的 0 与
 * 「运行了两周仍是 0」在报表上长得一模一样 —— 本测试的作用是把前者排除掉。
 *
 * <p><b>它证明三件事</b>：
 * <ol>
 *   <li><b>跑得起来</b>：真实 {@link RuleBasedPolicyEngine} + 真实连接 + 真实行，
 *       {@code shadowSamples > 0}；</li>
 *   <li><b>没有误报</b>：主体 = 行租户时 {@code mismatches = 0} 且 {@code taintViolations = 0}
 *       —— 与 P1 基线（{@code policy-mapping.yaml} 全 {@code TENANT_ONLY}）一致；</li>
 *   <li><b>真有检出力</b>：主体换成另一个租户去读本租户的行，mismatch 与逐行违例
 *       <b>必须真的变红</b>。这一条是重点 —— 染色一度用「查询绑定租户」当行租户，
 *       逻辑上恒不违例，是<b>永远不会红</b>的假覆盖，单测用常量桩根本发现不了。</li>
 * </ol>
 *
 * <p><b>前置</b>（凭据只从环境变量来，代码不假设任何用户名口令）：
 * <pre>
 *   export IT_PG_URL='jdbc:postgresql://host:5432/agent_dev'
 *   export IT_PG_USER='app_agent'          # ⚠️ 必须 NOBYPASSRLS，否则「读不到别租户的行」是假的
 *   export IT_PG_PASSWORD='***'
 *   mvn -o -pl agent-infrastructure -Dtest=AuthzShadowLiveIT test
 * </pre>
 *
 * <p>未设置 {@code IT_PG_URL} 时跳过（无库环境不阻塞构建）；但<b>跳过不等于通过</b>，
 * failsafe 报告会如实记录跳过数。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class AuthzShadowLiveIT {

    private static final String URL = System.getenv("IT_PG_URL");
    private static final String USER = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
    private static final String PASSWORD = System.getenv("IT_PG_PASSWORD");

    /** 平台域无哨兵（共享）行模型：空哨兵 ⇒ {@code TaintVerifier} 一律按「非共享」处理。 */
    private static final String NO_SENTINEL = "";

    private static HikariDataSource pool;

    /** 行数最多的两个租户（真实数据，不硬编码 UUID —— 不同环境种子不同）。 */
    private static String tenantA;
    private static String tenantB;

    @BeforeAll
    static void setUp() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(URL);
        cfg.setUsername(USER);
        cfg.setPassword(PASSWORD);
        // 池大小 1：强制复用同一物理连接，把「连接级 GUC 串租户」的风险也纳进来
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(10_000L);
        pool = new HikariDataSource(cfg);

        List<String> tenantsWithData = tenantsByAgentCountDesc();
        assertThat(tenantsWithData)
                .as("需要在活库上找到至少 2 个各有 agent 行的租户才能验证跨租户检出力；"
                        + "若库为空请先执行 deploy/sql 种子脚本")
                .hasSizeGreaterThanOrEqualTo(2);
        tenantA = tenantsWithData.get(0);
        tenantB = tenantsWithData.get(1);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("同一租户读自己的行：样本>0、零 mismatch、零染色违例")
    void sameTenant_isClean() throws SQLException {
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = reporter(metrics);
        ScopedDataAccess scoped = scoped();
        SubjectContext subject = SubjectContext.forTenant(tenantA);

        List<String> rowTenants = readAgentRowTenants(tenantA);
        assertThat(rowTenants).as("租户 A 必须有 agent 行，否则本测试无意义").isNotEmpty();

        // ① 影子比对：Gate 算出的 filter 与查询实际绑定租户一致
        AuthzShadowHooks.compare(scoped, reporter, true, "agent", subject, tenantA, true);
        // ② 逐行染色：行的真实 tenant_id 必须落在 filter 内
        for (String rowTenant : rowTenants) {
            AuthzShadowHooks.taint(scoped, reporter, true, "agent", NO_SENTINEL, subject, rowTenant, false);
        }

        assertThat(metrics.shadowSamples())
                .as("影子样本必须 > 0 —— 否则「AUTHZ_MISMATCH=0」只是「根本没跑过」")
                .isPositive();
        assertThat(metrics.mismatches()).as("同租户不应出现 mismatch").isZero();
        assertThat(metrics.taintViolations()).as("同租户不应出现染色违例").isZero();
        assertThat(metrics.unrestrictedUsages()).as("租户态主体不可产出 Unrestricted").isZero();
    }

    @Test
    @DisplayName("跨租户读：比对与逐行染色都必须真的变红（检出力实证）")
    void crossTenant_reallyReds() throws SQLException {
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = reporter(metrics);
        ScopedDataAccess scoped = scoped();

        // 主体是 B，查询绑定的却是 A —— 模拟「应用层谓词写错/被绕过」
        SubjectContext intruder = SubjectContext.forTenant(tenantB);
        List<String> rowTenants = readAgentRowTenants(tenantA);
        assertThat(rowTenants).as("租户 A 必须有 agent 行").isNotEmpty();

        AuthzShadowHooks.compare(scoped, reporter, true, "agent", intruder, tenantA, true);
        for (String rowTenant : rowTenants) {
            AuthzShadowHooks.taint(scoped, reporter, true, "agent", NO_SENTINEL, intruder, rowTenant, false);
        }

        assertThat(metrics.mismatches())
                .as("Gate 租户=B 而查询绑定租户=A ⇒ 必须记 mismatch")
                .isEqualTo(1L);
        assertThat(metrics.taintViolations())
                .as("每一行越权都必须被逐行染色捕获（不能只捕获一次）")
                .isEqualTo(rowTenants.size());
    }

    @Test
    @DisplayName("无主体（请求路径缺上下文）：Gate 拒绝而非放行")
    void missingSubject_isDenied() throws SQLException {
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = reporter(metrics);
        ScopedDataAccess scoped = scoped();

        List<String> rowTenants = readAgentRowTenants(tenantA);
        assertThat(rowTenants).isNotEmpty();

        // 环境主体重载：本仓请求路径没有线程级主体 ⇒ resolver fail-closed
        AuthzShadowHooks.compare(scoped, reporter, true, "agent", tenantA, true);
        for (String rowTenant : rowTenants) {
            AuthzShadowHooks.taint(scoped, reporter, true, "agent", NO_SENTINEL, rowTenant, false);
        }

        assertThat(metrics.shadowSamples())
                .as("被拒绝的比对不计样本（Gate 都没给出 filter）")
                .isZero();
        assertThat(metrics.mismatches())
                .as("无主体必须表现为 mismatch（暴露缺口），而不是静默放行")
                .isPositive();
        assertThat(metrics.taintViolations())
                .as("无主体时无法判定越权 ⇒ 不产生染色违例，只报 mismatch")
                .isZero();
    }

    @Test
    @DisplayName("未登记的资源类型：Gate fail-closed 拒绝（NO_RULE）")
    void unknownResourceType_isDenied() {
        ScopedDataAccess scoped = scoped();
        assertThatThrownBy(() -> scoped.scopeForCollection(
                SubjectContext.forTenant(tenantA), Action.RETRIEVE, "no_such_resource_type"))
                .as("无规则即拒绝，绝不回落放行")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("真实仓储端到端：装配绑定后钩子确实被触发（接缝通电）")
    void realRepository_triggersShadowHooks() {
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = reporter(metrics);

        // 走生产路径：单参构造 + 启动期绑定（与 Spring 的 @Autowired(required=false) setter 同一入口）
        JdbcAgentQueryRepository repo = new JdbcAgentQueryRepository(pool);
        repo.bindAuthzShadow(new AuthzShadowBinding(scoped(), reporter, NO_SENTINEL));

        Page<AgentItem> page = repo.listAgents(tenantA, null, 10);

        assertThat(page.items())
                .as("租户 A 必须有 agent 行，否则本用例无意义")
                .isNotEmpty();
        assertThat(metrics.shadowSamples())
                .as("listAgents 内必须真的调用到影子比对 —— 否则「装配存在」只是纸面结论")
                .isPositive();
        assertThat(metrics.mismatches())
                .as("仓储实际绑定的租户与 Gate 判定一致，不应出现 mismatch")
                .isZero();
        assertThat(metrics.taintViolations())
                .as("主表与 LATERAL 从表的行租户都属本租户，不应出现染色违例")
                .isZero();
    }

    // ── 装配（与 AuthzShadowConfiguration 一致，但采样 100%、阻断关闭） ──────────

    private static AuthzShadowReporter reporter(AuthzMismatchMetrics metrics) {
        // rate=1.0：测试环境必须全量（13 文 §5.2「测试环境 100%」）
        return new AuthzShadowReporter(metrics, false, AuthzSamplingPolicy.of(1.0d, 300_000L, Set.of()));
    }

    private static ScopedDataAccess scoped() {
        // 请求路径没有线程级主体 ⇒ 解析器 fail-closed，仓库一律走显式主体重载
        SubjectContextResolver rejecting = () -> {
            throw new AccessDeniedException("NO_SUBJECT", "测试：未接入线程级主体解析器");
        };
        AccessGate gate = new DefaultAccessGate(rejecting,
                new RuleBasedPolicyEngine(AuthzShadowPolicyDefaults.store()),
                NoOpAuthzDecisionRecorder.INSTANCE);
        return new ScopedDataAccess(gate);
    }

    // ── 活库读取（只读；RLS 生效，必须逐事务绑定租户） ────────────────────

    /** 读出租户下 agent 表的<b>真实行租户</b>（不是查询绑定租户）。 */
    private static List<String> readAgentRowTenants(String tenantId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            bindTenant(conn, tenantId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id::text FROM public.agent WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            conn.commit();
        }
        return out;
    }

    /** 按 agent 行数降序返回租户 id —— 只取有数据的租户，避免在空库上得出假结论。 */
    private static List<String> tenantsByAgentCountDesc() throws SQLException {
        List<String> candidates = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id::text FROM platform.tenant ORDER BY created_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(rs.getString(1));
                }
            }
        }
        record Counted(String tenantId, long rows) {
        }
        List<Counted> counted = new ArrayList<>();
        for (String t : candidates) {
            counted.add(new Counted(t, countAgents(t)));
        }
        counted.sort((a, b) -> Long.compare(b.rows(), a.rows()));
        return counted.stream().filter(c -> c.rows() > 0).map(Counted::tenantId).toList();
    }

    private static long countAgents(String tenantId) throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            bindTenant(conn, tenantId);
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM public.agent")) {
                try (ResultSet rs = ps.executeQuery()) {
                    long n = rs.next() ? rs.getLong(1) : 0L;
                    conn.commit();
                    return n;
                }
            }
        }
    }

    /** 事务局部绑定租户（{@code set_config(..., true)} 必须在事务内，否则 RLS 恒 0 行）。 */
    private static void bindTenant(Connection conn, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId);
            ps.execute();
        }
    }
}
