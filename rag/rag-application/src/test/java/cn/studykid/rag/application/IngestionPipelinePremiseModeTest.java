package cn.studykid.rag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;
import cn.studykid.rag.premise.chunker.HeadingHierarchyChunker;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;

/**
 * {@link IngestionPipeline} 双模式（RAG-D26 阶段 3.1）：
 * 注入 premise 切分器 → 新管线；不注入 → 旧内置循环（既有测试锚定）。
 *
 * <p>⚠️ 本测试放在 rag-application（premise 不依赖 application，依赖方向不能成环）。
 */
class IngestionPipelinePremiseModeTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    @Test
    @DisplayName("注入切分器后走 premise 管线：产出语义与旧路径一致")
    void premiseModeProducesSameSemantics() {
        LayoutBlock paragraph = new LayoutBlock(BlockType.PARAGRAPH, "顶点式定义。", 42, 0,
                "第3章", null, false, 0, 7);
        DocumentParserPort parser = (documentId, content) ->
                new ParsedDocument(documentId, List.of(paragraph));

        IngestionPipeline pipeline = new IngestionPipeline(
                parser, new HeadingHierarchyChunker(), ChunkPolicyConfig.productionDefault());

        assertThat(pipeline.premiseModeEnabled()).isTrue();
        List<cn.studykid.rag.core.chunk.Chunk> chunks = pipeline.ingest(
                "doc-1", "标题", EDITION, new byte[0], List.of());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("顶点式定义。");
        assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXPLANATION);
        assertThat(chunks.get(0).locator().sectionPath()).isEqualTo("第3章");
    }

    @Test
    @DisplayName("premise 模式下泄题闸门同样生效（答案区内容判为答案类）")
    void premiseModeKeepsLeakGate() {
        LayoutBlock answer = new LayoutBlock(BlockType.PARAGRAPH,
                "解：配方得 y=(x-2)^2-1，故顶点 (2,-1)。", 45, 0, "第3章", null, true, 0, 25);
        DocumentParserPort parser = (documentId, content) ->
                new ParsedDocument(documentId, List.of(answer));

        IngestionPipeline pipeline = new IngestionPipeline(
                parser, new HeadingHierarchyChunker(), ChunkPolicyConfig.productionDefault());

        List<cn.studykid.rag.core.chunk.Chunk> chunks = pipeline.ingest(
                "doc-1", "标题", EDITION, new byte[0], List.of());

        assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXAMPLE_SOLUTION);
        assertThat(chunks.get(0).answerBearing()).isTrue();
    }

    @Test
    @DisplayName("注入切分器但缺参数 → 拒绝构造（fail-closed）")
    void requiresConfigWhenChunkerPresent() {
        DocumentParserPort parser = (documentId, content) ->
                new ParsedDocument(documentId, List.of());
        assertThatThrownBy(() -> new IngestionPipeline(
                parser, new HeadingHierarchyChunker(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("premiseConfig");
    }

    @Test
    @DisplayName("默认构造保持旧模式（premiseModeEnabled=false，既有行为锚点）")
    void defaultConstructorIsLegacyMode() {
        DocumentParserPort parser = (documentId, content) ->
                new ParsedDocument(documentId, List.of());
        IngestionPipeline pipeline = new IngestionPipeline(parser);

        assertThat(pipeline.premiseModeEnabled()).isFalse();
    }
}
