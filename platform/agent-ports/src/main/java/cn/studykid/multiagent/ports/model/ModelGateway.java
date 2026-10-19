package cn.studykid.multiagent.ports.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型调用端口（ADR-002 相关）。
 *
 * <p>v3.1 约束：领域层只依赖本接口，<b>不直接依赖供应商 SDK 或 Spring AI 类型</b>。
 * Spring AI 2.0 的 {@code ChatClient} 等类型只能出现在 infrastructure 层适配器中。
 *
 * <p>实现方须保证（FR-007 / AT-MODEL-001/002）：
 * <ul>
 *   <li>超时、限流、供应商错误被归一化为 {@link ModelErrorKind}；</li>
 *   <li>返回 usage 供预算结算；</li>
 *   <li>秘密不出现在日志与异常消息中。</li>
 * </ul>
 */
public interface ModelGateway {

    /**
     * @param request 调用请求（含模型标识、消息、参数）
     * @return 调用结果（含归一化输出与 usage）
     * @throws ModelGatewayException 归一化后的失败
     */
    @NonNull ModelResponse complete(@NonNull ModelRequest request);

    /** 归一化错误分类，避免供应商专属异常泄漏到领域层。 */
    enum ModelErrorKind {
        /** 超时。可在无副作用前提下重试。 */
        TIMEOUT(true),
        /** 限流。可退避重试。 */
        RATE_LIMITED(true),
        /** 供应商内部错误。可退避重试。 */
        PROVIDER_ERROR(true),
        /** 请求非法（上下文超限、参数错误）。重试无意义。 */
        INVALID_REQUEST(false),
        /** 认证/授权失败。禁止重试。 */
        UNAUTHORIZED(false),
        /** 内容被安全策略拦截。禁止重试。 */
        CONTENT_BLOCKED(false);

        private final boolean retryable;

        ModelErrorKind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** 模型错误归一化异常。 */
    class ModelGatewayException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ModelErrorKind kind;

        public ModelGatewayException(@NonNull ModelErrorKind kind, @NonNull String message) {
            super(message);
            this.kind = kind;
        }

        public ModelGatewayException(@NonNull ModelErrorKind kind,
                                     @NonNull String message,
                                     @Nullable Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public @NonNull ModelErrorKind kind() {
            return kind;
        }
    }
}
