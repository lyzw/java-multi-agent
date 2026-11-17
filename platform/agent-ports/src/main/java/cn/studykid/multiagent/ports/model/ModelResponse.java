package cn.studykid.multiagent.ports.model;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型调用结果（领域中立表示）。
 *
 * @param content      归一化文本输出
 * @param toolCalls    模型请求调用的工具及其参数（<b>未执行</b>，须经服务端授权）
 * @param usage        用量，供预算结算（FR-012）
 * @param finishReason 归一化结束原因
 */
public record ModelResponse(
        @NonNull String content,
        @NonNull Map<String, ToolCallRequest> toolCalls,
        @NonNull ModelUsage usage,
        @NonNull FinishReason finishReason
) {

    public ModelResponse {
        toolCalls = toolCalls == null ? Map.of() : Map.copyOf(toolCalls);
    }

    /** 模型请求调用某工具。<b>此对象仅表示意图，执行必须经服务端授权闸门。</b> */
    public record ToolCallRequest(@NonNull String toolName, @NonNull Map<String, Object> arguments,
                                   @Nullable String callId) {
        public ToolCallRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /**
     * 用量。token 数与成本估算依据。
     *
     * @param estimated 是否为本地估算值。流式响应里部分供应商返回 {@code usage: null}
     *                  或 0 值；此时回退到本地粗估并标记本标志，便于成本口径区分「实测」与「估算」。
     */
    public record ModelUsage(long inputTokens, long outputTokens, boolean estimated) {
        /** 兼容构造器：供应商如实返回用量（或测试夹具）默认 {@code estimated=false}。 */
        public ModelUsage(long inputTokens, long outputTokens) {
            this(inputTokens, outputTokens, false);
        }

        public ModelUsage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("token 数不能为负");
            }
        }

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** 归一化结束原因。 */
    public enum FinishReason {
        /** 正常结束。 */
        STOP,
        /** 达到 token 上限。 */
        LENGTH,
        /** 请求调用工具。 */
        TOOL_CALLS,
        /** 被内容安全策略拦截。 */
        CONTENT_FILTER
    }
}
