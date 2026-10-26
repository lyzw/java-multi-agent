package cn.studykid.multiagent.bootstrap.config;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import javax.sql.DataSource;

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

        KeyPair keyPair = resolveKeyPair(privateKeyBase64, environment);
        return new JwtTokenService(
                keyPair.getPrivate(), keyPair.getPublic(),
                issuer, audience, Duration.ofSeconds(tokenTtlSeconds), null);
    }

    /**
     * 解析密钥对。
     *
     * <p>⚠️ <b>生产不允许启动生成</b>：每次启动生成新密钥会使所有已签发令牌失效，
     * 滚动重启即等于全员掉线。仅 dev/local/test profile 允许。
     */
    private KeyPair resolveKeyPair(String privateKeyBase64, Environment environment) {
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

        KeyPair keyPair = resolveKeyPair(privateKeyBase64, environment);
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

    @Bean
    public PlatformPrincipalRepository platformPrincipalRepository(DataSource dataSource) {
        return new JdbcPlatformPrincipalRepository(dataSource);
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