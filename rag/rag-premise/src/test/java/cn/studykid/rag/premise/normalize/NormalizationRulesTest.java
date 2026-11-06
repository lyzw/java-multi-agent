package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.model.ParseTrace;

/**
 * {@link NormalizationRules} 与 {@code SectionPathResolver} 的规则单测
 * （RAG-D26 阶段 1.4 的门禁：跨后端一致性的可执行形式）。
 */
class NormalizationRulesTest {

    private static PremiseBlock pb(String text, int page, int charStart) {
        return PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.PARAGRAPH, text, page, 0, "", null, false,
                charStart, charStart + text.length()));
    }

    // ---------- 页码基准 ----------

    @Test
    @DisplayName("0 基页码整体 +1（MinerU page_idx 口径）")
    void shiftsPageNumbers() {
        // LayoutBlock 契约要求页码 >= 1，因此 0 基页码在「适配器映射」时才出现——
        // 本测试用 1/4 基输入验证换算语义（等价于把 0/3 基 +1）。
        List<PremiseBlock> out = NormalizationRules.shiftPageNumbers(
                List.of(pb("第1页内容", 1, 0), pb("第4页内容", 4, 0)));

        assertThat(out.get(0).pageNumber()).isEqualTo(2);
        assertThat(out.get(1).pageNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("页码换算不改动文本与字符区间")
    void shiftKeepsTextAndSpans() {
        PremiseBlock original = pb("内容", 1, 7);
        List<PremiseBlock> out = NormalizationRules.shiftPageNumbers(List.of(original));

        assertThat(out.get(0).text()).isEqualTo("内容");
        assertThat(out.get(0).charStart()).isEqualTo(7);
        assertThat(out.get(0).charEnd()).isEqualTo(9);
    }

    // ---------- 阅读顺序 ----------

    @Test
    @DisplayName("跨页乱序块按页码稳定排序（同页内保持原相对次序）")
    void sortsByPageStable() {
        PremiseBlock p2a = pb("第2页第一块", 2, 0);
        PremiseBlock p2b = pb("第2页第二块", 2, 100);
        PremiseBlock p1 = pb("第1页内容", 1, 0);

        List<PremiseBlock> out = NormalizationRules.ensureReadingOrder(
                List.of(p2a, p1, p2b));

        assertThat(out).containsExactly(p1, p2a, p2b);
    }

    // ---------- 字符区间修复 ----------

    @Test
    @DisplayName("非法区间页重新连续化；合法页不动（保护 chunk_id 兼容）")
    void renumbersOnlyBrokenPages() {
        // p1：合法区间（不动）；p2：charEnd < charStart（非法，重编）
        PremiseBlock legal = PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.PARAGRAPH, "合法页内容", 1, 0, "", null, false, 10, 15));
        PremiseBlock broken = PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.PARAGRAPH, "第二块", 2, 0, "", null, false, 50, 3));
        PremiseBlock brokenFirst = PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.PARAGRAPH, "第一块", 2, 0, "", null, false, 40, 44));

        List<PremiseBlock> out = NormalizationRules.renumberBrokenCharSpans(
                List.of(legal, brokenFirst, broken));

        assertThat(out.get(0).charStart()).isEqualTo(10);
        assertThat(out.get(0).charEnd()).isEqualTo(15);
        // p2 按阅读顺序重编：第一块 [0,3)，第二块 [3,6)
        assertThat(out.get(1).charStart()).isZero();
        assertThat(out.get(1).charEnd()).isEqualTo(3);
        assertThat(out.get(2).charStart()).isEqualTo(3);
        assertThat(out.get(2).charEnd()).isEqualTo(6);
    }

    // ---------- 重复行噪声 ----------

    @Test
    @DisplayName("跨多页重复的短行整块改判 BOILERPLATE（页眉页脚兜底）")
    void marksRepeatedLineAsNoise() {
        List<PremiseBlock> pages = List.of(
                pb("人民教育出版社", 1, 0),
                pb("正文一", 1, 7),
                pb("人民教育出版社", 2, 0),
                pb("正文二", 2, 7),
                pb("人民教育出版社", 3, 0),
                pb("正文三", 3, 7));

        List<PremiseBlock> out = NormalizationRules.markRepeatedLineNoise(pages);

        assertThat(out.get(0).type()).isEqualTo(BlockType.BOILERPLATE);
        assertThat(out.get(2).type()).isEqualTo(BlockType.BOILERPLATE);
        assertThat(out.get(4).type()).isEqualTo(BlockType.BOILERPLATE);
        assertThat(out.get(1).type()).isEqualTo(BlockType.PARAGRAPH);
    }

    @Test
    @DisplayName("正文长行不参与重复统计（避免正文段落被误判页眉）")
    void longLinesExempt() {
        String body = "这是一段远超四十个字符的正文内容，用来验证长行不参与页眉页脚的重复统计，"
                + "所以即使它在多个页面重复出现也不会被改判为噪声块。";
        List<PremiseBlock> pages = List.of(
                pb(body, 1, 0), pb(body, 2, 0), pb(body, 3, 0));

        List<PremiseBlock> out = NormalizationRules.markRepeatedLineNoise(pages);

        assertThat(out).allSatisfy(b -> assertThat(b.type()).isEqualTo(BlockType.PARAGRAPH));
    }

    @Test
    @DisplayName("已是 BOILERPLATE 的块保持原类型（幂等）")
    void idempotentOnBoilerplate() {
        PremiseBlock noise = PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.BOILERPLATE, "页脚", 1, 0, "", null, false, 0, 2));

        List<PremiseBlock> out = NormalizationRules.markRepeatedLineNoise(
                List.of(noise, noise, noise));

        assertThat(out).allSatisfy(b -> assertThat(b.type()).isEqualTo(BlockType.BOILERPLATE));
    }

    // ---------- SectionPathResolver（与既有管道共用的规则） ----------

    @Test
    @DisplayName("标题层级回退时截断路径（D04 §5.3 的关键语义）")
    void sectionPathTruncates() {
        StringBuilder path = new StringBuilder();
        LayoutBlock h1 = new LayoutBlock(BlockType.HEADING, "第3章 二次函数", 40, 1, "",
                null, false, 0, 8);
        LayoutBlock h2 = new LayoutBlock(BlockType.HEADING, "3.1 概念", 41, 2, "",
                null, false, 0, 6);
        LayoutBlock h3 = new LayoutBlock(BlockType.HEADING, "3.1.1 定义", 42, 3, "",
                null, false, 0, 8);
        LayoutBlock h2again = new LayoutBlock(BlockType.HEADING, "3.2 图像", 43, 2, "",
                null, false, 0, 6);

        SectionPathResolver.advance(path, h1);
        SectionPathResolver.advance(path, h2);
        SectionPathResolver.advance(path, h3);
        assertThat(path.toString())
                .isEqualTo("第3章 二次函数 > 3.1 概念 > 3.1.1 定义");

        SectionPathResolver.advance(path, h2again);
        assertThat(path.toString()).isEqualTo("第3章 二次函数 > 3.2 图像");
    }

    @Test
    @DisplayName("有标题上下文时优先；无上下文时回退解析器路径")
    void resolvePrefersHeadingsFallsBackToParser() {
        StringBuilder withHeadings = new StringBuilder("第3章");
        LayoutBlock parserPathBlock = new LayoutBlock(BlockType.PARAGRAPH, "内容", 45, 0,
                "解析器路径", null, false, 0, 2);

        assertThat(SectionPathResolver.resolve(withHeadings, parserPathBlock))
                .isEqualTo("第3章");

        StringBuilder noHeadings = new StringBuilder();
        assertThat(SectionPathResolver.resolve(noHeadings, parserPathBlock))
                .isEqualTo("解析器路径");
    }

    // ---------- PremiseDocument / ChunkIdentifiers ----------

    @Test
    @DisplayName("PremiseDocument 与 ParsedDocument 的恒等往返（防双词汇表漂移）")
    void premiseDocumentRoundTrip() {
        LayoutBlock raw = new LayoutBlock(BlockType.HEADING, "第3章", 40, 1, "", null, false,
                0, 3);
        PremiseDocument doc = PremiseDocument.fromParsed("doc-1",
                new cn.studykid.rag.ports.ParsedDocument("doc-1", List.of(raw)));

        // 恒等：包装不复制字段，解包得到同一实例
        assertThat(doc.blocks().get(0).toLayoutBlock()).isSameAs(raw);

        cn.studykid.rag.ports.ParsedDocument down = doc.toParsedDocument();
        assertThat(down.blocks().get(0)).isSameAs(raw);
        assertThat(down.documentId()).isEqualTo("doc-1");
        assertThat(doc.pageCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("ChunkIdentifiers 与既有管道格式逐字符一致（锁定派生格式）")
    void chunkIdFormatLocked() {
        String id = ChunkIdentifiers.chunkId("doc-1", 42, 100);
        String expected = java.util.UUID.nameUUIDFromBytes(
                ("chunk:doc-1:42:100").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        assertThat(id).isEqualTo(expected);

        String hash = ChunkIdentifiers.contentHash("顶点式");
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        assertThat(hash).hasSize(64)
                .isEqualTo(java.util.HexFormat.of().formatHex(
                        md.digest("顶点式".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
