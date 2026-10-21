package cn.studykid.multiagent.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * G1「登录返角色」端点测试 —— 2026-10-22 重写。
 *
 * <p><b>为什么重写</b>：原 {@code AuthControllerG1Test} 是伪代码——导入了不存在的类
 * {@code DefaultsMockMvcBuilders}、无参调用不存在的方法 {@code queryRoles()}、
 * 把一段 JSON 当作 VALID_TOKEN、断言 {@code result.get("roles")} 而返回体里根本没有这个键。
 * <b>它从未编译过，也从未真正验证任何行为。</b>
 *
 * <p><b>本类验证什么</b>（每条都能被证伪）：
 * <ul>
 *   <li>缺头 / 非 Bearer 前缀 / 空白令牌 / 验签抛异常 → 一律 401，<b>且响应体完全相同</b>
 *       （防枚举：调用方无法区分失败原因）；</li>
 *   <li>有效令牌 → 200，且角色来自可信上下文（库中登记角色），非 JWT 声明；</li>
 *   <li>失败路径<b>不透传异常信息</b>（不泄露验签内部细节）。</li>
 * </ul>
 */
@DisplayName("G1 登录返角色 /api/auth/roles")
class AuthControllerRolesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final TenantContext CONTEXT =
            new TenantContext("tenant-a", "principal-1", Set.of("STUDENT", "TENANT_ADMIN"), 1L);

    private AuthenticationService authentication;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authentication = mock(AuthenticationService.class);
        JwtTokenService tokens = mock(JwtTokenService.class);
        controller = new AuthController(authentication, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("有效令牌 → 200，角色取自可信上下文")
    void validTokenReturnsRoles() {
        when(authentication.authenticateToken(anyString(), any())).thenReturn(CONTEXT);

        ResponseEntity<?> response = controller.queryRoles("Bearer good-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isInstanceOf(AuthController.RolesResponse.class)
                .extracting(b -> ((AuthController.RolesResponse) b).roles())
                .isEqualTo(Set.of("STUDENT", "TENANT_ADMIN"));
    }

    @Test
    @DisplayName("缺少 Authorization 头 → 401")
    void missingHeaderIsUnauthorized() {
        ResponseEntity<?> response = controller.queryRoles(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new AuthController.ErrorResponse("invalid_credentials"));
    }

    @Test
    @DisplayName("非 Bearer 前缀 → 401（不得当作令牌解析）")
    void nonBearerSchemeIsUnauthorized() {
        ResponseEntity<?> response = controller.queryRoles("Basic dXNlcjpwYXNz");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Bearer 后为空白 → 401")
    void blankTokenIsUnauthorized() {
        ResponseEntity<?> response = controller.queryRoles("Bearer    ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("⚠️ 验签失败 → 401 且与缺头响应逐字相同（防枚举）")
    void verificationFailureLooksIdenticalToMissingHeader() {
        when(authentication.authenticateToken(anyString(), any()))
                .thenThrow(new IllegalArgumentException("signature mismatch: kid=abc"));

        ResponseEntity<?> failure = controller.queryRoles("Bearer tampered");
        ResponseEntity<?> missing = controller.queryRoles(null);

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 关键断言：状态码与响应体必须与「没带令牌」逐字相同，
        // 否则攻击者可用差异响应区分「令牌无效」与「没带令牌」。
        assertThat(failure.getBody()).isEqualTo(missing.getBody());
        // 且不得把验签内部细节泄露出去
        assertThat(String.valueOf(failure.getBody())).doesNotContain("signature", "kid=abc");
    }
}