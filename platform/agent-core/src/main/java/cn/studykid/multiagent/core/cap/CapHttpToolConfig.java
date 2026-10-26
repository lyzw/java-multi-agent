package cn.studykid.multiagent.core.cap;

import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 远程 HTTP 请求工具的连接配置（{@code kind=http}）。
 *
 * <p>⚠️ <b>为什么单独成记录，而不是把字段摊平到 {@link CapToolDef}</b>：
 * 这三个字段<b>同生共死</b> —— 有方法就必须有 URL，有 URL 就必须有超时。
 * 摊平后「配了一半」的中间态在类型层面<b>无法表达</b>：
 * {@code kind=http} 却只有 {@code httpMethod} 没有 {@code httpUrl}，
 * 是一个能编译、能入库、但<b>永远跑不起来</b>的 Tool 定义。
 * 合成一个可空记录后，「非 HTTP 工具 = null」「HTTP 工具 = 三项齐全」
 * 就成了<b>类型层面的不变量</b>。
 *
 * <p>⚠️ <b>凭据不在这里</b>：认证材料只以<b>引用</b>形式存在（明文禁入库 · §4.2）。
 * 需要鉴权的 HTTP 工具应指向一个已登记凭据的 MCP Server，或在后续批次引入
 * 工具级凭据引用 —— 本记录<b>刻意不提供</b>任何可放凭据明文的字段。
 *
 * @param method    HTTP 方法（{@link #ALLOWED_METHODS} 之一）
 * @param url       目标 URL（绝对地址；{@code http} / {@code https}）
 * @param timeoutMs 单次请求超时（毫秒，正整数）
 */
public record CapHttpToolConfig(
        @NonNull String method,
        @NonNull String url,
        int timeoutMs) {

    /** 允许的 HTTP 方法（V1 白名单）。 */
    public static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    /** 默认超时（毫秒）—— 10 秒，与 MCP 读取超时同量级。 */
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    /** 超时上限（毫秒）—— 5 分钟。防止「配了 1 小时超时」把一个 Run 永久挂住。 */
    public static final int MAX_TIMEOUT_MS = 300_000;

    public CapHttpToolConfig {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("HTTP 方法不能为空");
        }
        method = method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("不允许的 HTTP 方法: " + method
                    + "（合法值: " + String.join(" / ", ALLOWED_METHODS.stream().sorted().toList()) + "）");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP 目标 URL 不能为空");
        }
        url = url.trim();
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("HTTP 超时必须为正数: " + timeoutMs);
        }
        if (timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("HTTP 超时不得超过 " + MAX_TIMEOUT_MS + " 毫秒: " + timeoutMs);
        }
    }

    /**
     * @return 是否为携带请求体的方法（其余方法的实参走查询串）
     */
    public boolean hasRequestBody() {
        return switch (method) {
            case "POST", "PUT", "PATCH" -> true;
            default -> false;
        };
    }
}
