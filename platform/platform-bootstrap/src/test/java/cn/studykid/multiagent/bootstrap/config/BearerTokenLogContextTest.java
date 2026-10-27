package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthenticationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 令牌过滤器写入日志上下文的行为。
 *
 * <p><b>要守住的性质</b>：日志里的租户/主体<b>只能是验签通过后的可信值</b>。
 * 排障时日志被当成证据读，若把令牌里未经验证的 claim 当身份打印，
 * 「这条日志显示是租户 A 干的」就成了伪造的证据。
 *
 * <p>租户链与平台链的身份形态不同，两条都测：
 * 平台主体<b>刻意不持有租户</b>（{@link PlatformPrincipal}），
 * 所以平台管理链的租户位必须是占位符而不是某个默认租户。
 */
@DisplayName("Bearer 过滤器 · 日志身份只来自已验签上下文")
class BearerTokenLogContextTest {

    @AfterEach
    void tearDown() {
        LogContext.clear();
    }

    private static Map<String, String> runThrough(SecurityConfiguration.BearerTokenFilter filter,
                                                  String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> seen.set(LogContext.snapshot().asMap()));
        return seen.get();
    }

    @Test
    @DisplayName("租户令牌验签通过 → 日志带该租户与主体")
    void verifiedTenantTokenWritesIdentity() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        when(auth.authenticateToken(eq("good-token"), any()))
                .thenReturn(new TenantContext("tenant-a", "user-42", Set.of("TEACHER"), 3L));

        Map<String, String> during = runThrough(
                new SecurityConfiguration.BearerTokenFilter(auth), "Bearer good-token");

        assertThat(during)
                .containsEntry(LogContext.TENANT_ID, "tenant-a")
                .containsEntry(LogContext.PRINCIPAL_ID, "user-42");
    }

    @Test
    @DisplayName("⚠️ 令牌无效 → 身份保持未知，绝不打印令牌里声称的租户")
    void invalidTokenKeepsIdentityUnknown() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        when(auth.authenticateToken(any(), any()))
                .thenThrow(new IllegalArgumentException("signature does not match"));

        Map<String, String> during = runThrough(
                new SecurityConfiguration.BearerTokenFilter(auth), "Bearer forged-token");

        assertThat(during)
                .containsEntry(LogContext.TENANT_ID, LogContext.NONE)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.NONE);
    }

    @Test
    @DisplayName("无 Authorization 头（公开端点/未登录）→ 身份保持未知")
    void anonymousRequestKeepsIdentityUnknown() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        Map<String, String> during = runThrough(
                new SecurityConfiguration.BearerTokenFilter(auth), null);

        assertThat(during)
                .containsEntry(LogContext.TENANT_ID, LogContext.NONE)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.NONE);
    }

    @Test
    @DisplayName("平台令牌验签通过 → 主体是平台用户，租户位保持未知")
    void platformTokenWritesPrincipalWithoutTenant() throws Exception {
        PlatformAuthenticationService auth = mock(PlatformAuthenticationService.class);
        when(auth.authenticateToken(eq("platform-token"), any()))
                .thenReturn(new PlatformPrincipal("ops-1", Set.of("PLATFORM_ADMIN"), 1L, true));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/platform/v1/tenants");
        request.addHeader("Authorization", "Bearer platform-token");
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        new SecurityConfiguration.PlatformBearerTokenFilter(auth)
                .doFilter(request, new MockHttpServletResponse(),
                        (rq, rs) -> seen.set(LogContext.snapshot().asMap()));

        assertThat(seen.get())
                .containsEntry(LogContext.PRINCIPAL_ID, "ops-1")
                .containsEntry(LogContext.TENANT_ID, LogContext.NONE);
    }
}
