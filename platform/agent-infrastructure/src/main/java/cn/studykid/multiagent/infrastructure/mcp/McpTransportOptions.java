package cn.studykid.multiagent.infrastructure.mcp;

import java.time.Duration;

import org.jspecify.annotations.NonNull;

/**
 * MCP 传输参数（§6.5 · 03 §3「{@code timeout}/{@code maxResponseBytes}/{@code maxConcurrency}」）。
 *
 * <p>⚠️ <b>这些上限是安全边界，不是性能调优项</b>：
 * <ul>
 *   <li>{@code maxResponseBytes} —— 远端返回的体积不受限，就能把内存/磁盘打满；
 *       恶意或失控的 MCP Server 返回超大内容是现实风险。</li>
 *   <li>{@code connectTimeout} / {@code readTimeout} —— 没有超时的连接会<b>永久占用</b>
 *       一个并发额度，攻击者只要开几个不响应的 server 就能耗尽配额。</li>
 *   <li>{@code maxConcurrency} —— 单个 MCP Server 的并发上限，
 *       防止一个慢 server 拖垮整个执行链。</li>
 * </ul>
 */
public record McpTransportOptions(
        @NonNull Duration connectTimeout,
        @NonNull Duration readTimeout,
        int maxResponseBytes,
        int maxRedirects,
        int maxConcurrency,
        int circuitFailureThreshold,
        @NonNull Duration circuitOpenDuration) {

    public McpTransportOptions {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout 必须为正数");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout 必须为正数");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes 必须为正数");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects 不得为负数");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency 必须为正数");
        }
        if (circuitFailureThreshold <= 0) {
            throw new IllegalArgumentException("circuitFailureThreshold 必须为正数");
        }
        if (circuitOpenDuration == null || circuitOpenDuration.isNegative()) {
            throw new IllegalArgumentException("circuitOpenDuration 不得为负数");
        }
    }

    /**
     * 默认参数：连接 3s / 读取 10s / 响应 1 MiB / 重定向 3 跳 / 并发 8 / 熔断 5 次 / 打开 30s。
     *
     * @return 默认选项
     */
    public static @NonNull McpTransportOptions defaults() {
        return new McpTransportOptions(
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                1024 * 1024,
                3,
                8,
                5,
                Duration.ofSeconds(30));
    }
}
