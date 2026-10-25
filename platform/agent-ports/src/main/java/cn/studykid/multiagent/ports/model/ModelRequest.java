package cn.studykid.multiagent.ports.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型调用请求（领域中立表示）。
 *
 * <p>刻意<b>不暴露</b>任何供应商或 Spring AI 专属类型，保证领域层零框架依赖。
 *
 * <p>⚠️ <b>单轮与多轮的兼容口径</b>：{@code messages} 为空时，
 * 请求等价于「只有一条 USER 消息 = {@code userContent}」；
 * {@code messages} 非空时，<b>以 messages 为准</b>，
 * {@code userContent} 仅作为首轮输入的冗余字段保留（便于旧调用点过渡）。
 * 这样既有单轮调用点不必改造，而多轮场景有正经的表达载体。
 *
 * @param modelId      模型标识
 * @param systemPrompt 系统提示（<b>唯一</b>的可信指令载体，不与消息混流）
 * @param userContent  首轮用户输入（{@code messages} 为空时使用）
 * @param temperature  采样温度
 * @param maxTokens    最大输出 token
 * @param timeout      单次调用超时
 * @param toolSchemas  工具 Schema
 * @param messages     对话消息（可空 → 退化为单轮）
 */
public record ModelRequest(
        @NonNull String modelId,
        @NonNull String systemPrompt,
        @NonNull String userContent,
        @NonNull Double temperature,
        @NonNull Integer maxTokens,
        @NonNull Duration timeout,
        @NonNull Map<String, Object> toolSchemas,
        @NonNull List<ModelMessage> messages) {

    /**
     * 兼容构造器：<b>单轮</b>调用（无消息历史）。
     *
     * @param modelId      模型标识
     * @param systemPrompt 系统提示
     * @param userContent  用户输入
     * @param temperature  采样温度
     * @param maxTokens    最大输出 token
     * @param timeout      单次调用超时
     * @param toolSchemas  工具 Schema
     */
    public ModelRequest(@NonNull String modelId,
                         @NonNull String systemPrompt,
                         @NonNull String userContent,
                         @NonNull Double temperature,
                         @NonNull Integer maxTokens,
                         @NonNull Duration timeout,
                         @NonNull Map<String, Object> toolSchemas) {
        this(modelId, systemPrompt, userContent, temperature, maxTokens, timeout,
                toolSchemas, List.of());
    }

    public ModelRequest {
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
        toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 实际生效的消息序列。
     *
     * @return {@code messages} 非空时为其本身；否则为「单条 USER 消息 = userContent」
     */
    public @NonNull List<ModelMessage> effectiveMessages() {
        if (!messages.isEmpty()) {
            return messages;
        }
        return List.of(ModelMessage.user(userContent));
    }
}
