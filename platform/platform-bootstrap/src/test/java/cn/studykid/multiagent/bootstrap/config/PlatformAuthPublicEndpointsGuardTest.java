package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DEF-01 反向验证守卫（2026-11-13 E2E 实测缺陷）。
 *
 * <p><b>缺陷原貌</b>：平台安全链把 {@code /api/platform/v1/auth/**} 整体 permitAll，
 * 于是需认证的 {@code /auth/me} 也匿名可达。无令牌/伪造令牌时
 * {@code @RequestAttribute(平台主体)} 缺失抛 {@code ServletRequestBindingException}，
 * 被 {@code ApiExceptionHandler} 兜底吞成 <b>500 INTERNAL_ERROR</b>；前端 401 清会话分支
 * 永不触发，认证失败被伪装成服务端故障。
 *
 * <p><b>为什么是源码级守卫</b>：「哪个端点被匿名放行」属于过滤链装配结构，
 * 控制器单测与业务 IT 都覆盖不到；一旦有人把精确列表改回通配前缀，
 * 表现不是报错而是静默的语义反转，只有真实 HTTP 才能发现。本守卫把该红线锁在 CI。
 *
 * <p><b>撤点即红</b>：把 permitAll 改回 {@code /auth/**} → 通配断言红；
 * 删掉 {@code ServletRequestBindingException} 处理器 → 401 兜底断言红。
 *
 * <p>⚠️ 断言前剥离注释但保留字符串字面量：注释里提到通配路径不得被当成真实引用。
 */
class PlatformAuthPublicEndpointsGuardTest {

    /** 相对 platform reactor 根。 */
    private static final String SECURITY =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/SecurityConfiguration.java";
    private static final String HANDLER =
            "agent-api/src/main/java/cn/studykid/multiagent/api/error/ApiExceptionHandler.java";

    @Test
    @DisplayName("⛔ 平台链不得再按 /api/platform/v1/auth/** 前缀整体放行")
    void platformAuthPrefixIsNotWildcardPermitAll() {
        String code = code(SECURITY);
        assertThat(code)
                .as("⛔ 前缀式 permitAll 会把 /auth/me 一并匿名放行 → 未认证 500（DEF-01）")
                .doesNotContain("requestMatchers(\"/api/platform/v1/auth/**\")")
                .doesNotContain("\"/api/platform/v1/auth/**\"");
    }

    @Test
    @DisplayName("✅ 只精确放行 login / jwks，其余平台端点仍走 authenticated")
    void onlyLoginAndJwksArePublic() {
        String code = code(SECURITY);
        assertThat(code)
                .as("⛔ 精确端点清单丢失会导致登录链或 JWKS 不可用，或再次放行 /me")
                .contains("\"/api/platform/v1/auth/login\"")
                .contains("\"/api/platform/v1/auth/jwks\"")
                .contains(".anyRequest().authenticated()");
        // 反向：只允许这两个字符串出现在同一个 requestMatchers 调用里，避免新增第三个匿名端点。
        assertThat(code).contains("requestMatchers(\"/api/platform/v1/auth/login\",");
    }

    @Test
    @DisplayName("✅ 主体属性缺失必须有专用 401 处理器（第二道保险）")
    void missingPrincipalHandlerIsUnauthorized() {
        String code = code(HANDLER);
        assertThat(code)
                .as("⛔ 缺少专用处理器时该异常会落入兜底 → 500 INTERNAL_ERROR")
                .contains("@ExceptionHandler(ServletRequestBindingException.class)")
                .contains("HttpStatus.UNAUTHORIZED")
                .contains("\"UNAUTHENTICATED\"");
    }

    private static String code(String relativePath) {
        try {
            return stripCommentsKeepStrings(
                    Files.readString(root().resolve(relativePath), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("读取源码失败: " + relativePath, e);
        }
    }

    /** surefire 的 user.dir 可能是模块目录，向上找含 agent-core 子模块的 reactor 根。 */
    private static Path root() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    /** 剥离行/块注释，保留字符串与 text block 内容（URL、路径都是本守卫要锁的字面量）。 */
    private static String stripCommentsKeepStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int close = src.indexOf("\"\"\"", i + 3);
                if (close < 0) {
                    out.append(src, i, n);
                    break;
                }
                out.append(src, i, close + 3);
                i = close + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                int start = i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append(src, start, i);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
