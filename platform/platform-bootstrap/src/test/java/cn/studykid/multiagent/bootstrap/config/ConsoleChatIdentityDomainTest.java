package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 控制台「直接对话」的<b>身份域归属</b>守卫（2026-11-07 事故）。
 *
 * <p><b>事故</b>：{@code /api/chat/stream} 未匹配 {@code /api/platform/**}，
 * 落在 @Order(1) 的租户链上，而控制台持有的是平台令牌
 * （{@code aud=admin-console-api}）。受众校验硬拒 → 每次点发送都 401；
 * 前端把该 401 当成「登录过期」清会话硬跳登录页，对话草稿一并丢失。
 *
 * <p><b>为何要有源码守卫</b>：这类错配在 Controller 单测里完全看不出来
 * （单测直接 new，不经过 Security 链），只有真实 HTTP 才暴露。守卫把
 * 「端点必须挂在平台链」「控制器不得再要求 TenantContext」两条约束固定下来，
 * 防止后续重构把端点挪回去、或有人图省事用 {@code permitAll} 绕过 401。
 *
 * <p>⚠️ <b>防空转</b>：先断言两份源码确实被读到且含预期锚点，
 * 再做「包含 / 不包含」判断 —— 路径发现失败会让空串恒绿。
 */
class ConsoleChatIdentityDomainTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("⛔ /api/chat/** 必须与平台管理 API 共用平台安全链")
    void chatEndpointBelongsToPlatformIdentityDomain() {
        String security = codeOnly(read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                        + "SecurityConfiguration.java")));
        String controller = codeOnly(read(platformRoot().resolve(
                "agent-api/src/main/java/cn/studykid/multiagent/api/chat/"
                        + "ConsoleChatController.java")));

        // ── 防空转锚点 ──────────────────────────────────────────────
        assertThat(security)
                .contains("platformSecurityFilterChain", "PUBLIC_ENDPOINTS", "@Order(0)");
        assertThat(controller).contains("@RequestMapping(\"/api/chat\")", "ChatCaller");

        assertThat(security)
                .as("漏配即落回租户链：平台令牌（aud=admin-console-api）被 aud=agent-platform 校验拒绝，"
                        + "直接对话每次发送都会 401")
                .contains("securityMatcher(PLATFORM_API_PREFIX + \"**\", CONSOLE_CHAT_PREFIX + \"**\")");
        assertThat(security).contains("CONSOLE_CHAT_PREFIX = \"/api/chat/\"");

        // ⚠️ 严禁用「加进匿名白名单」来消掉 401 —— 那等于把管理员的 LLM 通道公开。
        int publicStart = security.indexOf("PUBLIC_ENDPOINTS");
        int publicEnd = security.indexOf("}", publicStart);
        assertThat(publicEnd).isGreaterThan(publicStart);
        assertThat(security.substring(publicStart, publicEnd)).doesNotContain("/api/chat");

        assertThat(controller)
                .as("平台主体刻意不持有 tenantId；控制器若再依赖 TenantContext / CurrentPrincipal，"
                        + "请求到业务层仍会 401")
                .doesNotContain("CurrentPrincipal", "TenantContext");
    }

    @Test
    @DisplayName("⛔ 两条安全链都必须放行 ASYNC dispatch，否则 SSE 终帧后连接不收尾")
    void asyncDispatchMustBePermittedForSseCompletion() {
        String security = codeOnly(read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                        + "SecurityConfiguration.java")));

        // ── 防空转锚点 ──────────────────────────────────────────────
        assertThat(security).contains("platformSecurityFilterChain", "PUBLIC_ENDPOINTS", "@Order(0)");

        long matchers = security.lines()
                .filter(l -> l.contains("dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()"))
                .count();
        assertThat(matchers)
                .as("平台链（/api/chat SSE）与租户链（RunEventController SSE）各一处。"
                        + "SseEmitter.complete() 触发的 ASYNC dispatch 上，"
                        + "Bearer 过滤器（OncePerRequestFilter）不再执行、SecurityContext 为空，"
                        + "若被 AuthorizationFilter 二次授权拦下，会在响应已提交后抛 Access Denied，"
                        + "chunked 流写不出终止块 → 浏览器/反代永远等不到 EOF（实测挂起 15s+）")
                .isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════
    // chatCaller：只认过滤器已验签的请求属性，取不到即 fail-closed
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 平台主体 → 确定性 UUID 限流键（主路径，必须是合法 uuid）")
    void platformPrincipalYieldsPlatformKey() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.setAttribute(SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE,
                new PlatformPrincipal("ops-1", Set.of("PLATFORM_ADMIN"), 1L, true));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        String key = new ApplicationConfiguration().chatCaller().rateLimitKey();

        // ⚠️ JdbcRateLimiter 把该值写进 rate_limit_bucket.tenant_id (?::uuid)，
        //    非 UUID 会触发 SQLException → 限流器 fail-closed 返回 429。
        assertThat(UUID.fromString(key)).isNotNull();
        assertThat(UUID.fromString(key).version())
                .as("用 v3 派生 UUID 作平台域命名空间，与真实租户 v4 UUID 天然不碰撞")
                .isEqualTo(3);
        assertThat(key).isEqualTo(UUID.nameUUIDFromBytes(
                "platform-chat:ops-1".getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(new ApplicationConfiguration().chatCaller().rateLimitKey())
                .as("同一平台用户的分桶键必须稳定，否则限流形同虚设")
                .isEqualTo(key);
    }

    @Test
    @DisplayName("✅ 租户主体仍可用（兼容租户令牌直连），直接用真实 tenantId")
    void tenantPrincipalYieldsTenantKey() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.setAttribute(SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE,
                new TenantContext("t-a", "p-1", Set.of("USER"), 1L));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(new ApplicationConfiguration().chatCaller().rateLimitKey())
                .isEqualTo("t-a");
    }

    @Test
    @DisplayName("⛔ 未经身份验签的请求一律 401，绝不返回默认身份")
    void missingVerifiedIdentityFailsClosed() {
        // 情形一：有请求上下文、但过滤器没写入任何已验签主体
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/chat/stream")));
        assertUnauthorized();

        // 情形二：完全没有请求上下文（Worker / 异步线程误用）
        RequestContextHolder.resetRequestAttributes();
        assertUnauthorized();
    }

    private static void assertUnauthorized() {
        assertThatThrownBy(() -> new ApplicationConfiguration().chatCaller().rateLimitKey())
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════

    /**
     * 逐行剔除注释，只留完整的代码行。
     *
     * <p>⚠️ <b>不能用正则一次性删块注释</b>：注释文本里出现路径通配
     * {@code /api/chat/} 加双星号时，会形成伪的块注释起点；跨行非贪婪正则
     * 会从那里一路吞到很后面的结束符，把 {@code .securityMatcher(...)}
     * 这一真实代码行一起吃掉 → 守卫假红。按行做状态机则不受注释内容影响。
     */
    private static String codeOnly(String src) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        for (String line : src.split("\\R", -1)) {
            String trimmed = line.trim();
            if (inBlock) {
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlock = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }

    /** 与 DatabaseBoundaryTest#platformRoot 同构，额外兼容 user.dir 停在仓库根的情形。 */
    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path direct = dir.resolve("agent-core");
            if (Files.isDirectory(direct) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            Path nested = dir.resolve("platform");
            if (Files.isDirectory(nested.resolve("agent-core"))
                    && Files.exists(nested.resolve("pom.xml"))) {
                return nested;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "找不到 platform 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }
}
