package cn.studykid.rag.premise.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.model.RouteProfile;
import cn.studykid.rag.premise.spi.DocumentParseBackend;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 分级解析后端反证测试（D28 P1-1/P1-2）：用真实假后端驱动完整 parse 流程。
 */
class RoutingParseBackendTest {

    private static final byte[] PDF = "%PDF-fake".getBytes(StandardCharsets.UTF_8);

    /** 模拟真实教材整页文本量（短句重复到 ~200 字；27 字的测试串会被质量分正确判为低覆盖）。 */
    private static final String CLEAN_TEXT =
            "这是一段足够长的干净正文文本，用于通过质量分阈值判定。".repeat(8);

    private static PremiseBlock block(int page, BlockType type, String text, double confidence) {
        LayoutBlock layout = new LayoutBlock(type, text, page, 0, "章>节", null,
                false, 0, text.length());
        return new PremiseBlock(layout, null, confidence);
    }

    private static PremiseBlock para(int page, String text) {
        return block(page, BlockType.PARAGRAPH, text, 0.95);
    }

    private static final class FakeBackend implements DocumentParseBackend {
        private final String id;
        private final List<PremiseBlock> blocks;
        private final String doclingJsonRef;
        private final RuntimeException failure;
        private final ParseTrace traceOverride;
        private int calls;

        FakeBackend(String id, List<PremiseBlock> blocks) {
            this(id, blocks, null, null, null);
        }

        FakeBackend(String id, List<PremiseBlock> blocks, String doclingJsonRef,
                    RuntimeException failure) {
            this(id, blocks, doclingJsonRef, failure, null);
        }

        FakeBackend(String id, List<PremiseBlock> blocks, String doclingJsonRef,
                    RuntimeException failure, ParseTrace traceOverride) {
            this.id = id;
            this.blocks = blocks;
            this.doclingJsonRef = doclingJsonRef;
            this.failure = failure;
            this.traceOverride = traceOverride;
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public String backendVersion() {
            return "1.0";
        }

        @Override
        public PremiseDocument parse(String documentId, byte[] content) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            int pageCount = blocks.stream().mapToInt(PremiseBlock::pageNumber).max().orElse(0);
            ParseTrace trace = traceOverride != null
                    ? traceOverride
                    : new ParseTrace(id, "1.0", pageCount, false, 1, List.of());
            return new PremiseDocument(documentId, blocks, trace, doclingJsonRef);
        }
    }

    @Test
    @DisplayName("全干净页：只用轻量后端，升级后端零调用，doclingJsonRef 保留")
    void cleanDocumentUsesPrimaryOnly() {
        String text = CLEAN_TEXT;
        FakeBackend primary = new FakeBackend("docling", List.of(para(1, text), para(2, text)),
                "docling://raw.json", null);
        FakeBackend heavy = new FakeBackend("mineru", List.of(para(1, "升级结果")));
        var backend = new RoutingParseBackend(primary, heavy,
                new PageRouter(RoutingConfig.productionDefault()));

        PremiseDocument result = backend.parse("doc-1", PDF);

        assertThat(primary.calls).isEqualTo(1);
        assertThat(heavy.calls).isZero();
        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks()).allSatisfy(b ->
                assertThat(b.text()).startsWith("这是一段足够长"));
        assertThat(result.doclingJsonRef()).isEqualTo("docling://raw.json");
        assertThat(result.trace().routeReason()).isEqualTo("clean=2,escalated=0,quarantined=0");
        assertThat(result.trace().pageRoutes()).hasSize(2);
        assertThat(result.trace().pageRoutes()).allSatisfy(r ->
                assertThat(r.profile()).isEqualTo(RouteProfile.LIGHTWEIGHT));
        assertThat(result.trace().backendId()).isEqualTo("routing(docling+mineru)");
        assertThat(result.trace().elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("复杂页升级：逐页合并，干净页保留 primary，复杂页取 escalation，按页序输出")
    void complexPageEscalatesAndMergesPerPage() {
        String text = CLEAN_TEXT;
        FakeBackend primary = new FakeBackend("docling", List.of(
                para(1, text), block(2, BlockType.FORMULA, "$$x^2$$", 0.95)),
                "docling://raw.json", null);
        FakeBackend heavy = new FakeBackend("mineru", List.of(
                para(2, "MINERU-FORMULA-RESULT")));
        var backend = new RoutingParseBackend(primary, heavy,
                new PageRouter(RoutingConfig.productionDefault()));

        PremiseDocument result = backend.parse("doc-2", PDF);

        assertThat(heavy.calls).isEqualTo(1);
        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().get(0).text()).startsWith("这是一段足够长");
        assertThat(result.blocks().get(1).text()).isEqualTo("MINERU-FORMULA-RESULT");
        assertThat(result.trace().routeReason()).isEqualTo("clean=1,escalated=1,quarantined=0");
        assertThat(result.trace().pageRoutes().get(1).profile()).isEqualTo(RouteProfile.HEAVY);
        // ⚠️ 混合后端：整册 Docling JSON 已失效，必须清空让分块桥 fail-closed
        assertThat(result.doclingJsonRef()).isNull();
        assertThat(result.trace().warnings()).anyMatch(w -> w.contains("doclingJsonRef cleared"));
    }

