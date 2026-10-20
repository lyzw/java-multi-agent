package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * C1 端点连通性测试（P4-D07）。
 *
 * <p><b>为什么必须是端到端（真实 HTTP + 真实过滤链 + 真实 PostgreSQL）</b>：
 * 与 {@code PlatformSecurityE2ETest} 同一理由——端点的「存在 + 受保护」
 * 不会体现在任何单元测试里。若 {@code ConfigController} 未被注册、
 * 或 {@code ConfigReadService} 不是真实 Bean，单测全绿但 404 / 500。
 *
 * <p><b>⚠️ 必须带令牌才能证明端点真的存在</b>：未认证请求在
 * {@code anyRequest().authenticated()} 下对任何路径（含不存在的路径）都返回 401，
 * 故仅凭 401 无法区分「端点受保护」与「端点不存在」。带有效令牌命中 200
 * 才证明路由、Bean 装配、安全放行三者都成立。
 *
 * <p>⚠️ 须有数据库才能运行（同 PlatformSecurityE2ETest）：未设 {@code IT_PG_URL} 时跳过。
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
        // ⚠️ 关闭 Worker 主循环与 RabbitMQ 监听（同 E2E），本类只验证配置端点
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ⚠️ RetentionConfig 复用 worker 租户 slug（同 RetentionWiringTest），缺则上下文启动失败
        "agent.worker.tenant-slug=t-a"
})
@DisplayName("P4-D07 C1 配置端点连通性")
class ConfigEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    /** 直接签发一个有效令牌（verify 仅验签 + 标准声明，不查库）。 */
    private String validToken() {
        return tokens.issue(
                new TenantContext("dummy-tenant", "dummy-principal", Set.of("STUDENT"), 1L),
                Instant.now());
    }

    @Nested
    @DisplayName("E-1 未认证 → 401（端点受保护）")
    class Unauthenticated {

        @Test
        @DisplayName("E-1a 无令牌访问 → 401")
        void noTokenRejected() {
            rest.get().uri("/api/config")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Nested
    @DisplayName("E-2 已认证 → 200 + 结构正确")
    class Authenticated {

        @Test
        @DisplayName("E-2a 带令牌 → 200 且含 groups / zeroRetention")
        void returnsStructure() {
            rest.get().uri("/api/config")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.groups").isArray()
                    .jsonPath("$.groups.length()").isNotEmpty()
                    .jsonPath("$.zeroRetention").exists()
                    .jsonPath("$.zeroRetention.failClosed").isEqualTo(true)
                    .jsonPath("$.zeroRetention.anyConfirmed").isEqualTo(false)
                    .jsonPath("$.effectiveFlags").exists();
        }

        @Test
        @DisplayName("E-2b 零保留声明三家且均为 PENDING")
        void zeroRetentionDeclared() {
            rest.get().uri("/api/config")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.zeroRetention.declaredProviders.length()").isEqualTo(3)
                    .jsonPath("$.zeroRetention.registryStatus.deepseek")
                    .isEqualTo("PENDING")
                    .jsonPath("$.zeroRetention.registryStatus.minimax")
                    .isEqualTo("PENDING")
                    .jsonPath("$.zeroRetention.registryStatus.workbuddy")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("E-2c 响应不含私钥 / 口令")
        void noSecrets() {
            String body = rest.get().uri("/api/config")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(body)
                    .as("⚠️ 配置端点绝不暴露私钥/口令")
                    .doesNotContain("private-key")
                    .doesNotContain("RABBITMQ_PASSWORD")
                    .doesNotContain("BEGIN PRIVATE KEY");
        }
    }
}
