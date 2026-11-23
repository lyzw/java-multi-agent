package cn.studykid.rag.premise.chunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
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
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * {@link HybridChunkerClient} 的角色回填 + fail-closed 契约测试（RAG-D28 P0-7 补齐）。
 *
 * <p>源码注释（§"泄题闸门"）明确点名本类与 {@code ChunkPolicyTest} 共同守护上线前置条件：
 * HybridChunker 产出的 chunk 不带角色，必须按覆盖块回填最严角色，否则检索期
 * {@code RetrievalPolicy.applyRoleFilter} 失效 → 讲解模式泄题。
 *
 * <p>⚠️ 本测试用 JDK 内置 {@code HttpServer} 起<b>真实假桥</b>驱动完整 {@code chunk()} 流程
 * （不是反射调私有方法）：既锁桥响应 JSON 形状（{@link MinimalJsonArrays} 的解析契约），
 * 又验角色回填与 fail-closed 的真实行为——撤掉修复点（把角色回填改成恒定 EXPLANATION、
 * 或把桥失败改成静默回退）必然转红。
 */
class HybridChunkerClientTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final ChunkContext CONTEXT = ChunkContext.withoutKnowledgePoints(EDITION);

    private com.sun.net.httpserver.HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起一个假桥：固定返回给定响应体/状态码，并记录最近一次请求体。 */
    private String startBridge(int statusCode, String responseBody, AtomicReference<String> requestBody)
            throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HybridChunkerClient.CHUNK_PATH, exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            if (requestBody != null) {
                requestBody.set(new String(req, StandardCharsets.UTF_8));
            }
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static LayoutBlock block(BlockType type, String text, int page,
                                     String exerciseNo, boolean answerZone, int start, int end) {
        return new LayoutBlock(type, text, page, 0, "第3章", exerciseNo, answerZone, start, end);
    }

    /** 构造携带 doclingJsonRef 的文档（分块桥契约要求复用解析产物）。 */
    private static PremiseDocument document(List<LayoutBlock> blocks) {
        List<PremiseBlock> premiseBlocks = blocks.stream().map(PremiseBlock::fromLayoutBlock).toList();
        // doclingJsonRef 只需非空即可通过 chunk() 的前置校验；桥响应由假桥提供。
        return new PremiseDocument("doc-1", premiseBlocks, ParseTrace.untracked(), "{}");
    }

    private static String bridgeResponse(String chunk) {
        return "{\"chunks\":[" + chunk + "],\"producer\":{\"name\":\"docling\",\"version\":\"2.106.0\"}}";
    }

    // ---------- 角色回填（泄题闸门） ----------

    @Test
    @DisplayName("item_refs 指向答案区+题号块 → 回填 EXERCISE_ANSWER（讲解模式必须过滤）")
    void roleBackfillAnswerZoneWithExerciseNo() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"解：配方得 y=(x-2)^2-1。\",\"headings\":[\"第3章\"],"
                        + "\"page_numbers\":[45],\"char_spans\":[{\"start\":10,\"end\":30}],"
                        + "\"item_refs\":[\"#/texts/0\"]}"), null);

        PremiseDocument doc = document(List.of(block(BlockType.PARAGRAPH, "解：配方得。", 45, "12", true, 0, 20)));
        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks()).hasSize(1);
        var chunk = out.chunks().get(0);
        assertThat(chunk.role()).as("答案区块必须是习题标准答案，不能是讲解").isEqualTo(ChunkRole.EXERCISE_ANSWER);
        assertThat(chunk.answerBearing()).as("答案承载标记驱动检索期泄题闸门").isTrue();
        assertThat(chunk.locator().exerciseNo()).isEqualTo("12");
    }

    @Test
    @DisplayName("答案区无题号 → EXAMPLE_SOLUTION（取严，不降为讲解）")
    void roleBackfillAnswerZoneWithoutExerciseNo() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"例题解答：先配方。\",\"headings\":[],\"page_numbers\":[45],"
                        + "\"char_spans\":[],\"item_refs\":[\"#/texts/0\"]}"), null);

        PremiseDocument doc = document(List.of(block(BlockType.PARAGRAPH, "例题解答。", 45, null, true, 0, 8)));
        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks().get(0).role()).isEqualTo(ChunkRole.EXAMPLE_SOLUTION);
        assertThat(out.chunks().get(0).answerBearing()).isTrue();
    }

    @Test
    @DisplayName("合并块含任一答案区 → 整块取严（宁可过判为答案，不可泄题）")
    void mergedChunkTakesStrictestRole() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"题干 + 答案合并文本。\",\"headings\":[],\"page_numbers\":[45],"
                        + "\"char_spans\":[],\"item_refs\":[\"#/texts/0\",\"#/texts/1\"]}"), null);

        PremiseDocument doc = document(List.of(
                block(BlockType.EXERCISE, "求顶点坐标。", 45, "12", false, 0, 10),
                block(BlockType.PARAGRAPH, "解：顶点是 (2,-1)。", 45, "12", true, 10, 30)));
        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).role())
                .as("讲解块与答案块合并 → 整块取严为答案")
                .isEqualTo(ChunkRole.EXERCISE_ANSWER);
    }

    @Test
    @DisplayName("无答案覆盖块 → EXPLANATION（不过判）")
    void roleBackfillExplanation() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"二次函数的顶点式定义。\",\"headings\":[\"3.2\"],\"page_numbers\":[45],"
                        + "\"char_spans\":[],\"item_refs\":[\"#/texts/0\"]}"), null);

        PremiseDocument doc = document(List.of(block(BlockType.PARAGRAPH, "顶点式定义。", 45, null, false, 0, 8)));
        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks().get(0).role()).isEqualTo(ChunkRole.EXPLANATION);
        assertThat(out.chunks().get(0).answerBearing()).isFalse();
    }

    // ---------- 桥契约与留痕 ----------

    @Test
    @DisplayName("桥请求携带 max_tokens / merge_peers / repeat_table_header（留痕与切分一致）")
    void bridgeRequestCarriesConfig() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"内容。\",\"headings\":[],\"page_numbers\":[1],\"char_spans\":[],\"item_refs\":[]}"),
                requestBody);

        ChunkPolicyConfig config = new ChunkPolicyConfig(256, 0.2, false, false);
        new HybridChunkerClient(baseUrl, 30).chunk(document(List.of()), config, CONTEXT);

        String sent = requestBody.get();
        assertThat(sent).contains("\"max_tokens\":256")
                .contains("\"merge_peers\":false")
                .contains("\"repeat_table_header\":false");
    }

    @Test
    @DisplayName("locator 取桥 char_spans 可回取区间；sectionPath 取 headings 层级栈")
    void locatorFromBridgeSpans() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"图像性质。\",\"headings\":[\"第3章\",\"3.2 图像\"],\"page_numbers\":[43,44],"
                        + "\"char_spans\":[{\"start\":100,\"end\":120},{\"start\":115,\"end\":140}],"
                        + "\"item_refs\":[]}"), null);

        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(document(List.of()), ChunkPolicyConfig.productionDefault(), CONTEXT);
        var locator = out.chunks().get(0).locator();

        assertThat(locator.pageNumber()).isEqualTo(43);
        assertThat(locator.charStart()).isEqualTo(100);
        assertThat(locator.charEnd()).isEqualTo(140);
        assertThat(locator.sectionPath()).isEqualTo("第3章 > 3.2 图像");
    }

    @Test
    @DisplayName("策略与配置串写入留痕（chunk_policy 落库取值来源）")
    void traceCarriesPolicyAndConfig() throws IOException {
        String baseUrl = startBridge(200, bridgeResponse(
                "{\"text\":\"内容。\",\"headings\":[],\"page_numbers\":[1],\"char_spans\":[],\"item_refs\":[]}"), null);

        ChunkOutcome out = new HybridChunkerClient(baseUrl, 30)
                .chunk(document(List.of()), ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.policy()).isEqualTo(ChunkPolicy.HYBRID_CHUNKER);
        assertThat(out.trace().policy()).isEqualTo(ChunkPolicy.HYBRID_CHUNKER);
        assertThat(out.trace().configString())
                .isEqualTo(ChunkPolicyConfig.productionDefault().stableString());
    }

    // ---------- fail-closed（不静默回退 heading） ----------

    @Test
    @DisplayName("反证：doclingJsonRef 为空 → 抛异常，禁止二次解析")
    void blankDoclingJsonFailsClosed() {
        PremiseDocument doc = new PremiseDocument("doc-1", List.of(), ParseTrace.untracked());
        assertThatThrownBy(() -> new HybridChunkerClient("http://127.0.0.1:1", 30)
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT))
                .isInstanceOf(ChunkBridgeUnavailableException.class)
                .hasMessageContaining("不得二次解析");
    }

    @Test
    @DisplayName("反证：桥返回 HTTP 500 → 抛异常，绝不静默回退 heading")
    void bridgeHttpErrorFailsClosed() throws IOException {
        String baseUrl = startBridge(500, "boom", null);
        assertThatThrownBy(() -> new HybridChunkerClient(baseUrl, 30)
                .chunk(document(List.of()), ChunkPolicyConfig.productionDefault(), CONTEXT))
                .isInstanceOf(ChunkBridgeUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("反证：桥返回 0 个 chunk → 抛异常（空结果不是合法切分）")
    void emptyChunksFailsClosed() throws IOException {
        String baseUrl = startBridge(200, "{\"chunks\":[]}", null);
        assertThatThrownBy(() -> new HybridChunkerClient(baseUrl, 30)
                .chunk(document(List.of()), ChunkPolicyConfig.productionDefault(), CONTEXT))
                .isInstanceOf(ChunkBridgeUnavailableException.class)
                .hasMessageContaining("0 个 chunk");
    }

    @Test
    @DisplayName("反证：桥不可达 → 抛异常（连接失败不降级为 heading）")
    void unreachableBridgeFailsClosed() {
        // 端口 1 几乎不可能有服务监听，连接必然失败。
        assertThatThrownBy(() -> new HybridChunkerClient("http://127.0.0.1:1", 10)
                .chunk(document(List.of()), ChunkPolicyConfig.productionDefault(), CONTEXT))
                .isInstanceOf(ChunkBridgeUnavailableException.class)
                .hasMessageContaining("不可达");
    }
}
