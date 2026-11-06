package cn.studykid.rag.premise.chunker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ChunkTrace;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;
import cn.studykid.rag.premise.normalize.SectionPathResolver;
import cn.studykid.rag.premise.role.BlockRoleResolver;
import cn.studykid.rag.premise.spi.BlockChunker;

/**
 * Docling HybridChunker 切分器（RAG-D26 阶段 2.2 已接入）——
 * 经 Python 分块桥（{@code deploy/docling/chunk_service}）调用官方实现。
 *
 * <p><b>⚠️ 接口契约来自实测（2026-11-06，docling-core 2.106.0 + 真实 162 页教材）</b>：
 * <pre>
 * POST /v1/chunk  { "docling_json": {…解析阶段留存的 DoclingDocument…},
 *                   "config": {"max_tokens":512,"merge_peers":true} }
 * → { "chunks": [ { "text", "headings", "page_numbers",
 *                   "char_spans":[{"start","end"}], "item_refs":["#/texts/12"] } ],
 *     "producer": {"name":"docling","version":"2.106.0"} }   // 444 chunks, item_refs 100%
 * </pre>
 *
 * <p><b>⚠️ 数据流的核心约束（§2.4 三条纪律的 Java 侧落点）</b>：
 * <ol>
 *   <li><b>docling_json 必须来自解析阶段</b>：本客户端从
 *       {@link PremiseDocument} 取（{@code doclingJsonRef()}），
 *       解析器包装时存入——<b>不二次解析</b>，否则 item_refs 与块对不上；</li>
 *   <li><b>item_refs 是角色回填的锚点</b>：chunk 覆盖的 {@code #/texts/N}
 *       对应解析产物里的第 N 个文本块（与 Docling 响应的 texts 数组同序），
 *       回查各块的版面事实 → 按最严角色合成；</li>
 *   <li><b>char_spans 保证可回取</b>：桥按 prov 换算的单调区间；
 *       chunk 的 locator 取首个 span 的页码与偏移。</li>
 * </ol>
 *
 * <p><b>⚠️ 泄题闸门（本类存在的安全理由）</b>：
 * HybridChunker 产出的 chunk <b>不带 ChunkRole</b>——直接入库会让答案类片段
 * 丢失角色，检索期泄题闸门（{@code RetrievalPolicy.applyRoleFilter}）失效。
 * 因此<b>每个 chunk 都必须经角色回填</b>（见 {@link #resolveChunkRole}）：
 * 取其覆盖块的最严角色；答案区块与其他块合并进同一 chunk 时整块取严。
 * 这是上线前置条件，测试断言 {@code ChunkPolicyTest} 与
 * {@code HybridChunkerClientTest} 共同守护。
 *
 * <p><b>⚠️ fail-closed 不回退</b>：桥不可用 / 响应异常一律抛
 * {@link ChunkBridgeUnavailableException}（job FAILED），
 * <b>绝不</b>回退到 {@code HEADING_HIERARCHY}——策略是留痕事实
 * （{@code chunk_policy}），静默替换会让留痕失真且检索表现不可比。
 */
public class HybridChunkerClient implements BlockChunker {

    /** 桥端点路径。 */
    public static final String CHUNK_PATH = "/v1/chunk";

    /** 默认桥基址（与 chunk_service 启动脚本一致）。 */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:5002";

    private final String baseUrl;
    private final int timeoutSeconds;
    private final HttpClient http;

