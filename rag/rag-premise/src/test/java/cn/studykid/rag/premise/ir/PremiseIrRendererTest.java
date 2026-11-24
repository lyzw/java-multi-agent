package cn.studykid.rag.premise.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.FormulaAnnotation;
import cn.studykid.rag.premise.normalize.FormulaEnricher;

/**
 * P1-3（RAG-D28）IR 中间产物测试：Markdown/JSON 同源、bbox 不伪造、可回取原文 20/20。
 */
class PremiseIrRendererTest {

    private final PremiseIrRenderer renderer = new PremiseIrRenderer();

    private static PremiseBlock block(BlockType type, String text, int page, int level,
                                      double[] bbox, double confidence, int start, int end) {
        LayoutBlock layout = new LayoutBlock(type, text, page, level, "第1章 / 第1节",
                null, false, start, end);
        return new PremiseBlock(layout, bbox, confidence);
    }

    private static PremiseDocument doc(List<PremiseBlock> blocks) {
        return new PremiseDocument("doc-ir", blocks,
                new ParseTrace("mineru", "4.1.7", 1, false, 1, List.of(),
                        -1L, "formula-dense", Double.NaN, List.of()));
    }

    @Test
    @DisplayName("Markdown 保留标题层级/公式，剔除 BOILERPLATE 噪声")
    void markdownSemantics() {
        List<PremiseBlock> blocks = List.of(
                block(BlockType.HEADING, "光的直线传播", 1, 2, null, Double.NaN, 0, 6),
                block(BlockType.BOILERPLATE, "上海教育出版社", 1, 0, null, Double.NaN, 6, 13),
                block(BlockType.FORMULA, "v = v_0 + at", 1, 0, null, Double.NaN, 13, 25),
                block(BlockType.PARAGRAPH, "光在同种均匀介质中沿直线传播。", 1, 0,
                        new double[]{0.1, 0.2, 0.3, 0.4}, 0.97, 25, 39));
        String md = renderer.render(doc(blocks)).markdown();
        assertTrue(md.contains("## 光的直线传播"), "标题按层级渲染");
        assertTrue(md.contains("$$v = v_0 + at$$"), "公式保留 LaTeX 定界");
        assertFalse(md.contains("上海教育出版社"), "页眉噪声不进正文");
    }

    @Test
    @DisplayName("JSON 全字段有值：bbox/confidence 有值时写出，缺失输出 null 不伪造")
    void jsonFullFieldsHonest() {
        List<PremiseBlock> blocks = List.of(
                block(BlockType.FORMULA, "v=v0+at", 45, 0,
                        new double[]{120.5, 330.0, 460.0, 372.0}, 0.97, 0, 7),
                block(BlockType.PARAGRAPH, "无几何段落", 46, 0, null, Double.NaN, 7, 12));
        String json = renderer.render(doc(blocks)).json();
        assertTrue(json.contains("\"documentId\": \"doc-ir\""));
        assertTrue(json.contains("\"pageNumber\": 45"));
        assertTrue(json.contains("\"bbox\": [120.5,330.0,460.0,372.0]"));
        assertTrue(json.contains("\"confidence\": 0.97"));
        assertTrue(json.contains("\"readingOrder\": 0"));
        // ⛔ 不伪造：无 bbox / 无 confidence 的块输出 null
        assertTrue(json.contains("\"bbox\": null"), "无 bbox 必须输出 null");
        assertTrue(json.contains("\"confidence\": null"), "无 confidence 必须输出 null");
        assertFalse(json.contains("[0,0,0,0]"), "⛔ 不得出现伪造空框");
    }

    @Test
    @DisplayName("JSON 严格转义：正文含引号/换行/制表符不破坏结构")
    void jsonEscaping() {
        String tricky = "他说\"光沿直线\"\n第二行\tTab";
        List<PremiseBlock> blocks = List.of(
                block(BlockType.PARAGRAPH, tricky, 1, 0, null, Double.NaN, 0, tricky.length()));
        String json = renderer.render(doc(blocks)).json();
        assertTrue(json.contains("\\\""), "引号转义");
        assertTrue(json.contains("\\n"), "换行转义");
        assertTrue(json.contains("\\t"), "制表符转义");
    }

