package cn.studykid.multiagent.infrastructure.http;

import java.time.Duration;

import org.jspecify.annotations.NonNull;

/**
 * 远程 HTTP 工具的出站参数（第三十七批 · 设计文档 §13.1）。
 *
 * <p>⚠️ <b>这些上限是安全边界，不是性能调优项</b>：
 * <ul>
 *   <li>{@code maxResponseBytes} —— 远端返回的体积不受限，就能把内存/磁盘打满；
 *       一个被攻陷或恶意的站点返回超大内容是现实风险；</li>
 *   <li>{@code connectTimeoutMs} —— 没有超时的连接会<b>永久占用</b>一个并发额度；</li>
 *   <li>{@code maxRedirects} —— 重定向跳数不设上限，就能被用来做「跳转到内网」的链式绕过
 *       （每一跳都会重新校验，但跳数无限就等于把校验变成可绕过的软约束）。</li>
 * </ul>
 *
 * <p>⚠️ <b>单次请求的总超时来自 Tool 配置</b>（{@code CapHttpToolConfig.timeoutMs()}），
 * 不在这里 —— 它是<b>逐工具</b>的策略，必须由配置决定，不能由进程默认值决定。
 */
public record HttpToolOptions(
        int connectTimeoutMs,
        int maxResponseBytes,
        int maxRedirects) {

    /** 默认响应体积上限（1 MiB）。 */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    /** 默认连接超时（毫秒）。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;

    /** 默认重定向跳数上限。 */
    public static final int DEFAULT_MAX_REDIRECTS = 3;

    public HttpToolOptions {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeoutMs 必须为正数");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes 必须为正数");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects 不得为负数");
        }
    }

    /**
     * 默认参数：连接 3s / 响应 1 MiB / 重定向 3 跳。
     *
     * @return 默认选项
     */
    public static @NonNull HttpToolOptions defaults() {
        return new HttpToolOptions(DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_REDIRECTS);
    }

    /**
     * @return 连接超时
     */
    public @NonNull Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }
}
