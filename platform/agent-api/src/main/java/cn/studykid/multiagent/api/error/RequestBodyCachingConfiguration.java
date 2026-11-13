package cn.studykid.multiagent.api.error;

import java.io.IOException;
import java.util.Locale;

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

/**
 * 请求体缓存过滤器：让 <b>JSON 请求的 body 在异常到达时仍可回读</b>。
 *
 * <p><b>为什么必须有一个过滤器</b>：请求体是<b>一次性</b>的。消息转换器读完之后，
 * 流里就没有内容了；等异常处理器想记「这次请求带了什么」，能拿到的只有一个空壳。
 * 因此必须在 body 被读之前（入口处）把读过的字节存一份——这只能在过滤器里做。
 *
 * <p><b>装在哪一层</b>：注册在 {@link Ordered#HIGHEST_PRECEDENCE} 之后一位，
 * 即<b>安全链之前</b>。放在安全链之后的话，未认证就失败的请求（401、限流）
 * 拿不到 body，而那恰恰是最需要知道「客户端到底发了什么」的场景。
 *
 * <p><b>只缓存 JSON 请求</b>：文件上传（multipart）不碰——解析它可能落盘 / 占内存，
 * 而异常路径上为了多打几个字符去动文件流，收益与风险完全不成比例；
 * 表单请求的参数本来就从 {@code getParameterMap()} 拿得到，无需缓存 body。
 *
 * <p><b>缓存有界</b>：见 {@link CachedBodyHttpServletRequest}——只留前 8 KiB，
 * 超出部分照常透传，只是不再留存。
 *
 * <p>⚠️ <b>本过滤器不改变任何请求语义</b>：透传的仍是原始字节流，
 * 控制器读到的内容与未包装时完全一致（见 {@link CachedBodyHttpServletRequest} 的纪律）。
 */
@Configuration
public class RequestBodyCachingConfiguration {

    @Bean
    public FilterRegistrationBean<RequestBodyCachingFilter> requestBodyCachingFilter() {
        FilterRegistrationBean<RequestBodyCachingFilter> reg =
                new FilterRegistrationBean<>(new RequestBodyCachingFilter());
        reg.addUrlPatterns("/*");
        // ⚠️ 只在 REQUEST 派发包装：ERROR 二次派发（/error）拿到的是原始请求，
        //    再包一次既无意义（body 早已消费），又会让「同一次请求两个包装对象」。
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        reg.setName("requestBodyCachingFilter");
        // 紧跟 RequestLoggingFilter（HIGHEST_PRECEDENCE）之后，仍早于安全链。
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return reg;
    }

    /**
     * 把 JSON 请求包装成 {@link CachedBodyHttpServletRequest}；其余请求原样放行。
     */
    public static class RequestBodyCachingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {
            if (isCacheable(request)) {
                chain.doFilter(new CachedBodyHttpServletRequest(request), response);
            } else {
                chain.doFilter(request, response);
            }
        }

        /**
         * 是否值得缓存：<b>JSON + 允许带 body 的方法</b>。
         *
         * <p>方法限定为 POST / PUT / PATCH / DELETE：GET / HEAD / OPTIONS 按规范不带 body，
         * 包了也只是白包一层（多一次对象分配 + 一次流包装）。
         */
        private static boolean isCacheable(HttpServletRequest request) {
            if (!isJson(request)) {
                return false;
            }
            String method = request.getMethod();
            if (method == null) {
                return false;
            }
            return switch (method.toUpperCase(Locale.ROOT)) {
                case "POST", "PUT", "PATCH", "DELETE" -> true;
                default -> false;
            };
        }

        /** Content-Type 是否为 JSON（含 {@code application/vnd.api+json} 这类后缀形式）。 */
        private static boolean isJson(HttpServletRequest request) {
            String contentType = request.getContentType();
            if (contentType == null || contentType.isBlank()) {
                return false;
            }
            String normalized = contentType.toLowerCase(Locale.ROOT);
            return normalized.startsWith("application/json") || normalized.contains("+json");
        }
    }
}
