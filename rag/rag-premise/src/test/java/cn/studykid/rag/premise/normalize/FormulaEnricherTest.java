package cn.studykid.rag.premise.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1-6（RAG-D29 DEC-P3）公式受控增强测试。
 *
 * <p>核心反证：未收录公式<b>禁自猜</b>——自然语言必须留空 + 待抽检，
 * embedding 双写视图不得追加任何描述（否则幻觉入库）。
 */
class FormulaEnricherTest {

    private final FormulaEnricher enricher = FormulaEnricher.getInstance();

    @Test
    @DisplayName("规范化键：去 $ 定界/空白/下标花括号，上标压平")
    void normalizeKey() {
        assertEquals("v=v0+at", FormulaCatalogue.normalizeKey("$$v = v_{0} + at$$"));
        assertEquals("v=v0+at", FormulaCatalogue.normalizeKey("v=v_0+at"));
        assertEquals("e=mc2", FormulaCatalogue.normalizeKey("E = mc^2"));
        assertEquals("e=mc2", FormulaCatalogue.normalizeKey("E = mc²"));
        assertEquals("a2+b2=c2", FormulaCatalogue.normalizeKey("a² + b² = c²"));
    }

    @Test
    @DisplayName("命中受控词典：LaTeX 规范 + 人工描述 + 符号表 + 不待抽检")
    void mappedFormula() {
        FormulaAnnotation a = enricher.enrich("v = v_0 + at");
        assertTrue(a.catalogueHit(), "核心公式应命中");
        assertFalse(a.needsReview());
        assertEquals("v = v_0 + at", a.latex());
        assertTrue(a.naturalLanguage().contains("匀变速直线运动"));
        assertEquals("末速度", a.symbols().get("v"));
        assertEquals("加速度", a.symbols().get("a"));
    }

    @Test
    @DisplayName("块级定界 $$ 也命中（同一受控词条）")
    void mappedWithDisplayDelimiter() {
        FormulaAnnotation a = enricher.enrich("$$E = mc^2$$");
        assertTrue(a.catalogueHit());
        assertEquals("E = mc^2", a.latex());
        assertTrue(a.naturalLanguage().contains("质能方程"));
    }

    @Test
    @DisplayName("⛔ fail-closed：未收录公式自然语言留空 + 待抽检，禁按结构自猜")
    void unmappedNeverGuesses() {
        // 结构与 v=v0+at 相同，仅系数不同——若用「相似套用」会错误命中。
        FormulaAnnotation a = enricher.enrich("v = v_0 + bt");
        assertFalse(a.catalogueHit(), "非受控词条不得命中");
        assertTrue(a.needsReview(), "未命中必须送人工抽检");
        assertNull(a.naturalLanguage(), "⛔ 未命中自然语言必须留空，禁自猜");
        assertTrue(a.symbols().isEmpty());
    }

    @Test
    @DisplayName("⛔ 非 LaTeX 中文题干不走受控命中，标注待抽检（原文原样保留）")
    void nonLatexOriginalNotMapped() {
        String orig = "例３ 画一条数轴，并用数轴上的点表示下列各数";
        FormulaAnnotation a = enricher.enrich(orig);
        assertFalse(a.catalogueHit());
        assertTrue(a.needsReview());
        assertNull(a.naturalLanguage());
        assertEquals(orig, a.latex(), "非 LaTeX 原文原样保留，不伪造");
        assertEquals(orig, a.rawText());
    }

    @Test
    @DisplayName("语义双写视图：命中追加自然语言+符号，未命中只回 LaTeX")
    void embeddingViewDoubleWrite() {
        FormulaAnnotation mapped = enricher.enrich("F = ma");
        String view = mapped.embeddingView();
        assertTrue(view.startsWith("F = ma"), "视图以 LaTeX 开头");
        assertTrue(view.contains("牛顿第二定律"), "命中追加自然语言");
        assertTrue(view.contains("F为合外力"), "命中追加符号含义");

        FormulaAnnotation unmapped = enricher.enrich("q = k_1 + z_2");
        assertEquals(unmapped.latex(), unmapped.embeddingView(),
                "⛔ 未命中只回 LaTeX，不得追加任何描述");
    }

    @Test
    @DisplayName("LaTeX 规范化：只无损清洗，不改公式结构")
    void normalizeLatexLossless() {
        assertEquals("a + b = c", FormulaEnricher.normalizeLatex("  a   +  b = c  "));
        assertEquals("x^2", FormulaEnricher.normalizeLatex("$$x^2$$"));
        assertEquals("x", FormulaEnricher.normalizeLatex("$x$"));
    }

    @Test
    @DisplayName("受控词典非空且每条符号表自洽（抽检清单可用）")
    void catalogueIsCurated() {
        assertTrue(FormulaCatalogue.size() >= 5, "核心词条应有足够覆盖");
        for (FormulaEntry e : FormulaCatalogue.entries()) {
            assertFalse(e.naturalLanguage().isBlank());
            assertFalse(e.symbols().isEmpty(), "词条 " + e.latex() + " 应有符号含义");
        }
    }
}