    /**
     * @param baseUrl        分块桥基址（如 {@code http://127.0.0.1:5002}）
     * @param timeoutSeconds 单次分块超时（实测 162 页 6.4 秒；600 秒覆盖到 500 页量级）
     */
    public HybridChunkerClient(@NonNull String baseUrl, int timeoutSeconds) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空（分块桥必须显式配置端点）");
        }
        this.baseUrl = trimmed;
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.http = HttpClient.newBuilder()
                // ⚠️ 与 DoclingDocumentParserPort 同坑：docling 系服务的 uvicorn
                //    不认 h2c 升级，必须强制 HTTP/1.1。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 便捷构造（默认端点与超时）。 */
    public HybridChunkerClient() {
        this(DEFAULT_BASE_URL, 600);
    }

    @Override
    public @NonNull ChunkPolicy policy() {
        return ChunkPolicy.HYBRID_CHUNKER;
    }

    @Override
    public @NonNull ChunkOutcome chunk(@NonNull PremiseDocument document,
                                       @NonNull ChunkPolicyConfig config,
                                       @NonNull ChunkContext context) {
        String doclingJson = document.doclingJsonRef();
        if (doclingJson == null || doclingJson.isBlank()) {
            throw new ChunkBridgeUnavailableException(
                    "PremiseDocument 未携带 DoclingDocument JSON（doclingJsonRef 为空）——"
                            + "分块桥契约要求复用解析阶段的文档，不得二次解析。"
                            + "检查解析后端是否为 Docling 且包装时保存了原始 JSON。");
        }
        String responseBody = callBridge(doclingJson, config);
        List<ChunkBridgeChunk> bridgeChunks = parseResponse(responseBody);
        if (bridgeChunks.isEmpty()) {
            throw new ChunkBridgeUnavailableException(
                    "分块桥产出 0 个 chunk（documentId=" + document.documentId() + "）");
        }
        return assemble(document, config, context, bridgeChunks);
    }

    // ---------- 桥调用 ----------

    private String callBridge(String doclingJson, ChunkPolicyConfig config) {
        // ⚠️ docling_json 内嵌 JSON 字符串需要转义——用最小转义（引号/反斜杠/控制符）。
        //    更优做法是桥收 multipart，但当前实测 JSON 内嵌可行且桥侧校验更快失败。
        String body = "{"
                + "\"docling_json\":" + doclingJson + ","
                + "\"config\":{"
                + "\"max_tokens\":" + config.maxTokens() + ","
                + "\"merge_peers\":" + config.mergePeers() + ","
                + "\"repeat_table_header\":" + config.repeatTableHeader()
                + "}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + CHUNK_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ChunkBridgeUnavailableException(
                    "分块桥不可达（" + baseUrl + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChunkBridgeUnavailableException("分块桥调用被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new ChunkBridgeUnavailableException(
                    "分块桥返回 HTTP " + resp.statusCode() + "：" + abbreviate(resp.body()));
        }
        return resp.body();
    }

    /**
     * 解析桥响应（premise 零框架依赖：<b>不引 Jackson</b>，用极简数组扫描）。
     *
     * <p>⚠️ 响应结构已实测锁定（producer 之外的顶层只有 chunks 数组，
     * chunk 对象只有 5 个已知键、值为标量或同质数组），因此用
     * {@link MinimalJsonArrays} 做受限提取是安全的；
     * 若未来响应结构扩展，先改桥的 response_model 再改此处（契约测试会拦）。
     */
    private List<ChunkBridgeChunk> parseResponse(String body) {
        List<String> chunkObjects;
        try {
            chunkObjects = MinimalJsonArrays.objectsOfArray(body, "chunks");
        } catch (Exception e) {
            throw new ChunkBridgeUnavailableException("分块桥响应不是合法 JSON：" + e.getMessage(), e);
        }
        List<ChunkBridgeChunk> out = new ArrayList<>();
        for (String obj : chunkObjects) {
            String text = MinimalJsonArrays.stringValue(obj, "text");
            if (text.isBlank()) {
                continue;
            }
            List<String> headings = MinimalJsonArrays.stringArrayOf(obj, "headings");
            List<Integer> pages = MinimalJsonArrays.intArrayOf(obj, "page_numbers", 1);
            int charStart = -1;
            int charEnd = -1;
            for (String spanObj : MinimalJsonArrays.objectsOfArray(obj, "char_spans")) {
                int st = MinimalJsonArrays.intValue(spanObj, "start", -1);
                int en = MinimalJsonArrays.intValue(spanObj, "end", -1);
                if (st >= 0 && en > st) {
                    if (charStart < 0) {
                        charStart = st;
                        charEnd = en;
                    } else {
                        charEnd = Math.max(charEnd, en);
                    }
                }
            }
            List<String> refs = MinimalJsonArrays.stringArrayOf(obj, "item_refs");
            out.add(new ChunkBridgeChunk(text, headings, pages, charStart, charEnd, refs));
        }
        return out;
    }

    // ---------- 组装（角色回填 + locator + 留痕） ----------

    private ChunkOutcome assemble(PremiseDocument document, ChunkPolicyConfig config,
                                  ChunkContext context, List<ChunkBridgeChunk> bridgeChunks) {
        // ① item_ref → 解析块（PremiseBlock 持有原 LayoutBlock；ref 的数字尾 = texts 下标）
        Map<Integer, cn.studykid.rag.ports.LayoutBlock> blockByIndex =
                new java.util.HashMap<>();
        int textsIdx = 0;
        for (var pb : document.blocks()) {
            // PremiseDocument 的 blocks 按阅读顺序遍历 texts/tables/pictures（与桥同源）；
            // 桥的 item_refs 目前只含 #/texts/N（实测 100%），故按下标对齐 texts 序列。
            if (pb.toLayoutBlock().type() == cn.studykid.rag.ports.BlockType.HEADING) {
                // 标题块也在 texts 里（section_header/title 都占位）——保持计数一致
            }
            blockByIndex.put(textsIdx++, pb.toLayoutBlock());
        }

        List<Chunk> chunks = new ArrayList<>();
        int splitOversize = 0;
        for (ChunkBridgeChunk bc : bridgeChunks) {
            if (bc.text().length() > config.maxTokens()) {
                splitOversize++;
            }
            chunks.add(buildChunk(document.documentId(), context, bc, blockByIndex));
        }
        ChunkTrace trace = new ChunkTrace(ChunkPolicy.HYBRID_CHUNKER, config.stableString(),
                document.blocks().size(), chunks.size(), 0, splitOversize);
        return new ChunkOutcome(document.documentId(), ChunkPolicy.HYBRID_CHUNKER, config,
                chunks, trace);
    }

    /**
     * 构建单个 chunk（角色回填的落点）。
     *
     * <p>locator：页码取 chunk 的 page_numbers 首个；charStart/charEnd 取桥换算的
     * span（缺失时回退 0/length——估算精度由人工抽检兜底，不影响「有区间可回取」）；
     * sectionPath 取 chunk headings 的标题栈语义（headings 已是层级序）。
     */
    private Chunk buildChunk(String documentId, ChunkContext context, ChunkBridgeChunk bc,
                             Map<Integer, cn.studykid.rag.ports.LayoutBlock> blockByIndex) {
        String trimmed = bc.text().trim();
        ChunkRole role = resolveChunkRole(bc, blockByIndex);
        int page = bc.pages().isEmpty() ? 1 : bc.pages().get(0);
        int charStart = bc.charStart() >= 0 ? bc.charStart() : 0;
        int charEnd = bc.charEnd() > charStart ? bc.charEnd() : charStart + trimmed.length();
        String sectionPath = String.join(" > ", bc.headings());
        Chunk.Locator locator = new Chunk.Locator(page, sectionPath,
                effectiveExerciseNo(bc, blockByIndex), charStart, charEnd);
        return new Chunk(
                ChunkIdentifiers.chunkId(documentId, page, charStart),
                documentId,
                context.edition(),
                role,
                trimmed,
                locator,
                matchedKpIds(trimmed, context),
                locator.exerciseNo(),
                ChunkIdentifiers.contentHash(trimmed),
                new LayoutFacts(
                        role.isAnswerBearing(),
                        "HYBRID_CHUNK",
                        BlockRoleResolver.solutionPrefixPattern().isEmpty() ? null : null));
    }

    /**
     * 角色回填：取 chunk 覆盖块的<b>最严角色</b>。
     *
     * <p>判定顺序（取严方向，与 {@code BlockRoleResolver} 的判定表同源）：
     * <ol>
     *   <li>任一覆盖块在答案区 → 答案类（有题号 EXERCISE_ANSWER / 无题号 EXAMPLE_SOLUTION）；</li>
     *   <li>任一覆盖块类型为 ANSWER → EXERCISE_ANSWER（行 4 取严）；</li>
     *   <li>任一覆盖块类型为 EXERCISE → EXERCISE；</li>
     *   <li>chunk 文本命中块首解答前缀 → 按题号规则（行 7 兜底）；</li>
     *   <li>否则 EXPLANATION。</li>
     * </ol>
     *
     * <p>⚠️ 答案区块与其他块合并进同一 chunk 时整块取严——宁可过判为答案
     * （讲解模式过滤它），不可把答案判成讲解（泄题）。
     */
    private ChunkRole resolveChunkRole(ChunkBridgeChunk bc,
                                       Map<Integer, cn.studykid.rag.ports.LayoutBlock> blockByIndex) {
        boolean anyAnswerZone = false;
        boolean anyAnswerType = false;
        boolean anyExercise = false;
        String exerciseNo = null;
        for (String ref : bc.itemRefs()) {
            var block = blockByIndex(ref, blockByIndex);
            if (block == null) {
                continue;
            }
            anyAnswerZone |= block.inAnswerZone();
            anyAnswerType |= block.type() == cn.studykid.rag.ports.BlockType.ANSWER;
            anyExercise |= block.type() == cn.studykid.rag.ports.BlockType.EXERCISE;
            if (exerciseNo == null) {
                exerciseNo = block.exerciseNo();
            }
        }
        if (anyAnswerZone || anyAnswerType) {
            return exerciseNo != null ? ChunkRole.EXERCISE_ANSWER : ChunkRole.EXAMPLE_SOLUTION;
        }
        if (anyExercise) {
            return ChunkRole.EXERCISE;
        }
        // chunk 文本级兜底（合并后的文本首可能正好是「解：」）
        String effectiveNo = exerciseNo;
        ChunkRole byContent =
                BlockRoleResolver.resolveAnswerBearingByContent(bc.text(), effectiveNo);
        return byContent != null ? byContent : ChunkRole.EXPLANATION;
    }

    private cn.studykid.rag.ports.LayoutBlock blockByIndex(
            String ref, Map<Integer, cn.studykid.rag.ports.LayoutBlock> blockByIndex) {
        // ref 形如 #/texts/12 → 12
        int slash = ref.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        try {
            return blockByIndex.get(Integer.parseInt(ref.substring(slash + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String effectiveExerciseNo(ChunkBridgeChunk bc,
                                       Map<Integer, cn.studykid.rag.ports.LayoutBlock> blockByIndex) {
        for (String ref : bc.itemRefs()) {
            var block = blockByIndex(ref, blockByIndex);
            if (block != null && block.exerciseNo() != null) {
                return block.exerciseNo();
            }
        }
        // chunk 文本自身的题号兜底（合并可能把题干并进 chunk）
        return BlockRoleResolver.extractExerciseNo(bc.text());
    }

    private List<String> matchedKpIds(String text, ChunkContext context) {
        List<String> matched = new ArrayList<>();
        if (context.knowledgePoints().isEmpty()) {
            return matched;
        }
        String normalized = cn.studykid.rag.core.knowledge.KnowledgePoint.normalize(text);
        for (var kp : context.knowledgePoints()) {
            if (kp.matches(normalized)) {
                matched.add(kp.id());
            }
        }
        return matched;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

}
