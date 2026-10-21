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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * Agent 配置与发布端点契约 + C6 配置审计连通性（P4-D14 / G2 · FE-01 §P18 / §P19）。
 *
 * <p>⚠️ 与 {@code GovernanceEndpointIT} 同源：<b>必须用 RANDOM_PORT</b>
 * （MOCK 下不装配 Security 过滤器链，401/403 断言全是假的）。
 *
 * <p>⛔ <b>D01 连通性</b>：本类不仅验证状态码语义，
 * 还验证「{@code GET /api/config} 后 {@code audit_record} 真的多了一条 {@code CONFIG_VIEW}」——
 * 否则 C6 只是「看起来通了」。
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
@DisplayName("P4-D14 G2 Agent 配置与 C6 配置审计端点契约")
class AgentEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    private HikariDataSource adminPool;
    private String tenantA;

    @BeforeAll
    static void unusedHook() {
    }

    private synchronized void adminPool() {
        if (adminPool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
            cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
// ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
//    密码认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，否则连接失败。
            String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
            if (adminPwd != null && !adminPwd.isBlank()) {
                cfg.setPassword(adminPwd);
            }
            cfg.setMaximumPoolSize(2);
            adminPool = new HikariDataSource(cfg);
        }
    }

    private String token(String role) {
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

    private synchronized void initTenants() {
        if (tenantA == null) {
            tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        }
    }

    private String newAgentId() {
        String agentId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO agent (agent_id, tenant_id, name, description, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'ep-agent-%s', 'seeded', now(), now())
                """.formatted(agentId, tenantA, agentId));
        return agentId;
    }

    // ── G2：Agent 端点契约 ──────────────────────────────────────────

    @Test
    @DisplayName("✅ G2-1 无令牌 → 401（三个端点一致）")
    void agentEndpointsRequireToken() {
        initTenants();
        rest.get().uri("/api/agents").exchange().expectStatus().isUnauthorized();
        rest.post().uri("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"x\"}").exchange().expectStatus().isUnauthorized();
        rest.post().uri("/api/agents/{id}/versions", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).body("{\"definition\":\"{}\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("✅ G2-2 无 AGENT_MANAGE 角色（VIEWER）→ 403")
    void agentEndpointsRequirePermission() {
        initTenants();
        rest.get().uri("/api/agents")
                .header("Authorization", token("VIEWER"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("✅ G2-3 列出 Agent → 200，items 数组，不含 tenantId")
    void listAgentsReturns200() {
        initTenants();
        rest.get().uri("/api/agents?limit=10")
                .header("Authorization", adminAuth())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items[*].agentId").exists()
                .jsonPath("$.items[*].name").exists()
                .jsonPath("$.items[*].versionCount").exists()
                .jsonPath("$.items[*].tenantId").doesNotExist();
    }

    @Test
    @DisplayName("✅ G2-4 创建 Agent → 200，返回 agentId 且 versionCount=0")
    void createAgentReturns200() {
        initTenants();
        String name = "ep-create-" + UUID.randomUUID();
        rest.post().uri("/api/agents")
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"" + name + "\",\"description\":\"d\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.agentId").exists()
                .jsonPath("$.name").isEqualTo(name)
                .jsonPath("$.versionCount").isEqualTo(0);
    }

    @Test
    @DisplayName("⛔ G2-5 重名创建 → 409（唯一约束冲突，非 400）")
    void duplicateNameReturns409() {
        initTenants();
        String name = "ep-dup-" + UUID.randomUUID();
        String auth = adminAuth();
        rest.post().uri("/api/agents")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"" + name + "\"}")
                .exchange().expectStatus().isOk();
        rest.post().uri("/api/agents")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"" + name + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("✅ G2-6 发布新版本 → 200，versionNumber=1")
    void publishVersionReturns200() {
        initTenants();
        String agentId = newAgentId();
        rest.post().uri("/api/agents/{id}/versions", agentId)
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"definition\":\"{\\\"kind\\\":\\\"tutor\\\"}\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.versionId").exists()
                .jsonPath("$.versionNumber").isEqualTo(1)
                .jsonPath("$.definition").exists();
    }

    @Test
    @DisplayName("⛔ G2-7 发布到不存在的 Agent → 404")
    void publishToUnknownAgentReturns404() {
        initTenants();
        rest.post().uri("/api/agents/{id}/versions", UUID.randomUUID())
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"definition\":\"{\\\"kind\\\":\\\"tutor\\\"}\"}")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("⛔ G2-8 非法 definition（非 JSON）→ 400")
    void publishInvalidDefinitionReturns400() {
        initTenants();
        String agentId = newAgentId();
        rest.post().uri("/api/agents/{id}/versions", agentId)
                .header("Authorization", adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"definition\":\"not-json\"}")
                .exchange().expectStatus().isBadRequest();
    }

    // ── C6：配置查看审计连通性 ──────────────────────────────────────

    @Test
    @DisplayName("⛔ C6-1 GET /api/config 后 audit_record 真多一条 CONFIG_VIEW（fail-open 连通性）")
    void configViewWritesAudit() {
        initTenants();
        long before = countConfigViewAudit();
        rest.get().uri("/api/config")
                .header("Authorization", adminAuth())
                .exchange().expectStatus().isOk();
        long after = countConfigViewAudit();
        assertThat(after - before)
                .as("⛔ C6 不只是「端点通了」，查看配置必须真留痕（否则 R-1 缺口补不上）")
                .isGreaterThanOrEqualTo(1);
    }

    private long countConfigViewAudit() {
        return Long.parseLong(adminQuery(
                "SELECT count(*)::text FROM audit_record "
                        + "WHERE tenant_id='%s'::uuid AND action='CONFIG_VIEW' AND resource_type='config'"
                        .formatted(tenantA)));
    }
}
