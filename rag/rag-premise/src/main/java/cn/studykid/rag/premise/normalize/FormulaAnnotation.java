package cn.studykid.rag.premise.normalize;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 公式的受控语义标注（P1-6，RAG-D29 DEC-P3）。
 *
 * <p><b>为什么是独立标注而不是直接改块 text</b>：D27 §4.4 要求「LaTeX 化 +
 * 语义化双写 + 符号进词法通道」。三条消费路径（IR Markdown、embedding 模板、
 * 词法词典）对公式的需求不同——正文要 LaTeX，向量要自然语言别名，词法要符号串。
 * 若把这些一股脑塞进 {@code LayoutBlock.text()}，就会污染可回取原文区间
 * （{@code charStart/charEnd} 是 mapper 按原始文本累加的权威偏移）。
 * 因此公式的「额外语义」挂在 PremiseBlock 的扩展字段上，与各消费路径解耦。
 *
 * <p><b>⚠️ fail-closed 纪律（DEC-P3「禁生成期自猜」）</b>：
 * <ul>
 *   <li>{@code naturalLanguage} 只来自<b>人工审核的受控词典</b>
 *       （{@link FormulaCatalogue}）；词典未命中时<b>恒为 null</b>，
 *       绝不由运行时规则「拼一句看起来合理的描述」——那是幻觉入库；</li>
 *   <li>未命中时 {@code needsReview=true}，把公式送人工抽检队列，
 *       而不是让检索侧猜；</li>
 *   <li>{@code embeddingView()} 在未命中时<b>只返回 LaTeX 原文</b>，
 *       不追加任何自然语言，避免把无依据的描述写进向量。</li>
 * </ul>
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 */
public final class FormulaAnnotation {

    /** 规范化后的 LaTeX（去定界符/空白）；解析原文不是 LaTeX 时原样保留。 */
    private final String latex;

    /** 受控词典命中的自然语言描述；未命中恒 null（禁自猜）。 */
    private final String naturalLanguage;

    /** 符号 → 含义（来自受控词典，供词法通道/别名使用；未命中为空）。 */
    private final Map<String, String> symbols;

    /** 是否命中受控词典。 */
    private final boolean catalogueHit;

    /** 是否需人工抽检（未命中恒 true）。 */
    private final boolean needsReview;

    /** 解析原文（抽检对照用；与 latex 可能相同）。 */
    private final String rawText;

    private FormulaAnnotation(String latex, String naturalLanguage,
                              Map<String, String> symbols, boolean catalogueHit,
                              boolean needsReview, String rawText) {
        this.latex = latex;
        this.naturalLanguage = naturalLanguage;
        this.symbols = symbols;
        this.catalogueHit = catalogueHit;
        this.needsReview = needsReview;
        this.rawText = rawText;
    }

    /**
     * 受控词典命中：使用人工审核的描述与符号表。
     *
     * @param latex           规范化 LaTeX
     * @param naturalLanguage 词典中的自然语言描述（不可为 null/空）
     * @param symbols         符号含义表（不可为 null）
     * @param rawText         解析原文
     * @return 命中标注
     */
    public static @NonNull FormulaAnnotation mapped(
            @NonNull String latex, @NonNull String naturalLanguage,
            @NonNull Map<String, String> symbols, @NonNull String rawText) {
        if (latex == null || naturalLanguage == null || naturalLanguage.isBlank()) {
            throw new IllegalArgumentException("命中标注的 latex/naturalLanguage 不可为空");
        }
        return new FormulaAnnotation(latex, naturalLanguage,
                Map.copyOf(new LinkedHashMap<>(symbols)), true, false, rawText);
    }

    /**
     * 未命中：自然语言留空 + 需人工抽检（fail-closed）。
     *
     * @param latex   规范化 LaTeX（或原文本）
     * @param rawText 解析原文
     * @return 未命中标注
     */
    public static @NonNull FormulaAnnotation unmapped(
            @NonNull String latex, @NonNull String rawText) {
        if (latex == null) {
            throw new IllegalArgumentException("latex 不可为空");
        }
        return new FormulaAnnotation(latex, null, Map.of(), false, true, rawText);
    }

    /** @return 规范化 LaTeX */
    public @NonNull String latex() {
        return latex;
    }

    /** @return 自然语言描述（未命中为 null，禁自猜） */
    public String naturalLanguage() {
        return naturalLanguage;
    }

    /** @return 符号含义表（不可变；未命中为空） */
    public @NonNull Map<String, String> symbols() {
        return symbols;
    }

    /** @return 是否命中受控词典 */
    public boolean catalogueHit() {
        return catalogueHit;
    }

    /** @return 是否需人工抽检 */
    public boolean needsReview() {
        return needsReview;
    }

    /** @return 解析原文 */
    public @NonNull String rawText() {
        return rawText;
    }

    /** @return 是否带有可写入 embedding 的自然语言描述 */
    public boolean hasNaturalLanguage() {
        return naturalLanguage != null && !naturalLanguage.isBlank();
    }

    /**
     * 语义双写视图（D27 §4.4「在 embedding 文本中追加自然语言」）。
     *
     * <p>⚠️ 命中时才双写；未命中只回 LaTeX——不把无依据的描述写进向量。
     * 本方法供后续 P3-1 {@code EmbeddingInputTemplate} 消费，本批不接 ChunkIndexer
     * （那属于输入模板批次，避免跨工作项混改）。
     *
     * @return embedding 用的公式文本
     */
    public @NonNull String embeddingView() {
        if (!hasNaturalLanguage()) {
            return latex;
        }
        StringBuilder sb = new StringBuilder(latex);
        sb.append('。').append(naturalLanguage);
        if (!symbols.isEmpty()) {
            sb.append('。');
            boolean first = true;
            for (Map.Entry<String, String> e : symbols.entrySet()) {
                if (!first) {
                    sb.append('，');
                }
                sb.append(e.getKey()).append('为').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }
}
