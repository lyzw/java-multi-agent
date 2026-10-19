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

    /** 用量。token 数与成本估算依据。 */
    public record ModelUsage(long inputTokens, long outputTokens) {
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
