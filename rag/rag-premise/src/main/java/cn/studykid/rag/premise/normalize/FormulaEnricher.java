package cn.studykid.rag.premise.normalize;

import org.jspecify.annotations.NonNull;

/**
 * 公式语义增强编排器（P1-6，RAG-D29 DEC-P3）。
 *
 * <p>把解析出的公式原文（Docling {@code orig} / MinerU content 文本）走三步：
 * <ol>
 *   <li><b>LaTeX 规范化</b>：去 {@code $$}/{@code $} 定界、压空白；
 *       原文不是 LaTeX（如 Docling 实测的中文题干）时原样保留，<b>不伪造</b> LaTeX；</li>
 *   <li><b>受控词典精确查找</b>：{@link FormulaCatalogue#lookup} 命中则用人工审核描述；</li>
 *   <li><b>fail-closed</b>：未命中 → {@link FormulaAnnotation#unmapped}（自然语言留空 +
 *       needsReview=true），<b>禁止</b>按结构拼装描述。</li>
 * </ol>
 *
 * <p><b>⚠️ 与「小模型生成」的边界</b>：本类<b>不调用任何 LLM</b>。DEC-P3 要求先受控规则
 * + 人工抽检；将来若引入生成式描述，须另立决策（数据出域/成本评估）并保留本类作为
 * 兜底与反证锚点，不得在本类里静默接模型。
 */
public final class FormulaEnricher {

    /** 单例（无状态）。 */
    private static final FormulaEnricher INSTANCE = new FormulaEnricher();

    private FormulaEnricher() {
    }

    /** @return 共享实例 */
    public static @NonNull FormulaEnricher getInstance() {
        return INSTANCE;
    }

    /**
     * 增强单条公式原文。
     *
     * @param raw 公式原文（不可为 null）
     * @return 公式标注（命中=受控描述；未命中=待抽检、自然语言留空）
     */
    public @NonNull FormulaAnnotation enrich(@NonNull String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("公式原文不可为空");
        }
        String latex = normalizeLatex(raw);
        FormulaEntry entry = FormulaCatalogue.lookup(latex);
        if (entry == null) {
            return FormulaAnnotation.unmapped(latex, raw);
        }
        return FormulaAnnotation.mapped(
                entry.latex(), entry.naturalLanguage(), entry.symbols(), raw);
    }

    /**
     * LaTeX 规范化：去行内/块级定界符、压首尾空白、内部连续空白压成单空格。
     *
     * <p>⚠️ 只做无损清洗，不改公式结构。原文不是 LaTeX 时（如中文题干）清洗后仍是原文，
     * 查表自然不命中 → 走 fail-closed，不会被误当成已审核公式。
     */
    static @NonNull String normalizeLatex(@NonNull String raw) {
        String s = raw.trim();
        if (s.startsWith("$$") && s.endsWith("$$") && s.length() >= 4) {
            s = s.substring(2, s.length() - 2).trim();
        } else if (s.startsWith("$") && s.endsWith("$") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.replaceAll("\\s+", " ");
    }
}
