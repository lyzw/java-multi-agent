package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * JSON 请求的 body 快照：<b>打得出来、掩得准、注不进、爆不了</b>。
 *
 * <p><b>四条判据</b>：
 * <ol>
 *   <li><b>打得出来</b>：JSON 请求必须能看见 body 里的字段——
 *       只打 query 参数等于什么都没打（payload 全在 body 里）；</li>
 *   <li><b>掩得准</b>：body 里的口令 / 令牌 / 密钥必须掩码，
 *       「键名无害但值像凭据」（JWT、Bearer）也要掩；</li>
 *   <li><b>注不进</b>：body 里的 CR/LF 不得原样进日志；</li>
 *   <li><b>爆不了</b>：超长 body 必须被截断。</li>
 * </ol>
 *
 * <p><b>反向验证</b>：把 {@link RequestLogContext} 的 body 掩码分支去掉 → 用例 3/4 变红；
 * 把截断去掉 → 用例 6 变红；把 {@code body}/{@code params} 的分流去掉 → 用例 1/2 变红。
 */
@DisplayName("RequestLogContext · JSON 请求打 body")
class RequestLogContextJsonBodyTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 构造一个「已被过滤器包装、body 已读完」的 JSON 请求。 */
    private static CachedBodyHttpServletRequest jsonRequest(String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        // 读一遍：模拟消息转换器把 body 读空（异常发生时的真实状态）。
        cached.getInputStream().readAllBytes();
        return cached;
    }

    private static String describe(CachedBodyHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return RequestLogContext.describe();
    }

    @Test
    @DisplayName("✅ JSON 请求打 body：字段看得见（payload 全在 body 里，只打参数等于没打）")
    void jsonRequestPrintsBody() throws Exception {
        String ctx = describe(jsonRequest("{\"tenantId\":\"t-1\",\"question\":\"今天讲什么\"}"));

        assertThat(ctx)
                .as("⛔ 看不到 body，排障就只能回头翻前端代码猜这次到底传了什么")
                .contains("body=")
                .contains("\"tenantId\":\"t-1\"")
                .contains("question");
    }

    @Test
    @DisplayName("✅ 普通请求仍然打参数：body 与 params 两条路各走各的")
    void plainRequestPrintsParams() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.setParameter("tenantId", "t-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        String ctx = RequestLogContext.describe();

        assertThat(ctx).contains("params={").contains("tenantId=t-1");
        assertThat(ctx).as("非 JSON 请求不该有 body 段").doesNotContain("body=");
    }

    @Test
    @DisplayName("⛔ body 里的敏感键一律掩码：口令 / 令牌 / 密钥")
    void masksSensitiveBodyKeys() throws Exception {
        String ctx = describe(jsonRequest(
                "{\"tenantId\":\"t-1\",\"password\":\"hunter2\",\"clientSecret\":\"cs-456\"}"));

        assertThat(ctx)
                .as("⛔ body 里的口令明文不得进日志")
                .doesNotContain("hunter2")
                .doesNotContain("cs-456");
        assertThat(ctx)
                .contains("\"password\":\"***\"")
                .contains("\"clientSecret\":\"***\"")
                .contains("\"tenantId\":\"t-1\"");
    }

    @Test
    @DisplayName("⛔ 键名无害但值像凭据（JWT / Bearer）同样掩码")
    void masksCredentialShapedBodyValues() throws Exception {
        String ctx = describe(jsonRequest(
                "{\"assertion\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
                        + ".dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk\"}"));

        assertThat(ctx)
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("\"assertion\":\"***\"");
    }

    @Test
    @DisplayName("⛔ body 格式错误时也要能看见原文（但敏感键仍掩码）")
    void malformedBodyStillVisible() throws Exception {
        String ctx = describe(jsonRequest("{\"password\": \"hunter2\", \"tenantId\": "));

        assertThat(ctx)
                .as("⛔ 格式错误恰恰是最需要看原文的时候")
                .contains("tenantId")
                .doesNotContain("hunter2");
    }

    @Test
    @DisplayName("⛔ body 里的 CR/LF 不得原样进日志（防日志注入）")
    void sanitizesControlCharactersInBody() throws Exception {
        String ctx = describe(jsonRequest("{\"note\":\"a\\nb\\n2026-01-01 WARN 管理员已授权\"}"));

        assertThat(ctx)
                .as("⛔ 未净化的换行可伪造出整行假日志，把排障结论带偏")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("⛔ 超长 body 被截断：一条日志不能冲垮日志系统")
    void truncatesLongBody() throws Exception {
        String huge = "{\"question\":\"" + "x".repeat(5000) + "\"}";

        String ctx = describe(jsonRequest(huge));

        assertThat(ctx.length())
                .as("⛔ body 整体长度必须有界")
                .isLessThan(1200);
        assertThat(ctx).contains("...");
    }

    @Test
    @DisplayName("✅ 拿不到 body 时明确标注 unavailable：与「客户端没传」区分开")
    void missingBodyIsExplicit() {
        // 未被过滤器包装的 JSON 请求：body 读不到。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json");
        request.setParameter("tenantId", "t-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        String ctx = RequestLogContext.describe();

        assertThat(ctx)
                .as("「我们没读到」与「客户端没传」是两件事，日志里必须能分开")
                .contains("body={unavailable}");
    }

    @Test
    @DisplayName("✅ JSON 请求同时带 query 参数时，参数照样附在后面")
    void jsonRequestKeepsQueryParams() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json");
        request.setParameter("dryRun", "true");
        request.setContent("{\"tenantId\":\"t-1\"}".getBytes(StandardCharsets.UTF_8));
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        cached.getInputStream().readAllBytes();

        String ctx = describe(cached);

        assertThat(ctx).contains("body=").contains("\"tenantId\":\"t-1\"");
        assertThat(ctx).contains("params=").contains("dryRun=true");
    }
}