    @Test
    @DisplayName("升级不可逆：escalation 结果更差也不回退 primary（撤点反证目标）")
    void escalationIsIrreversible() {
        String text = CLEAN_TEXT;
        FakeBackend primary = new FakeBackend("docling", List.of(
                para(1, text), block(2, BlockType.FORMULA, "$$x^2$$", 0.99)),
                null, null);
        // 升级后端在该页「没有结果」——仍不得回退到 primary 的公式块
        FakeBackend heavy = new FakeBackend("mineru", List.of());
        var backend = new RoutingParseBackend(primary, heavy,
                new PageRouter(RoutingConfig.productionDefault()));

        PremiseDocument result = backend.parse("doc-3", PDF);

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).text()).startsWith("这是一段足够长");
        assertThat(result.blocks()).noneMatch(b -> b.text().contains("x^2"));
    }

    @Test
    @DisplayName("升级后端失败：异常直接传播，不静默用轻量结果")
    void escalationFailurePropagates() {
        String text = CLEAN_TEXT;
        FakeBackend primary = new FakeBackend("docling", List.of(
                para(1, text), block(2, BlockType.FORMULA, "$$x^2$$", 0.95)),
                null, null);
        var boom = new IllegalStateException("mineru down");
        FakeBackend heavy = new FakeBackend("mineru", List.of(), null, boom);
        var backend = new RoutingParseBackend(primary, heavy,
                new PageRouter(RoutingConfig.productionDefault()));

        assertThatThrownBy(() -> backend.parse("doc-4", PDF))
                .isSameAs(boom);
    }

    @Test
    @DisplayName("疑似扫描件：抛隔离异常，升级后端零调用（不静默当文本 PDF）")
    void scanPageQuarantines() {
        FakeBackend primary = new FakeBackend("docling", List.of(
                para(1, "这是一段足够长的干净正文文本，用于通过质量分阈值判定。"),
                block(2, BlockType.FIGURE, "图像", 0.95)),
                null, null);
        FakeBackend heavy = new FakeBackend("mineru", List.of(para(2, "OCR")));
        var backend = new RoutingParseBackend(primary, heavy,
                new PageRouter(RoutingConfig.productionDefault()));

        assertThatThrownBy(() -> backend.parse("doc-5", PDF))
                .isInstanceOf(PageQuarantineException.class)
                .satisfies(e -> assertThat(((PageQuarantineException) e).pageNumbers())
                        .containsExactly(2));
        assertThat(heavy.calls).isZero();
    }

    @Test
    @DisplayName("留痕反证：撤掉 withRouting 的逐字段带过，warnings/后端版本不应丢失")
    void traceCarriesAllFields() {
        String text = CLEAN_TEXT;
        ParseTrace base = new ParseTrace("docling", "2.3", 1, false, 1,
                List.of("original-warning"));
        FakeBackend primary = new FakeBackend("docling", List.of(para(1, text)),
                "ref", null, base);
        var backend = new RoutingParseBackend(primary,
                new FakeBackend("mineru", List.of()),
                new PageRouter(RoutingConfig.productionDefault()));

        PremiseDocument result = backend.parse("doc-6", PDF);
        assertThat(result.trace().warnings()).contains("original-warning");
        assertThat(result.trace().backendVersion()).isEqualTo("1.0/1.0");
        assertThat(result.trace().qualityScore()).isBetween(0.0, 1.0);
    }
}
