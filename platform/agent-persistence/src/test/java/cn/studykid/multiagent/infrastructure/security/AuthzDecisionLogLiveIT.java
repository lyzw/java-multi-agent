package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.tenant.SubjectContext;

/**
 * 决策日志 <b>活库实证</b>：在真实 {@code agent_dev} 上把「写一行」跑通（13 文 §5.3 出口门禁）。
 *
 * <p><b>为什么单测不够</b>：`JdbcAuthzDecisionLogTest` 用动态代理 JDBC 只证明
 * 「参数按序绑定、异常不外抛」；`AuthzDecisionLogSchemaTest` 只证明「列集与快照一致」。
 * 两者都<b>没有一条 SQL 真的抵达过数据库</b>。而这张表的失效方式恰恰是静默的：
 * 表缺、列漂、没授权 —— 三者都会让写入抛 {@code SQLException}，
 * 而本类「限频告警 + 绝不外抛」的设计会把它变成一行 WARN 后继续跑。
 * 结果是<b>判定照常发生、审计一行不涨、系统看起来完全正常</b>。
 *
 * <p><b>本类要钉死的四件事</b>：
 * <ol>
 *   <li><b>启动探测在活库真的能通过</b>：{@code probe()} 是 P2 的 fail-closed 门禁，
 *       它自己从没跑过就等于门禁不存在；</li>
 *   <li><b>不只「可写」，而是「真写了」</b>：写入后用<b>库 owner / 运维通道</b>回读，
 *       断言行真的在、字段真的对 —— 13 文 §5.3 明确写了
 *       「零行探测只能证明可写，不能证明在写，两者不可互相替代」；</li>
 *   <li><b>只写不读是真的</b>：应用角色在本表上没有 SELECT，且不是「脚本里写了 REVOKE」
 *       而是活库上真的读不了；</li>
 *   <li><b>{@code INSERT … RETURNING} 会被拒</b>：这是实测出来的坑 ——
 *       RETURNING 需要返回列的 SELECT 权限，而应用角色按设计没有。
 *       静态守卫（{@code AuthzDecisionLogSchemaTest}）拦的是源码，
 *       这里拦的是「将来有人绕过源码守卫直接拼 SQL」。</li>
 * </ol>
 *
 * <p><b>前置</b>（凭据只从环境变量来，代码不假设任何用户名口令）：
 * <pre>
 *   export IT_PG_URL='jdbc:postgresql://host:5432/agent_dev'
 *   export IT_PG_USER='app_agent'            # ⚠️ 必须是应用角色，用 owner 跑会假绿
 *   export IT_PG_PASSWORD='***'
 *   export IT_PG_ADMIN_USER='ai_agent'       # 表 owner：回读与清理需要（应用角色读不了）
 *   export IT_PG_ADMIN_PASSWORD='***'
 *   mvn -o -pl agent-infrastructure -Dtest=AuthzDecisionLogLiveIT test
 * </pre>
 *
 * <p>⚠️ <b>跳过 ≠ 通过</b>：缺任一凭据时本类整体跳过（无库环境不阻塞构建），
 * 但「决策日志真实落库过」这一验收项<b>不会</b>因此视为达成。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IT_PG_ADMIN_USER", matches = ".+")
class AuthzDecisionLogLiveIT {

    private static final String URL = System.getenv("IT_PG_URL");
    private static final String USER = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
    private static final String PASSWORD = System.getenv("IT_PG_PASSWORD");
    private static final String ADMIN_USER = System.getenv("IT_PG_ADMIN_USER");
    private static final String ADMIN_PASSWORD = System.getenv("IT_PG_ADMIN_PASSWORD");

    private static final String TABLE = "platform.authz_decision_log";

    /** 测试专用租户标记：所有本类写入的行都可按它精确定位与清理，绝不碰真实租户的流水。 */
    private static final String MARKER_TENANT = "it-probe-decision-log";

    /** 本轮唯一标识，避免与上次运行（含失败残留）混淆。 */
    private static final String RUN_TOKEN = UUID.randomUUID().toString();

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;

    @BeforeAll
    static void setUp() throws SQLException {
        HikariConfig app = new HikariConfig();
        app.setJdbcUrl(URL);
        app.setUsername(USER);
        app.setPassword(PASSWORD);
        app.setMaximumPoolSize(2);
        app.setConnectionTimeout(10_000L);
        appPool = new HikariDataSource(app);

        HikariConfig admin = new HikariConfig();
        admin.setJdbcUrl(URL);
        admin.setUsername(ADMIN_USER);
        admin.setPassword(ADMIN_PASSWORD);
        admin.setMaximumPoolSize(2);
        admin.setConnectionTimeout(10_000L);
        adminPool = new HikariDataSource(admin);

        // 清掉历史残留（上一次失败运行留下的行），保证断言「恰好 1 行」不会因陈数据误判
        purgeMarkerRows();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        try {
            purgeMarkerRows();
        } finally {
            if (appPool != null) {
                appPool.close();
            }
            if (adminPool != null) {
                adminPool.close();
            }
        }
    }

    @Test
    @DisplayName("✅ 启动探测在活库通过：表在、可 INSERT、列与写入语句一致")
    void probeSucceedsOnLiveSchema() {
        JdbcAuthzDecisionLog log = new JdbcAuthzDecisionLog(appPool);

        // probe() 是 AuthzDecisionLogGuard 的判据：返回 false 即拒绝启动。
        // 这里若失败，说明活库与 deploy/sql 快照已漂移（或授权没跟上）。
        assertThat(log.probe())
                .as("决策日志零行探测失败 —— 检查 %s 是否已建、%s 是否有 INSERT 权限、列是否漂移",
                        TABLE, USER)
                .isTrue();
    }

    @Test
    @DisplayName("✅ 放行判定真实落库：写入后由库 owner 回读，字段逐个对得上")
    void permitDecisionActuallyLandsRow() throws SQLException {
        String principal = "it-probe-permit-" + RUN_TOKEN;
        SubjectContext subject = new SubjectContext(MARKER_TENANT, principal, Set.of(), Scope.TENANT, 1L);

        new JdbcAuthzDecisionLog(appPool)
                .record(subject, Action.RETRIEVE, ResourceRef.collection("rag_chunk"), true, null);

        // ⚠️ 回读必须用 owner/运维身份：应用角色在本表上没有 SELECT（下一例就在钉这条）
        List<Map<String, Object>> rows = readMarkerRows(principal);
        assertThat(rows)
                .as("判定已发生但审计流水里没有对应行 —— 写入被静默吞掉了（限频告警不会让构建变红）")
                .hasSize(1);

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tenant_id")).isEqualTo(MARKER_TENANT);
        assertThat(row.get("principal_id")).isEqualTo(principal);
        assertThat(row.get("scope")).isEqualTo("TENANT");
        assertThat(row.get("action")).isEqualTo("RETRIEVE");
        assertThat(row.get("resource_type")).isEqualTo("rag_chunk");
        assertThat(row.get("permitted")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("reason_code"))
                .as("放行为 NULL：reason_code 只表达「为什么被拒」，放行填码会让它失去判别力")
                .isNull();
        assertThat(row.get("occurred_at"))
                .as("occurred_at 必须由写入方显式给值（now() 默认值与本列 NOT NULL 不冲突，但时间是取证的关键字段）")
                .isNotNull();
    }

    @Test
    @DisplayName("✅ 拒绝判定连原因码一起落库（403 与 500 靠它区分）")
    void denyDecisionPersistsReasonCode() throws SQLException {
        String principal = "it-probe-deny-" + RUN_TOKEN;
        SubjectContext subject = new SubjectContext(MARKER_TENANT, principal, Set.of(), Scope.TENANT, 1L);

        new JdbcAuthzDecisionLog(appPool)
                .record(subject, Action.WRITE, ResourceRef.collection("cap_agent_def"), false, "NO_RULE");

        List<Map<String, Object>> rows = readMarkerRows(principal);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("permitted")).isEqualTo(Boolean.FALSE);
        assertThat(rows.get(0).get("reason_code"))
                .as("拒绝原因码丢失：调用方将无法区分 403（策略拒绝）与 500（内部错误）")
                .isEqualTo("NO_RULE");
    }

    @Test
    @DisplayName("⛔ 应用角色在活库上读不了决策流水（只写不读，不是只写在脚本里）")
    void appRoleCannotSelectDecisionLog() throws SQLException {
        try (Connection conn = appPool.getConnection();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT has_table_privilege(current_user, '" + TABLE + "', 'SELECT')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                        .as("应用角色竟有 SELECT：该表含全平台各租户的判定记录，读必须走 platform_ops 运维通道")
                        .isFalse();
            }

            // 权限位说没有，还要真的读一次 —— 两者都做才算钉死
            assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM " + TABLE))
                    .as("has_table_privilege 说不给读，实际却读得动：说明授权/角色假设与活库不符")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    @DisplayName("⛔ INSERT … RETURNING 在应用角色下被拒（RETURNING 需要 SELECT 权限）")
    void insertWithReturningIsDenied() throws SQLException {
        // 这条不是「测 PostgreSQL 的行为」，而是在钉住一个真实踩过的坑：
        // 同一个角色、同一张表，不带 RETURNING 的 INSERT 成功，带 RETURNING 的直接被拒。
        // 若有人为了「拿到插入的 id」给写入语句加上 RETURNING，
        // 决策日志会在每次判定时静默失败（异常被限频告警吞掉），而系统表面一切正常。
        String columns = "(occurred_at, tenant_id, principal_id, scope, action, resource_type, permitted, reason_code)";

        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);

            // ① 普通 INSERT：允许（随后回滚，不留行）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TABLE + " " + columns + " VALUES (now(), ?, ?, 'TENANT', 'READ', 'probe', true, NULL)")) {
                ps.setString(1, MARKER_TENANT);
                ps.setString(2, "it-probe-returning-ok-" + RUN_TOKEN);
                assertThat(ps.executeUpdate())
                        .as("应用角色连普通 INSERT 都做不了：03_platform_roles.sql 的授权没生效")
                        .isEqualTo(1);
            }
            conn.rollback();

            // ② 带 RETURNING：拒绝
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TABLE + " " + columns
                            + " VALUES (now(), ?, ?, 'TENANT', 'READ', 'probe', true, NULL) RETURNING id")) {
                ps.setString(1, MARKER_TENANT);
                ps.setString(2, "it-probe-returning-denied-" + RUN_TOKEN);
                assertThatThrownBy(ps::executeQuery)
                        .as("RETURNING 竟然成功了 —— 若应用角色真的拿到了 SELECT，"
                                + "本表就不再是「只写不读」，取证隔离被打破")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    // ── 支撑 ──────────────────────────────────────────────────────

    /** 按 principal 精确回读（owner 身份；app 角色没有 SELECT）。 */
    private static List<Map<String, Object>> readMarkerRows(String principal) throws SQLException {
        String sql = "SELECT tenant_id, principal_id, scope, action, resource_type, permitted, "
                + "reason_code, occurred_at FROM " + TABLE + " WHERE tenant_id = ? AND principal_id = ?";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MARKER_TENANT);
            ps.setString(2, principal);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Map.of(
                            "tenant_id", rs.getString("tenant_id"),
                            "principal_id", rs.getString("principal_id"),
                            "scope", rs.getString("scope"),
                            "action", rs.getString("action"),
                            "resource_type", rs.getString("resource_type"),
                            "permitted", rs.getBoolean("permitted"),
                            "reason_code", rs.getString("reason_code") == null ? "" : rs.getString("reason_code"),
                            "occurred_at", rs.getTimestamp("occurred_at")));
                }
            }
        }
        // Map.of 不允许 null，reason_code 的「NULL 语义」用空串占位后再还原
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : out) {
            Map<String, Object> copy = new HashMap<>(row);
            if ("".equals(copy.get("reason_code"))) {
                copy.put("reason_code", null);
            }
            normalized.add(copy);
        }
        return normalized;
    }

    /** 只删本类自己写的标记行：审计流水不掺假，测试也不留垃圾。 */
    private static void purgeMarkerRows() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE tenant_id = ?")) {
            ps.setString(1, MARKER_TENANT);
            ps.executeUpdate();
        }
    }
}