    @Test
    @DisplayName("可回取原文 20/20：char 区间切片逐块命中原文")
    void retrieveOriginalTwentyOfTwenty() {
        List<PremiseBlock> blocks = new ArrayList<>();
        int cursor = 0;
        for (int i = 1; i <= 20; i++) {
            String text = "第" + i + "段知识点内容用于验证原文回取。";
            blocks.add(block(BlockType.PARAGRAPH, text, (i + 9) / 10, 0,
                    new double[]{0.1, 0.1, 0.2, 0.2}, 0.9, cursor, cursor + text.length()));
            cursor += text.length();
        }
        IntermediateRepresentation ir = renderer.render(doc(blocks));
        List<String> retrieved = renderer.retrieveOriginal(ir);
        assertEquals(20, retrieved.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(blocks.get(i).text(), retrieved.get(i),
                    "第 " + (i + 1) + " 段必须能回取到原文");
        }
    }

    @Test
    @DisplayName("反证：字符区间越界必须抛错，不用空串冒充回取成功")
    void retrieveOutOfBoundsFails() {
        PremiseBlock bad = block(BlockType.PARAGRAPH, "短文本", 1, 0, null, Double.NaN, 0, 999);
        IntermediateRepresentation ir = renderer.render(doc(List.of(bad)));
        assertThrows(IllegalStateException.class, () -> renderer.retrieveOriginal(ir),
                "越界区间必须转红，不得静默返回空串");
    }

    @Test
    @DisplayName("P1-6 命中公式：Markdown 语义双写 + JSON 输出受控描述/符号")
    void formulaMappedDoubleWrite() {
        FormulaAnnotation fa = FormulaEnricher.getInstance().enrich("v = v_0 + at");
        PremiseBlock fb = block(BlockType.FORMULA, "v = v_0 + at", 1, 0,
                null, Double.NaN, 0, 12).withFormula(fa);
        IntermediateRepresentation ir = renderer.render(doc(List.of(fb)));
        assertTrue(ir.markdown().contains("$$v = v_0 + at$$"), "LaTeX 定界保留");
        assertTrue(ir.markdown().contains("匀变速直线运动速度时间公式"), "Markdown 追加受控自然语言");
        assertTrue(ir.json().contains("\"catalogueHit\": true"));
        assertTrue(ir.json().contains("\"needsReview\": false"));
        assertTrue(ir.json().contains("匀变速直线运动速度时间公式"), "JSON 输出自然语言");
        assertTrue(ir.json().contains("\"v\": \"末速度\""), "JSON 输出符号含义");
    }

    @Test
    @DisplayName("P1-6 未命中公式：JSON naturalLanguage=null、needsReview=true（不伪造）")
    void formulaUnmappedHonest() {
        FormulaAnnotation fa = FormulaEnricher.getInstance().enrich("v = v_0 + bt");
        PremiseBlock fb = block(BlockType.FORMULA, "v = v_0 + bt", 1, 0,
                null, Double.NaN, 0, 12).withFormula(fa);
        IntermediateRepresentation ir = renderer.render(doc(List.of(fb)));
        assertFalse(ir.markdown().contains("速度时间公式"), "未命中不得追加描述");
        assertTrue(ir.json().contains("\"naturalLanguage\": null"), "未命中自然语言为 null");
        assertTrue(ir.json().contains("\"needsReview\": true"), "未命中送人工抽检");
    }

    @Test
    @DisplayName("P1-6 非公式块 formula=null（不伪造公式对象）")
    void nonFormulaHasNoFormulaJson() {
        PremiseBlock pb = block(BlockType.PARAGRAPH, "正文", 1, 0, null, Double.NaN, 0, 2);
        String json = renderer.render(doc(List.of(pb))).json();
        assertTrue(json.contains("\"formula\": null"), "非公式块输出 null");
    }

    @Test
    @DisplayName("中间产物引用可挂载：withArtifacts 保留 doclingJsonRef（逐字段带过）")
    void artifactsKeepDoclingRef() {
        PremiseDocument base = new PremiseDocument("doc-ir", List.of(),
                new ParseTrace("docling-serve", "2.0", 0, false, 1, List.of()),
                "raw-docling-json");
        PremiseDocument withRefs = base.withArtifacts("md-ref", "cache-ref");
        assertEquals("raw-docling-json", withRefs.doclingJsonRef(), "compat 不得清空 doclingJsonRef");
        assertEquals("md-ref", withRefs.markdownRef());
        assertEquals("cache-ref", withRefs.pageCacheRef());
    }
}
