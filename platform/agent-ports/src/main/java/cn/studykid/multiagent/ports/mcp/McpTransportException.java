package cn.studykid.multiagent.ports.mcp;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP 传输异常（§6.5）。
 *
 * <p>⚠️ <b>异常消息里绝不能出现凭据材料</b>：
 * 传输失败时往往要把请求细节带进日志（排查需要），
 * 而请求细节里带着 {@code Authorization} 头 —— 那就是凭据明文。
 * 实现方必须只引用「凭据引用」或「凭据类型」，绝不引用材料本身。
 */
public class McpTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败类别（供调用方决定是否重试）。 */
    public enum Kind {
        /** SSRF 拦截：目标地址不被允许。 */
        SSRF_BLOCKED,
        /** 连接失败（网络不可达 / 拒绝连接）。 */
        CONNECT_FAILED,
        /** 超时。 */
        TIMEOUT,
        /** 熔断打开（连续失败过多）。 */
        CIRCUIT_OPEN,
        /** 限流触发。 */
        RATE_LIMITED,
        /** 协议层错误（响应格式非法 / JSON-RPC 错误）。 */
        PROTOCOL_ERROR,
        /** 凭据缺失或不可用。 */
        CREDENTIAL_UNAVAILABLE,
        /** 其它。 */
        OTHER
    }

    private final Kind kind;

    public McpTransportException(@NonNull Kind kind, @NonNull String message) {
        super(message);
        this.kind = kind;
    }

    public McpTransportException(@NonNull Kind kind, @NonNull String message,
                                 @Nullable Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * @return 失败类别
     */
    public @NonNull Kind kind() {
        return kind;
    }
}
