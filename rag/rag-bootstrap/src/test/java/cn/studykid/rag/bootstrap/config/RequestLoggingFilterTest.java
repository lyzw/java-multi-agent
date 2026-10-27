package cn.studykid.rag.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.studykid.rag.application.observability.LogContext;
import cn.studykid.rag.bootstrap.config.RequestLoggingConfiguration.RequestLoggingFilter;

/**
 * RAG 检索服务 HTTP 入口的链路上下文。
 *
 * <p>判据与主平台侧 {@code RequestLoggingFilterTest} 对齐（两份实现必须同行为，
 * 否则跨进程 traceId 检索会静默失效）：
 * 沿用合法上游值、丢弃含 CR/LF 的值、回写响应头、请求结束清空 MDC。
 */
@DisplayName("rag RequestLoggingFilter · HTTP 入口链路上下文")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach
    void tearDown() {
        LogContext.clear();
    }

    private Map<String, String> run(MockHttpServletRequest request,
                                    MockHttpServletResponse response) throws Exception {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        filter.doFilter(request, response,
                (rq, rs) -> seen.set(LogContext.snapshot().asMap()));
        return seen.get();
    }

    @Test
    @DisplayName("沿用主平台透传的 X-Trace-Id：跨进程用同一个 id 检索")
    void reusesUpstreamTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/retrieval");
        request.addHeader(RequestLoggingConfiguration.TRACE_ID_HEADER, "a1b2c3d4e5f6");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).containsEntry(LogContext.TRACE_ID, "a1b2c3d4e5f6");
        assertThat(response.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .isEqualTo("a1b2c3d4e5f6");
    }

    @Test
    @DisplayName("无上游头 → 生成 32 位十六进制（与主平台同形态）并回写响应头")
    void generatesWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> during =
                run(new MockHttpServletRequest("GET", "/api/v1/retrieval"), response);

        assertThat(during.get(LogContext.TRACE_ID)).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .isEqualTo(during.get(LogContext.TRACE_ID));
    }

    @Test
    @DisplayName("⚠️ 含 CR/LF 的头值不得原样回写响应头（HTTP 响应拆分）")
    void rejectsHostileHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/retrieval");
        request.addHeader(RequestLoggingConfiguration.TRACE_ID_HEADER, "ok\r\nX-Injected: 1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String traceId = run(request, response).get(LogContext.TRACE_ID);
        assertThat(traceId).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ");
        assertThat(response.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .isEqualTo(traceId);
    }

    @Test
    @DisplayName("身份位进入链路时是占位符：由鉴权过滤器在验签成功后覆盖")
    void identityStartsUnknown() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/retrieval"), response))
                .containsEntry(LogContext.TENANT_ID, LogContext.NONE)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.NONE);
    }

    @Test
    @DisplayName("⚠️ 请求结束清空 MDC（容器线程复用 → 漏清即跨租户串号）")
    void clearsAfterRequest() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/v1/retrieval"),
                new MockHttpServletResponse());
        assertThat(LogContext.traceId()).isNull();
    }
}
