package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * {@link ModelRequest} → {@link ContextBlocks} 的<b>确定性</b>全窗口投影
 * （ADR-013 / M4 影子计量，方案 §3.1 六块口径）。
 *
 * <p>⚠️ <b>为什么需要它</b>：{@code HeuristicTokenCounter} 的入参是六块
 * {@code ContextBlocks}，但影子期拿到的是已经组装好的 {@code ModelRequest}。
 * 本类把请求<b>可逆地</b>还原成六块，使计数器能按「system + 工具 Schema +
 * 全部消息」的<b>全窗口</b>口径估算，而不是退回旧链路的「只看消息序列」。
 *
 * <p>⚠️ <b>确定性是硬契约</b>：{@code ModelRequest.toolSchemas()} 是
 * {@code Map.copyOf}（迭代顺序不保证），若直接 {@code toString()}，
 * 同一请求的两次估算会得到不同长度 → 校准样本全是噪声、
 * {@code TokenCounter}「同输入恒等输出」的约定被破坏，且这种漂移
 * <b>不报错、只是 bias 悄悄失真</b>。故所有 Map 递归按 key 排序。
 *
 * <p>⚠️ <b>影子期只读</b>：本类只产生计量用的值对象，
 * 绝不参与请求构造、不修改 {@code ModelRequest}（它是不可变 record）。
 * 这从类型层面保证「校准桥接进去也不会改变模型请求字节」（G1）。
 *
 * <p>⚠️ 本类不知道哪些消息来自 RAG / 记忆（它们已被
 * {@code PromptGuard} 作为 USER 消息注入 {@code messages}），
 * 故 {@code ragBlocks} / {@code memoryBlock} 恒空、所有历史进
 * {@code historyTexts}。影子期只要<b>总量不漏</b>即可，S2 的
 * {@code ContextAssembler} 才会做分块级精细预算。
 */
public final class RequestContextProjection {

    private RequestContextProjection() {
    }

    /** 把请求投影成六块；相同输入恒得相同输出。 */
    public static @NonNull ContextBlocks project(@NonNull ModelRequest request) {
        List<String> history = new ArrayList<>();
        for (ModelMessage message : request.effectiveMessages()) {
            history.add(render(message));
        }
        return new ContextBlocks(
                request.systemPrompt(),
                canonical(request.toolSchemas()),
                "",                       // 影子期无独立记忆块（已混入 messages）
                List.of(),                // 影子期无独立 RAG 块（同上）
                List.copyOf(history),
                request.userContent());
    }

    /** 渲染单条消息为确定性文本（含 role / 工具名 / 工具调用回显，不漏 token 载体）。 */
    private static @NonNull String render(@NonNull ModelMessage message) {
        StringBuilder b = new StringBuilder();
        b.append(message.role().name()).append(':').append(message.content());
        if (message.toolName() != null) {
            b.append(" [tool=").append(message.toolName()).append(']');
        }
        if (message.toolCallId() != null) {
            b.append(" [call=").append(message.toolCallId()).append(']');
        }
        for (ModelMessage.ToolCallEcho echo : message.toolCalls()) {
            b.append(" [echo=").append(echo.callId()).append('/')
                    .append(echo.toolName()).append('=').append(echo.argumentsJson()).append(']');
        }
        return b.toString();
    }

    /**
     * 工具 Schema 的<b>确定性序列化</b>（M7-b 组合根 adapter 构造 {@code ContextSpec.toolSchemas} 的唯一入口）。
     *
     * <p>⚠️ {@code ModelRequest.toolSchemas()} 是 {@code Map.copyOf}（迭代序不保证），
     * 绝不能 {@code toString()}；预算计量若与真正送模型的定义不同源或无序，
     * 「算 A 发 B」的失真不会报错，只会静默超窗。本方法与
     * {@link #project(ModelRequest)} 共用同一份递归口径，因此影子计量、M7 预算、
     * 真实请求三者的工具定义长度天然一致。
     */
    public static @NonNull String canonicalToolSchemas(
            java.util.Map<String, Object> toolSchemas) {
        return canonical(toolSchemas == null ? java.util.Map.of() : toolSchemas);
    }

    /**
     * 递归确定性序列化：Map 按 key 排序、Collection 保序、其余用 {@code String.valueOf}。
     * ⚠️ 不处理循环引用——工具 Schema 是 JSON 兼容结构（无环），故安全；
     * 若将来出现环，{@code StackOverflowError} 会暴露而非静默产生不一致长度。
     */
    static @NonNull String canonical(@NonNull Object value) {
        StringBuilder b = new StringBuilder();
        appendCanonical(b, value);
        return b.toString();
    }

    private static void appendCanonical(@NonNull StringBuilder b, Object value) {
        if (value == null) {
            b.append("null");
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append('"').append(e.getKey()).append("\":");
                appendCanonical(b, e.getValue());
            }
            b.append('}');
        } else if (value instanceof Collection<?> coll) {
            b.append('[');
            boolean first = true;
            for (Object item : coll) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                appendCanonical(b, item);
            }
            b.append(']');
        } else {
            b.append(value);
        }
    }
}
