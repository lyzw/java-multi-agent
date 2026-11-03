package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import cn.studykid.multiagent.application.observability.LogContext;

/**
 * HTTP 入口的日志上下文装配 + 请求访问日志（请求线程一侧）。
 *
 * <p><b>请求行日志在本类一并完成</b>：在 {@code chain.doFilter} 返回后、{@link LogContext#clear()}
 * 之前打印一行访问日志。此时安全链里的 Bearer 过滤器已把租户/主体写进 MDC，
 * 因此这行日志的 {@code [traceId tenantId principalId]} 段是完整的；未认证请求的
 * 两个身份字段照旧为 {@code -}（见下）。
 *
 * <p><b>为什么是最外层过滤器而不是安全链内部</b>：
 * traceId 的价值在于「任何一段日志都能被定位到同一条链路」，
 * 包括<b>还没认证就已经失败</b>的请求（401、限流、框架异常）。
 * 放在 Spring Security 之后，这些恰恰最难排障的场景反而没有链路号。
 * 本过滤器注册在 {@link Ordered#HIGHEST_PRECEDENCE}，早于安全链
 * （{@code OrderedFilter} 默认 −100）。
 *
 * <p><b>租户与主体不在这里写</b>：身份只有验签成功后才可信，
 * 由 {@link SecurityConfiguration} 的两个 Bearer 过滤器写入。
 * 未认证请求的两个字段因此是 {@code -} —— 这是事实，不是缺失。
 *
 * <p><b>traceId 的来源优先级</b>：请求属性（同一请求的二次派发，见下）
 * &gt; {@code X-Trace-Id} 请求头 &gt; 新生成。头是外部可控输入，
 * 净化与长度截断在 {@link LogContext#sanitize} 内完成。
 *
 * <p><b>为什么要存请求属性</b>：ERROR 派发（{@code /error}）与 ASYNC 派发
 * 会再次进入容器过滤器链。只认请求头的话，第二次进来时头可能已不存在，
 * 于是<b>同一次请求打出两个 traceId</b> —— 排障时最迷惑的假象。
 * 属性挂在同一个 request 对象上，二次派发必然取到同一个值。
 */
@Configuration
public class RequestLoggingConfiguration {

    /** 请求头 / 响应头名：贯穿调用链的标识。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 请求属性名：本次请求最终采用的 traceId。
     *
     * <p>包级可见：{@link ApplicationConfiguration} 的 {@code correlationId()}
     * 读它，使「日志里的链路号」与「落库的 correlation_id」是同一个值。
     */
    static final String TRACE_ID_ATTRIBUTE = "cn.studykid.multiagent.traceId";

    /**
     * 访问日志 logger。独立命名以便运维单独控制级别
     * （{@code logging.level.cn.studykid.multiagent.http.access=OFF} 即可关闭，无需改代码）。
     * 行格式为 {@code <METHOD> <URI> <STATUS> <durationMs>ms}；
     * 链路三要素由 logback 的 MDC 段统一带上，不在此重复。
     */
    private static final Logger ACCESS_LOG =
            LoggerFactory.getLogger("cn.studykid.multiagent.http.access");

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> reg =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        reg.addUrlPatterns("/*");
        // ERROR：/error 派发也要有链路号；ASYNC：异步派发的线程另由 LogContext.wrap 负责。
        // ⚠️ 访问日志只在 REQUEST 派发打一行：ERROR 二次派发（/error）若也打会重复计数。
        reg.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        reg.setName("requestLoggingFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * 写入 traceId、回写响应头，并在请求结束时打印访问日志、清空 MDC。
     *
     * <p>响应头必须回写：用户报障时只需要复制响应头，
     * 不需要我们再去服务端日志里按时间猜。
     *
     * <p>访问日志在 {@code chain.doFilter} 之后、{@link LogContext#clear()} 之前打印：
     * 此时安全链里的 Bearer 过滤器已把租户/主体写进 MDC，日志的链路段是完整的；
     * {@code durationMs} 取自进入过滤器到链返回的时间差。
     * URI 只取 path（{@code getRequestURI()}）、不带 query string——
     * query 可能含令牌/授权码等敏感参数，落访问日志即泄露（本项目日志不存原文/敏感字段）。
     *
     * <p>{@code finally} 里的 {@link LogContext#clear()} 不是可选的：
     * 容器线程会被复用，漏清会把上一个请求的租户/主体带进下一个请求的日志（串号）。
     */
    public static class RequestLoggingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            String traceId = resolveTraceId(request);
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            LogContext.setTraceId(traceId);
            LogContext.clearIdentity();
            response.setHeader(TRACE_ID_HEADER, traceId);

            // 仅 REQUEST 派发计为一次访问；ERROR 二次派发复用同一 traceId，不再额外计数。
            boolean accessLoggable = request.getDispatcherType() == DispatcherType.REQUEST;
            long startNanos = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                if (accessLoggable) {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    ACCESS_LOG.info("{} {} {} {}ms",
                            request.getMethod(), request.getRequestURI(),
                            response.getStatus(), elapsedMs);
                }
                LogContext.clear();
            }
        }

        private String resolveTraceId(HttpServletRequest request) {
            Object fromPreviousDispatch = request.getAttribute(TRACE_ID_ATTRIBUTE);
            if (fromPreviousDispatch instanceof String existing && !existing.isBlank()) {
                return existing;
            }
            String fromHeader = request.getHeader(TRACE_ID_HEADER);
            if (fromHeader == null || fromHeader.isBlank()) {
                return LogContext.newTraceId();
            }
            // 净化后再回写：未净化的头值进响应头就是 HTTP 响应拆分（CR/LF 注入）。
            String sanitized = LogContext.sanitize(fromHeader);
            return LogContext.NONE.equals(sanitized) ? LogContext.newTraceId() : sanitized;
        }
    }
}
