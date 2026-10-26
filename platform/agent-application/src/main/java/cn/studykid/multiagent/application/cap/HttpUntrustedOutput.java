package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 远程 HTTP 响应的<b>不可信</b>处理（第三十七批 · 设计文档 §13.1「响应视为不可信」）。
 *
 * <p>⚠️ <b>与 {@link McpUntrustedOutput} 同构，但标记不同</b>：
 * 二者的机制完全一样（加显式边界 + 限体积），实现也共用
 * {@link UntrustedOutputEnvelope} 这<b>一处</b>。
 * 但标记必须分开 —— 载荷里写着 {@code [MCP-UNTRUSTED-DATA]} 的内容
 * 实际上来自一个 HTTP 端点，会让排障的人<b>找错来源</b>
 * （去翻 MCP Server 的清单，而真正的来源是某个远程 URL）。
 *
 * <p>⚠️ <b>HTTP 响应比 MCP 返回值更不可信</b>：
 * MCP Server 是登记在册的远端服务，而 HTTP 目标可以指向互联网上的任意站点 ——
 * 它的响应体是<b>完全陌生的一方</b>写的内容。
 * 故这条防线对 HTTP 工具是<b>固有义务</b>，不是可选项。
 */
public final class HttpUntrustedOutput {

    /** 数据信封起始标记。 */
    public static final String HEADER = "[HTTP-UNTRUSTED-DATA";

    /** 数据信封结束标记。 */
    public static final String FOOTER = "[END-HTTP-UNTRUSTED-DATA]";

    /** 截断标记。 */
    public static final String TRUNCATED = "[TRUNCATED]";

    /** 默认体积上限（与传输层的响应上限对齐量级）。 */
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private HttpUntrustedOutput() {
    }

    /**
     * 受控的输出。
     *
     * @param payload        加边界后的载荷
     * @param truncated      是否被截断
     * @param originalLength 原始长度（字符数）
     */
    public record Guarded(@NonNull String payload,
                          boolean truncated,
                          int originalLength) {
    }

    /**
     * 把 HTTP 响应体包裹成「显式外部数据 + 体积有界」的载荷。
     *
     * @param raw      响应体文本
     * @param toolId   工具 id（用于定位来源）
     * @param maxBytes 体积上限（字节）
     * @return 受控输出
     */
    public static @NonNull Guarded guard(@Nullable String raw,
                                         @NonNull String toolId,
                                         int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须为正数");
        }
        String headerLine = HEADER + " tool=" + toolId + "]\n";
        int reserved = HEADER.length() + FOOTER.length() + TRUNCATED.length()
                + toolId.length() + 16;
        UntrustedOutputEnvelope.Guarded guarded = UntrustedOutputEnvelope.wrap(
                headerLine, FOOTER, TRUNCATED, raw, maxBytes, reserved);
        return new Guarded(guarded.payload(), guarded.truncated(), guarded.originalLength());
    }

    /**
     * 把 HTTP 响应体包裹成「显式外部数据 + 体积有界」的载荷（默认体积上限）。
     *
     * @param raw    响应体文本
     * @param toolId 工具 id（用于定位来源）
     * @return 受控输出
     */
    public static @NonNull Guarded guard(@Nullable String raw, @NonNull String toolId) {
        return guard(raw, toolId, DEFAULT_MAX_BYTES);
    }
}
