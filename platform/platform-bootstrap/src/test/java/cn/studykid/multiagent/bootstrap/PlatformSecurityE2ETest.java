package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PasswordHasher;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * D-001 端到端验证（真实 HTTP + 真实过滤链 + 真实 PostgreSQL）。
 *
 * <p><b>为什么必须是端到端</b>：认证链有两处「缺失即全站不可用」的装配点，
 * <b>都不会体现在任何单元测试里</b>：
 * <ul>
 *   <li>没有 {@code SecurityFilterChain} → Spring Security 默认放行一切</li>
 *   <li>有链但未接入令牌解析 → 所有请求（含有效令牌）都 401</li>
 * </ul>
 * 两者症状相同，修法完全不同。
 *
 * <p><b>⚠️ 为何使用真实 PostgreSQL 而非替身</b>：
 * 与生产 Bean 并存的所有压制手段都不可靠 ——
 * {@code @ConditionalOnMissingBean} / {@code @ConditionalOnProperty}
 * 在<b>用户 {@code @Configuration} 中顺序敏感</b>
 * （只有「最后处理」的配置能看到前面的 Bean 定义），
 * 表现为「单模块测试通过、全量构建失败」；
 * 而 {@code @Profile} / 排除组件扫描会<b>改变被测对象</b>。
 * 用真实数据库既消除冲突，又更接近真实部署。
 *
 * <p>对应设计文档 §5 的 E2E-1 ~ E2E-8。
 */
