package cn.studykid.multiagent.ports.context;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 一次模型请求的<b>全窗口</b>上下文组成块（ADR-013 / 《上下文压缩完整方案》§3.1）。
 *
 * <p>⚠️ <b>为什么必须有这六个块</b>：项目既有的
 * {@code ContextBudgetTrimmer.DEFAULT_CONTEXT_TOKEN_BUDGET} 只统计「消息序列」，
 * systemPrompt / 工具 Schema / 记忆块 / RAG 命中 / 当前输入都不进预算 ——
 * 于是预算显示「未超窗」、供应商却可能已经拒绝请求（静默超窗，方案现状 #2）。
 * 本 record 是 {@code TokenCounter} 的唯一入参，六个块缺一不可。
 *
 * <p>⚠️ <b>S1 影子期纪律</b>：本类型只用于<b>计量与对照</b>，
 * 不得参与任何模型请求的构造或修改；`enabled=false` 时不得改变请求字节（[D11]）。
 *
 * <p>⚠️ 纯值对象：不含 Spring / JDBC 类型，端口层不认识任何实现。
 */
public record ContextBlocks(
        /** 系统提示词基段（稳定前缀，逐字不压缩，[D6]）。 */
        @NonNull String systemPrompt,
        /** 工具定义 / function schema 序列化结果（<b>必须计入预算</b>，RV-1）。 */
        @NonNull String toolSchemas,
        /** 跨 Run 记忆块（带围栏的 USER 消息，绝不进 systemPrompt）。 */
        @NonNull String memoryBlock,
        /** RAG 命中块列表（注入前按 token 配额裁剪）。 */
        @NonNull List<String> ragBlocks,
        /** 历史对话文本（按轮，保留 tool_call/tool_result 配对）。 */
        @NonNull List<String> historyTexts,
        /** 本轮用户输入（永不裁剪，任务锚）。 */
        @NonNull String currentInput) {

    public ContextBlocks {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolSchemas = toolSchemas == null ? "" : toolSchemas;
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        ragBlocks = ragBlocks == null ? List.of() : List.copyOf(ragBlocks);
        historyTexts = historyTexts == null ? List.of() : List.copyOf(historyTexts);
        currentInput = currentInput == null ? "" : currentInput;
    }

    /** 空上下文（影子对照与单测基线用）。 */
    public static @NonNull ContextBlocks empty() {
        return new ContextBlocks("", "", "", List.of(), List.of(), "");
    }
}
