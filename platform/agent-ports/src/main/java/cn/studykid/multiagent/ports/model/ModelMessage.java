package cn.studykid.multiagent.ports.model;

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
 * @param role     消息角色
 * @param content  消息内容
 * @param toolName 产生该消息的工具名（仅 {@link Role#TOOL} 有值）
 */
public record ModelMessage(@NonNull Role role,
                            @NonNull String content,
                            @Nullable String toolName) {

    public ModelMessage {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
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
     * @param toolName 工具名
     * @param content  工具输出（<b>不可信数据</b>）
     * @return 工具结果消息
     */
    public static @NonNull ModelMessage toolResult(@NonNull String toolName,
                                                   @NonNull String content) {
        return new ModelMessage(Role.TOOL, content, toolName);
    }
}
