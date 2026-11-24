package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * P1-6（DEC-P3）公式映射端到端：两个 mapper 的 FORMULA 块都挂上受控语义标注，
 * 非公式块不挂（不伪造）。
 */
class FormulaMappingEndToEndTest {

    private final DoclingResponseMapper docling = new DoclingResponseMapper(null);
    private final MineruResponseMapper mineru = new MineruResponseMapper(null);

    /** Docling 公式节点（orig 承载原文）。 */
    private static String doclingFormula(String orig) {
        return "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[{\"self_ref\":\"#/texts/0\",\"label\":\"formula\","
                + "\"text\":\"\",\"orig\":\"" + orig + "\","
                + "\"level\":6,\"prov\":[{\"page_no\":14}]}],"
                + "\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"}]}}}}";
    }

    /** MinerU 公式块（content[type=text] 承载 LaTeX）。 */
    private static String mineruFormula(String latex) {
        return "{\"schema\":\"docvortex.middle\",\"pages\":[{\"page_idx\":13,\"blocks\":["
                + "{\"type\":\"equation\",\"bbox\":[0.1,0.2,0.3,0.4],"
                + "\"content\":[{\"type\":\"text\",\"content\":\"" + latex + "\"}]}"
                + "]}]}";
    }

    @Test
    @DisplayName("Docling 公式（受控 LaTeX）→ 命中标注 + 自然语言")
    void doclingFormulaMapped() {
        List<PremiseBlock> blocks = docling.mapToPremiseBlocks(
                doclingFormula("F = ma"));
        assertEquals(1, blocks.size());
        PremiseBlock b = blocks.get(0);
        assertTrue(b.hasFormulaAnnotation(), "公式块必须带语义标注");
        assertTrue(b.formulaAnnotation().catalogueHit());
        assertTrue(b.formulaAnnotation().naturalLanguage().contains("牛顿第二定律"));
        assertEquals("合外力", b.formulaAnnotation().symbols().get("F"));
    }

    @Test
    @DisplayName("Docling 公式（题干文字 orig）→ 未命中待抽检，自然语言留空")
    void doclingOrigTextFailClosed() {
        List<PremiseBlock> blocks = docling.mapToPremiseBlocks(
                doclingFormula("例３ 画一条数轴，并用数轴上的点表示下列各数"));
        PremiseBlock b = blocks.get(0);
        assertTrue(b.hasFormulaAnnotation());
        assertFalse(b.formulaAnnotation().catalogueHit());
        assertTrue(b.formulaAnnotation().needsReview());
        assertNull(b.formulaAnnotation().naturalLanguage(), "禁自猜");
    }

    @Test
    @DisplayName("MinerU 公式（受控 LaTeX）→ 命中标注（与 Docling 同一词典）")
    void mineruFormulaMapped() {
        List<PremiseBlock> blocks = mineru.mapToPremiseBlocks("doc-f", mineruFormula("E = mc^2"));
        assertEquals(1, blocks.size());
        PremiseBlock b = blocks.get(0);
        assertTrue(b.hasFormulaAnnotation());
        assertTrue(b.formulaAnnotation().catalogueHit());
        assertTrue(b.formulaAnnotation().naturalLanguage().contains("质能方程"));
    }

    @Test
    @DisplayName("非公式块不挂公式标注（不伪造）")
    void nonFormulaHasNoAnnotation() {
        List<PremiseBlock> blocks = docling.mapToPremiseBlocks(
                "{\"status\":\"success\",\"document\":{\"json_content\":{"
                        + "\"texts\":[{\"self_ref\":\"#/texts/0\",\"label\":\"text\","
                        + "\"text\":\"正文段落\",\"level\":6,\"prov\":[{\"page_no\":1}]}],"
                        + "\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"}]}}}}");
        assertEquals(1, blocks.size());
        assertFalse(blocks.get(0).hasFormulaAnnotation());
        assertNull(blocks.get(0).formulaAnnotation());
    }

    @Test
    @DisplayName("compat：3/4 参构造不挂公式标注（老调用方零改）")
    void compatConstructorsHaveNoAnnotation() {
        var layout = new cn.studykid.rag.ports.LayoutBlock(
                cn.studykid.rag.ports.BlockType.FORMULA, "x", 1, 0, "", null, false, 0, 1);
        PremiseBlock b3 = new PremiseBlock(layout, null, Double.NaN);
        PremiseBlock b4 = new PremiseBlock(layout, null, Double.NaN, null);
        assertFalse(b3.hasFormulaAnnotation());
        assertFalse(b4.hasFormulaAnnotation());
        assertNotNull(b3.toLayoutBlock());
    }
}
