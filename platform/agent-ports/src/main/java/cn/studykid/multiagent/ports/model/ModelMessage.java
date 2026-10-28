package cn.studykid.multiagent.ports.model;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 对话消息（领域中立表示）。
 *
 * <p>⚠️ <b>为什么需要它</b>：模型调用要能「看到自己上一轮问了什么、
 * 工具返回了什么」，否则<b>多轮工具协作不可能</b>——
 * 子 Agent 调完工具就看不到结果，等于工具调用毫无意义。
 * 单轮的 {@code userContent} 表达不了这个过程。
 *
 * <p>⚠️ <b>{@link Role#TOOL} 的内容是不可信数据</b>：工具输出可能来自
 * 外部系统、网页或用户输入，即<b>可能携带指令</b>。
 * 它只能作为<b>数据</b>进入上下文，绝不能被提升为系统/开发者指令
 * （MA-T14）。系统提示词由 {@code ModelRequest.systemPrompt} 单独承载，
 * 不与消息混流。
 *
 * <p>⚠️ <b>调用标识（{@link #toolCallId()} / {@link #toolCalls()}）不是装饰</b>：
 * OpenAI 兼容协议要求「带 {@code tool_calls} 的 assistant 消息」与
 * 「{@code tool_call_id} 指向它的 tool 消息」<b>成对出现且标识一致</b>。
 * 少任何一边，供应商直接拒收整次请求
 * （2026-10-28 真实 MiniMax 实测：{@code tool result's tool id ... not found}，HTTP 400）。
 *
 * @param role       消息角色
 * @param content    消息内容
 * @param toolName   产生该消息的工具名（仅 {@link Role#TOOL} 有值）
 * @param toolCallId 本次工具调用标识（仅 {@link Role#TOOL} 有值，须与上一轮 assistant 的调用对齐）
 * @param toolCalls  本条 assistant 消息发起的工具调用（其余角色为空列表）
 */
public record ModelMessage(@NonNull Role role,
                            @NonNull String content,
                            @Nullable String toolName,
                            @Nullable String toolCallId,
                            @NonNull List<ToolCallEcho> toolCalls) {

    public ModelMessage {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 兼容构造器：不带调用标识的消息（纯文本轮次）。 */
    public ModelMessage(@NonNull Role role, @NonNull String content, @Nullable String toolName) {
        this(role, content, toolName, null, List.of());
    }

    /**
     * 消息角色。
     */
    public enum Role {
        /** 用户/调用方输入。 */
        USER,
        /** 模型上一轮的输出（可能包含工具调用意图）。 */
        ASSISTANT,
        /** 工具执行结果（<b>不可信数据</b>）。 */
        TOOL
    }

    /**
     * 回灌给模型的「本条 assistant 发起过哪次工具调用」。
     *
     * <p>⚠️ 这是<b>模型自己产出的内容</b>的回显，不是新增的敏感面：
     * 参数原文已在上一轮出过网络，协议要求原样带回才能配平 tool 消息。
     *
     * @param callId        供应商下发的调用标识；缺失时由渲染层补稳定占位
     * @param toolName      工具名
     * @param argumentsJson 参数 JSON 文本（供应商要求字符串，不接受对象）
     */
    public record ToolCallEcho(@NonNull String callId,
                               @NonNull String toolName,
                               @NonNull String argumentsJson) {
    }

    /**
     * @param content 用户输入
     * @return 用户消息
     */
    public static @NonNull ModelMessage user(@NonNull String content) {
        return new ModelMessage(Role.USER, content, null);
    }

    /**
     * @param content 模型输出
     * @return 模型消息
     */
    public static @NonNull ModelMessage assistant(@NonNull String content) {
        return new ModelMessage(Role.ASSISTANT, content, null);
    }

    /**
     * 带工具调用意图的 assistant 消息（多轮工具协作的<b>另一半</b>）。
     *
     * @param content   意图的可读描述（保留人读的「本轮调了哪些工具」）
     * @param toolCalls 本轮发起的调用，必须与后续 tool 消息的标识一一对应
     * @return 模型消息
     */
    public static @NonNull ModelMessage assistantToolCalls(
            @NonNull String content, @NonNull List<ToolCallEcho> toolCalls) {
        return new ModelMessage(Role.ASSISTANT, content, null, null, toolCalls);
    }

    /**
     * @param toolName 工具名
     * @param content  工具输出（<b>不可信数据</b>）
     * @return 工具结果消息
     */
    public static @NonNull ModelMessage toolResult(@NonNull String toolName,
                                                   @NonNull String content) {
        return new ModelMessage(Role.TOOL, content, toolName);
    }

    /**
     * @param toolName   工具名
     * @param content    工具输出（<b>不可信数据</b>）
     * @param toolCallId 对应的调用标识（须命中上一轮 assistant 的 {@link ToolCallEcho}）
     * @return 工具结果消息
     */
    public static @NonNull ModelMessage toolResult(@NonNull String toolName,
                                                   @NonNull String content,
                                                   @Nullable String toolCallId) {
        return new ModelMessage(Role.TOOL, content, toolName, toolCallId, List.of());
    }
}
