package cn.studykid.rag.premise.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.FormulaAnnotation;
import cn.studykid.rag.premise.normalize.FormulaEnricher;
import cn.studykid.rag.premise.normalize.TableGrid;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * P3-1 语义透传：切分器必须从 {@link PremiseBlock} 的扩展字段（解包前）采集
 * 公式 embeddingView / 表格结构摘要，写入 {@link Chunk} 的两个 embedding 槽位。
 *
 * <p>⚠️ 这是「静态存在 ≠ 运行期可达」的反证锚点：公式标注此前只挂在 premise 包装层，
 * 切分器把块解成 {@link LayoutBlock} 后语义全丢，模板槽位恒 null。本测试钉死透传。
 */
class ChunkerEmbeddingSemanticsTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final ChunkContext CONTEXT = ChunkContext.withoutKnowledgePoints(EDITION);

    private static LayoutBlock block(BlockType type, String text) {
        return new LayoutBlock(type, text, 5, 0, "第3章", null, false, 0, text.length());
    }

    @Test
    @DisplayName("公式块（受控词典命中）→ Chunk 带 LaTeX+自然语言+符号的 embeddingView")
    void formulaMappedPropagates() {
        FormulaAnnotation annotation = FormulaEnricher.getInstance().enrich("F = ma");
        LayoutBlock layout = block(BlockType.FORMULA, "F = ma");
        PremiseBlock premise = new PremiseBlock(layout, null, Double.NaN, null, annotation);
        PremiseDocument doc = new PremiseDocument("doc-f", List.of(premise),
                ParseTrace.untracked());

        ChunkOutcome out = new HeadingHierarchyChunker().chunk(
                doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks()).hasSize(1);
        String semantics = out.chunks().get(0).formulaNaturalLanguage();
        assertThat(semantics).as("公式槽位必须带上 embeddingView").isNotNull();
        assertThat(semantics).contains("牛顿第二定律").contains("合外力");
        assertThat(out.chunks().get(0).captionOrTableSummary()).isNull();
    }

    @Test
    @DisplayName("公式块未命中受控词典 → 只回 LaTeX，不猜自然语言（DEC-P3 fail-closed）")
    void formulaUnmappedHasNoNaturalLanguage() {
        FormulaAnnotation annotation = FormulaEnricher.getInstance().enrich("画一条数轴");
        PremiseBlock premise = new PremiseBlock(block(BlockType.FORMULA, "画一条数轴"),
                null, Double.NaN, null, annotation);
        PremiseDocument doc = new PremiseDocument("doc-f", List.of(premise),
                ParseTrace.untracked());

        ChunkOutcome out = new HeadingHierarchyChunker().chunk(
                doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        String semantics = out.chunks().get(0).formulaNaturalLanguage();
        assertThat(semantics).isEqualTo(annotation.embeddingView());
        assertThat(semantics).doesNotContain("表示").as("未命中不得拼出自猜描述");
    }

    @Test
    @DisplayName("表格块 → Chunk 带确定性结构摘要（行列数+表头），与网格绑定")
    void tableGridPropagatesDeterministicSummary() {
        TableGrid grid = new TableGrid(List.of(
                List.of("知识点", "难度"),
                List.of("二次函数", "中")), 1);
        PremiseBlock premise = new PremiseBlock(block(BlockType.TABLE, "| 知识点 | 难度 |"),
                null, Double.NaN, grid, null);
        PremiseDocument doc = new PremiseDocument("doc-t", List.of(premise),
                ParseTrace.untracked());

        ChunkOutcome out = new HeadingHierarchyChunker().chunk(
                doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        String summary = out.chunks().get(0).captionOrTableSummary();
        assertThat(summary).as("摘要槽位必须带上表格结构摘要").isNotNull();
        assertThat(summary).contains("2 行").contains("2 列").contains("知识点").contains("难度");
        assertThat(out.chunks().get(0).formulaNaturalLanguage()).isNull();
        // 确定性：同一网格两次摘要逐字相等
        assertThat(grid.toEmbeddingSummary()).isEqualTo(summary);
    }

    @Test
    @DisplayName("普通段落 → 两个语义槽位均为 null（不伪造）")
    void plainParagraphHasNoSemantics() {
        PremiseDocument doc = new PremiseDocument("doc-p",
                List.of(PremiseBlock.fromLayoutBlock(block(BlockType.PARAGRAPH, "正文段落"))),
                ParseTrace.untracked());

        ChunkOutcome out = new HeadingHierarchyChunker().chunk(
                doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks().get(0).formulaNaturalLanguage()).isNull();
        assertThat(out.chunks().get(0).captionOrTableSummary()).isNull();
    }
}
