package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 配置写入端点契约（P4-D15 / F6 · C3 端点 + C5 收紧 + C7 理由）。
 *
 * <p>⚠️ 与其余端点 IT 同源：<b>必须用 RANDOM_PORT</b>
 * （MOCK 下不装配 Security 过滤器链，401/403 断言全是假的）。
 *
 * <p>⛔ <b>错误码语义是本类重点</b>：400（输入错）与 409（安全门禁降级）
 * 必须区分——前端要据此提示「你的输入有问题」还是「这次变更被安全策略拒绝」，
 * 混为一谈会让用户反复重试一个注定被拒的操作。
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
@DisplayName("P4-D15 配置写入端点契约")
class ConfigOverrideEndpointIT {

    private static final String REASON = "试点期收紧上限以控制成本";

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private DataSource dataSource;

    private HikariDataSource adminPool;
    private String tenantA;

    private synchronized void adminPool() {
        if (adminPool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
            cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
            cfg.setMaximumPoolSize(2);
            adminPool = new HikariDataSource(cfg);
        }
    }

    private synchronized void initTenants() {
        if (tenantA == null) {
            tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
            adminExec("DELETE FROM tenant_config_override WHERE tenant_id = '"
                    + tenantA + "'::uuid");
        }
    }

    private String token(String role) {
        initTenants();
        return "Bearer " + tokens.issue(
                new TenantContext(tenantA, "admin-1", Set.of(role), 1L), Instant.now());
    }

    private String adminAuth() {
        return token("TENANT_ADMIN");
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

    private void putOverride(String key, String value, String reason) {
        rest.put().uri("/api/config/overrides/{key}", key)
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"value\":\"" + value + "\",\"reason\":\"" + reason + "\"}")
                .exchange().expectStatus().isOk();
    }

    // ── 鉴权 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-0 无令牌 → 401（三个端点一致）")
    void endpointsRequireToken() {
        initTenants();
        rest.get().uri("/api/config/overrides").exchange()
                .expectStatus().isUnauthorized();
        rest.put().uri("/api/config/overrides/agent.run.max-steps")
                .contentType(MediaType.APPLICATION_JSON).body("{}")
                .exchange().expectStatus().isUnauthorized();
        rest.delete().uri("/api/config/overrides/agent.run.max-steps")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ D15-0b 无 AGENT_MANAGE（VIEWER）→ 403")
    void endpointsRequirePermission() {
        initTenants();
        rest.get().uri("/api/config/overrides")
                .header("Authorization", token("VIEWER"))
                .exchange().expectStatus().isForbidden();
    }

    // ── 写入与读取 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-1 PUT 写入成功，GET 返回生效值与来源 db_override")
    void writeThenRead() {
        initTenants();
        // ⚠️ **必须用 L2 键**：P4-D16 起 L3 键直写返回 409（须走审批）。
        //    本用例验的是「写入→读取」的连通性，用 L2（approval.ttl）语义等价。
        putOverride("agent.approval.ttl", "10m", REASON);

        String body = rest.get().uri("/api/config/overrides")
                .header("Authorization", adminAuth())
                .exchange().expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).as("生效值应来自 DB 覆盖").contains("\"effectiveValue\":\"10m\"");
        assertThat(body).as("来源应标记 db_override").contains("\"source\":\"db_override\"");
    }

    @Test
    @DisplayName("⛔ D15-S 覆盖列表不泄露私钥/口令（与 C1 端点同一红线）")
    void noSecretsInOverrides() {
        initTenants();
        String body = rest.get().uri("/api/config/overrides")
                .header("Authorization", adminAuth())
                .exchange().expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // ⚠️ 与 ConfigEndpointIT#noSecrets 同一条红线：
        //    新端点返回「键 + 声明值 + 生效值」，若目录里混入敏感键就会泄露。
        //    白名单（ConfigOverridePort.WRITABLE）是这条断言的承重结构。
        assertThat(body)
                .as("⛔ 配置写端点泄露密钥")
                .doesNotContain("private-key")
                .doesNotContain("RABBITMQ_PASSWORD")
                .doesNotContain("BEGIN PRIVATE KEY")
                .doesNotContain("password");
    }

