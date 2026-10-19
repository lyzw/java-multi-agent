package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * RLS 连接池上下文隔离集成测试（ADR-006 / FR-010 / R-004）。
 *
 * <p><b>验证 RLS 最隐蔽的失效模式</b>：{@code app.tenant_id} 绑定在
 * <b>数据库连接</b> 上，而连接池会复用连接。若绑定逻辑有误，上一请求的租户身份
 * 会<b>静默沿用</b>到下一请求——越权数据不会报错，只会安静地泄漏。
 *
 * <p>本测试使用<b>真实 HikariCP 连接池且池大小设为 1</b>：这强制所有请求
 * 复用同一物理连接，正是最容易发生上下文串租户的场景。生产池更大、
 * 串租户概率更低，但一旦发生影响相同，故以此配置做最严验证。
 *
 * <p>运行前置：可访问的 PostgreSQL，已执行 V1 迁移与种子数据。
 *
 * <p><b>凭据由环境变量提供，代码中不假设任何用户名或密码</b>：
 * <pre>
 *   export IT_PG_URL='jdbc:postgresql://127.0.0.1:5432/ddl_verify'
 *   export IT_PG_USER='&lt;非超级用户&gt;'        # ⚠️ 不可用超级用户，否则 RLS 会被绕过
 *   export IT_PG_PASSWORD='&lt;密码&gt;'
 *   mvn -pl agent-infrastructure test -Dtest=TenantSessionBinderIT
 * </pre>
 *
 * <p><b>必须使用非超级用户且非 BYPASSRLS 的角色</b>：超级用户会绕过所有 RLS
 * 策略，{@code FORCE ROW LEVEL SECURITY} 也拦不住。用超级用户跑本测试会得到
 * 「隔离生效」的虚假结论。
 *
 * <p>未设置 {@code IT_PG_URL} 时自动跳过，不影响常规 {@code mvn verify}。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class TenantSessionBinderIT {

    private static final String URL = System.getenv("IT_PG_URL");
    private static final String USER = System.getenv().getOrDefault("IT_PG_USER", "agent");
    private static final String PASSWORD = System.getenv("IT_PG_PASSWORD");


    private static HikariDataSource pool;

    private static TenantContext tenantA;
    private static TenantContext tenantB;

    @BeforeAll
    static void setUpPool() throws SQLException {
        assertThat(URL).as("IT_PG_URL 必须设置").isNotBlank();

        // 池大小 = 1：强制复用同一连接，最大化串租户风险以做最严验证
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(URL);
        cfg.setUsername(USER);
        if (PASSWORD != null) {
            cfg.setPassword(PASSWORD);
        }
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("it-tenant-isolation");
        cfg.setConnectionTimeout(10_000);
        pool = new HikariDataSource(cfg);

        // 按 slug 解析租户 ID —— 不硬编码 UUID，重建库后仍有效
        tenantA = new TenantContext(
                resolveTenantId("t-a"), "principal-a", Set.of(), 0);
        tenantB = new TenantContext(
                resolveTenantId("t-b"), "principal-b", Set.of(), 0);
    }

    private static String resolveTenantId(String slug) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tenant_id::text FROM tenant WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("种子数据缺失：租户 %s 不存在，请先执行 V1_seed.sql", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    @AfterAll
    static void tearDownPool() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("⚠️ 连接池复用时租户上下文不得串租户（池大小=1，最严场景）")
    void contextDoesNotLeakAcrossReusedConnection() throws SQLException {
        // 从池中借用三次，物理上极可能是同一条连接
        assertThat(borrowAndBind(tenantA)).isEqualTo(tenantA.tenantId());
        assertThat(borrowAndBind(tenantB))
                .as("第二个租户必须读到自己的身份，不得残留上一租户")
                .isEqualTo(tenantB.tenantId());
        assertThat(borrowAndBind(tenantA))
                .as("来回切换后仍须正确")
                .isEqualTo(tenantA.tenantId());
    }

    @Test
    @DisplayName("⚠️ 连续处理两个租户的请求，可见数据不得交叉")
    void sequentialRequestsDoNotCrossRead() throws SQLException {
        List<String> seenByA = queryAgentNamesAs(tenantA);
        List<String> seenByB = queryAgentNamesAs(tenantB);

        assertThat(seenByA)
                .as("租户 A 不应看到租户 B 的数据（即使两者 agent 同名）")
                .allSatisfy(n -> assertThat(n).doesNotContain("agent-of-b-only"));
        assertThat(seenByB)
                .as("租户 B 不应看到租户 A 的数据")
                .allSatisfy(n -> assertThat(n).doesNotContain("agent-of-a-only"));
    }

    @Test
    @DisplayName("⚠️ 未绑定上下文时查不到任何租户数据（默认拒绝，fail-closed）")
    void unboundContextSeesNoTenantData() throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(true);
            TenantSessionBinder.clear(conn);
            assertThat(countAgent(conn))
                    .as("未绑定租户上下文时应看不到任何行（RLS 默认拒绝）")
                    .isZero();
        }
    }

    @Test
    @DisplayName("事务回滚后上下文自动恢复，不污染后续请求")
    void rollbackRestoresContext() throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.bind(conn, tenantA);
            assertThat(TenantSessionBinder.currentTenantId(conn)).isEqualTo(tenantA.tenantId());

            conn.rollback();

            String after = TenantSessionBinder.currentTenantId(conn);
            assertThat(after == null || after.isEmpty())
                    .as("事务回滚后不应残留租户身份（set_config is_local=true）")
                    .isTrue();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("runInTenantScope 正常执行并在异常时回滚传播")
    void runInTenantScopeHandlesErrors() throws SQLException {
        TenantSessionBinder.runInTenantScope(pool, tenantA,
                conn -> assertThat(TenantSessionBinder.currentTenantId(conn))
                        .isEqualTo(tenantA.tenantId()));

        assertThatThrownBy(() -> TenantSessionBinder.runInTenantScope(pool, tenantA, conn -> {
            TenantSessionBinder.bind(conn, tenantA);
            throw new IllegalStateException("模拟业务异常");
        })).isInstanceOf(IllegalStateException.class)
          .hasMessage("模拟业务异常");

        // 异常后连接归还池中，下一次请求不应继承该上下文
        assertThat(borrowAndBind(tenantB)).isEqualTo(tenantB.tenantId());
    }

    // ------------------------------------------------------------------

    /** 从池借用连接、绑定租户、读取当前绑定值后归还。 */
    private String borrowAndBind(TenantContext ctx) throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.bind(conn, ctx);
            String current = TenantSessionBinder.currentTenantId(conn);
            conn.rollback();
            conn.setAutoCommit(true);
            return current;
        }
    }

    /** 以指定租户身份查询 agent 名称。 */
    private List<String> queryAgentNamesAs(TenantContext ctx) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.bind(conn, ctx);
            try (PreparedStatement ps =
                         conn.prepareStatement("SELECT name FROM agent ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            conn.rollback();
            conn.setAutoCommit(true);
        }
        return names;
    }

    private long countAgent(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM agent")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }
}