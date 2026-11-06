package cn.studykid.rag.infrastructure.premise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.infrastructure.parsing.LocalDeterministicDocumentParserPort;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.DocumentParseBackend;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * {@link DoclingPremiseBackend} 包装行为与 premise 装配冒烟
 * （RAG-D26 阶段 1.1 验收：包装产物与既有端口逐字段等价、异常穿透、留痕正确）。
 */
class DoclingPremiseBackendTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    @Test
    @DisplayName("包装产物与既有端口恒等：块序列同实例、字段零漂移")
    void wrapsWithoutFieldDrift() {
        LayoutBlock raw = new LayoutBlock(BlockType.PARAGRAPH, "顶点式定义。", 42, 0,
                "第3章", null, false, 0, 7);
        DocumentParserPort stub = (documentId, content) ->
                new ParsedDocument(documentId, List.of(raw));

        DoclingPremiseBackend backend = new DoclingPremiseBackend(stub);
        PremiseDocument doc = backend.parse("doc-1", new byte[] {1});

        // 恒等包装：内部持有的就是同一个 LayoutBlock 实例
        assertThat(doc.blocks()).hasSize(1);
        assertThat(doc.blocks().get(0).toLayoutBlock()).isSameAs(raw);
        assertThat(doc.blocks().get(0).text()).isEqualTo("顶点式定义。");
        assertThat(doc.blocks().get(0).pageNumber()).isEqualTo(42);
        assertThat(doc.documentId()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("留痕：backendId=docling-serve，版本取既有端口声明，页数取块最大页码")
    void traceCarriesBackendIdentity() {
        DocumentParserPort stub = (documentId, content) -> new ParsedDocument(documentId,
                List.of(
                        new LayoutBlock(BlockType.PARAGRAPH, "a", 3, 0, "", null, false, 0, 1),
                        new LayoutBlock(BlockType.PARAGRAPH, "b", 7, 0, "", null, false, 0, 1)));

        DoclingPremiseBackend backend = new DoclingPremiseBackend(stub);
        PremiseDocument doc = backend.parse("doc-1", new byte[] {1});

        assertThat(backend.backendId()).isEqualTo("docling-serve");
        assertThat(doc.trace().backendId()).isEqualTo("docling-serve");
        // 测试桩（lambda）无权威版本来源 → 留痕为空串（契约：未知为空串，不冒充）
        assertThat(doc.trace().backendVersion()).isEmpty();
        assertThat(doc.trace().pageCount()).isEqualTo(7);
        assertThat(doc.pageCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("包装真实 Docling 端口实现时版本号取 PARSER_VERSION")
    void versionFromDoclingPortConstant() {
        DocumentParserPort doclingStub = new cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort(
                "", 900) {
            // 仅借其类型触发 instanceof 分支；parse 不会被调用（fail-closed 语义已有独立测试）
        };

        DoclingPremiseBackend backend = new DoclingPremiseBackend(doclingStub);
        assertThat(backend.backendVersion())
                .isEqualTo(cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort.PARSER_VERSION);
    }

    @Test
    @DisplayName("既有端口的异常原样穿透（fail-closed 继承，不吞不换）")
    void delegatesExceptionsUnchanged() {
        RuntimeException boom = new IllegalStateException("解析服务不可用");
        DocumentParserPort failing = (documentId, content) -> {
            throw boom;
        };

        DoclingPremiseBackend backend = new DoclingPremiseBackend(failing);
        assertThatThrownBy(() -> backend.parse("doc-1", new byte[] {1}))
                .isSameAs(boom);
    }

    @Test
    @DisplayName("本地占位解析器经包装后端到端可用（G1 链路在 SPI 形状下不变）")
    void endToEndWithLocalParser() {
        DoclingPremiseBackend backend =
                new DoclingPremiseBackend(new LocalDeterministicDocumentParserPort());
        String text = "第1章 一次函数\n这是定义。\n答案：y=kx+b\n";

        PremiseDocument doc = backend.parse("doc-1", text.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.blocks()).isNotEmpty();

        var headingChunker = new cn.studykid.rag.premise.chunker.HeadingHierarchyChunker();
        var outcome = headingChunker.chunk(doc, ChunkPolicyConfig.productionDefault(),
                ChunkContext.withoutKnowledgePoints(EDITION));

        assertThat(outcome.chunks()).isNotEmpty();
        // 泄题闸门在 premise 链路下同样生效：答案行必须判为答案类
        assertThat(outcome.chunks().stream().anyMatch(c -> c.role().isAnswerBearing()))
                .as("答案区内容应被判为答案类角色（premise 链路与既有管道同规则）")
                .isTrue();
    }

    @Test
    @DisplayName("装配冒烟：LocalRagPortsConfig 的 premise 门面可解析且策略齐全")
    void configWiringSmoke() {
        var config = new cn.studykid.rag.infrastructure.LocalRagPortsConfig(false);
        var pre = config.premisePreprocessor(new LocalDeterministicDocumentParserPort(),
                "docling", "", "standard", 600);

        assertThat(pre.parseBackendId()).isEqualTo("docling-serve");
        assertThat(pre.availablePolicies())
                .contains(ChunkPolicy.HEADING_HIERARCHY, ChunkPolicy.HYBRID_CHUNKER);
        // 未配置 Docling base-url 时，包装的是本地占位解析器（既有 fail-closed 语义不变）
        assertThat(pre.parse("doc-1", "第1章\n定义。\n".getBytes(StandardCharsets.UTF_8)))
                .isNotNull();
    }
}
