package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 报表中心端点测试（工作台统计 · {@code GET /api/statistics/daily}）。
 *
 * <p><b>⚠️ 本类属「连通性测试」</b>：它验证的不只是「直接调用控制器行为正确」，
 * 而是<b>端点在真实 Spring 装配下真的被注册、真的路由得到、真的带租户隔离</b>。
 * P4-D08 的 R-1 已证明：缺这一层，整条链路可断裂而不被发现。
 *
 * <p><b>⚠️ 须有真实数据库</b>：未设 {@code IT_PG_URL} 时跳过（不是失败）。
 *
 * <p><b>⚠️ 必须用 {@code RANDOM_PORT} 而非 {@code MOCK}</b>：
 * {@code MOCK} 环境下安全过滤器链不装配，无令牌请求会直达控制器，
 * 使「无令牌 → 401」这项永远红。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("报表中心端点（工作台统计）")
class StatisticsEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private DataSource dataSource;

    /** 本用例创建的 Run 标识（供清理）：租户 + run_id 成对记录。 */
    private final List<String[]> createdRuns = new ArrayList<>();

    // ── 令牌与夹具 ────────────────────────────────────────────────

    private String token(String tenantId, String principalId, String role) {
        return tokens.issue(
                new TenantContext(tenantId, principalId, Set.of(role), 1L),
                java.time.Instant.now());
    }

    private String tenantIdOf(String slug) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT tenant_id::text FROM tenant WHERE slug = ?", String.class, slug);
    }

    /** 在绑定租户的事务内执行（RLS 铁律：租户绑定必须在事务内）。 */
    private void inTenantTx(String tenantId, TenantAction action) {
        new JdbcTemplate(dataSource).execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                    boolean prev = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        try (var ps = conn.prepareStatement(
                                "SELECT set_config('app.tenant_id', ?, true)")) {
                            ps.setString(1, tenantId);
                            ps.execute();
                        }
                        action.run(conn);
                        conn.commit();
                    } catch (java.sql.SQLException e) {
                        conn.rollback();
                        throw new IllegalStateException("夹具写入失败", e);
                    } catch (RuntimeException e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(prev);
                    }
                    return null;
                });
    }

    @FunctionalInterface
    private interface TenantAction {
        void run(java.sql.Connection conn) throws java.sql.SQLException;
    }

    /**
     * 播种一条 Run。
     *
     * @param state Run 状态
     */
    private void seedRun(String tenantId, String runId, String state) {
        inTenantTx(tenantId, conn -> {
            // ⚠️⚠️ 终态必须补 completed_at，否则触发 run_completed_at_consistent 约束：
            //    这些是**不变量**，测试**不得**绕过——绕过即等于让不变量失效。
            boolean terminal = !"QUEUED".equals(state) && !"RUNNING".equals(state);
            String completedAt = terminal ? "now()" : "NULL";
            String resultPayload = "SUCCEEDED".equals(state)
                    ? "'{\"answer\":\"ok\"}'::jsonb" : "NULL";
            try (var ps = conn.prepareStatement(
                    "INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                  + "request_digest, state, input_payload, result_payload, completed_at, "
                  + "correlation_id, created_at) "
                  + "VALUES (?::uuid, ?::uuid, (SELECT snapshot_id FROM execution_snapshot "
                  + "  WHERE tenant_id = ?::uuid LIMIT 1), ?, ?, ?::run_state, "
                  + "  '{}'::jsonb, " + resultPayload + ", " + completedAt + ", ?, now())")) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, tenantId);
                ps.setString(4, "stat-http-" + runId);
                ps.setString(5, "stat-http-" + runId);
                ps.setString(6, state);
                ps.setString(7, "corr-" + runId);
                ps.executeUpdate();
            }
            createdRuns.add(new String[] {tenantId, runId});
        });
    }

    /**
     * 清理本用例播种的 Run。
     *
     * <p>⚠️⚠️ <b>删除必须在绑定租户的事务里做</b>：{@code run} 表是
     * {@code FORCE ROW LEVEL SECURITY}（策略 {@code tenant_id = current_tenant_id()}）。
     * 若在**没有** {@code app.tenant_id} 的连接上执行 DELETE，
     * 策略会把所有行过滤掉 —— DELETE <b>不报错</b>，只是**删掉 0 行**。
     * 症状是「测试全绿，但库里每轮都多出一条 run 残留」，
     * 并且会污染其它 IT 的统计基线（当日辅导量被越算越高）。
     */
    @AfterEach
    void cleanUp() {
        for (String[] pair : createdRuns) {
            String tenantId = pair[0];
            String runId = pair[1];
            inTenantTx(tenantId, conn -> {
                try (var ps = conn.prepareStatement(
                        "DELETE FROM run WHERE run_id = ?::uuid")) {
                    ps.setString(1, runId);
                    ps.executeUpdate();
                }
            });
        }
        createdRuns.clear();
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 端点可达：200 + 连续日期轴 + 环比基线 + 排行双口径")
    void statisticsEndpointReturnsAggregates() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED");

        rest.get().uri("/api/statistics/daily?days=7")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-stat", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // 连续日期轴：7 天 → 7 行，缺数据补 0
                .jsonPath("$.runsByDay.length()").isEqualTo(7)
                .jsonPath("$.weaknessesByDay.length()").isEqualTo(7)
                .jsonPath("$.studentsByDay.length()").isEqualTo(7)
                .jsonPath("$.generatedDays").isEqualTo(7)
                // ⚠️ 环比：两个窗口的汇总都要在（前端不做求和，口径在后端）
                .jsonPath("$.currentPeriod.total").exists()
                .jsonPath("$.currentPeriod.terminal").exists()
                .jsonPath("$.currentPeriod.weaknessCount").exists()
                .jsonPath("$.previousPeriod.total").exists()
                // 知识点排行双口径
                .jsonPath("$.topKnowledgePoints").exists()
                .jsonPath("$.topKnowledgePointsInRange").exists();
    }

    @Test
    @DisplayName("⛔ 无令牌 → 401（不是 500，也不是 200 空数据）")
    void noTokenRejected() {
        rest.get().uri("/api/statistics/daily")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ days 越界被钳制在 1～90（不得触发全表聚合）")
    void daysAreClamped() {
        String tenantId = tenantIdOf("t-a");

        rest.get().uri("/api/statistics/daily?days=9999")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-stat", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedDays").isEqualTo(90);

        rest.get().uri("/api/statistics/daily?days=0")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-stat", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedDays").isEqualTo(1);
    }

    /**
     * 取某租户「今日辅导量」（走 HTTP，与前端看到的是同一条路径）。
     */
    private long todayRunTotal(String tenantId, String principalId) {
        // ⚠️ 必须**结构化取值**（JSON 反序列化成 Map），不能用字符串截取：
        //    响应里 "total" 这个键出现**多次**——逐日 runsByDay 每行一个、
        //    汇总 currentPeriod 又一个。字符串截取取到的是**第一个** total
        //    （逐日数组里的某一天），而不是「今日总量」，
        //    断言会静默退化成「某一天没变」，结论就错了。
        //    取 currentPeriod.total：days=1 时它的窗口就是 [今天, 明天)，
        //    与 runsByDay[0] 同源，正是「今日发起量（含在途）」。
        Map<String, Object> body = rest.get().uri("/api/statistics/daily?days=1")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, principalId, "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();

        if (body == null) {
            throw new IllegalStateException("响应体为空");
        }
        Object current = body.get("currentPeriod");
        if (!(current instanceof Map<?, ?> currentMap)) {
            throw new IllegalStateException("响应里没有 currentPeriod: " + body);
        }
        Object total = currentMap.get("total");
        if (!(total instanceof Number number)) {
            throw new IllegalStateException("响应里 currentPeriod.total 不是数值: " + body);
        }
        return number.longValue();
    }

    @Test
    @DisplayName("⛔ 跨租户：租户 B 的报表里看不到租户 A 的辅导量")
    void tenantIsolationHoldsOverHttp() {
        String tenantA = tenantIdOf("t-a");
        String tenantB = tenantIdOf("t-b");

        // ⚠️ **前后对比**而不是「绝对为 0」：库里有别的 IT 留下的历史数据，
        //    绝对值断言会把环境噪声当成本用例的结论。
        //    真正的命题是「A 新增的那一条**不得**出现在 B 的报表里」。
        long before = todayRunTotal(tenantB, "p-stat-b");
        seedRun(tenantA, UUID.randomUUID().toString(), "SUCCEEDED");
        long after = todayRunTotal(tenantB, "p-stat-b");

        assertThat(after)
                .as("⛔ 租户 B 的辅导量不得因租户 A 新增 Run 而变化")
                .isEqualTo(before);
    }
}
