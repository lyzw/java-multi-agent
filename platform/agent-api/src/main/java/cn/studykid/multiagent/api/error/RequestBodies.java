package cn.studykid.multiagent.api.error;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 读取 {@link CachedBodyHttpServletRequest} 缓存的请求体（跨包唯一入口）。
 *
 * <p><b>⚠️ 为什么需要这个入口</b>：缓存包装器是包级私有的，而「对话审计」过滤器
 * 在另一个包里也需要同一份 body。直接把类改成 public 会把「有界缓存」的实现细节
 * 变成公共 API；这里只暴露两件事——<b>读到什么</b>与<b>是否被截断</b>。
 *
 * <p><b>⚠️ 必须沿包装链下探</b>：缓存过滤器注册在安全链之前，之后 Spring Security
 * 还会再包若干层 {@link HttpServletRequestWrapper}。只判最外层类型在生产环境恒 false
 * （而绕过安全链的单测恒 true）——这是本项目已经踩过一次的坑。
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    /** 已缓存的请求体字节（UTF-8 文本）；没有缓存包装器时返回空。 */
    public static @NonNull Optional<String> cachedBody(@Nullable HttpServletRequest request) {
        CachedBodyHttpServletRequest cached = find(request);
        if (cached == null) {
            return Optional.empty();
        }
        byte[] bytes = cached.cachedBody();
        if (bytes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    /** 缓存是否因超过上限被截断（未读到内容时为 {@code false}）。 */
    public static boolean isTruncated(@Nullable HttpServletRequest request) {
        CachedBodyHttpServletRequest cached = find(request);
        return cached != null && cached.isTruncated();
    }

    private static CachedBodyHttpServletRequest find(@Nullable HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof HttpServletRequest servletRequest) {
            if (servletRequest instanceof CachedBodyHttpServletRequest cached) {
                return cached;
            }
            if (servletRequest instanceof HttpServletRequestWrapper wrapper) {
                current = wrapper.getRequest();
            } else {
                return null;
            }
        }
        return null;
    }
}