    // ── C5：收紧约束的 HTTP 语义 ───────────────────────────────────

    @Test
    @DisplayName("✅ D15-3 试图放宽上限 → 409（非 400：安全门禁降级）")
    void looseningRejectedAsConflict() {
        initTenants();
        putOverride("agent.approval.ttl", "10m", REASON);

        rest.put().uri("/api/config/overrides/agent.approval.ttl")
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"value\":\"30m\",\"reason\":\"" + REASON + "\"}")
                .exchange().expectStatus().isEqualTo(409)
                // ⚠️ code 必须是 CONFIG_RELAXATION_REJECTED 而非 IDEMPOTENCY_CONFLICT：
                //    前端据此决定提示「只能收紧」而非「刷新重试」——
                //    错标会把安全拒绝伪装成可重试冲突，诱导无效重试。
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONFIG_RELAXATION_REJECTED");

        // ⚠️ 被拒的写入**不得落库**——否则「拒绝了但还是生效」比不拒绝更危险。
        String stored = adminQuery("SELECT value_text FROM tenant_config_override "
                + "WHERE tenant_id = '" + tenantA + "'::uuid "
                + "AND config_key = 'agent.approval.ttl'");
        assertThat(stored).as("⛔ 被拒的放宽写入竟落库").isEqualTo("10m");
    }

    @Test
    @DisplayName("✅ D15-4 审批矩阵放松 → 409")
    void approvalRelaxationRejected() {
        initTenants();
        putOverride("agent.approval.required-by-risk",
                "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED", REASON);

        rest.put().uri("/api/config/overrides/agent.approval.required-by-risk")
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"value\":\"READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED\","
                        + "\"reason\":\"" + REASON + "\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONFIG_RELAXATION_REJECTED");
    }

    // ── C7：理由强制 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-5 理由过短 → 400")
    void shortReasonRejected() {
        initTenants();
        rest.put().uri("/api/config/overrides/agent.approval.ttl")
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"value\":\"10m\",\"reason\":\"太贵\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("✅ D15-5b 值格式非法 → 400（不放行、不取默认值兜底）")
    void malformedValueRejected() {
        initTenants();
        rest.put().uri("/api/config/overrides/agent.approval.ttl")
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"value\":\"not-a-duration\",\"reason\":\"" + REASON + "\"}")
                .exchange().expectStatus().isBadRequest();
    }

    // ── 不可写键 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 不可写键 → 404（不暴露「哪些键存在」）")
    void nonWritableKeyReturnsNotFound() {
        initTenants();
        for (String key : new String[]{
                "auth.jwt.private-key", "agent.tool.risk-level-map", "nope"}) {
            rest.put().uri("/api/config/overrides/{key}", key)
                    .header("Authorization", adminAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"value\":\"x\",\"reason\":\"" + REASON + "\"}")
                    .exchange().expectStatus().isNotFound();
        }
    }

    // ── 清除 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-8 DELETE 清除覆盖 → 204，且回落 yml 默认")
    void clearOverride() {
        initTenants();
        putOverride("agent.approval.ttl", "10m", REASON);

        rest.delete().uri("/api/config/overrides/agent.approval.ttl")
                .header("Authorization", adminAuth())
                .exchange().expectStatus().isNoContent();

        String stored = adminQuery("SELECT count(*)::text FROM tenant_config_override "
                + "WHERE tenant_id = '" + tenantA + "'::uuid "
                + "AND config_key = 'agent.approval.ttl'");
        assertThat(stored).as("⛔ 清除后仍有残留").isEqualTo("0");
    }

    // ── C4：审计连通性 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-6 配置变更真落 audit_record（CONFIG_UPDATE）")
    void changeIsAudited() {
        initTenants();
        putOverride("agent.approval.ttl", "10m", REASON);

        long n = Long.parseLong(adminQuery("SELECT count(*)::text FROM audit_record "
                + "WHERE tenant_id = '" + tenantA + "'::uuid "
                + "AND action = 'CONFIG_UPDATE' "
                + "AND resource_id = 'agent.approval.ttl'"));
        assertThat(n).as("⛔ 配置变更未落审计 = 无声变更").isGreaterThanOrEqualTo(1L);
    }
}
