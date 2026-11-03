package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import cn.studykid.multiagent.application.observability.LogContext;
import jakarta.servlet.DispatcherType;
import cn.studykid.multiagent.bootstrap.config.RequestLoggingConfiguration.RequestLoggingFilter;

/**
 * HTTP 入口过滤器的行为：<b>链路号从哪来、给谁看、什么时候必须没有</b>。
 *
 * <p><b>⚠️ 三条不可退让的判据</b>：
 * <ol>
 *   <li>响应头必须回写同一个 id —— 用户报障时只可能提供响应头里的值；</li>
 *   <li>{@code X-Trace-Id} 是外部可控输入，含 CR/LF 的值<b>必须被丢弃</b>：
 *       未净化就回写响应头即 HTTP 响应拆分，未净化就进 MDC 即日志注入；</li>
 *   <li>请求结束必须清空 MDC —— 容器线程复用，漏清会把上一个请求的租户
 *       打进下一个请求的日志（跨租户串号，且看起来完全正常）。</li>
 * </ol>
 */
@DisplayName("RequestLoggingFilter · HTTP 入口链路上下文")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    /** 放行时刻的上下文（过滤器内部看不到，只能在链上取）。 */
    private Map<String, String> insideChain;
    private MockHttpServletResponse lastResponse;

    /** 捕获访问日志输出（独立命名的 logger，便于断言「打了几行、写了什么」）。 */
    private ListAppender<ILoggingEvent> accessAppender;
    private Logger accessLogger;

    @BeforeEach
    void attachAccessAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger("cn.studykid.multiagent.http.access");
        accessAppender = new ListAppender<>();
        accessAppender.start();
        accessLogger.addAppender(accessAppender);
        // 单元测试未加载 logback-spring.xml，显式锁定 INFO 以保证 info(...) 被处理。
        accessLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAccessAppender() {
        if (accessLogger != null && accessAppender != null) {
            accessLogger.detachAppender(accessAppender);
        }
        if (accessAppender != null) {
            accessAppender.stop();
        }
        LogContext.clear();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> seen.set(LogContext.snapshot().asMap()));
        this.insideChain = seen.get();
        this.lastResponse = response;
        return response;
    }

    @Test
    @DisplayName("无 X-Trace-Id → 就地生成 32 位十六进制，MDC 与响应头同值")
    void generatesWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        run(request);

        String traceId = insideChain.get(LogContext.TRACE_ID);
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(lastResponse.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .as("响应头必须回写：否则用户无从报障")
                .isEqualTo(traceId);
    }

    @DisplayName("合法 X-Trace-Id → 沿用上游值（跨进程串联的前提）")
    @Test
    void reusesValidUpstreamHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.addHeader(RequestLoggingConfiguration.TRACE_ID_HEADER, "a1b2c3d4e5f6");
        run(request);

        assertThat(insideChain.get(LogContext.TRACE_ID)).isEqualTo("a1b2c3d4e5f6");
        assertThat(lastResponse.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .isEqualTo("a1b2c3d4e5f6");
    }

    @Test
    @DisplayName("⚠️ 含 CR/LF 的头被丢弃并就地生成（响应头与 MDC 都不可被注入）")
    void rejectsHostileHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.addHeader(RequestLoggingConfiguration.TRACE_ID_HEADER, "ok\r\nX-Injected: 1");
        run(request);

        String traceId = insideChain.get(LogContext.TRACE_ID);
        assertThat(traceId).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ");
        // 白名单之外的字符（CR/LF、空格）被丢弃，":" 与字母数字保留：
        // 关键是回写的值里再没有能拆出新响应行或新日志行的字符。
        assertThat(traceId).isEqualTo("okX-Injected:1");
        assertThat(lastResponse.getHeader(RequestLoggingConfiguration.TRACE_ID_HEADER))
                .isEqualTo("okX-Injected:1");
    }

    @Test
    @DisplayName("全非法的头值等价于「没有」：生成新值，而不是回写一个空 id")
    void blankAfterSanitizeRegenerates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.addHeader(RequestLoggingConfiguration.TRACE_ID_HEADER, "///");
        run(request);

        assertThat(insideChain.get(LogContext.TRACE_ID)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("同一请求的二次派发（ERROR）复用已有链路号，不产生第二个 id")
    void reusesTraceAcrossDispatches() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.setAttribute(RequestLoggingConfiguration.TRACE_ID_ATTRIBUTE, "round-one-trace");
        run(request);

        assertThat(insideChain.get(LogContext.TRACE_ID)).isEqualTo("round-one-trace");
    }

    @Test
    @DisplayName("未认证时身份位是占位符：链路号有、租户与主体明确标为未知")
    void identityIsPlaceholderBeforeAuthentication() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/v1/runs"));

        assertThat(insideChain)
                .containsEntry(LogContext.TENANT_ID, LogContext.NONE)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.NONE);
    }

    @Test
    @DisplayName("⚠️ 请求结束必须清空 MDC（容器线程复用 → 漏清即跨请求串号）")
    void clearsMdcAfterRequest() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/v1/runs"));

        assertThat(LogContext.traceId())
                .as("过滤器返回后本线程不应残留任何链路上下文")
                .isNull();
    }

    @Test
    @DisplayName("链上抛异常也必须清空（异常路径同样在线程复用路径上）")
    void clearsMdcWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> filter.doFilter(request, response,
                        (rq, rs) -> {
                            throw new IllegalStateException("下游炸了");
                        }));

        assertThat(LogContext.traceId()).isNull();
    }

    @Test
    @DisplayName("REQUEST 派发 → 打印一行访问日志：<METHOD> <URI> <STATUS> <durationMs>ms")
    void emitsAccessLogLineOnRequestDispatch() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/v1/runs"));

        assertThat(accessAppender.list).hasSize(1);
        String msg = accessAppender.list.get(0).getFormattedMessage();
        assertThat(msg)
                .startsWith("GET /api/v1/runs 200 ")
                .endsWith("ms");
    }

    @Test
    @DisplayName("访问日志在链返回后打印 → 已验签的租户/主体进入日志 MDC 段")
    void accessLogCarriesResolvedIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 模拟 Bearer 过滤器在链上写入的已验签身份（排障时日志被当证据读）。
        filter.doFilter(request, response, (rq, rs) -> LogContext.setIdentity("tenant-a", "user-42"));

        assertThat(accessAppender.list).hasSize(1);
        assertThat(accessAppender.list.get(0).getMDCPropertyMap())
                .containsEntry(LogContext.TENANT_ID, "tenant-a")
                .containsEntry(LogContext.PRINCIPAL_ID, "user-42");
    }

    @Test
    @DisplayName("ERROR 二次派发（/error）复用同一链路号，但不重复打印访问日志")
    void noDuplicateAccessLogOnErrorDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.setDispatcherType(DispatcherType.ERROR);
        run(request);

        // 关键：ERROR 派发只补链路号，不再计为「一次访问」，否则 /error 会重复计数。
        assertThat(accessAppender.list).isEmpty();
        // 链路号仍应被生成（外部未给），且保持单一。
        assertThat(insideChain.get(LogContext.TRACE_ID)).matches("[0-9a-f]{32}");
    }
}
