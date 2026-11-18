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
        /**
         * 额度耗尽 / 限流（HTTP 429 或供应商报告的配额耗尽）。
         *
         * <p>⚠️ <b>本平台收敛为终态、不重试</b>：重试只会继续撞同一堵墙，
         * 并持续消耗配额与时间（Run 会永远停在 RUNNING、进度页永远「执行中」）。
         * 统一由 {@link ModelGatewayException#exhausted()} 在构造期识别，
         * Run 执行层据此立即收敛 FAILED，故这里标记 {@code retryable=false} ——
         * 与运行行为一致，observer 记录的 retryable 标签也更准确。
         */
        RATE_LIMITED(false),
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
        /**
         * 额度/限流是否已耗尽（终态，重试无意义）。
         *
         * <p>由构造期从 {@link #kind} 与错误信息推导，网关实现<b>无需改动</b>即可受益：
         * <ul>
         *   <li>{@link ModelErrorKind#RATE_LIMITED}（HTTP 429）直接判为耗尽；</li>
         *   <li>MiniMax 在 HTTP 200 下用 {@code base_resp.status_code} 报告配额/鉴权失败，
         *       被归一为 {@link ModelErrorKind#PROVIDER_ERROR}，只能从错误信息结构识别。</li>
         * </ul>
         */
        private final transient boolean exhausted;

        public ModelGatewayException(@NonNull ModelErrorKind kind, @NonNull String message) {
            super(message);
            this.kind = kind;
            this.exhausted = detectExhausted(kind, message);
        }

        public ModelGatewayException(@NonNull ModelErrorKind kind,
                                     @NonNull String message,
                                     @Nullable Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.exhausted = detectExhausted(kind, message);
        }

        public @NonNull ModelErrorKind kind() {
            return kind;
        }

        /** @return 是否为额度/限流耗尽 —— 终态，消费者应立即收敛 Run 而非重试。 */
        public boolean exhausted() {
            return exhausted;
        }

        private static boolean detectExhausted(ModelErrorKind kind, String message) {
            if (kind == ModelErrorKind.RATE_LIMITED) {
                return true;
            }
            if (message == null || message.isBlank()) {
                return false;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            // ⚠️ 只识别**语义明确**的额度/限流耗尽信号。
            //    ⛔ 不再用 `base_resp.status_code` 裸串判定：MiniMax 对任意
            //    base_resp.status_code != 0（内容拦截 / 参数非法 / 系统繁忙）都抛
            //    ProviderStatusException(200, "base_resp.status_code=…")，
            //    全部归一为 PROVIDER_ERROR(retryable=true)。若按裸串判耗尽，
            //    可重试的瞬时错误会被误收敛为 FAILED，永不重试。
            //    真正的配额/鉴权失败其 status_msg 会带下列语义词。
            return lower.contains("quota")
                    || lower.contains("exhausted")
                    || lower.contains("insufficient")
                    || lower.contains("rate limit")
                    || lower.contains("too many requests")
                    || lower.contains("balance")
                    || message.contains("额度")
                    || message.contains("余额")
                    || message.contains("欠费")
                    || message.contains("限流");
        }
    }
}
