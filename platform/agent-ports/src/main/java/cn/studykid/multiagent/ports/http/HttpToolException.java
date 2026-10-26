package cn.studykid.multiagent.ports.http;

import org.jspecify.annotations.NonNull;

/**
 * 远程 HTTP 工具的执行异常（第三十七批 · 设计文档 §13.1）。
 *
 * <p>⚠️ <b>为什么要独立于 {@code McpTransportException}</b>：
 * 二者的<b>失败语义粒度不同</b> —— MCP 传输层的异常按「协议/连接/熔断」分类，
 * 而 HTTP 工具还需要区分「被出站防线拦截」「响应超限」「重定向过多」
 * 这三类<b>出站层特有</b>的失败。把它们塞进 MCP 的异常类型里，
 * 会出现「HTTP 工具抛出一个 MCP 语义的异常」——
 * 那会让读代码的人误以为这条路径与 MCP 有关。
 *
 * <p>⚠️ <b>Kind 决定「确定失败」还是「结果未知」</b>：
 * 判定规则见 {@code HttpBackedToolHandler}：
 * <ul>
 *   <li>请求<b>没发出去</b>（{@link Kind#SSRF_BLOCKED} / {@link Kind#CONFIG_INVALID}
 *       / {@link Kind#CONNECT_FAILED}）→ 确定失败，可重试；</li>
 *   <li>请求<b>已经发出</b>但结果不可信（{@link Kind#TIMEOUT} /
 *       {@link Kind#PROTOCOL_ERROR} / {@link Kind#RESPONSE_TOO_LARGE} /
 *       {@link Kind#TOO_MANY_REDIRECTS}）→ 结果未知，副作用可能已发生，
 *       必须进 UNKNOWN 对账，<b>禁止盲目重试</b>。</li>
 * </ul>
 */
public class HttpToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败种类。 */
    public enum Kind {
        /** 目标被出站防线拦截（SSRF）。 */
        SSRF_BLOCKED,
        /** 配置非法（方法/URL/参数映射不成立）—— 请求不会发出。 */
        CONFIG_INVALID,
        /** 连接失败 —— 请求没有送达。 */
        CONNECT_FAILED,
        /** 超时 —— 请求可能已送达。 */
        TIMEOUT,
        /** 协议错误（响应无法解析）—— 请求已发出。 */
        PROTOCOL_ERROR,
        /** 响应超出体积上限 —— 请求已发出。 */
        RESPONSE_TOO_LARGE,
        /** 重定向跳数超限 —— 请求已发出。 */
        TOO_MANY_REDIRECTS
    }

    private final transient Kind kind;

    private HttpToolException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * 构造异常。
     *
     * @param kind    失败种类
     * @param message 原因描述
     * @param cause   底层异常（可空）
     * @return 异常实例
     */
    public static @NonNull HttpToolException of(@NonNull Kind kind,
                                                @NonNull String message,
                                                Throwable cause) {
        return new HttpToolException(kind, message, cause);
    }

    /**
     * @return 失败种类
     */
    public @NonNull Kind kind() {
        return kind;
    }
}
