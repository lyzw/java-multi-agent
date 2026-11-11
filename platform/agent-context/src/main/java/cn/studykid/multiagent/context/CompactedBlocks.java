package cn.studykid.multiagent.context;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 压缩后的逻辑上下文块（《方案》§4.2 步骤⑥，M5-b）。全参构造是唯一构造，
 * 不提供 with 方法——从类型上消除「拷贝漏字段」的静默风险（铁律 #11）。
 *
 * <p>⚠️ 分「可信稳定前缀」与「不可信围栏块」两类，渲染时必须分开放置：
 * systemPrompt / toolSchemas 逐字保留；notice / summary / rag / memory 是不可信数据，
 * 只能进带围栏的 USER 消息，⛔ 绝不进 systemPrompt（[D6] / MA-T14）。
 */
public record CompactedBlocks(
        /** 系统提示词基段：稳定前缀，逐字不变。 */
        @NonNull String systemPrompt,
        /** 工具定义：逐字保留。 */
        @NonNull String toolSchemas,
        /** 首轮任务锚：逐字保留。 */
        @NonNull String firstUserTurn,
        /** W2 裁剪说明（不可信，走围栏 USER 块；{@code null} = 无）。 */
        @Nullable String notice,
        /** W3 增量摘要（不可信，走围栏 USER 块；{@code null} = 无摘要）。 */
        @Nullable String summaryText,
        /** RAG 命中块（不可信）。 */
        @NonNull List<String> ragBlocks,
        /** 跨 Run 记忆块（不可信）。 */
        @NonNull String memoryBlock,
        /** 保留的历史消息（含近期逐字轮次，保持 tool_call/tool_result 配对）。 */
        @NonNull List<ModelMessage> historyMessages,
        /** 本轮用户输入：逐字保留、永不裁。 */
        @NonNull String currentInput) {

    public CompactedBlocks {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolSchemas = toolSchemas == null ? "" : toolSchemas;
        firstUserTurn = firstUserTurn == null ? "" : firstUserTurn;
        ragBlocks = ragBlocks == null ? List.of() : List.copyOf(ragBlocks);
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        historyMessages = historyMessages == null ? List.of() : List.copyOf(historyMessages);
        currentInput = currentInput == null ? "" : currentInput;
    }
}
