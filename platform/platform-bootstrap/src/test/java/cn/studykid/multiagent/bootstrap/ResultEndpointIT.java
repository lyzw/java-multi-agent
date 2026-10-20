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
 * G5 结果读取端点测试（P4-D10）。
 *
 * <p>⚠️ <b>本类属「连通性测试」</b>（P4-D01/D08 确立的方法论）：
 * 它验证的不只是「端点被直接调用时行为正确」，
 * 而是<b>端点在真实 Spring 装配下真的被注册、真的能取到数据</b>。
 * P4-D08 的 R-1 已证明：**缺这一层，整条链路可断裂而不被发现**。
 *
 * <p>⚠️ <b>须有真实数据库</b>：未设 {@code IT_PG_URL} 时跳过（不是失败）。
 *
 * <p>⚠️ <b>必须用 {@code RANDOM_PORT} 而非 {@code MOCK}</b>：
 * {@code MOCK} 环境下安全过滤器链<b>不装配</b>，无令牌请求会直达控制器
 * 并被兜底处理器吞成 <b>500</b>，使「无令牌 → 401」这项永远红。
 * 产品安全链本身正确，坏的只是测试环境。
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
@DisplayName("P4-D10 G5 结果读取端点")
class ResultEndpointIT {

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

    private String token(String tenantId, String principalId, String role) {
        return tokens.issue(
                new TenantContext(tenantId, principalId, Set.of(role), 1L),
                Instant.now());
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
     * 播种一个 Run。
     *
     * @param state    Run 状态
     * @param payload  {@code result_payload} 的 JSON 文本；{@code null} 表示无载荷
     */
    private void seedRun(String tenantId, String runId, String state, String payload) {
        inTenantTx(tenantId, conn -> {
            // ⚠️⚠️ 终态必须补 completed_at，否则触发 run_completed_at_consistent 约束：
            //    这些是**不变量**，测试**不得**绕过——绕过即等于让不变量失效。
            boolean terminal = !"QUEUED".equals(state) && !"RUNNING".equals(state);
            String completedAt = terminal ? "now()" : "NULL";
            String resultPayload = payload == null ? "NULL" : "'" + payload + "'::jsonb";
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
                ps.setString(4, "d10-" + runId);
                ps.setString(5, "d10-" + runId);
                ps.setString(6, state);
                ps.setString(7, "corr-" + runId);
                ps.executeUpdate();
            }
        });
        createdRunIds.add(runId);
    }

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

