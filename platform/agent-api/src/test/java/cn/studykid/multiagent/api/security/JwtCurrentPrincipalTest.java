package cn.studykid.multiagent.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository;

/**
 * 认证信任链测试（D-001 API 层）。
 *
 * <p><b>本类验证的是「上下文不可伪造」这一性质</b>，
 * 而非「某个端点能否正确解析令牌」。
 *
 * <p>背景：D1 部署阻断的根因是 {@code TenantContext} 由 Spring
 * 按参数注入 —— 任何调用方都能构造。本类确保修复后不再回退。
 */
class JwtCurrentPrincipalTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final Set<String> ROLES = Set.of("STUDENT", "TENANT_ADMIN", "VIEWER");

    private static KeyPair keyPair;
    private static JwtTokenService tokens;
    private static AuthenticationService auth;

    @BeforeAll
    static void setUp() {
        keyPair = JwtTokenService.generateKeyPair();
        tokens = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                "https://auth.studykid.cn", "agent-platform", Duration.ofMinutes(15), "k1");
        auth = new AuthenticationService(
                new PrincipalRepository(null), tokens,
                Duration.ofMinutes(15), 5, ROLES);
    }

    private static JwtCurrentPrincipal principal(String header) {
        return new JwtCurrentPrincipal(auth,
                Clock.fixed(NOW, ZoneOffset.UTC), header);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ════════════════════════════════════════════════════════════
    @Test
    @DisplayName("✅ 有效令牌 → 可信上下文（租户/主体/版本均来自令牌）")
    void validTokenYieldsTrustedContext() {
        TenantContext expected = new TenantContext(TENANT, "p-1", Set.of("STUDENT"), 5L);
        String token = tokens.issue(expected, NOW);

        TenantContext actual = principal(bearer(token)).require();

        assertThat(actual.tenantId()).isEqualTo(TENANT);
        assertThat(actual.principalId()).isEqualTo("p-1");
        assertThat(actual.authzVersion())
                .as("authz_version 必须透传，否则执行时重新授权会失效（红线 9）")
                .isEqualTo(5L);
    }

    // ════════════════════════════════════════════════════════════
    @Test
    @DisplayName("⛔ 无 Authorization 头 → 拒绝，无任何兜底租户")
    void missingHeaderRejected() {
        assertThatThrownBy(() -> principal(null).require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
        assertThatThrownBy(() -> principal("").require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
        assertThatThrownBy(() -> principal("   ").require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
    }

    @Test
    @DisplayName("⛔ 非 Bearer 方案 → 拒绝")
    void nonBearerSchemeRejected() {
        // ⚠️ 宽松匹配会让 Basic 凭据或拼接畸形值被误当令牌，
        //    掩盖真实的认证失败原因
        assertThatThrownBy(() -> principal("Basic dXNlcjpwYXNz").require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
        assertThatThrownBy(() -> principal("Bearer").require())
                .as("只有前缀没有令牌")
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
        assertThatThrownBy(() -> principal("Bearer ").require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
        assertThatThrownBy(() -> principal("BearerXabc").require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
    }

    @Test
    @DisplayName("⛔ 伪造令牌 → 拒绝且消息统一为 unauthorized")
    void forgedTokenRejected() {
        String forged = bearer("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhdHRhY2tlciJ9.AAAA");

        assertThatThrownBy(() -> principal(forged).require())
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class)
                .hasMessage("unauthorized")
                .as("消息须统一：告诉调用方「过期」还是「签名无效」等于送调试信号");
    }

    @Test
    @DisplayName("⛔ 过期令牌 → 拒绝")
    void expiredRejected() {
        String token = tokens.issue(
                new TenantContext(TENANT, "p-1", Set.of("STUDENT"), 1L), NOW);

        JwtCurrentPrincipal later = new JwtCurrentPrincipal(auth,
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC), bearer(token));

        assertThatThrownBy(later::require)
                .isInstanceOf(CurrentPrincipal.UnauthenticatedException.class);
    }

    @Test
    @DisplayName("⛔ 伪造 TENANT_ADMIN 角色被白名单过滤")
    void forgedAdminRoleFiltered() {
        // 假设签发能力泄露：攻击者签一个带超管角色的令牌。
        // 若服务端信任令牌 roles，此处就会拿到 TENANT_ADMIN。
        String token = tokens.issue(
                new TenantContext(TENANT, "p-1", Set.of("SUPER_ROOT"), 1L), NOW);

        TenantContext ctx = principal(bearer(token)).require();

        assertThat(ctx.roles())
                .as("未在白名单登记的角色必须被丢弃，不能凭令牌声明提权")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════
    @Test
    @DisplayName("⛔ 结构性防回退：控制器不得直接接收 TenantContext 参数")
    void controllersMustNotAcceptTenantContext() throws Exception {
        // ⚠️ 这是**防止部署阻断复发**的结构性门禁。
        //    D1 曾因控制器按参数接收 TenantContext 而可被伪造；
        //    若日后有人「顺手改回」这种写法，须在构建期失败。
        for (Class<?> controller : new Class<?>[]{
                cn.studykid.multiagent.api.run.RunController.class}) {

            for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
                boolean exposed = java.util.Arrays.stream(method.getParameterTypes())
                        .anyMatch(t -> t.getName().equals(TenantContext.class.getName()));

                assertThat(exposed)
                        .as("%s.%s 直接暴露 TenantContext 参数 —— "
                                + "上下文将可被客户端伪造，D1 部署阻断会复发。"
                                + "应改为通过 CurrentPrincipal 获取。",
                                controller.getSimpleName(), method.getName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("✅ CurrentPrincipal 是唯一可信身份来源")
    void principalInterfaceIsTheSingleSource() {
        Field[] fields = cn.studykid.multiagent.api.run.RunController.class
                .getDeclaredFields();

        boolean hasPrincipal = java.util.Arrays.stream(fields)
                .anyMatch(f -> f.getType().getSimpleName().equals("CurrentPrincipal"));

        assertThat(hasPrincipal)
                .as("控制器必须持有 CurrentPrincipal，否则无认证上下文来源")
                .isTrue();

        assertThat(java.util.Arrays.stream(fields)
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .map(Field::getType))
                .noneMatch(t -> t.getName().contains("Tenant"))
                .as("不得静态缓存 TenantContext —— 跨请求复用会导致租户串号");
    }
}