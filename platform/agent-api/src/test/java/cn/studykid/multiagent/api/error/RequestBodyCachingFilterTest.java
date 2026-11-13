package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.studykid.multiagent.api.error.RequestBodyCachingConfiguration.RequestBodyCachingFilter;

/**
 * 请求体缓存过滤器：<b>包得对、读得到、爆不了</b>。
 *
 * <p><b>三条判据</b>：
 * <ol>
 *   <li><b>包得对</b>：只有「JSON + 允许带 body 的方法」才包装；
 *       其余请求原样放行（不碰文件流、不碰表单流）；</li>
 *   <li><b>读得到</b>：包装后控制器读到的内容与未包装时<b>完全一致</b>
 *       （包装只缓存，不改语义）；</li>
 *   <li><b>爆不了</b>：缓存有上限，超出部分照常透传但不再留存。</li>
 * </ol>
 */
@DisplayName("RequestBodyCachingFilter · JSON 请求体缓存")
class RequestBodyCachingFilterTest {

    private final RequestBodyCachingFilter filter = new RequestBodyCachingFilter();

    /** 链上看到的请求（过滤器把包装后的请求传下去）。 */
    private final AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        seen.set(null);
        filter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> seen.set((HttpServletRequest) rq));
        return seen.get();
    }

    @Test
    @DisplayName("✅ JSON POST 被包装，且 body 能被缓存下来")
    void wrapsJsonPostAndCachesBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json");
        request.setContent("{\"tenantId\":\"t-1\"}".getBytes(StandardCharsets.UTF_8));

        HttpServletRequest downstream = run(request);

        assertThat(downstream).isInstanceOf(CachedBodyHttpServletRequest.class);
        CachedBodyHttpServletRequest cached = (CachedBodyHttpServletRequest) downstream;
        assertThat(new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .as("⛔ 包装不得改变语义：控制器读到的必须还是原始 body")
                .isEqualTo("{\"tenantId\":\"t-1\"}");
        assertThat(new String(cached.cachedBody(), StandardCharsets.UTF_8))
                .as("⛔ body 必须被缓存下来，否则异常到达时什么都看不到")
                .isEqualTo("{\"tenantId\":\"t-1\"}");
    }

    @Test
    @DisplayName("✅ 非 JSON 请求不包装：不为多打几个字符去动文件流 / 表单流")
    void leavesNonJsonRequestUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/upload");
        request.setContentType("multipart/form-data; boundary=xxx");

        assertThat(run(request)).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    @DisplayName("✅ GET 不包装：按规范不带 body，包了只是白包一层")
    void leavesGetUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.setContentType("application/json");

        assertThat(run(request)).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    @DisplayName("⛔ 缓存有上限：超大 body 只留前 8 KiB，其余照常透传")
    void cacheIsBoundedButStreamIsNot() throws Exception {
        String huge = "x".repeat(50_000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json");
        request.setContent(huge.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequest cached = (CachedBodyHttpServletRequest) run(request);

        // 先读：缓存是「边读边留」，没被读过的字节不会凭空出现。
        String delivered = new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(cached.cachedBody())
                .as("⛔ 缓存必须有上限：客户端发 100 MB JSON 就把服务器堆吃光，是排障本身变成故障")
                .hasSize(CachedBodyHttpServletRequest.CACHE_LIMIT);
        assertThat(cached.isTruncated()).isTrue();
        assertThat(delivered)
                .as("⛔ 超限只影响留存，不影响业务读取")
                .hasSize(50_000);
    }

    @Test
    @DisplayName("✅ 流只能读一次语义保持：两次 getInputStream 返回同一个实例（否则缓存会重复）")
    void inputStreamIsCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        request.setContentType("application/json");
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequest cached = (CachedBodyHttpServletRequest) run(request);

        assertThat(cached.getInputStream()).isSameAs(cached.getInputStream());
        cached.getInputStream().readAllBytes();
        assertThat(new String(cached.cachedBody(), StandardCharsets.UTF_8))
                .as("⛔ 重复缓存同一段 body 会让日志里的 body 变成两倍")
                .isEqualTo("{\"a\":1}");
    }
}
