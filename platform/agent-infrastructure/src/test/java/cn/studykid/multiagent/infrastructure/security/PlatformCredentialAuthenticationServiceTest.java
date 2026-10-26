package cn.studykid.multiagent.infrastructure.security;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformRoles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台登录认证测试（重规划 §7）。
 *
 * <p>核心断言：口令校验、状态检查、角色加载、令牌签发、统一失败（防枚举）。
 */
class PlatformCredentialAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final KeyPair keyPair = PlatformJwtTokenService.generateKeyPair();
    private final PlatformJwtTokenService tokens = new PlatformJwtTokenService(
            keyPair.getPrivate(), keyPair.getPublic(),
            "https://auth.test", IdentityDomains.PLATFORM_AUDIENCE,
            java.time.Duration.ofSeconds(900), null);

    private PlatformCredentialAuthenticationService service(InMemoryPlatformPrincipalRepository repo) {
        return new PlatformCredentialAuthenticationService(repo, tokens, CLOCK);
    }

    @Test
    void successfulLoginIssuesPlatformTokenWithRoles() {
        InMemoryPlatformPrincipalRepository repo = new InMemoryPlatformPrincipalRepository();
        repo.putUser(InMemoryPlatformPrincipalRepository.testUser(
                "u-1", "admin", "s3cret", "ACTIVE", false, 7), Set.of(PlatformRoles.SUPER_ADMIN));

        PlatformCredentialAuthenticationService.LoginResult result =
                service(repo).login("admin", "s3cret");

        assertTrue(result.token().contains("."));
        assertEquals("u-1", result.principal().userId());
        assertTrue(result.principal().hasRole(PlatformRoles.SUPER_ADMIN));
        assertEquals(7L, result.principal().authzVersion());
    }

    @Test
    void wrongPasswordFailsUniformly() {
        InMemoryPlatformPrincipalRepository repo = new InMemoryPlatformPrincipalRepository();
        repo.putUser(InMemoryPlatformPrincipalRepository.testUser(
                "u-1", "admin", "s3cret", "ACTIVE", false, 1), Set.of(PlatformRoles.AUDITOR));

        assertThrows(PlatformCredentialAuthenticationService.PlatformLoginException.class,
                () -> service(repo).login("admin", "wrong"));
    }

    @Test
    void unknownUserFailsUniformly() {
        InMemoryPlatformPrincipalRepository repo = new InMemoryPlatformPrincipalRepository();
        assertThrows(PlatformCredentialAuthenticationService.PlatformLoginException.class,
                () -> service(repo).login("ghost", "whatever"));
    }

    @Test
    void disabledUserFailsUniformly() {
        InMemoryPlatformPrincipalRepository repo = new InMemoryPlatformPrincipalRepository();
        repo.putUser(InMemoryPlatformPrincipalRepository.testUser(
                "u-1", "admin", "s3cret", "DISABLED", false, 1), Set.of(PlatformRoles.AUDITOR));

        assertThrows(PlatformCredentialAuthenticationService.PlatformLoginException.class,
                () -> service(repo).login("admin", "s3cret"));
    }

    @Test
    void issuedTokenIsRejectedByTenantService() {
        // 登录签发的平台令牌带 aud=admin-console-api，租户服务必须拒（令牌层隔离一致性）
        InMemoryPlatformPrincipalRepository repo = new InMemoryPlatformPrincipalRepository();
        repo.putUser(InMemoryPlatformPrincipalRepository.testUser(
                "u-1", "admin", "s3cret", "ACTIVE", false, 1), Set.of(PlatformRoles.SUPER_ADMIN));

        String platformToken = service(repo).login("admin", "s3cret").token();

        JwtTokenService tenantSvc = new JwtTokenService(
                keyPair.getPrivate(), keyPair.getPublic(),
                "https://auth.test", IdentityDomains.TENANT_AUDIENCE,
                java.time.Duration.ofSeconds(900), null);

        assertThrows(JwtTokenService.JwtValidationException.class,
                () -> tenantSvc.verify(platformToken, NOW, Set.of("TENANT_ADMIN")));
    }
}
