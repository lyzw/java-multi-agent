package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 请求上下文提取器的行为：<b>带得全、掩得准、注不进、爆不了</b>。
 *
 * <p><b>四条判据</b>：
 * <ol>
 *   <li><b>带得全</b>：接口地址、方法、参数三样都要在（缺一样排障就得回头猜）；</li>
 *   <li><b>掩得准</b>：口令 / 令牌 / 密钥一类必须掩码，且<b>键名无害但值像凭据</b>
 *       （JWT、Bearer）也要掩；</li>
 *   <li><b>注不进</b>：参数值里的 CR/LF 不得原样进日志（否则是日志注入）；</li>
 *   <li><b>爆不了</b>：超长值与超多参数必须被截断（否则一条日志能冲垮日志系统）。</li>
 * </ol>
 *
 * <p><b>反向验证</b>：把 {@link RequestLogContext} 里的掩码分支去掉 → 用例 2/3 变红；
 * 把控制字符替换去掉 → 用例 4 变红；把截断去掉 → 用例 5 变红。
 */
@DisplayName("RequestLogContext · 异常日志里的请求上下文")
class RequestLogContextTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest bind(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    @DisplayName("✅ 没有请求上下文时给占位符（不是空串）：日志必须始终可读")
    void noRequestBoundReturnsPlaceholder() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(RequestLogContext.describe())
                .as("「没有上下文」与「上下文是空串」在日志里长得一样，后者往往是上游塞了空租户")
                .isEqualTo(RequestLogContext.NONE);
        assertThat(RequestLogContext.describe((jakarta.servlet.http.HttpServletRequest) null))
                .isEqualTo(RequestLogContext.NONE);
    }

    @Test
    @DisplayName("✅ 方法 + 接口地址 + 参数三样齐全")
    void describesMethodUriAndParams() {
        MockHttpServletRequest request = bind("GET", "/api/v1/runs");
        request.setParameter("tenantId", "t-1");
        request.setParameter("page", "2");

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .contains("method=GET")
                .contains("uri=/api/v1/runs")
                .contains("params={")
                .contains("tenantId=t-1")
                .contains("page=2");
    }

    @Test
    @DisplayName("⛔ 敏感键一律掩码：口令 / 令牌 / 密钥 / 凭据")
    void masksSensitiveKeys() {
        MockHttpServletRequest request = bind("POST", "/api/v1/login");
        request.setParameter("password", "hunter2");
        request.setParameter("access_token", "at-123");
        request.setParameter("clientSecret", "cs-456");
        request.setParameter("tenantId", "t-1");

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .as("⛔ 记参数 ≠ 把凭据抄进日志：日志进了集中式系统后权限边界完全不同")
                .doesNotContain("hunter2")
                .doesNotContain("at-123")
                .doesNotContain("cs-456");
        assertThat(ctx)
                .contains("password=***")
                .contains("access_token=***")
                .contains("clientSecret=***")
                .contains("tenantId=t-1");
    }

    @Test
    @DisplayName("⛔ 键名无害但值像凭据（JWT / Bearer）同样掩码")
    void masksCredentialShapedValues() {
        MockHttpServletRequest request = bind("GET", "/api/v1/callback");
        request.setParameter("assertion",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        request.setParameter("credential", "Bearer abc.def.ghi");

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("⛔ 参数值里的 CR/LF 不得原样进日志（防日志注入）")
    void sanitizesControlCharacters() {
        MockHttpServletRequest request = bind("GET", "/api/v1/runs");
        request.setParameter("name", "a\r\n2026-01-01 WARN 管理员已授权");

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .as("⛔ 未净化的换行可伪造出整行假日志，把排障结论带偏")
                .doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(ctx).contains("2026-01-01 WARN 管理员已授权");
    }

    @Test
    @DisplayName("⛔ 超长值与超多参数被截断：一条日志不能冲垮日志系统")
    void truncatesLongValuesAndManyParams() {
        MockHttpServletRequest request = bind("GET", "/api/v1/runs");
        request.setParameter("blob", "x".repeat(500));
        for (int i = 0; i < 20; i++) {
            request.setParameter("p" + i, "v" + i);
        }

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .as("⛔ 超长值必须截断")
                .doesNotContain("x".repeat(200));
        assertThat(ctx).contains("blob=xxx");
        assertThat(ctx)
                .as("⛔ 参数条数必须有上限，且要写出还剩多少条")
                .contains("...(+9)");
        assertThat(ctx.length())
                .as("⛔ 整体长度必须有界")
                .isLessThan(2000);
    }

    @Test
    @DisplayName("⛔ multipart 请求不碰参数解析：不为多打几个字符去动文件流")
    void multipartRequestSkipsParams() {
        MockHttpServletRequest request = bind("POST", "/api/v1/upload");
        request.setContentType("multipart/form-data; boundary=xxx");
        request.setParameter("tenantId", "t-1");

        assertThat(RequestLogContext.describe()).contains("params={}");
    }

    @Test
    @DisplayName("✅ 路由模板在场：URI 有真实 id，模板有端点形状，两者互补")
    void includesMatchedPattern() {
        MockHttpServletRequest request = bind("GET", "/api/v1/runs/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/runs/{runId}");

        assertThat(RequestLogContext.describe())
                .contains("uri=/api/v1/runs/42")
                .contains("pattern=/api/v1/runs/{runId}");
    }
}
