package cn.studykid.rag.premise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.BlockChunker;
import cn.studykid.rag.premise.spi.DocumentParseBackend;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * {@link PremisePreprocessor} 门面与占位 fail-closed 行为
 * （RAG-D26：占位不产出、不回退；策略查表 miss 即刻失败）。
 */
class PremisePreprocessorTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static LayoutBlock block(BlockType type, String text, int page) {
        return new LayoutBlock(type, text, page, 0, "", null, false, 0, text.length());
    }

    /** 确定性测试后端：把文本字节按行拆成段落块。 */
    private static DocumentParseBackend stubBackend() {
        return new DocumentParseBackend() {
            @Override
            public String backendId() {
                return "stub";
            }

            @Override
            public String backendVersion() {
                return "test-1";
            }

            @Override
            public PremiseDocument parse(String documentId, byte[] content) {
                String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                List<PremiseBlock> blocks = text.lines()
                        .filter(l -> !l.isBlank())
                        .map(l -> PremiseBlock.fromLayoutBlock(
                                new LayoutBlock(BlockType.PARAGRAPH, l.trim(), 1, 0, "",
                                        null, false, 0, l.length())))
                        .toList();
                return new PremiseDocument(documentId, blocks,
                        new ParseTrace("stub", "test-1", 1, false, 1, List.of()));
            }
        };
    }

    @Test
    @DisplayName("完整工序：解析 → 切分（HEADING_HIERARCHY），留痕贯穿")
    void processEndToEnd() {
        PremisePreprocessor pre = new PremisePreprocessor(
                stubBackend(),
                Map.of(ChunkPolicy.HEADING_HIERARCHY, new cn.studykid.rag.premise.chunker.HeadingHierarchyChunker()),
                ChunkPolicyConfig.productionDefault());

        ChunkOutcome out = pre.process("doc-1",
                "第1章 一次函数\n这是定义。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ChunkPolicy.HEADING_HIERARCHY,
                ChunkContext.withoutKnowledgePoints(EDITION));

        assertThat(pre.parseBackendId()).isEqualTo("stub");
        assertThat(out.documentId()).isEqualTo("doc-1");
        assertThat(out.policy()).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        // stub 后端不识别标题类型（两行都是 PARAGRAPH），两行各产一片；
        // 标题类型驱动「推进路径不产片」的行为由 Parity 测试与既有管道测试覆盖。
        assertThat(out.chunks()).hasSize(2);
        assertThat(out.chunks()).allSatisfy(c -> assertThat(c.role())
                .isEqualTo(ChunkRole.EXPLANATION));
    }

    @Test
    @DisplayName("未装配的策略即刻失败（不静默取默认实现——防留痕失真）")
    void unknownPolicyFailsFast() {
        PremisePreprocessor pre = PremisePreprocessor.of(
                stubBackend(),
                new cn.studykid.rag.premise.chunker.HeadingHierarchyChunker(),
                ChunkPolicyConfig.productionDefault());

        PremiseDocument doc = pre.parse("doc-1", "内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> pre.chunk(doc, ChunkPolicy.HYBRID_CHUNKER,
                ChunkContext.withoutKnowledgePoints(EDITION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HYBRID_CHUNKER");
    }

    @Test
    @DisplayName("MinerU 占位 fail-closed：抛异常而非产出空文档")
    void mineruPlaceholderFailsClosed() {
        var mineru = new cn.studykid.rag.premise.adapter.mineru.MineruParseBackend();
        assertThat(mineru.isAvailable()).isFalse();
        assertThatThrownBy(() -> mineru.parse("doc-1", new byte[] {1}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("MinerU");
    }

    @Test
    @DisplayName("PP-StructureV3 占位 fail-closed：抛异常而非产出空文档")
    void paddlePlaceholderFailsClosed() {
        var paddle = new cn.studykid.rag.premise.adapter.paddle.PaddleStructureParseBackend();
        assertThat(paddle.isAvailable()).isFalse();
        assertThatThrownBy(() -> paddle.parse("doc-1", new byte[] {1}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PP-StructureV3");
    }

    @Test
    @DisplayName("HybridChunker 数据流前提缺失时 fail-closed：不回退到 HEADING_HIERARCHY")
    void hybridFailsClosedWithoutDoclingJson() {
        // 阶段 2.2 已真实接入分块桥；此时无 doclingJsonRef 的文档
        // （MinerU/占位解析路径）必须在调桥前就明确失败——不得二次解析、不得回退。
        var hybrid = new cn.studykid.rag.premise.chunker.HybridChunkerClient();
        assertThat(hybrid.policy()).isEqualTo(ChunkPolicy.HYBRID_CHUNKER);

        PremiseDocument doc = new PremiseDocument("doc-1",
                PremiseBlock.fromLayoutBlocks(List.of(block(BlockType.PARAGRAPH, "内容", 1))),
                ParseTrace.untracked());

        assertThatThrownBy(() -> hybrid.chunk(doc, ChunkPolicyConfig.productionDefault(),
                ChunkContext.withoutKnowledgePoints(EDITION)))
                .isInstanceOf(cn.studykid.rag.premise.chunker.ChunkBridgeUnavailableException.class)
                .hasMessageContaining("doclingJsonRef");
    }

    @Test
    @DisplayName("ChunkPolicyConfig 校验：重叠为 0 拒绝（D04 §5.1 硬约束）")
    void configRejectsZeroOverlap() {
        assertThatThrownBy(() -> new ChunkPolicyConfig(512, 0.0, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapRatio");
        assertThatThrownBy(() -> new ChunkPolicyConfig(0, 0.15, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ChunkContext 校验：版本坐标不可为空（Chunk 领域不变式）")
    void contextRequiresEdition() {
        assertThatThrownBy(() -> new ChunkContext(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("edition");
    }

    @Test
    @DisplayName("withLookup：只装配函数返回非 null 的策略")
    void withLookupBuildsPartialTable() {
        PremisePreprocessor pre = PremisePreprocessor.withLookup(
                stubBackend(),
                policy -> policy == ChunkPolicy.HEADING_HIERARCHY
                        ? new cn.studykid.rag.premise.chunker.HeadingHierarchyChunker()
                        : null,
                ChunkPolicyConfig.productionDefault());

        assertThat(pre.availablePolicies()).containsExactly(ChunkPolicy.HEADING_HIERARCHY);
    }

    @Test
    @DisplayName("占位切分器不满足 BlockChunker 的「可用」预期时门面仍可装配（占位入表但调用即抛）")
    void placeholderInTableThrowsOnlyOnUse() {
        PremisePreprocessor pre = new PremisePreprocessor(
                stubBackend(),
                Map.of(ChunkPolicy.HYBRID_CHUNKER,
                        new cn.studykid.rag.premise.chunker.HybridChunkerClient()),
                ChunkPolicyConfig.productionDefault());

        PremiseDocument doc = pre.parse("doc-1", "内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(pre.availablePolicies()).contains(ChunkPolicy.HYBRID_CHUNKER);
        // HybridChunker 已真实接入：无 doclingJsonRef 的文档在调桥前 fail-closed。
        assertThatThrownBy(() -> pre.chunk(doc, ChunkPolicy.HYBRID_CHUNKER,
                ChunkContext.withoutKnowledgePoints(EDITION)))
                .isInstanceOf(cn.studykid.rag.premise.chunker.ChunkBridgeUnavailableException.class);
    }
}
