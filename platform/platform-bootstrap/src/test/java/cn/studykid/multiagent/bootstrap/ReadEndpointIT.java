package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * G3/G6 只读端点连通性测试（P4-D09）。
 *
 * <p>⚠️ <b>本类属「连通性测试」</b>（P4-D01/D08 确立的方法论）：
 * 它验证的不只是「端点被直接调用时行为正确」，
 * 而是<b>端点在真实 Spring 装配下真的被注册、真的能取到数据</b>。
 * P4-D08 的 R-1 已证明：**缺这一层，整条链路可断裂而不被发现**
 * （工具 Bean 未注册 → 端点全绿但生产永不执行）。
 *
 * <p>⚠️ <b>须有真实数据库</b>：未设 {@code IT_PG_URL} 时跳过（不是失败）。
 *
 * <p>⚠️ <b>必须用 {@code RANDOM_PORT} 而非 {@code MOCK}（P4-D09 修复实测）</b>：
 * {@code MOCK} 环境下 {@code @AutoConfigureRestTestClient} <b>不装配
 * Spring Security 过滤器链</b>，请求直达控制器方法：
 * 无令牌请求本应在过滤器链被拦为 401，实际却进入控制器并抛
 * {@code UnauthenticatedException} → 被兜底处理器吞成 <b>500</b>。
 * 结果是 <b>G3-4/G6-5（无令牌 → 401）两项永远红</b>，
 * 且会让后来者误以为「安全链坏了」而去改产品代码——
 * 实测证明<b>产品安全链本身正确</b>，坏的只是测试环境。
 *
 * <p>⚠️ <b>不设 {@code agent.worker.tenant-slug}</b>：本类不验证 Worker 链路。
 *
 * <p>⚠️ <b>关于 {@code @Nested}</b>：本类<b>平铺</b>测试方法而非用 {@code @Nested}
 * 分组，与全绿的 {@code PlatformSecurityE2ETest} 保持一致。
 * 但<b>理由与先前记载的不同</b>——先前归因为
 * 「{@code @Nested} + {@code @AutoConfigureRestTestClient} 导致上下文引导失败」，
 * 该归因<b>已被实测证伪</b>：真正原因是 failsafe 的 classpath 里
 * {@code platform-bootstrap/target/*.jar} 是 Spring Boot <b>fat jar</b>，
 * 普通 classloader 读不到 {@code BOOT-INF/classes} 下的
 * {@code PlatformApplication}（见 {@code P4-D09 §已知限制 L-8}）。
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
        // ⚠️ 两个开关必须同时设（P4-D03 实测）：
        //    agent.worker.enabled 只关 WorkerLoopConfig 的 @Configuration，
        //    而 @RabbitListener 端点由 RabbitBootstrap 注册，不受 ConditionalOnProperty 影响。
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D09 G3/G6 只读端点连通性")
class ReadEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private DataSource dataSource;

    /** 本用例创建的 Run 标识（供清理）。 */
    private final List<String> createdRunIds = new ArrayList<>();

    /** 本用例创建的学生标识（供清理）。 */
    private final List<String> createdStudentIds = new ArrayList<>();

    // ── 令牌与夹具 ────────────────────────────────────────────────

    /**
     * 签发带指定角色与租户的令牌。
     *
     * <p>⚠️ 租户与角色都来自令牌——端点<b>不</b>从请求参数取租户。
     */
    private String token(String tenantId, String principalId, String role) {
        return tokens.issue(
                new TenantContext(tenantId, principalId, Set.of(role), 1L),
                Instant.now());
    }

    /** 解析 slug 对应的租户 ID（租户表由 dev-it-db.sh 播种）。 */
    private String tenantIdOf(String slug) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT tenant_id::text FROM tenant WHERE slug = ?", String.class, slug);
    }

    /** 在绑定租户的事务内执行（RLS 铁律：租户绑定必须在事务内）。 */
    private void inTenantTx(String tenantId, TenantAction action) {
        // ⚠️ JdbcTemplate.execute(ConnectionCallback) 抛的是 DataAccessException（非受检），
        //    故内部只捕获受检的 SQLException 并包装为 RuntimeException。
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

    /** 事务内动作。 */
    @FunctionalInterface
    private interface TenantAction {
        void run(java.sql.Connection conn) throws java.sql.SQLException;
    }

    /** 播种一个 Run。 */
    private void seedRun(String tenantId, String runId, String state) {
        inTenantTx(tenantId, conn -> {
            // ⚠️⚠️ 终态必须补 completed_at，否则触发 run_completed_at_consistent 约束：
            //    CHECK ((state IN (QUEUED,RUNNING) AND completed_at IS NULL)
            //        OR (state IN (CANCELLED,SUCCEEDED,FAILED,TIMED_OUT)
            //            AND completed_at IS NOT NULL))
            // ⚠️ 且 SUCCEEDED 还必须有 result_payload（run_result_consistent 约束）。
            //    这两条约束是「不变量」，测试**不得**绕过——绕过即等于让不变量失效。
            boolean terminal = !"QUEUED".equals(state) && !"RUNNING".equals(state);
            String completedAt = terminal ? "now()" : "NULL";
            String resultPayload = "SUCCEEDED".equals(state)
                    ? "'{\"content\":\"d09\"}'::jsonb" : "NULL";
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
                ps.setString(4, "d09-" + runId);
                ps.setString(5, "d09-" + runId);
                ps.setString(6, state);
                ps.setString(7, "corr-" + runId);
                ps.executeUpdate();
            }
        });
        createdRunIds.add(runId);
    }

    /** 播种学生画像。 */
    private void seedProfile(String tenantId, String studentId) {
        inTenantTx(tenantId, conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO student_profile (student_id, tenant_id, grade, subject) "
                  + "VALUES (?, ?::uuid, '初二', '数学') "
                  + "ON CONFLICT (student_id, tenant_id) DO NOTHING")) {
                ps.setString(1, studentId);
                ps.setString(2, tenantId);
                ps.executeUpdate();
            }
        });
        createdStudentIds.add(studentId);
    }

    /** 播种一条薄弱点（需 run_id 外键存在）。 */
    private void seedWeakness(String tenantId, String studentId, String runId) {
        inTenantTx(tenantId, conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO weakness_record (tenant_id, student_id, run_id, "
                  + "knowledge_point, error_type, confidence) "
                  + "VALUES (?::uuid, ?, ?::uuid, '根式分母有理化', '符号错误', 0.82) "
                  + "ON CONFLICT (tenant_id, student_id, run_id, knowledge_point) DO NOTHING")) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                ps.setString(3, runId);
                ps.executeUpdate();
            }
        });
    }

    /** 生成一个符合白名单格式的随机学生标识。 */
    private static String newStudentId() {
        return "stu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 清理本用例创建的数据。
     *
     * <p>⚠️⚠️ <b>删除必须绑定租户</b>（P4-D10 实测教训）：
     * 直接用 {@code JdbcTemplate}（连接为非特权角色 {@code app_agent}）执行
     * {@code DELETE FROM run ...} 会命中 RLS：
     * 策略是 {@code tenant_id = current_setting('app.tenant_id', TRUE)::uuid}，
     * 而未绑定租户时该设置返回 {@code NULL} → <b>恒不匹配</b> →
     * {@code DELETE 0}，<b>什么都没删掉</b>。
     *
     * <p>后果不是「库里多几行」：残留的 {@code run} 行会占满
     * {@code claimTasks} 的 {@code ORDER BY created_at LIMIT 1000} 窗口，
     * 且残留的 {@code rate_limit_bucket} 计数会**吃掉限流配额**，
     * 使后续测试随机收到 429 —— <b>失败现场指向错误方向</b>。
     * （这已是 F-9「测试数据污染」的同形态第三次出现。）
     */
    @AfterEach
    void cleanUp() {
        for (String slug : new String[] {"t-a", "t-b"}) {
            String tenantId = tenantIdOf(slug);
            inTenantTx(tenantId, conn -> {
                for (String runId : createdRunIds) {
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM run WHERE run_id = ?::uuid")) {
                        ps.setString(1, runId);
                        ps.executeUpdate();
                    }
                }
                for (String studentId : createdStudentIds) {
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM weakness_record WHERE student_id = ?")) {
                        ps.setString(1, studentId);
                        ps.executeUpdate();
                    }
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM student_profile WHERE student_id = ?")) {
                        ps.setString(1, studentId);
                        ps.executeUpdate();
                    }
                }
                // ⚠️ 限流桶计数同样必须清：它是 DB 状态，跨轮次累积会把
                //    「30 次/60 秒」的配额吃光 → 后续测试随机 429。
                try (var ps = conn.prepareStatement(
                        "DELETE FROM rate_limit_bucket")) {
                    ps.executeUpdate();
                }
            });
        }
        createdRunIds.clear();
        createdStudentIds.clear();
    }

    // ── G3：Run 状态查询 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ G3-1 存在的 Run → 200 且状态字段正确（连通性）")
    void g3ReturnsStatus() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED");

        rest.get().uri("/api/runs/{runId}", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(runId)
                .jsonPath("$.state").isEqualTo("SUCCEEDED")
                .jsonPath("$.attempt").isNumber()
                .jsonPath("$.createdAt").exists();
    }

    @Test
    @DisplayName("✅ G3-2 不存在的 Run → 404「资源不存在」")
    void g3MissingRunIs404() {
        String tenantId = tenantIdOf("t-a");
        rest.get().uri("/api/runs/{runId}", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("⛔ G3-3 跨租户的 Run → 404（不是 200 也不是 403）")
    void g3CrossTenantRunIs404() {
        String tenantA = tenantIdOf("t-a");
        String tenantB = tenantIdOf("t-b");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantA, runId, "SUCCEEDED");

        // ⚠️ 用 B 租户的令牌读 A 租户的 Run：
        //    必须 404（不是 403）——403 与 404 的差异会构成存在性探测通道。
        rest.get().uri("/api/runs/{runId}", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantB, "p-d09b", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("⛔ G3-4 无令牌 → 401")
    void g3NoTokenRejected() {
        rest.get().uri("/api/runs/{runId}", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ G3-5 非终态 Run 的 completedAt 为空")
    void g3NonTerminalHasNoCompletedAt() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "QUEUED");

        rest.get().uri("/api/runs/{runId}", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.state").isEqualTo("QUEUED")
                .jsonPath("$.completedAt").doesNotExist();
    }

    @Test
    @DisplayName("✅ G3-6 state 绝不为 UNKNOWN（UNKNOWN 是 tool_operation 状态）")
    void g3StateIsNeverUnknown() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "FAILED");

        String body = rest.get().uri("/api/runs/{runId}", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body)
                .as("⚠️⚠️ UNKNOWN 不是 run_state 枚举值——若返回它，"
                        + "前端 isTerminalState 判 false → 轮询永不停止")
                .doesNotContain("\"UNKNOWN\"");
    }

    // ── G6：学习画像查询 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ G6-1 有画像 → 200 且字段正确（连通性）")
    void g6ReturnsProfile() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED");
        String studentId = newStudentId();
        seedProfile(tenantId, studentId);
        seedWeakness(tenantId, studentId, runId);

        rest.get().uri("/api/profiles/{studentId}", studentId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.studentId").isEqualTo(studentId)
                .jsonPath("$.grade").isEqualTo("初二")
                .jsonPath("$.subject").isEqualTo("数学")
                .jsonPath("$.notFound").isEqualTo(false)
                .jsonPath("$.weaknesses[0].knowledgePoint").isEqualTo("根式分母有理化")
                .jsonPath("$.weaknesses[0].errorType").isEqualTo("符号错误")
                .jsonPath("$.weaknesses[0].confidence").isEqualTo(0.82);
    }

    @Test
    @DisplayName("✅ G6-2 不存在的学生 → 200 + notFound=true（不是 404）")
    void g6MissingStudentIsNotFoundNot404() {
        String tenantId = tenantIdOf("t-a");

        rest.get().uri("/api/profiles/{studentId}", newStudentId())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.notFound").isEqualTo(true)
                .jsonPath("$.grade").doesNotExist()
                .jsonPath("$.weaknesses.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("⛔ G6-3 响应不含姓名/学校/班级等键（白名单纪律）")
    void g6NoIdentifyingKeys() {
        String tenantId = tenantIdOf("t-a");
        String studentId = newStudentId();
        seedProfile(tenantId, studentId);

        String body = rest.get().uri("/api/profiles/{studentId}", studentId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body)
                .as("⛔ 画像响应只允许四类信息：随机 studentId / 年级 / 学科 / 薄弱点")
                .doesNotContain("\"name\"")
                .doesNotContain("\"school\"")
                .doesNotContain("\"className\"")
                .doesNotContain("\"idCard\"")
                .doesNotContain("\"mobile\"");
    }

    @Test
    @DisplayName("⛔ G6-4 跨租户画像不可见")
    void g6CrossTenantProfileNotVisible() {
        String tenantA = tenantIdOf("t-a");
        String tenantB = tenantIdOf("t-b");
        String studentId = newStudentId();
        seedProfile(tenantA, studentId);

        rest.get().uri("/api/profiles/{studentId}", studentId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantB, "p-d09b", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.notFound").isEqualTo(true);
    }

    @Test
    @DisplayName("⛔ G6-5 无令牌 → 401")
    void g6NoTokenRejected() {
        rest.get().uri("/api/profiles/{studentId}", "stu_0123456789abcdef")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ G6-6 无 profile:read 权限的角色 → 403")
    void g6RoleWithoutProfileReadForbidden() {
        String tenantId = tenantIdOf("t-a");
        // ⚠️ WORKER 角色刻意不含 PROFILE_READ（执行侧服务账号不该读学生画像）
        rest.get().uri("/api/profiles/{studentId}", "stu_0123456789abcdef")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d09w", "WORKER"))
                .exchange()
                .expectStatus().isForbidden();
    }
}
