package cn.studykid.rag.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.grounding.GroundingService;
import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.grounding.GroundingVerdict;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.infrastructure.grounding.LocalLexicalEntailmentPort;
import cn.studykid.rag.infrastructure.parsing.LocalDeterministicDocumentParserPort;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.EntailmentPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * 本地占位端口 + G1/G3 用例的端到端切片测试（无 DB）。
 *
 * <p>目的：证明 G1（解析→切分）与 G3（蕴含判定）在<b>仅使用本地占位实现</b>时
 * 即可装配并跑通，不依赖 Docling / NLI 模型。不涉及 PostgreSQL，可在任意 CI 节点运行。
 *
 * <p>⚠️ 仅验证链路可达与基本语义；占位实现本身的质量（解析精度、蕴含准确率）
 * 不在本测试范围（见端口类注释中的已知缺陷）。
 */
class LocalRagPortsSliceTest {

    private static final EditionCoordinate PEP_MATH_7 =
            new EditionCoordinate(EditionCoordinate.Subject.MATH,
                    EditionCoordinate.GradeBand.GRADE_7,
                    EditionCoordinate.Term.FIRST,
                    EditionCoordinate.Publisher.PEP, "2024修订");

    @Test
    void parserProducesBlocksAndMarksAnswerZone() {
        DocumentParserPort parser = new LocalDeterministicDocumentParserPort();
        String text = "第1章 一次函数\n这是定义。\n答案：y=kx+b\n";
        ParsedDocument doc = parser.parse("doc-1", text.getBytes(StandardCharsets.UTF_8));

        List<LayoutBlock> blocks = doc.blocks();
        assertNotNull(blocks);
        // 标题块 + 定义段 + 答案行（空行被跳过）
        assertTrue(blocks.size() >= 2, "应至少产出定义段与答案块");
        assertTrue(blocks.stream().anyMatch(b -> b.type() == BlockType.HEADING), "应识别标题块");
        assertTrue(blocks.stream().anyMatch(LayoutBlock::inAnswerZone), "应标记答案区");
    }

    @Test
    void entailmentScoresOverlapAndDisjoint() {
        EntailmentPort port = new LocalLexicalEntailmentPort();
        double similar = port.entailment("三角形内角和等于180度", "三角形内角和为180度");
        double disjoint = port.entailment("苹果是水果", "勾股定理");

        assertTrue(similar > 0.0, "高度重叠应得正分");
        assertEquals(0.0, disjoint, 1e-9, "无共享词应得 0 分");
        assertTrue(similar >= disjoint, "重叠应不低于不相交");
    }

    @Test
    void judgeMapsToVerdictViaVerbatimQuote() {
        EntailmentPort port = new LocalLexicalEntailmentPort();
        EditionCoordinate ed = PEP_MATH_7;
        Chunk.Locator locator = new Chunk.Locator(1, "", null, 0, 10);
        Citation citation = new Citation("c1", ed, "doc", locator,
                "三角形内角和为180度", "三角形内角和等于180度");
        Map<String, List<Citation>> candidates =
                Map.of("三角形内角和等于180度", List.of(citation));

        List<GroundingVerdict> verdicts =
                port.judge(List.of("三角形内角和等于180度"), candidates);
        assertEquals(1, verdicts.size());
        assertEquals(GroundingVerdict.Verdict.GROUNDED, verdicts.get(0).verdict());
    }

    @Test
    void ingestionSliceResolvesRolesWithoutLeak() {
        IngestionPipeline pipeline =
                new IngestionPipeline(new LocalDeterministicDocumentParserPort());
        String content = "第1章 一次函数\n这是定义。\n答案：y=kx+b\n";
        List<Chunk> chunks = pipeline.ingest("doc-1", "一次函数", PEP_MATH_7,
                content.getBytes(StandardCharsets.UTF_8), List.of());

        assertFalse(chunks.isEmpty(), "应切分出片段");
        // 标题被消费为章节路径（不产片段），定义段=EXPLANATION，答案行=EXERCISE_ANSWER
        assertTrue(chunks.stream().anyMatch(c -> c.role().isAnswerBearing()),
                "答案区内容应被判为答案类角色（泄题闸门生效）");
        assertTrue(chunks.stream().anyMatch(c -> !c.answerBearing()),
                "非答案内容不应被误判为答案类");
    }

    @Test
    void groundingServiceWiresWithLocalPort() {
        // 仅验证用例可经本地端口装配并调用（不校验判定质量）。
        GroundingService service = new GroundingService(
                new LocalLexicalEntailmentPort(),
                cn.studykid.rag.core.grounding.GroundingPolicy.productionDefault());
        assertNotNull(service);
    }
}
