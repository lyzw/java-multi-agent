package cn.studykid.multiagent.infrastructure.security;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台/租户身份令牌层隔离测试（重规划验收清单第 1 条：身份隔离）。
 *
 * <p>核心断言：平台令牌（aud=admin-console-api）与租户令牌（aud=agent-platform）
 * 在<b>令牌层</b>互相拒绝 —— 任一服务校验对方令牌都必须抛异常。
 */
class PlatformIdentityBoundaryTest {

    private static final String ISSUER = "https://auth.test";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private static final Set<String> TENANT_ROLES =
            Set.of("STUDENT", "PARENT", "TEACHER", "TENANT_ADMIN", "PLATFORM_ADMIN");
    private static final Set<String> PLATFORM_ROLES = Set.of(
            "PLATFORM_SUPER_ADMIN", "PLATFORM_RESOURCE_ADMIN", "PLATFORM_SECURITY_ADMIN",
            "PLATFORM_AUDITOR", "PLATFORM_SUPPORT", "TENANT_LIFECYCLE_ADMIN");

    private final KeyPair keyPair = JwtTokenService.generateKeyPair();
    private final JwtTokenService tenantSvc = new JwtTokenService(
            keyPair.getPrivate(), keyPair.getPublic(), ISSUER, "agent-platform",
            Duration.ofSeconds(900), null);
    private final PlatformJwtTokenService platformSvc = new PlatformJwtTokenService(
            keyPair.getPrivate(), keyPair.getPublic(), ISSUER, "admin-console-api",
            Duration.ofSeconds(900), null);

    @Test
    void platformTokenVerifiesWithPlatformService() {
        PlatformPrincipal principal = new PlatformPrincipal(
                "u1", Set.of("PLATFORM_SUPER_ADMIN"), 7, true);
        String token = platformSvc.issue(principal, NOW);

        PlatformPrincipal verified = platformSvc.verify(token, NOW, PLATFORM_ROLES);
        assertEquals("u1", verified.userId());
        assertTrue(verified.hasRole("PLATFORM_SUPER_ADMIN"));
        assertEquals(7L, verified.authzVersion());
        assertTrue(verified.mfaVerified());
    }

    @Test
    void platformTokenRejectedByTenantService() {
        // 令牌层隔离：租户服务绝不应接受平台令牌（aud 不匹配）
        PlatformPrincipal principal = new PlatformPrincipal(
                "u1", Set.of("PLATFORM_SUPER_ADMIN"), 1, true);
        String platformToken = platformSvc.issue(principal, NOW);

        assertThrows(JwtTokenService.JwtValidationException.class,
                () -> tenantSvc.verify(platformToken, NOW, TENANT_ROLES));
    }

    @Test
    void tenantTokenRejectedByPlatformService() {
        // 令牌层隔离：平台服务绝不应接受租户令牌（aud 不匹配）
        var tenantContext = new cn.studykid.multiagent.core.tenant.TenantContext(
                "tenant-1", "p-1", Set.of("TENANT_ADMIN"), 1);
        String tenantToken = tenantSvc.issue(tenantContext, NOW);

        assertThrows(PlatformJwtTokenService.PlatformJwtValidationException.class,
                () -> platformSvc.verify(tenantToken, NOW, PLATFORM_ROLES));
    }

    @Test
    void platformRolesOutsideWhitelistAreDropped() {
        // 与租户侧同纪律：令牌声明中未在白名单登记的角色被忽略，防自封管理员
        PlatformPrincipal principal = new PlatformPrincipal(
                "u1", Set.of("PLATFORM_SUPER_ADMIN", "NOT_A_REAL_ROLE"), 1, false);
        String token = platformSvc.issue(principal, NOW);

        PlatformPrincipal verified = platformSvc.verify(token, NOW, PLATFORM_ROLES);
        assertTrue(verified.hasRole("PLATFORM_SUPER_ADMIN"));
        assertFalse(verified.hasRole("NOT_A_REAL_ROLE"));
    }

    @Test
    void expiredPlatformTokenRejected() {
        PlatformPrincipal principal = new PlatformPrincipal("u1", PLATFORM_ROLES, 1, true);
        // 签发于 1000s 前、有效期 900s → 已过期
        String token = platformSvc.issue(principal, NOW.minusSeconds(1000));

        assertThrows(PlatformJwtTokenService.PlatformJwtValidationException.class,
                () -> platformSvc.verify(token, NOW, PLATFORM_ROLES));
    }
}
