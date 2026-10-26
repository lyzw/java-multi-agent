package cn.studykid.multiagent.api.security;

import java.util.Set;

import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台管理面授权入口测试（重规划 §4.3）。
 *
 * <p>断言：{@code PlatformSecurity.require} 在授权服务拒绝时抛
 * {@link AccessDeniedException}（→ 403），允许时通过。控制器据此在执行入口强制授权。
 */
class PlatformSecurityTest {

    private final PlatformAuthorizationService authz = mock(PlatformAuthorizationService.class);
    private final PlatformSecurity security = new PlatformSecurity(authz);

    private static PlatformPrincipal p(String role) {
        return new PlatformPrincipal("u1", Set.of(role), 1, false);
    }

    @Test
    void allowedActionPasses() {
        when(authz.authorize(any(), eq(Namespaces.Platform.USER_READ), any(), any()))
                .thenReturn(AuthorizationDecision.allow(1));
        assertDoesNotThrow(() ->
                security.require(p(PlatformRoles.AUDITOR), Namespaces.Platform.USER_READ, null, null));
    }

    @Test
    void deniedActionThrowsAccessDenied() {
        when(authz.authorize(any(), eq(Namespaces.Platform.USER_MANAGE), any(), any()))
                .thenReturn(AuthorizationDecision.deny("ROLE_LACKS_PERMISSION", 1));
        assertThrows(AccessDeniedException.class, () ->
                security.require(p(PlatformRoles.AUDITOR), Namespaces.Platform.USER_MANAGE, null, null));
    }

    @Test
    void supportCannotReadUsers() {
        when(authz.authorize(any(), eq(Namespaces.Platform.USER_READ), any(), any()))
                .thenReturn(AuthorizationDecision.deny("ROLE_LACKS_PERMISSION", 1));
        assertThrows(AccessDeniedException.class, () ->
                security.require(p(PlatformRoles.SUPPORT), Namespaces.Platform.USER_READ, null, null));
    }
}
