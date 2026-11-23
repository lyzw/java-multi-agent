package cn.studykid.rag.premise.ir;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 解析中间产物（RAG-D27 §4.2 / RAG-D28 P1-3）——Markdown + JSON + 原页缓存引用三件套。
 *
 * <p><b>⚠️ 为什么是一个 record</b>：D27 要求「PDF 不应直接作为切分输入」，
 * 统一中间产物为 Markdown（人读）与 JSON（机读，带 bbox/reading_order/confidence/char 区间）。
 * 把渲染结果打包成一个不可变载体，保证「人读版」与「机读版」来自<b>同一次</b>渲染，
 * 杜绝两份产物各自演进导致引用错位。
 *
 * @param markdown     人读 Markdown（标题层级/段落/公式/表格；已剥离 BOILERPLATE 噪声）
 * @param json         机读 JSON（D27 §4.2 示例结构，每块带几何与字符区间）
 * @param readingOrder 显式阅读顺序（块序号 → 原始块索引；sequence 与 JSON blocks 顺序一致）
 * @param blocks       渲染所依据的归一化块（供可回取原文验证）
 */
public record IntermediateRepresentation(
        @NonNull String markdown,
        @NonNull String json,
        @NonNull List<Integer> readingOrder,
        @NonNull List<PremiseBlock> blocks) {

    public IntermediateRepresentation {
        markdown = markdown == null ? "" : markdown;
        json = json == null ? "" : json;
        readingOrder = readingOrder == null ? List.of() : List.copyOf(readingOrder);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
