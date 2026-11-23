package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * P1-3（RAG-D28）几何真实赋值测试：bbox 来自后端原始响应，confidence 无源保持 NaN。
 *
 * <p><b>⚠️ 反证点</b>：① 有 bbox 字段必须原样带到 PremiseBlock；② 缺 bbox 字段
 * <b>不得</b>用 {@code [0,0,0,0]} 冒充（hasBbox 必须 false）；③ confidence 后端无字段时
 * 恒 NaN（hasConfidence false）——撤掉任一赋值判别都会转红。
 */
class GeometryAssignmentTest {

    private static final double EPS = 1e-9;

    // ---------- Docling: prov[0].bbox{l,t,r,b} ----------

    private static String docling(String bboxJson) {
        // bboxJson 为空时不留逗号；非空时自身以 ",\"bbox\":..." 形式传入。
        return "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[{\"self_ref\":\"#/texts/0\",\"label\":\"text\","
                + "\"text\":\"匀变速直线运动速度公式 v=v0+at\",\"level\":null,"
                + "\"prov\":[{\"page_no\":45" + bboxJson + "}]}],"
                + "\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"}]}}}}";
    }

    @Test
    @DisplayName("Docling prov.bbox{l,t,r,b} → PremiseBlock.pageBbox [l,t,r,b]")
    void doclingBboxAssigned() {
        List<PremiseBlock> blocks = new DoclingResponseMapper(null)
                .mapToPremiseBlocks(docling(",\"bbox\":{\"l\":120.5,\"t\":330,\"r\":460,\"b\":372}"));
        assertEquals(1, blocks.size());
        PremiseBlock b = blocks.get(0);
        assertTrue(b.hasBbox(), "有 bbox 字段必须 hasBbox=true");
        double[] box = b.pageBbox();
        assertEquals(4, box.length);
        assertEquals(120.5, box[0], EPS);
        assertEquals(330.0, box[1], EPS);
        assertEquals(460.0, box[2], EPS);
        assertEquals(372.0, box[3], EPS);
        assertFalse(b.hasConfidence(), "Docling 无 confidence 字段 → hasConfidence=false");
        assertTrue(Double.isNaN(b.confidence()));
    }

    @Test
    @DisplayName("Docling 缺 bbox 不得伪造空框：hasBbox=false 且 pageBbox=null")
    void doclingMissingBboxNoFake() {
        List<PremiseBlock> blocks = new DoclingResponseMapper(null)
                .mapToPremiseBlocks(docling(""));
        PremiseBlock b = blocks.get(0);
        assertFalse(b.hasBbox(), "缺 bbox 字段必须 hasBbox=false");
        assertEquals(null, b.pageBbox(), "⛔ 不得用 [0,0,0,0] 冒充空框");
    }

    @Test
    @DisplayName("Docling bbox 坐标缺一个 → 整体判无（不补全 0）")
    void doclingPartialBboxRejected() {
        List<PremiseBlock> blocks = new DoclingResponseMapper(null)
                .mapToPremiseBlocks(docling(",\"bbox\":{\"l\":120,\"t\":330,\"b\":372}"));
        assertFalse(blocks.get(0).hasBbox(), "缺 r 坐标必须整体判无，不得补 0");
    }

    // ---------- MinerU: blocks[].bbox 四元组 ----------

    private static String mineru(String bboxField, String confField) {
        // bboxField / confField 各自形如 ",\"bbox\":[...]"，空串表示无该字段。
        return "{\"schema\":\"docvortex.middle\",\"pages\":[{\"page_idx\":44,"
                + "\"blocks\":[{\"type\":\"text\",\"index\":0"
                + bboxField + confField
                + ",\"content\":[{\"type\":\"text\",\"content\":\"相反数与倒数的区别\"}]}]}]}";
    }

    @Test
    @DisplayName("MinerU bbox 四元组原样保留（0-1 归一化不换算）")
    void mineruBboxAssigned() {
        List<PremiseBlock> blocks = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru(",\"bbox\":[0.12,0.33,0.46,0.37]", ""));
        assertEquals(1, blocks.size());
        PremiseBlock b = blocks.get(0);
        assertTrue(b.hasBbox());
        assertArrayEquals(new double[]{0.12, 0.33, 0.46, 0.37}, b.pageBbox(), EPS);
        assertEquals(45, b.pageNumber(), "page_idx 0 基 → 页码 +1");
        assertFalse(b.hasConfidence(), "无 confidence 字段 → NaN");
    }

    @Test
    @DisplayName("MinerU confidence 字段存在才赋值，否则 NaN（不伪造）")
    void mineruConfidenceHonest() {
        PremiseBlock withConf = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru(",\"bbox\":[0.1,0.2,0.3,0.4]",
                        ",\"confidence\":0.97"))
                .get(0);
        assertTrue(withConf.hasConfidence());
        assertEquals(0.97, withConf.confidence(), EPS);

        PremiseBlock withoutConf = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru(",\"bbox\":[0.1,0.2,0.3,0.4]", ""))
                .get(0);
        assertFalse(withoutConf.hasConfidence());
        assertTrue(Double.isNaN(withoutConf.confidence()));
    }

    @Test
    @DisplayName("MinerU 缺 bbox / bbox 长度非 4 → hasBbox=false（不伪造空框）")
    void mineruMissingOrMalformedBbox() {
        PremiseBlock missing = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru("", ""))
                .get(0);
        assertFalse(missing.hasBbox());

        PremiseBlock malformed = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru(",\"bbox\":[0.1,0.2]", ""))
                .get(0);
        assertFalse(malformed.hasBbox(), "长度非 4 必须判无");
    }

    @Test
    @DisplayName("map() 既有契约不变：解包后的 LayoutBlock 与带几何块九字段同源")
    void mapCompatibility() {
        var premise = new MineruResponseMapper(null)
                .mapToPremiseBlocks("doc-1", mineru(",\"bbox\":[0.1,0.2,0.3,0.4]", ""));
        var legacy = new MineruResponseMapper(null)
                .map("doc-1", mineru(",\"bbox\":[0.1,0.2,0.3,0.4]", ""));
        assertEquals(premise.size(), legacy.size());
        for (int i = 0; i < premise.size(); i++) {
            assertEquals(premise.get(i).text(), legacy.get(i).text());
            assertEquals(premise.get(i).charStart(), legacy.get(i).charStart());
            assertEquals(premise.get(i).pageNumber(), legacy.get(i).pageNumber());
        }
    }
}
