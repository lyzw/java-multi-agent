package cn.studykid.multiagent.bootstrap.config;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.JdbcPlatformPrincipalRepository;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthenticationService;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.infrastructure.security.PlatformCredentialAuthenticationService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;

/**
 * D-001 认证装配。
 *
 * <p><b>本类的存在理由</b>：认证逻辑单测全绿，但若不装配，
 * Spring Security 会<b>放行所有请求</b>（无 SecurityFilterChain 时的默认行为），
 * 且控制器根本拿不到 {@code AuthenticationService}。
 *
 * <p>⚠️ <b>配置一律 fail-closed</b>：缺失或非法即<b>启动失败</b>，
 * 不接受默认值。认证配置错误会导致全站 401，
 * 与其启动后才发现，不如启动即失败（DoD 配置化铁律第 1 条）。
 */
@Configuration
public class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    /** 令牌有效期的允许范围。超出即视为配置错误。 */
    private static final int MIN_TTL_SECONDS = 60;
    private static final int MAX_TTL_SECONDS = 3600;

    /** 未配置私钥时，仅这些 profile 允许启动生成。 */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test");

    @Bean
    public JwtTokenService jwtTokenService(
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.audience}") String audience,
            @Value("${auth.jwt.token-ttl-seconds}") long tokenTtlSeconds,
            @Value("${auth.jwt.private-key:}") String privateKeyBase64,
            Environment environment) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("auth.jwt.issuer 不得为空");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("auth.jwt.audience 不得为空");
        }
        if (tokenTtlSeconds < MIN_TTL_SECONDS || tokenTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalStateException(
                    "auth.jwt.token-ttl-seconds 须在 " + MIN_TTL_SECONDS
                            + "~" + MAX_TTL_SECONDS + " 之间，当前 " + tokenTtlSeconds);
        }

        KeyPair keyPair = resolveKeyPair(privateKeyBase64, environment,
                "auth.jwt.private-key", "RAG_TENANT_JWT_PUBLIC_KEY");
        return new JwtTokenService(
                keyPair.getPrivate(), keyPair.getPublic(),
                issuer, audience, Duration.ofSeconds(tokenTtlSeconds), null);
    }

    /**
     * 解析密钥对。
     *
     * <p>⚠️ <b>生产不允许启动生成</b>：每次启动生成新密钥会使所有已签发令牌失效，
     * 滚动重启即等于全员掉线。仅 dev/local/test profile 允许。
     *
     * @param privateKeyBase64 配置的「私钥.公钥」串（空 = 未配置）
     * @param environment 运行环境（判定是否 dev/local/test）
     * @param propertyName 出问题的配置键名（写进告警，指明该补哪个键）
     * @param counterpartKeyName 对端服务用来验签本实例令牌的配置键名（告警里点名它）
     */
    private KeyPair resolveKeyPair(String privateKeyBase64, Environment environment,
                                   String propertyName, String counterpartKeyName) {
        if (privateKeyBase64 != null && !privateKeyBase64.isBlank()) {
            // ⚠️ 格式为「私钥.公钥」（Base64URL 的 PKCS#8 与 X.509，以点分隔）。
            //    刻意要求**成对**提供：若允许只配私钥，运维需另配公钥，
            //    两处不一致时表现为「签发的令牌全部验签失败」——
            //    这种故障在开发环境不可见，只在生产暴露。
            String[] parts = privateKeyBase64.trim().split("\\.");
            if (parts.length != 2) {
                throw new IllegalStateException(
                        "auth.jwt.private-key 格式须为「私钥Base64URL.公钥Base64URL」，"
                                + "当前段数 " + parts.length);
            }
            return new KeyPair(
                    JwtTokenService.loadPublicKey(parts[1]),
                    JwtTokenService.loadPrivateKey(parts[0]));
        }

        boolean dev = java.util.Arrays.stream(environment.getActiveProfiles())
                .anyMatch(DEV_PROFILES::contains);
        if (!dev) {
            throw new IllegalStateException(
                    "auth.jwt.private-key 未配置。当前 profile 非 dev/local/test —— "
                            + "生产环境禁止启动生成密钥（会导致已签发令牌全部失效）");
        }
        // ⚠️ **必须把「静默降级」变成响亮告警**：本仓 2026-10-30 实测踩过一次——
        //    平台实例没加载 jwt.env，于是走了这条分支临时生成密钥对，
        //    而 RAG 服务配的验签公钥来自 jwt.env，两端**公钥指纹不一致** →
        //    RAG 管理面每个端点都 401，而控制台把它显示成「身份域隔离」，
        //    排查方向被整个带偏（真正的现场是「平台 JWKS 公钥 ≠ jwt.env 公钥」）。
        //    这类故障在 dev profile 下不报错、只表现为「别的服务连不上」，
        //    所以必须在此处明说它会让**跨服务验签**失效。
        log.warn("""

                ┌─ [JWT 密钥未配置] 本次启动临时生成密钥对 ─────────────────────────────
                │ 配置项 {} 为空，当前 profile 允许启动生成。
                │ ⚠️ 后果 1：本实例签发的令牌在进程重启后**全部验签失败**。
                │ ⚠️ 后果 2：与本实例互验的服务（{} 来自 jwt.env）
                │           公钥**必然不一致** → RAG 管理面 401，跨服务链路整体不可用。
                │ ✅ 修法：加载 jwt.env（scripts/dev-backend.sh 已自动 source；
                │          IDEA 直启需在运行配置里挂 EnvFile=platform.env + jwt.env）。
                └───────────────────────────────────────────────────────────────────────""",
                propertyName, counterpartKeyName);
        return JwtTokenService.generateKeyPair();
    }

    /**
     * 账号存储。
     *
     * <p>⚠️ <b>无条件注册</b>：曾用 {@code @Profile} 与 {@code @Primary}
     * 让测试替身压制本实现，两者都失败 ——
     * 前者导致「非该 profile 时认证服务无依赖可用」，
     * 后者因 {@code @ConditionalOnMissingBean} 在用户 {@code @Configuration}
     * 中<b>顺序敏感</b>而不可靠。
     *
     * <p>最终解法：端到端测试<b>使用真实数据库</b>，不做任何压制 ——
     * 既消除冲突，又更接近真实部署。
     */
    @Bean
    public PrincipalRepository principalRepository(DataSource dataSource) {
        return new PrincipalRepository(dataSource);
    }

    @Bean
    public AuthenticationService authenticationService(
            PrincipalRepository principals,
            JwtTokenService tokens,
            @Value("${auth.login.max-failed-attempts}") int maxFailedAttempts,
            @Value("${auth.login.lock-duration-minutes}") long lockMinutes,
            @Value("${auth.login.known-roles}") String knownRoles) {

        if (maxFailedAttempts <= 0) {
            throw new IllegalStateException("auth.login.max-failed-attempts 必须为正");
        }
        if (lockMinutes <= 0) {
            throw new IllegalStateException("auth.login.lock-duration-minutes 必须为正");
        }

        Set<String> roles = parseRoles(knownRoles);
        return new AuthenticationService(principals, tokens,
                Duration.ofMinutes(lockMinutes), maxFailedAttempts, roles);
    }

    /**
     * 解析角色白名单。
     *
     * <p>⚠️ 白名单为空会导致所有令牌角色被丢弃 → 全站 403。
     * 这属配置错误，必须在启动时暴露而非运行期。
     */
    static Set<String> parseRoles(String raw) {
        Set<String> roles = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    roles.add(trimmed);
                }
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalStateException(
                    "auth.login.known-roles 不得为空：未登记的角色会被丢弃，导致全站 403");
        }
        return roles;
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    // ── 平台身份域（重规划 §4 / §7）：独立令牌、独立受众、独立密钥 ──

    @Bean
    public PlatformJwtTokenService platformJwtTokenService(
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.platform-jwt.audience:admin-console-api}") String audience,
            @Value("${auth.platform-jwt.token-ttl-seconds:900}") long tokenTtlSeconds,
            @Value("${auth.platform-jwt.private-key:}") String privateKeyBase64,
            Environment environment) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("auth.jwt.issuer 不得为空");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("auth.platform-jwt.audience 不得为空");
        }
        if (tokenTtlSeconds < MIN_TTL_SECONDS || tokenTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalStateException(
                    "auth.platform-jwt.token-ttl-seconds 须在 " + MIN_TTL_SECONDS
                            + "~" + MAX_TTL_SECONDS + " 之间，当前 " + tokenTtlSeconds);
        }

        KeyPair keyPair = resolveKeyPair(privateKeyBase64, environment,
                "auth.platform-jwt.private-key", "RAG_PLATFORM_JWT_PUBLIC_KEY");
        return new PlatformJwtTokenService(
                keyPair.getPrivate(), keyPair.getPublic(),
                issuer, audience, Duration.ofSeconds(tokenTtlSeconds), null);
    }

    @Bean
    public PlatformAuthenticationService platformAuthenticationService(
            PlatformJwtTokenService tokens,
            @Value("${auth.platform.login.known-roles:"
                    + "PLATFORM_SUPER_ADMIN,PLATFORM_RESOURCE_ADMIN,PLATFORM_SECURITY_ADMIN,"
                    + "PLATFORM_AUDITOR,PLATFORM_SUPPORT,TENANT_LIFECYCLE_ADMIN}") String knownRoles) {
        return new PlatformAuthenticationService(tokens, parseRoles(knownRoles));
    }

    // ── 平台身份仓储 / 授权 / 登录（重规划 §2.1 / §4 / §7）──

    /**
     * 平台身份仓储（平台账号 / 角色）。
     *
     * <p>⚠️ <b>必须用平台运维角色的数据源，不得用主数据源</b>：
     * {@code platform.platform_user} 等身份/凭据表对主数据源角色
     * {@code app_agent} 刻意无授权（03_platform_roles.sql §2b），
     * 误接主数据源的表现是登录报
     * {@code permission denied for table platform_user}
     * （2026-11-01 实测踩坑）。运维数据源未接入时 fail-closed
     * （取连接即抛 {@code PlatformOpsUnavailableException}），绝不回落应用角色。
     */
    @Bean
    public PlatformPrincipalRepository platformPrincipalRepository(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider platformOps) {
        return new JdbcPlatformPrincipalRepository(platformOps.platformIdentityDataSource());
    }

    /**
     * 平台审计仓储（{@code platform.platform_audit_event} 的写入与查询）。
     *
     * <p>⚠️ 同 {@link #platformPrincipalRepository}：走平台运维角色数据源。
     * ⚠️ <b>此前完全缺失装配</b>：agent-api 的用户管理/租户服务/工单服务都依赖
     * {@code PlatformAuditPort}，缺 Bean 时应用启动即失败
     * （{@code UnsatisfiedDependencyException}），2026-11-01 补上。
     */
    @Bean
    public cn.studykid.multiagent.ports.audit.PlatformAuditPort platformAuditPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider platformOps) {
        return new cn.studykid.multiagent.infrastructure.security.JdbcPlatformAuditRepository(
                platformOps.platformIdentityDataSource());
    }

    @Bean
    public PlatformAuthorizationServiceImpl platformAuthorizationService() {
        // 矩阵驱动、默认拒绝；SoD 规则内置。无需数据库依赖。
        return new PlatformAuthorizationServiceImpl();
    }

    @Bean
    public PlatformCredentialAuthenticationService platformCredentialAuthenticationService(
            PlatformPrincipalRepository platformUsers,
            PlatformJwtTokenService platformTokens,
            Clock clock) {
        return new PlatformCredentialAuthenticationService(platformUsers, platformTokens, clock);
    }
}