package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 未映射路径的 HTTP 语义（2026-10-23 全栈 E2E 实测发现的真实缺陷 · P4-D09 同族第 3 例）。
 *
 * <p><b>缺陷原文</b>：{@code /api/nonexistent}、{@code /api/cap/config/nope}、
 * {@code /api/audit-records}、{@code /actuator/nope} 全部返回 <b>500</b>；
 * 而「已映射但资源不存在」正确返回 404 —— 两者本应是同一个码。
 *
 * <p><b>根因</b>：Spring 6+ 起，路径未映射时抛
 * {@link org.springframework.web.servlet.resource.NoResourceFoundException}，
 * Spring 默认映射为 404；但 {@code ApiExceptionHandler} 的兜底
 * {@code @ExceptionHandler(Exception.class)} <b>优先于</b> Spring 内置的
 * {@code DefaultHandlerExceptionResolver}，于是 404 被吞成 500。
 *
 * <p><b>危害</b>：客户端无法区分「路径写错」（4xx，重试无用）与「服务端故障」
 * （5xx，可重试），会做出<b>完全相反</b>的重试决策；同时把用户笔误计入服务端错误率，
 * 淹没真实故障。
 *
 * <p><b>⚠️ 为什么必须是真实 HTTP 而不是直接调用处理器</b>：
 * 「{@code @ExceptionHandler} 的选路优先级（最具体匹配优先于 {@code Exception.class}）」
 * 属于 Spring 框架行为，处理器自身的单元测试证明不了。
 * 只有走完整过滤链 + DispatcherServlet 才能证明「未映射路径确实落到 404」。
 *
 * <p><b>⚠️ 为什么必须带令牌</b>：安全链是 {@code anyRequest().authenticated()}，
 * 无令牌时请求在<b>到达 DispatcherServlet 之前</b>就被 401 拦下，
 * 根本走不到路径映射逻辑 —— 那样测到的是认证链，不是路径映射。
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
@DisplayName("未映射路径 → 404（不是 500）：2026-10-23 全栈 E2E 真实缺陷回归")
class NotFoundMappingIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    /**
     * 管理员连接池（<b>静态 + 必须关闭</b>）。
     *
     * <p>⚠️ <b>为什么必须是 static</b>：JUnit 默认每个测试方法新建一个测试类实例，
     * 实例字段因而「每个方法都是 null」→ <b>每个方法都新建一个连接池</b>；
     * Hikari 的 {@code minimumIdle} 默认等于 {@code maximumPoolSize}，
     * 池不关闭则连接永不归还，累积后打满 PostgreSQL 的 {@code max_connections=100}
     * → <b>别的 IT 类的上下文启动失败</b>（报错点与真因相隔一个类）。
     */
    private static HikariDataSource adminPool;

    private static String tenantA;

    /** 关闭管理员池（<b>必须</b>：static 只降级泄漏规模，不关闭则泄漏依旧）。 */
    @AfterAll
    static void closeAdminPool() {
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
        }
    }

    private static synchronized HikariDataSource adminPool() {
        if (adminPool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
            cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
            String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
            if (adminPwd != null && !adminPwd.isBlank()) {
                cfg.setPassword(adminPwd);
            }
            cfg.setMaximumPoolSize(1);
            adminPool = new HikariDataSource(cfg);
        }
        return adminPool;
    }

    private static synchronized String tenant() {
        if (tenantA == null) {
            try (Connection c = adminPool().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
                 var rs = ps.executeQuery()) {
                assertThat(rs.next()).as("租户 t-a 应存在").isTrue();
                tenantA = rs.getString(1);
            } catch (Exception e) {
                throw new IllegalStateException("读取租户失败", e);
            }
        }
        return tenantA;
    }

    private String auth() {
        return "Bearer " + tokens.issue(
                new TenantContext(tenant(), "nf-it-admin", Set.of("TENANT_ADMIN"), 1L),
                Instant.now());
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 未映射路径 → 404（不是 500）：客户端数据错误不得被记成服务端故障")
    void unmappedPathReturns404() {
        var status = rest.get().uri("/api/nonexistent")
                .header(HttpHeaders.AUTHORIZATION, auth())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getStatus();

        assertThat(status.value())
                .as("⛔ 路径不存在是 4xx；报 500 会诱导客户端无限重试")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("✅ 未映射路径的响应体是归一化 JSON：code=NOT_FOUND，且不回显路径原文")
    void unmappedPathReturnsNormalizedJsonBody() {
        String body = rest.get().uri("/api/cap/config/nope")
                .header(HttpHeaders.AUTHORIZATION, auth())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("⛔ 响应须为归一化 JSON，code=NOT_FOUND")
                .contains("\"code\":\"NOT_FOUND\"")
                .contains("\"message\"");
        assertThat(body)
                .as("⛔ 不得回显请求路径原文（可能含客户端数据）")
                .doesNotContain("/api/cap/config/nope");
    }

    @Test
    @DisplayName("⛔ 未映射路径与「资源不存在」同码：404 语义必须一致")
    void unmappedPathAndMissingResourceShareSameCode() {
        String unmapped = rest.get().uri("/api/definitely-not-mapped")
                .header(HttpHeaders.AUTHORIZATION, auth())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();

        // 已映射但资源不存在：发布一个不存在的版本 → 404 AGENT_SNAPSHOT_UNAVAILABLE
        String missing = rest.post().uri("/api/agents/{id}/versions", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, auth())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"definition\":\"{}\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(unmapped).contains("\"code\":\"NOT_FOUND\"");
        assertThat(missing).contains("\"code\"");
        // 两者状态码同为 404（code 各自归一化，但不得一个 404 一个 500）
        assertThat(unmapped).contains("NOT_FOUND");
        assertThat(missing).doesNotContain("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("⛔ 未映射的 actuator 路径同样是 404（不得因路径前缀不同而变成 500）")
    void unmappedActuatorPathReturns404() {
        rest.get().uri("/actuator/nope")
                .header(HttpHeaders.AUTHORIZATION, auth())
                .exchange()
                .expectStatus().isNotFound();
    }
}
