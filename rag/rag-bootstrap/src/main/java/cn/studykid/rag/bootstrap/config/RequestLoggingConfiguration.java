package cn.studykid.rag.bootstrap.config;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import cn.studykid.rag.application.observability.LogContext;

/**
 * RAG 检索服务 HTTP 入口的日志上下文装配。
 *
 * <p>与主平台侧 {@code RequestLoggingConfiguration} 同构（键名、净化规则、
 * traceId 形态必须一致，否则跨进程日志无法用同一个 id 整词检索）：
 * <ul>
 *   <li>traceId 取自 {@code X-Trace-Id}（主平台调用 RAG 时透传），缺失或不合法则新生成；</li>
 *   <li>注册在 {@link Ordered#HIGHEST_PRECEDENCE}，早于
 *       {@link cn.studykid.rag.bootstrap.security.RagJwtAuthFilter}，
 *       因此 401/403 的日志同样带链路号；</li>
 *   <li>租户与主体由 {@code RagJwtAuthFilter} 在验签成功后写入，此处只留占位。</li>
 * </ul>
 *
 * <p>⚠️ 本类只登记一个 {@code /*} 的过滤器，不参与鉴权；
 * 鉴权仍是 {@link cn.studykid.rag.bootstrap.security.RagSecurityConfig} 的
 * {@code /api/*} 范围，两者不要合并——排查「日志没有链路号」时，
 * 合并后的过滤器会让人先去查鉴权配置。
 */
@Configuration
public class RequestLoggingConfiguration {

    /** 请求头 / 响应头名：贯穿调用链的标识（与主平台同名）。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 请求属性名：本次请求最终采用的 traceId（供 ERROR 二次派发复用）。 */
    static final String TRACE_ID_ATTRIBUTE = "cn.studykid.rag.traceId";

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> ragRequestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> reg =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        reg.addUrlPatterns("/*");
        reg.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        reg.setName("ragRequestLoggingFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /** 写入 traceId、回写响应头，并在请求结束时清空 MDC（容器线程复用，漏清即串号）。 */
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

            try {
                chain.doFilter(request, response);
            } finally {
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
