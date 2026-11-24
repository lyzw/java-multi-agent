package cn.studykid.rag.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.PremisePreprocessor;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ChunkTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;
import cn.studykid.rag.premise.spi.BlockChunker;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * 批 9：摄取链接线——{@code IngestionPipeline} 完整 premise 链的<b>运行期可达性</b>测试。
 *
 * <p>⚠️ 核心命题：批 1~8 的 P1/P2 能力（门面解析、归一化、父子装配）此前只"静态存在"
 * 却被生产链绕过。本测试用可观测的桩后端/切分器，断言它们<b>真的被调用</b>——
 * 不是靠"有类/有方法"判断（项目铁律 #15）。
 */
class IngestionPipelineFullChainTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    /** 可观测解析后端：记录 parse 次数，返回带重复行噪声的块（供归一化清理）。 */
    private static final class TrackingBackend implements DocumentParseBackend {
        int parseCalls;
        private final List<PremiseBlock> blocks;

        TrackingBackend(List<PremiseBlock> blocks) {
            this.blocks = blocks;
        }

        @Override
        public String backendId() {
            return "tracking-backend";
        }

        @Override
        public String backendVersion() {
            return "test-1";
        }

        @Override
        public PremiseDocument parse(String documentId, byte[] content) {
            parseCalls++;
            return new PremiseDocument(documentId, blocks, new cn.studykid.rag.premise.model.ParseTrace(
                    backendId(), backendVersion(),
                    blocks.stream().mapToInt(PremiseBlock::pageNumber).max().orElse(0),
                    false, 1, List.of()));
        }
    }

    /** 可观测切分器：记录 chunk 次数，回显传入块数（验证归一化后的块真的传进来）。 */
    private static final class TrackingChunker implements BlockChunker {
        int chunkCalls;
        int lastBlockCount;

        @Override
        public ChunkPolicy policy() {
            return ChunkPolicy.HEADING_HIERARCHY;
        }

        @Override
        public ChunkOutcome chunk(PremiseDocument document, ChunkPolicyConfig config,
                                  ChunkContext context) {
            chunkCalls++;
            lastBlockCount = document.blocks().size();
            List<Chunk> chunks = new ArrayList<>();
            int i = 0;
            for (PremiseBlock b : document.blocks()) {
                if (b.toLayoutBlock().type() == BlockType.BOILERPLATE) {
                    continue;
                }
                LayoutBlock raw = b.toLayoutBlock();
                int page = Math.max(1, raw.pageNumber());
                chunks.add(new Chunk(
                        ChunkIdentifiers.chunkId("doc-1", page, i++ * 10),
                        "doc-1", context.edition(), ChunkRole.EXPLANATION,
                        raw.text().trim(),
                        new Chunk.Locator(page, "第3章", null, 0, raw.text().length()),
                        List.of(), null, ChunkIdentifiers.contentHash(raw.text())));
            }
            return new ChunkOutcome("doc-1", policy(), config, chunks, new ChunkTrace(
                    policy(), config.stableString(), chunks.size(), chunks.size(), 0, 0));
        }
    }

    private static PremiseBlock para(String text, int page, int start) {
        LayoutBlock raw = new LayoutBlock(BlockType.PARAGRAPH, text, page, 0,
                "第3章", null, false, start, start + text.length());
        return PremiseBlock.fromLayoutBlock(raw);
    }

    private static PremisePreprocessor facade(DocumentParseBackend backend, BlockChunker chunker) {
        return new PremisePreprocessor(backend, Map.of(chunker.policy(), chunker),
                ChunkPolicyConfig.productionDefault());
    }

    @Test
    @DisplayName("完整链真实调用门面 parse + 切分（不是静态存在）")
    void fullChainInvokesFacadeParseAndChunk() {
        TrackingBackend backend = new TrackingBackend(List.of(para("顶点式定义。", 45, 0)));
        TrackingChunker chunker = new TrackingChunker();
        IngestionPipeline pipeline = new IngestionPipeline(
                facade(backend, chunker), ChunkPolicy.HEADING_HIERARCHY, false, false);

        List<Chunk> chunks = pipeline.ingest("doc-1", "标题", EDITION,
                new byte[]{1, 2, 3}, List.of());

        assertThat(pipeline.fullChainEnabled()).isTrue();
        assertThat(backend.parseCalls).isEqualTo(1);
        assertThat(chunker.chunkCalls).isEqualTo(1);
        assertThat(chunker.lastBlockCount).isEqualTo(1);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("顶点式定义。");
    }

    @Test
    @DisplayName("归一化真实生效：跨页重复的短行被判 BOILERPLATE 并从切分产物剔除")
    void normalizationReallyRuns() {
        // 同一短行出现在 3 页 → markRepeatedLineNoise 判 BOILERPLATE；
        // 归一化发生在切分前，切分器收到的块应已被标记（BOILERPLATE 不产 chunk）。
        TrackingBackend backend = new TrackingBackend(List.of(
                para("第3章", 1, 0),   // 标题行（单页，保留但切分器按 PARAGRAPH 处理）
                para("页眉重复", 1, 10),
                para("页眉重复", 2, 10),
                para("页眉重复", 3, 10)));
        TrackingChunker chunker = new TrackingChunker();
        IngestionPipeline pipeline = new IngestionPipeline(
                facade(backend, chunker), ChunkPolicy.HEADING_HIERARCHY, false, false);

        List<Chunk> chunks = pipeline.ingest("doc-1", "标题", EDITION, new byte[]{1}, List.of());

        // 3 处"页眉重复"应被归一化为 BOILERPLATE，不进切分产物；只剩"第3章"一块。
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("第3章");
    }

    @Test
    @DisplayName("父子装配真实生效：同小节两块挂到父块，子块 parentId 非空")
    void parentAssemblyReallyRuns() {
        TrackingBackend backend = new TrackingBackend(List.of(
                para("顶点式定义。", 45, 0),
                para("配方求顶点。", 45, 20)));
        TrackingChunker chunker = new TrackingChunker();
        IngestionPipeline pipeline = new IngestionPipeline(
                facade(backend, chunker), ChunkPolicy.HEADING_HIERARCHY, false, true);

        List<Chunk> chunks = pipeline.ingest("doc-1", "标题", EDITION, new byte[]{1}, List.of());

        // 2 子块（同"第3章"）→ 1 父块 + 2 子块 = 3
        assertThat(chunks).hasSize(3);
        assertThat(chunks.stream().filter(Chunk::isChild)).hasSize(2);
        String parentId = chunks.stream().filter(Chunk::isChild).findFirst().orElseThrow().parentId();
        assertThat(parentId).isNotBlank();
        assertThat(chunks.stream().anyMatch(c -> c.id().equals(parentId))).isTrue();
    }

    @Test
    @DisplayName("父子装配关闭时：产出全是顶层块（灰度开关承重）")
    void parentAssemblyOffKeepsTopLevel() {
        TrackingBackend backend = new TrackingBackend(List.of(
                para("顶点式定义。", 45, 0),
                para("配方求顶点。", 45, 20)));
        TrackingChunker chunker = new TrackingChunker();
        IngestionPipeline pipeline = new IngestionPipeline(
                facade(backend, chunker), ChunkPolicy.HEADING_HIERARCHY, false, false);

        List<Chunk> chunks = pipeline.ingest("doc-1", "标题", EDITION, new byte[]{1}, List.of());

        assertThat(chunks).hasSize(2);
        assertThat(chunks).noneMatch(Chunk::isChild);
    }

    @Test
    @DisplayName("fail-closed：门面未装配所选策略 → 装配期拒绝构造")
    void rejectsUnassembledPolicy() {
        TrackingBackend backend = new TrackingBackend(List.of());
        TrackingChunker chunker = new TrackingChunker();
        PremisePreprocessor onlyHeading = facade(backend, chunker);
        assertThatThrownBy(() -> new IngestionPipeline(
                onlyHeading, ChunkPolicy.HYBRID_CHUNKER, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未装配");
    }

    @Test
    @DisplayName("fail-closed：解析抛异常不吞、不回退 legacy")
    void parseFailurePropagates() {
        DocumentParseBackend failing = new DocumentParseBackend() {
            @Override
            public String backendId() {
                return "failing";
            }

            @Override
            public String backendVersion() {
                return "test";
            }

            @Override
            public PremiseDocument parse(String documentId, byte[] content) {
                throw new IllegalStateException("解析后端不可用");
            }
        };
        IngestionPipeline pipeline = new IngestionPipeline(
                facade(failing, new TrackingChunker()), ChunkPolicy.HEADING_HIERARCHY, false, false);
        assertThatThrownBy(() -> pipeline.ingest("doc-1", "标题", EDITION,
                "内容".getBytes(StandardCharsets.UTF_8), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析后端不可用");
    }
}
