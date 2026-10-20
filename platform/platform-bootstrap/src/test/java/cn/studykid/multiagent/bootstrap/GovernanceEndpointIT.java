package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * G7~G10 治理端点连通性与 HTTP 契约测试（P4-D13）。
 *
 * <p>⚠️ <b>必须用 {@code RANDOM_PORT} 而非 {@code MOCK}</b>（P4-D09 实测）：
 * {@code MOCK} 下不装配 Spring Security 过滤器链，
 * 无令牌请求会直达控制器 → 401 断言永远红，
 * 且会让后来者误以为「安全链坏了」而去改产品代码。
 *
 * <p>本类验证<b>端点契约</b>：状态码语义（400/403/404/409）、响应字段白名单、
 * 租户隔离。仓储层行为正确性由 {@code GovernanceQueryIT} 覆盖。
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
@DisplayName("P4-D13 G7~G10 治理端点契约")
class GovernanceEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private DataSource dataSource;

    /**
     * ⛔⛔ <b>夹具专用的高权限连接——绝不是注入的 {@link #dataSource}</b>。
     *
     * <p>Spring 注入的是 <b>非特权角色 app_agent</b>（且表上强制 RLS），
     * 它<b>读不到 {@code execution_snapshot}、也写不进 {@code run}</b>。
     * 用它造夹具会得到一个极具误导性的失败现场：
     * 「找不到快照」「审批列表永远是空的」——
     * 而真实原因是<b>夹具没造出来</b>，与被测端点无关。
     *
     * <p>这与 GovernanceQueryIT 的 {@code adminQuery} 同源。
     */
    private HikariDataSource adminPool;

    private String tenantA;
    private String tenantB;
    private String snapshotId;

    @BeforeAll
    static void unusedHook() {
        // 占位以提醒：真正初始化在 initTenants()（懒加载，避免与 Spring 生命周期竞争）
    }

    private synchronized void adminPool() {
        if (adminPool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
            cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
            cfg.setMaximumPoolSize(2);
            adminPool = new HikariDataSource(cfg);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private String token(String tenantId, String principalId, String role) {
        return tokens.issue(
                new TenantContext(tenantId, principalId, Set.of(role), 1L), Instant.now());
    }

    private String adminQuery(String sql) {
        adminPool();
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("应有结果: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private void adminExec(String sql) {
        adminPool();
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入失败: " + sql, e);
        }
    }

    private synchronized void initTenants() {
        if (tenantA == null) {
            tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
            tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
        }
    }

    /**
     * 造一个 Run。
     *
     * <p>⚠️ <b>不能依赖 {@code dev-it-db.sh} 的播种</b>：那个脚本每次都会
     * <b>清理</b>既有数据，种子库里并不保证有 Run。
     * 用例自建夹具才能做到「连跑两轮结果一致」（F-9 的教训：被清理的夹具会让后来者看到莫名失败）。
     */
    private synchronized String newRun() {
        initTenants();
        if (snapshotId == null) {
            snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
        }
        String runId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key,
                                 request_digest, state, input_payload, correlation_id)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, '%s', 'digest',
                        'RUNNING', '{"question":"x"}'::jsonb, '%s')
                """.formatted(runId, tenantA, snapshotId, "ep-run-" + runId, "corr-" + runId));
        return runId;
    }

    /** 造一条指定状态的 tool_operation（真实审批需要它做外键）。 */
    private String newOperation(String state) {
        String runId = newRun();
        String operationId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO tool_operation
                    (operation_id, tenant_id, run_id, tool_name, risk_level, state,
                     idempotency_key, request_digest, tool_arguments, fencing_token,
                     created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, 'record_weakness',
                        'WRITE_PROFILE', '%s'::tool_operation_state, '%s', 'digest',
                        '{}'::jsonb, 0, now(), now())
                """.formatted(operationId, tenantA, runId, state, "ep-op-" + operationId));
        return operationId;
    }

    private String newUnknownOperation() {
        return newOperation("UNKNOWN");
    }

    private String newApproval(String operationId, boolean expired) {
        Instant expiresAt = Instant.now().plusSeconds(expired ? -60 : 3600);
        String approvalId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO approval
                    (approval_id, tenant_id, operation_id, state, requirement,
                     argument_digest, target_resource, approver_scope, expires_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, 'PENDING', 'REQUIRED',
                        'sha256:abc', 'profile/s1', 'TENANT', '%s'::timestamptz)
                """.formatted(approvalId, tenantA, operationId,
                java.sql.Timestamp.from(expiresAt).toString()));
        return approvalId;
    }

    private String adminAuth() {
        return "Bearer " + token(tenantA, "admin-1", "TENANT_ADMIN");
    }

    private String approverAuth() {
        return "Bearer " + token(tenantA, "approver-1", "APPROVER");
    }

    // ── G7：Run 列表 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ G7-1 无令牌 → 401")
    void runListRequiresToken() {
        initTenants();
        rest.get().uri("/api/runs").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ G7-2 有令牌 → 200，且响应不含敏感字段")
    void runListReturnsSafeFields() {
        initTenants();
        // ⚠️ 用 jsonPath 逐字段断言而非字符串匹配：
        //    「响应里没有 inputPayload」必须由**字段不存在**来证明，
        //    字符串 contains 会被同名的其它文案误判。
        rest.get().uri("/api/runs?limit=5")
                .header("Authorization", adminAuth())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items[*].runId").isArray()
                .jsonPath("$.items[*].state").isArray()
                .jsonPath("$.items[*].createdAt").isArray()
                // ⛔⛔ 不得泄漏学生提交内容与内部错误细节
                .jsonPath("$.items[*].inputPayload").doesNotExist()
                .jsonPath("$.items[*].input_payload").doesNotExist()
                .jsonPath("$.items[*].errorMessage").doesNotExist()
                .jsonPath("$.items[*].error_message").doesNotExist()
                .jsonPath("$.items[*].tenantId").doesNotExist()
                .jsonPath("$.items[*].idempotencyKey").doesNotExist()
                .jsonPath("$.items[*].requestDigest").doesNotExist();
    }

    @Test
    @DisplayName("✅ G7-3 limit 越界 / 非法 state / 坏游标 / 时间格式错 → 400")
    void invalidParamsReturn400() {
        initTenants();
        String auth = adminAuth();

        // ⛔ 上限 100 是契约不是建议（反证 A-3）
        rest.get().uri("/api/runs?limit=101").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        rest.get().uri("/api/runs?limit=0").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        rest.get().uri("/api/runs?limit=-5").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        // ⛔ run_state 不含 UNKNOWN——必须 400 而非空列表
        //    （空列表会把「状态不存在」伪装成「没有数据」）
        rest.get().uri("/api/runs?state=UNKNOWN").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        rest.get().uri("/api/runs?cursor=%21%21bad").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        rest.get().uri("/api/runs?createdFrom=not-a-date").header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
        rest.get().uri(
                "/api/runs?createdFrom=2030-01-01T00:00:00Z&createdTo=2020-01-01T00:00:00Z")
                .header("Authorization", auth)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("✅ G7-4 VIEWER 有 RUN_READ → 200")
    void viewerCanReadRunList() {
        initTenants();
        rest.get().uri("/api/runs?limit=1")
                .header("Authorization", "Bearer " + token(tenantA, "viewer-1", "VIEWER"))
                .exchange()
                .expectStatus().isOk();
    }

    // ── G8：审批 ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ G8-1 无令牌 → 401")
    void approvalListRequiresToken() {
        initTenants();
        rest.get().uri("/api/approvals").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ G8-2 列表 200，且不含 tenantId")
    void approvalListReturnsSafeFields() {
        initTenants();
        rest.get().uri("/api/approvals?state=PENDING&limit=20")
                .header("Authorization", approverAuth())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                // ⛔ FR-014：审批人必须能看到「参数摘要 + 目标资源 + 有效期」
                .jsonPath("$.items[*].argumentDigest").exists()
                .jsonPath("$.items[*].targetResource").exists()
                .jsonPath("$.items[*].expiresAt").exists()
                .jsonPath("$.items[*].expired").exists()
                .jsonPath("$.items[*].tenantId").doesNotExist();
    }

    @Test
    @DisplayName("⛔ G8-3 过期审批批准 → 409（FE-01：过期须拒绝而非静默通过）")
    void approvingExpiredApprovalReturns409() {
        String operationId = newUnknownOperation();
        String approvalId = newApproval(operationId, true);

        rest.post().uri("/api/approvals/{id}/decision", approvalId)
                .header("Authorization", approverAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(adminQuery("SELECT state::text FROM approval WHERE approval_id='"
                + approvalId + "'"))
                .as("⛔ 拒绝后状态必须仍是 PENDING（不得因「已过期」而被改成 APPROVED）")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("✅ G8-4 正常批准 → 200，state=APPROVED")
    void approvingValidApprovalReturns200() {
        String operationId = newUnknownOperation();
        String approvalId = newApproval(operationId, false);

        rest.post().uri("/api/approvals/{id}/decision", approvalId)
                .header("Authorization", approverAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus().isOk();

        assertThat(adminQuery("SELECT state::text FROM approval WHERE approval_id='"
                + approvalId + "'")).isEqualTo("APPROVED");
        assertThat(adminQuery("SELECT approver_id FROM approval WHERE approval_id='"
                + approvalId + "'"))
                .as("审批人标识必须落库，且来自令牌")
                .isEqualTo("approver-1");
    }

    @Test
    @DisplayName("✅ G8-5 拒绝无理由 → 400（客户端请求体不合法，不是状态冲突）")
    void denyingWithoutReasonIsRejected() {
        String operationId = newUnknownOperation();
        String approvalId = newApproval(operationId, false);

        rest.post().uri("/api/approvals/{id}/decision", approvalId)
                .header("Authorization", approverAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"DENY\"}")
                .exchange()
                // ⚠️ 必须是 400 而非 409：409 会让前端提示「这条审批已被别人处理」，
                //    而真相是「你少填了理由」——完全错误的引导。
                .expectStatus().isBadRequest();

        assertThat(adminQuery("SELECT state::text FROM approval WHERE approval_id='"
                + approvalId + "'")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("⛔ G8-6 不存在 / 跨租户 → 都返回 404（合并，反证 A-6）")
    void missingAndCrossTenantBothReturn404() {
        initTenants();
        String operationId = newUnknownOperation();
        String approvalId = newApproval(operationId, false);

        rest.post().uri("/api/approvals/{id}/decision", UUID.randomUUID())
                .header("Authorization", approverAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus().isNotFound();

        rest.post().uri("/api/approvals/{id}/decision", approvalId)
                .header("Authorization",
                        "Bearer " + token(tenantB, "approver-x", "APPROVER"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                // ⛔ 跨租户与不存在必须同码——不同码即构成存在性探测通道（反证 A-6）
                .expectStatus().isNotFound();
    }

    // ── G9：UNKNOWN 处置 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ G9-1 无令牌 → 401")
    void unknownListRequiresToken() {
        initTenants();
        rest.get().uri("/api/operations").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ G9-2 disposition=RETRY → 400（枚举里根本没有重试）")
    void retryDispositionIsRejected() {
        String operationId = newUnknownOperation();

        rest.post().uri("/api/operations/{id}/disposition", operationId)
                .header("Authorization", adminAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"disposition\":\"RETRY\"}")
                .exchange()
                // ⛔⛔ 盲目重放 = Sev-0——RETRY 必须在协议层就不存在（反证 A-5）
                .expectStatus().isBadRequest();

        assertThat(adminQuery("SELECT state::text FROM tool_operation WHERE operation_id='"
                + operationId + "'"))
                .as("被拒绝的处置不得改变状态")
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("✅ G9-3 合法处置 → 200，落到归档态并标记人工")
    void validDispositionSucceeds() {
        String operationId = newUnknownOperation();

        rest.post().uri("/api/operations/{id}/disposition", operationId)
                .header("Authorization", adminAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"disposition\":\"RECONCILED_ABSENT\",\"note\":\"已核实未发生\"}")
                .exchange()
                .expectStatus().isOk();

        assertThat(adminQuery("SELECT state::text FROM tool_operation WHERE operation_id='"
                + operationId + "'")).isEqualTo("RECONCILED_ABSENT");
        assertThat(adminQuery("SELECT reconciliation_source FROM tool_operation "
                + "WHERE operation_id='" + operationId + "'"))
                .as("⛔ 必须标记为人工处置，与 P3-D03 自动对账可区分")
                .isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("⛔ G9-4 非 UNKNOWN 态处置 → 409")
    void disposingNonUnknownReturns409() {
        // ⚠️ 自建 SUCCEEDED 操作而不是「找一条现成的」：
        //    依赖库里的存量数据会让本用例随环境漂移（这次就因种子库为空而红）。
        String succeededOperationId = newOperation("SUCCEEDED");

        rest.post().uri("/api/operations/{id}/disposition", succeededOperationId)
                .header("Authorization", adminAuth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"disposition\":\"MANUAL_DISPOSED\"}")
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(adminQuery("SELECT state::text FROM tool_operation WHERE operation_id='"
                + succeededOperationId + "'"))
                .as("⛔ 被拒绝的处置不得改变状态")
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("✅ G9-5 列表不返回工具入参/回包")
    void operationListOmitsToolPayloads() {
        initTenants();
        rest.get().uri("/api/operations?state=UNKNOWN&limit=10")
                .header("Authorization", adminAuth())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                // ⛔ 处置台不得暴露工具入参（学生内容）与回包（内部细节）
                .jsonPath("$.items[*].toolArguments").doesNotExist()
                .jsonPath("$.items[*].tool_arguments").doesNotExist()
                .jsonPath("$.items[*].resultPayload").doesNotExist()
                .jsonPath("$.items[*].result_payload").doesNotExist()
                .jsonPath("$.items[*].tenantId").doesNotExist();
    }

    // ── G10：审计 ───────────────────────────────────────────────────

    @Test
    @DisplayName("✅ G10-1 无令牌 → 401")
    void auditQueryRequiresToken() {
        initTenants();
        rest.get().uri("/api/audit").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ G10-2 查询 → 200，且不含 tenantId")
    void auditQueryReturns200() {
        initTenants();
        rest.get().uri("/api/audit?limit=10")
                .header("Authorization", adminAuth())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items[*].auditId").exists()
                .jsonPath("$.items[*].actorId").exists()
                .jsonPath("$.items[*].action").exists()
                .jsonPath("$.items[*].createdAt").exists()
                .jsonPath("$.items[*].tenantId").doesNotExist();
    }

    @Test
    @DisplayName("✅ G10-3 无 AUDIT_READ 权限的角色 → 403")
    void auditQueryRequiresPermission() {
        initTenants();
        rest.get().uri("/api/audit")
                .header("Authorization", "Bearer " + token(tenantA, "author-1", "AGENT_AUTHOR"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("✅ G-12 治理查询落在**自己的桶**，不污染 EVENT_QUERY")
    void governanceUsesItsOwnBucket() {
        initTenants();
        String auth = adminAuth();

        // ⚠️ **先清两个桶**，否则同窗口内的历史计数会让断言不可重复
        adminExec("DELETE FROM rate_limit_bucket WHERE tenant_id='"
                + tenantA + "'::uuid AND bucket_key IN ('GOVERNANCE_QUERY','EVENT_QUERY')");

        rest.get().uri("/api/audit?limit=1")
                .header("Authorization", auth).exchange()
                .expectStatus().isOk();

        long governance = bucketCount("GOVERNANCE_QUERY");
        long event = bucketCount("EVENT_QUERY");

        assertThat(governance)
                .as("⛔ 治理查询必须计入 GOVERNANCE_QUERY 桶")
                .isGreaterThanOrEqualTo(1);
        assertThat(event)
                .as("⛔⛔ 治理查询若计入 EVENT_QUERY，学生侧进度页轮询配额会被蚕食"
                        + "（FE-D9 配额表余量仅 4 次/分）")
                // ⚠️ `bucketCount` 返回 **-1 表示「无行」**，那正是期望结果：
                //    治理请求**根本没有碰过** EVENT_QUERY 桶。
                //    故判据是「不大于 0」，而不是「等于 0」。
                .isLessThanOrEqualTo(0);
    }

    /** 读某桶在当前窗口的计数（-1 = 无行）。 */
    private long bucketCount(String bucket) {
        String sql = """
                SELECT COALESCE(MAX(count), -1) FROM rate_limit_bucket
                WHERE tenant_id = '%s'::uuid AND bucket_key = '%s'
                  AND window_start = to_timestamp(
                        floor(extract(epoch from now()) / 60)::bigint * 60)
                """.formatted(tenantA, bucket);
        return Long.parseLong(adminQuery(sql));
    }
}