    /** 播种一个未决（UNKNOWN）工具操作——待核对分区的权威来源。 */
    private void seedUnknownOperation(String tenantId, String runId, String toolName) {
        inTenantTx(tenantId, conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO tool_operation (tenant_id, run_id, tool_name, risk_level, "
                  + "state, idempotency_key, request_digest, tool_arguments, fencing_token) "
                  + "VALUES (?::uuid, ?::uuid, ?, 'WRITE_PROFILE'::tool_risk_level, "
                  + "'UNKNOWN'::tool_operation_state, ?, ?, '{}'::jsonb, 1)")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setString(3, toolName);
                ps.setString(4, "d10-op-" + runId);
                ps.setString(5, "d10-op-" + runId);
                ps.executeUpdate();
            }
        });
    }

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

    // ── 连通性（E）与契约（R）────────────────────────────────────────

    @Test
    @DisplayName("✅ E-1/R-1 成功的 Run → 200，讲解内容与完成时刻都在")
    void resultReturnsExplanation() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"先配方化为顶点式\"}");

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(runId)
                .jsonPath("$.state").isEqualTo("SUCCEEDED")
                .jsonPath("$.explanation").isEqualTo("先配方化为顶点式")
                .jsonPath("$.completedAt").exists();
    }

    @Test
    @DisplayName("⛔ E-2 无令牌 → 401（不是 500）")
    void noTokenRejected() {
        rest.get().uri("/api/runs/{runId}/result", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ E-3 不存在的 Run → 404")
    void missingRunIs404() {
        String tenantId = tenantIdOf("t-a");
        rest.get().uri("/api/runs/{runId}/result", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("⛔ E-4 跨租户的 Run → 404（不是 403）")
    void crossTenantRunIs404() {
        String tenantA = tenantIdOf("t-a");
        String tenantB = tenantIdOf("t-b");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantA, runId, "SUCCEEDED", "{\"content\":\"d10\"}");

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantB, "p-d10b", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("✅ R-2 非终态 Run → completedAt 与 explanation 都不存在")
    void nonTerminalHasNoCompletedAtNorExplanation() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "RUNNING", null);

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.state").isEqualTo("RUNNING")
                .jsonPath("$.completedAt").doesNotExist()
                .jsonPath("$.explanation").doesNotExist();
    }

    @Test
    @DisplayName("⛔ R-3 失败的 Run → 有 errorCode，但绝不返回失败原因原文")
    void failureExposesCodeButNotReason() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        // ⚠️ 失败载荷的真实形状是 {"reason":"<内部失败原因>"}
        seedRun(tenantId, runId, "FAILED", "{\"reason\":\"模型调用失败：TIMEOUT\"}");

        String body = rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("⚠️ 失败原因属内部错误细节，不得出现在响应里")
                .doesNotContain("模型调用失败")
                .doesNotContain("TIMEOUT")
                .doesNotContain("reason");
        assertThat(body).contains("\"state\":\"FAILED\"");
    }

    @Test
    @DisplayName("✅ R-4 weaknesses 与 steps 恒为数组（空即空数组，不是 null）")
    void collectionsAreAlwaysArrays() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"d10\"}");

        String body = rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("前端必须能区分「没有数据」与「接口坏了」")
                .contains("\"weaknesses\":[]")
                .contains("\"steps\":[]");
    }

    @Test
    @DisplayName("✅ R-5 待核对分区恒存在（结构固定，不是「有才显示」）")
    void reconciliationAlwaysPresent() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"d10\"}");

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reconciliation").exists()
                .jsonPath("$.reconciliation.pending").isEqualTo(false)
                .jsonPath("$.reconciliation.operations").isArray();
    }

    @Test
    @DisplayName("⛔ R-6 存在未决 UNKNOWN 操作 → pending=true 且列出该操作")
    void unresolvedOperationIsExposed() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"d10\"}");
        seedUnknownOperation(tenantId, runId, "record_weakness");

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reconciliation.pending").isEqualTo(true)
                .jsonPath("$.reconciliation.operations.length()").isEqualTo(1)
                .jsonPath("$.reconciliation.operations[0].toolName")
                .isEqualTo("record_weakness")
                .jsonPath("$.reconciliation.operations[0].state").isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("✅ R-7 响应不含学生提交内容、租户标识与引用字段")
    void responseLeaksNothing() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"d10\"}");

        String body = rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("⚠️ 敏感载荷/租户标识/引用字段一律不得出现")
                .doesNotContain("inputPayload")
                .doesNotContain("input_payload")
                .doesNotContain("tenantId")
                .doesNotContain("tenant_id")
                .doesNotContain("errorMessage")
                .doesNotContain("citation")
                .doesNotContain("pageNumber")
                .doesNotContain("ISBN");
    }

    @Test
    @DisplayName("✅ R-6b 薄弱点按结构化对象返回（knowledgePoint/errorType/confidence）")
    void weaknessesAreStructured() {
        String tenantId = tenantIdOf("t-a");
        String runId = UUID.randomUUID().toString();
        String studentId = newStudentId();
        seedRun(tenantId, runId, "SUCCEEDED", "{\"content\":\"d10\"}");
        seedProfile(tenantId, studentId);
        seedWeakness(tenantId, studentId, runId);

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantId, "p-d10", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.weaknesses.length()").isEqualTo(1)
                .jsonPath("$.weaknesses[0].knowledgePoint").isEqualTo("根式分母有理化")
                .jsonPath("$.weaknesses[0].errorType").isEqualTo("符号错误")
                .jsonPath("$.weaknesses[0].confidence").isNumber();
    }

    @Test
    @DisplayName("⛔ R-8 跨租户读不到薄弱点（A-1 反证：去掉 tenant_id 条件即转红）")
    void crossTenantWeaknessesHidden() {
        String tenantA = tenantIdOf("t-a");
        String tenantB = tenantIdOf("t-b");
        String runId = UUID.randomUUID().toString();
        String studentId = newStudentId();
        seedRun(tenantA, runId, "SUCCEEDED", "{\"content\":\"d10\"}");
        seedProfile(tenantA, studentId);
        seedWeakness(tenantA, studentId, runId);

        rest.get().uri("/api/runs/{runId}/result", runId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(tenantB, "p-d10b", "TENANT_ADMIN"))
                .exchange()
                .expectStatus().isNotFound();
    }
}