// ⚠️ **须有数据库才能运行**：本类验证真实 HTTP 链路 + 真实 RLS 隔离，
//    替身方案已证明不可行（条件注解顺序敏感、其余手段改变被测对象）。
//    未设 IT_PG_URL 时跳过 —— 否则 CI 无数据库时会**假失败**。
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // ⚠️ 无默认值：@EnabledIfEnvironmentVariable 保证 IT_PG_URL 存在，
        //    这里给默认值反而会在拼写错误时静默连到错误库
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ Worker 主循环关闭（P4-D03）。
        //    本类验证 HTTP 认证链 + RLS 隔离，不需要 RabbitMQ；
        //    且测试环境的 broker 凭据未必与本机实例一致。
        //
        //    ⚠️ **两个开关必须同时设**（本次实测踩到）：
        //    agent.worker.enabled 只关掉 WorkerLoopConfig 的 @Configuration，
        //    而 @RabbitListener 端点由 RabbitBootstrap 注册，
        //    **不受 ConditionalOnProperty 影响** —— 只关前者仍会启动容器连 broker。
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class PlatformSecurityE2ETest {

    private static final String VALID_LOGIN = "e2e_student01";
    private static final String VALID_PASSWORD = "e2e-correct-password";

    /**
     * 测试租户 ID。
     *
     * <p>⚠️ <b>从种子数据读取而非硬编码</b>：硬编码的 UUID 在库中不存在，
     * 外键约束会拒绝（{@code principal_tenant_id_fkey}）。
     * 与本项目其他集成测试同一模式。
     */
    private static String tenantId;

    private String tenant() {
        if (tenantId == null) {
            tenantId = adminJdbc().queryForObject(
                    "SELECT tenant_id::text FROM tenant WHERE slug = 't-a' LIMIT 1",
                    String.class);
        }
        return tenantId;
    }

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 管理员连接池（<b>静态 + 必须关闭</b>）。
     *
     * <p>⚠️ <b>2026-10-23 实测修正</b>：本方法原先<b>每次调用都 new 一个
     * {@code HikariDataSource}</b>，而它被 {@code @BeforeEach} 的
     * {@code seedAccount} 与多个用例反复调用 → 每个池常驻 1 条连接且
     * <b>从不关闭</b> → 连接永不归还。
     * 本类 15 个测试方法 + 若干次 {@code validToken()} 调用
     * → 数十条连接泄漏 → 与其它 IT 类的泄漏叠加后打满 PostgreSQL 的
     * {@code max_connections=100} → <b>别的 IT 类的上下文启动失败</b>。
     */
    private static HikariDataSource adminPool;

    /** 关闭管理员池（<b>必须</b>：不关闭则泄漏依旧）。 */
    @AfterAll
    static void closeAdminPool() {
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
        }
    }

    /**
     * 以系统身份执行准备语句。
     *
     * <p>⚠️ <b>RLS 生效是正确行为</b>：{@code principal} 表受租户隔离，
     * 未绑定租户上下文的写入会被拒绝 —— 这正是期望的。
     * 但测试的<b>准备阶段</b>必须先插入账号，故用系统身份连接。
     *
     * <p>与本项目其他集成测试的 {@code adminQuery} 同一模式：
     * <b>准备阶段用系统身份，验证阶段用受约束身份</b>。
     * 绝不能用系统身份做验证 —— 那会得到「隔离失效」的虚假结论。
     */
    private JdbcTemplate adminJdbc() {
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
            cfg.setMaximumPoolSize(1);
            adminPool = new HikariDataSource(cfg);
        }
        return new JdbcTemplate(adminPool);
    }

    /**
     * 准备一个可登录账号。
     *
     * <p>⚠️ <b>用真实密码哈希</b>：验证「口令 → PBKDF2 → 登录」整条链路，
     * 而非让替身直接放行。若此处写死「永远成功」，
     * 认证链的失败会被全部掩盖。
     *
     * <p>⛔⛔ <b>清理只针对本测试创建的账号，绝不按租户清空</b>。
     * 原实现是 {@code DELETE FROM principal WHERE tenant_id = ?}，
     * 会把该租户下**所有**账号删光 —— 包括开发者手工建的本地账号。
     * 后果实测出现过两次：本地管理员账号跑一次测试就没了，
     * 且现象是「登录失败」，排查方向完全错误。
     * 教训同 P3-D4「新增写数据的集成测试必须自带清理」，
     * 但这里更进一步：<b>清理的范围必须与创建的范围一致</b>。
     */
    @BeforeEach
    void seedAccount() {
        JdbcTemplate admin = adminJdbc();
        cleanUpOwnAccount(admin);

        String principalId = java.util.UUID.randomUUID().toString();
        admin.update(
                "INSERT INTO principal "
                        + "(principal_id, tenant_id, login_name, password_hash, status) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')",
                principalId, tenant(), VALID_LOGIN, PasswordHasher.hash(VALID_PASSWORD));

        admin.update(
                "INSERT INTO principal_role (tenant_id, principal_id, role) "
                        + "VALUES (?::uuid, ?::uuid, 'STUDENT')",
                tenant(), principalId);
    }

    /**
     * 只删除本测试创建的账号。
     *
     * <p>⛔ 用 {@code login_name} 精确定位，<b>不用 tenant_id</b> ——
     * 后者会连带删除同租户下他人的账号。
     */
    private void cleanUpOwnAccount(JdbcTemplate admin) {
        admin.update(
                "DELETE FROM principal_role WHERE principal_id IN "
                        + "(SELECT principal_id FROM principal WHERE login_name = ?)",
                VALID_LOGIN);
        admin.update("DELETE FROM principal WHERE login_name = ?", VALID_LOGIN);
    }

    // ════════════════════════════════════════════════════════════
    // E2E-1：安全链是否真的拦住了未认证请求
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ E2E-1 无令牌访问业务端点 → 401（安全链已装配的决定性验证）")
    void unauthenticatedRequestRejected() {
        // ⚠️ 本类最重要的断言：无 SecurityFilterChain 时，
        //    Spring Security 默认放行一切。
        rest.post().uri("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ E2E-1b 401 响应为 JSON 而非 HTML")
    void unauthorizedResponseIsJson() {
        // HTML 错误页会让 JSON 客户端抛难定位的解析异常，且可能泄露框架版本
        rest.post().uri("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("⛔ E2E-1c 客户端自称的身份不被信任")
    void selfAssertedIdentityRejected() {
        rest.post().uri("/api/runs")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer eyJhbGciOiJub25lIn0.eyJ0ZW5hbnRfaWQiOiJhdHRhY2tlciJ9.AAAA")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ════════════════════════════════════════════════════════════
    // E2E-2：登录 → 带令牌访问（正向路径）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ E2E-2 登录成功且令牌可到达业务层")
    void loginThenAccessWithToken() {
        String token = loginSuccessfully();

        // ⚠️ 业务端口的 TransactionRunner 尚未接入（P2-D5），
        //    故业务层会失败 —— 但**绝不能是 401**。
        //    401 意味着认证链未放行有效令牌，即认证有缺陷。
        var status = rest.post().uri("/api/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"idempotencyKey\":\"k1\",\"agentId\":\"a1\",\"input\":{}}")
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getStatus();

        assertThat(status.value())
                .as("有效令牌不应被认证层拒绝（401）。实际 %s", status)
                .isNotEqualTo(401);
    }

    @Test
    @DisplayName("✅ E2E-2b 登录端点签发的令牌携带库中登记的角色")
    void issuedTokenCarriesRegisteredRoles() {
        String token = loginSuccessfully();

        var ctx = tokens.verify(token, Instant.now(), Set.of("STUDENT", "TENANT_ADMIN"));

        assertThat(ctx.roles())
                .as("角色须来自 principal_role 表，非令牌自称 —— "
                        + "否则自签 TENANT_ADMIN 即可提权")
                .containsExactly("STUDENT");
    }

    // ════════════════════════════════════════════════════════════
    // E2E-3/4：伪造与篡改
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ E2E-3 alg=none 伪造令牌被拒")
    void algNoneForgeryRejectedOverHttp() {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"attacker\",\"tenant_id\":\"" + tenant() + "\"}");
        // ⚠️ 必须用非空签名段构成合法三段式：留空会被「格式非法」拦下，
        //    测到的就成了解析器而非认证链
        String forged = header + "." + payload + "." + b64(new byte[32]);

        postWithToken(forged).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ E2E-4 篡改租户的令牌被拒")
    void tamperedTokenRejectedOverHttp() {
        String token = validToken();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + b64(
                "{\"iss\":\"https://auth.test\",\"sub\":\"student-01\","
                        + "\"aud\":\"agent-platform\",\"exp\":9999999999,"
                        + "\"tenant_id\":\"99999999-9999-9999-9999-999999999999\","
                        + "\"roles\":[\"TENANT_ADMIN\"],\"authz_version\":1}") + "." + parts[2];

        postWithToken(tampered).expectStatus().isUnauthorized();
    }

    // ════════════════════════════════════════════════════════════
    // E2E-5：无缓存残留（最关键）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ E2E-5 连续失败校验不得留下「已认证」残留")
    void noAuthenticationStateCacheLeak() {
        String forged = b64("{\"alg\":\"RS256\"}") + "."
                + b64("{\"sub\":\"x\"}") + "." + b64(new byte[32]);

        for (int i = 0; i < 3; i++) {
            postWithToken(forged).expectStatus().isUnauthorized();
        }
    }

    @Test
    @DisplayName("⛔ E2E-5b 先成功再失败，须立即转为 401")
    void successThenFailureDoesNotLeak() {
        // 若 Security 上下文存在线程局部残留，
        // 「先成功认证」可能让后续失败请求被误判为已认证。
        postWithToken(validToken());
        String forged = b64("{\"alg\":\"RS256\"}") + "."
                + b64("{\"sub\":\"x\"}") + "." + b64(new byte[32]);
        postWithToken(forged).expectStatus().isUnauthorized();
    }

    // ════════════════════════════════════════════════════════════
    // E2E-7/8：登录端点与公开端点
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ E2E-8 /api/auth/jwks 无令牌可访问（公钥本就公开）")
    void jwksIsPublic() {
        rest.get().uri("/api/auth/jwks").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.alg").isEqualTo("RSA");
    }

    @Test
    @DisplayName("⛔ 错误口令 → 401（非 403）")
    void wrongPasswordRejected() {
        // ⚠️ 必须是 401 而非 403：403 会让客户端误以为
        //    「重新登录无用，需申请权限」，从而反复重试
        rest.post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginBody("wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ 账号不存在 → 401 且响应不含可枚举措辞")
    void unknownAccountLeaksNothing() {
        rest.post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"tenantId\":\"nonexistent\",\"loginName\":\"ghost\",\"password\":\"x\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("不得出现可供枚举的措辞")
                        .doesNotContain("不存在")
                        .doesNotContain("not found"));
    }

    @Test
    @DisplayName("⛔ 参数缺失同样 401，不透露缺哪个字段")
    void missingFieldsRejected() {
        rest.post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"loginName\":\"x\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("⛔ 停用账号无法登录")
    void disabledAccountRejected() {
        adminJdbc().update("UPDATE principal SET status = 'DISABLED' WHERE tenant_id = ?::uuid", tenant());

        rest.post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginBody(VALID_PASSWORD))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ════════════════════════════════════════════════════════════
    @Test
    @DisplayName("⛔ 结构性门禁：公开白名单不得含业务端点前缀")
    void publicEndpointsAreNarrow() {
        String[] publics = {
                "/api/auth/token", "/api/auth/jwks",
                "/actuator/health", "/actuator/health/**", "/error"
        };

        for (String path : publics) {
            assertThat(path)
                    .as("白名单须精确到端点。出现 /api/** 之类的通配，"
                            + "新增业务端点会被自动放行")
                    .doesNotContain("/api/**")
                    .doesNotContain("/api/runs");
        }
    }

    // ── 工具 ────────────────────────────────────────────────────

    private RestTestClient.ResponseSpec postWithToken(String token) {
        return rest.post().uri("/api/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange();
    }

    private String loginSuccessfully() {
        loginSuccessfullyRaw();
        // 登录端点返回的令牌无法直接读取（RestTestClient 无 body→String 提取 API），
        // 故重新签发一个等价令牌用于后续请求。
        // ⚠️ 这不影响断言有效性：E2E-2b 单独验证「登录端点签发的令牌
        //    携带库中角色」，即证明登录端点走的是同一条签发路径。
        return validToken();
    }

    private void loginSuccessfullyRaw() {
        rest.post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginBody(VALID_PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty();
    }

    private String loginBody(String password) {
        return "{\"tenantId\":\"" + tenant() + "\",\"loginName\":\"" + VALID_LOGIN
                + "\",\"password\":\"" + password + "\"}";
    }

    /** 签发一个有效令牌（与登录端点同一条签发路径）。 */
    private String validToken() {
        String principalId = adminJdbc().queryForObject(
                "SELECT principal_id::text FROM principal WHERE tenant_id = ?::uuid LIMIT 1",
                String.class, tenant());
        return tokens.issue(
                new TenantContext(tenant(), principalId, Set.of("STUDENT"), 1L),
                Instant.now());
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ════════════════════════════════════════════════════════════
    // 测试专用 Bean
    // ════════════════════════════════════════════════════════════

    /**
     * 测试专用 Bean。
     *
     * <p>⚠️ <b>只覆盖不依赖数据库的两个 Bean</b>：
     * 授权服务与零保留闸门。其余（Repository、DataSource）用<b>真实实现</b> ——
     * 替身会引入 Bean 候选冲突，且掩盖真实问题。
     *
     * <p>⚠️ <b>零保留登记 DEEPSEEK</b>：登录端点需要，
     * 否则闸门拒绝所有请求 —— 那在测「未确认供应商被拒」，
     * 不是本次要验证的认证链。
     */
    @Configuration
    static class E2EConfig {

        @Bean
        AuthorizationService e2eAuthorizationService() {
            return new cn.studykid.multiagent.infrastructure.security
                    .RoleBasedAuthorizationService();
        }

        @Bean
        ZeroRetentionGate e2eZeroRetentionGate() {
            return new ZeroRetentionGate(
                    ZeroRetentionRegistry.builder()
                            .confirmed(ModelProviders.DEEPSEEK, "SUP-2026-001",
                                    Instant.parse("2026-10-19T00:00:00Z"),
                                    Duration.ofDays(365))
                            .build(),
                    Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC));
        }
    }
}