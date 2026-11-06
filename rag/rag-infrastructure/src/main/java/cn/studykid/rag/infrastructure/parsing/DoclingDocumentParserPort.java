package cn.studykid.rag.infrastructure.parsing;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

import java.util.List;

/**
 * Docling 解析适配器（V1.0.2 S2-1 / 设计 §3.1 / 方案 §4 Z5a）。
 *
 * <p><b>✅ T-4 已确认并实现</b>（2026-11-02 实测 {@code docling-serve}）：
 * <ul>
 *   <li><b>部署形态</b>：官方镜像 {@code quay.io/docling-project/docling-serve}，独立容器 + HTTP；</li>
 *   <li><b>API</b>：{@code POST /v1/convert/file}，multipart 字段 {@code files}（复数），
 *       必须带 {@code to_formats=json}；</li>
 *   <li><b>响应</b>：{@code document.json_content} 下是 DoclingDocument，
 *       正文顺序由 {@code body.children} 的 {@code $ref} 序列决定；</li>
 *   <li><b>映射</b>：{@link DoclingResponseMapper} 负责 DOC → {@link LayoutBlock}。</li>
 * </ul>
 *
 * <p><b>⚠️ 不静默降级</b>：未配置或调用失败一律抛 {@link DoclingUnavailableException}，
 * 由编排器置 job FAILED——<b>绝不</b>回落到占位解析器（见下）。
 *
 * <p><b>⚠️⚠️ 分段提交（整册同步不可行）</b>：实测沪教版六年级下册
 * （148 页 / 13.4 MB）整册同步解析需 <b>710 秒</b>，已逼近服务端
 * {@code DOCLING_SERVE_MAX_SYNC_WAIT} 上限且远超任何 HTTP 客户端的合理超时。
 * 实测 20 页/段耗时 36~54 秒，故本适配器<b>按页段提交并合并</b>：
 * <ul>
 *   <li>单段失败 → 整册失败（<b>不部分入库</b>，D04 §1）；</li>
 *   <li>合并时按页码顺序拼接，各段的 {@code charStart/charEnd} 重新连续化
 *       （否则跨段会出现字符区间空洞，破坏「可回取原文」这条门禁证据）；</li>
 *   <li>段数上限保护：防止页数极多的文件把服务打满。</li>
 * </ul>
 * 调用方（{@link cn.studykid.rag.ports.DocumentParserPort} 契约）零感知。
 *
 * <p><b>⚠️⚠️ 为什么绝不回落到占位解析器</b>：
 * 占位解析器的答案区判定是关键词启发式（"答案/解/解析"即判答案区），
 * 而答案区误判的代价是<b>泄题</b>（D04 §3.1）。若 Docling 不可用时静默回落，
 * 系统会在「看起来正常」的状态下生产出可能泄题的语料——
 * 这类失败比直接报错危险一个数量级。故本类对可用性采取 fail-closed。
 *
 * <p><b>⚠️ 与 {@link DocumentParserPort} 契约的对应关系</b>：
 * 端口要求输出<b>带版面结构的块序列</b>（标题层级/题号/答案区/页码/字符区间），
 * 不是一坨纯文本。因此 Docling 的 {@code layout} 与 {@code reading order}
 * 必须被完整映射到 {@link cn.studykid.rag.ports.LayoutBlock}，
 * 映射失败（如缺 pageNumber）应当报错而不是瞎填默认值——
 * 瞎填的页码会让「可回取原文」这条门禁证据（D09 G1 ③）整体失效。
 */
public class DoclingDocumentParserPort implements DocumentParserPort {

    private static final Logger log = LoggerFactory.getLogger(DoclingDocumentParserPort.class);

    /**
     * 解析器版本标识（写入 {@code rag_document.parser_version} 与 job，用于可复现性留痕）。
     *
     * <p>⚠️ 当前为占位串，T-4 确认 Docling 版本后替换为真实版本号（如 {@code docling-2.x}）。
     * 版本号必须真实：它决定了「这批语料是用哪版解析器切的」，
     * 换版后需要重灌，而重灌的依据就是这个字段。
     */
    public static final String PARSER_VERSION = "docling-serve-1.x";

    /**
     * 每段页数（默认 20）。
     *
     * <p>⚠️ 取值依据（实测）：20 页/段耗时 36~54 秒，
     * 单段远低于任何合理超时；段数太多又会增加往返与失败面。
     */
    public static final int DEFAULT_PAGES_PER_SEGMENT = 20;

    /**
     * 最大段数（默认 60 = 1200 页）。
     *
     * <p>⚠️ 保护措施：页数异常的文件（误传、拼接稿）不应把服务打满。
     */
    public static final int DEFAULT_MAX_SEGMENTS = 60;

    private final String baseUrl;
    private final int timeoutSeconds;

    /** Docling 响应 → LayoutBlock 的映射器。 */
    private final DoclingResponseMapper mapper;

    /** 每段页数（≤0 表示不分段，整册一次提交）。 */
    private final int pagesPerSegment;

    /** 最大段数（超出则拒绝，防打满解析服务）。 */
    private final int maxSegments;

    /**
     * 复用的 HTTP 客户端（连接池由 JDK 管理）。
     *
     * <p><b>⚠️ 必须强制 HTTP/1.1</b>：JDK 的 {@code HttpClient} 默认优先 HTTP/2，
     * 会先发 h2c 升级请求；而 docling-serve 的 uvicorn <b>不认这个升级</b>，
     * 直接回 {@code Invalid HTTP request received.} + 400。
     * 实测（2026-11-02）：同样的 multipart 请求体，强制 1.1 后立即 200。
     * 这是「请求体完全正确却 400」的真实原因，不改协议版本无论怎么调 body 都没用。
     */
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /**
     * @param baseUrl        Docling 服务基址（空串表示未配置）
     * @param timeoutSeconds 单册解析超时（秒）
     */
    public DoclingDocumentParserPort(@NonNull String baseUrl, int timeoutSeconds) {
        this(baseUrl, timeoutSeconds, new DoclingResponseMapper(
                new tools.jackson.databind.ObjectMapper()),
                DEFAULT_PAGES_PER_SEGMENT, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * @param baseUrl         Docling 服务基址
     * @param timeoutSeconds  单次 HTTP 请求超时
     * @param pagesPerSegment 每段页数（≤0 = 不分段）
     * @param maxSegments     最大段数
     */
    public DoclingDocumentParserPort(@NonNull String baseUrl, int timeoutSeconds,
                                     int pagesPerSegment, int maxSegments) {
        this(baseUrl, timeoutSeconds, new DoclingResponseMapper(
                        new tools.jackson.databind.ObjectMapper()),
                pagesPerSegment, maxSegments);
    }

    /**
     * @param baseUrl         Docling 服务基址
     * @param timeoutSeconds  单次 HTTP 请求超时
     * @param mapper          响应映射器
     * @param pagesPerSegment 每段页数（≤0 = 不分段）
     * @param maxSegments     最大段数
     */
    public DoclingDocumentParserPort(@NonNull String baseUrl, int timeoutSeconds,
                                     DoclingResponseMapper mapper,
                                     int pagesPerSegment, int maxSegments) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.mapper = mapper == null ? new DoclingResponseMapper(
                new tools.jackson.databind.ObjectMapper()) : mapper;
        this.pagesPerSegment = pagesPerSegment;
        this.maxSegments = Math.max(1, maxSegments);
    }

    /**
     * @param baseUrl        Docling 服务基址
     * @param timeoutSeconds 单册解析超时
     * @param mapper         响应映射器
     */
    public DoclingDocumentParserPort(@NonNull String baseUrl, int timeoutSeconds,
                                     DoclingResponseMapper mapper) {
        this(baseUrl, timeoutSeconds, mapper,
                DEFAULT_PAGES_PER_SEGMENT, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * 是否已配置（未配置时不应装配本适配器，装配方据此决定）。
     *
     * @return 已配置返回 true
     */
    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    @Override
    public @NonNull ParsedDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        return parseWithRaw(documentId, content).parsed();
    }

    /**
     * 解析并<b>同时返回原始响应</b>（RAG-D26 §2.4 数据流约束①的落点）。
     *
     * <p>⚠️ 为什么需要它：分块桥（HybridChunker）必须复用<b>解析阶段</b>的
     * DoclingDocument JSON——二次解析会让 chunk 的 {@code item_refs}
     * 与块对不上，角色回填必然错位。本方法让「一次 HTTP 往返同时产出
     * 块序列与可复用的原始 JSON」，调用方（{@code DoclingPremiseBackend}）
     * 不需要第二个请求，也不需要自行拼 multipart。
     *
     * <p>⚠️ 分段路径下 raw 保存的是<b>最后一段</b>的 json_content——
     * 分段产物合并后的块序列与「整册 DoclingDocument」不再一一对应
     * （各段独立映射、char 区间已重编），此时 raw <b>不可</b>交给分块桥
     * （item_refs 会错位）。调用方据此判断：分段文档 → HybridChunker
     * fail-closed（明确报错，不是静默换策略）。
     *
     * @param documentId 文档 ID
     * @param content    PDF 字节
     * @return 解析结果（块序列 + json_content 原文 + 是否整册单响应）
     */
    public @NonNull ParseWithRaw parseWithRaw(@NonNull String documentId,
                                              @NonNull byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (!isConfigured()) {
            // ⚠️ fail-closed：未配置时不返回空文档、不回落到占位解析器。
            throw new DoclingUnavailableException(
                    "Docling 解析服务未配置（rag.docling.base-url 为空）");
        }

        // ① 先探测总页数（决定分段方案）
        int totalPages = probePageCount(documentId, content);
        if (pagesPerSegment <= 0 || totalPages <= pagesPerSegment) {
            // 小册：整册一次提交
            log.debug("[RAG] Docling 解析 documentId={} bytes={} 页数={} → 整册提交",
                    documentId, content.length, totalPages);
            String raw = callDocling(documentId, content, 1, totalPages);
            List<LayoutBlock> blocks = mapper.map(raw);
            return new ParseWithRaw(documentId, blocks,
                    mapper.extractJsonContent(raw), true);
        }

        // ② 大册：按页段提交并合并
        int segmentCount = (totalPages + pagesPerSegment - 1) / pagesPerSegment;
        if (segmentCount > maxSegments) {
            throw new DoclingUnavailableException(
                    "文档页数 " + totalPages + " 超出分段上限（"
                            + maxSegments + " 段 × " + pagesPerSegment + " 页）——"
                            + "疑似文件异常，拒绝发起会打满解析服务的请求");
        }
        log.info("[RAG] Docling 分段解析 documentId={} 总页数={} 段数={} 每段={}页",
                documentId, totalPages, segmentCount, pagesPerSegment);

        List<LayoutBlock> merged = new java.util.ArrayList<>();
        String lastRaw = null;
        for (int seg = 0; seg < segmentCount; seg++) {
            int from = seg * pagesPerSegment + 1;
            int to = Math.min(totalPages, (seg + 1) * pagesPerSegment);
            // ⚠️ 单段失败 → 整册失败（不部分入库，D04 §1）：
            //    半本教材入库会把「数据缺失」伪装成「知识边界」。
            String body = callDocling(documentId, content, from, to);
            List<LayoutBlock> segBlocks = mapper.map(body);
            merged.addAll(segBlocks);
            lastRaw = body;
            log.debug("[RAG] Docling 分段 {}/{} 页 {}-{} → {} 块",
                    seg + 1, segmentCount, from, to, segBlocks.size());
        }
        // ③ 合并后重新连续化字符区间（各段独立映射 → 区间会重叠/有空洞）
        List<LayoutBlock> continuous = rebaseCharOffsets(merged);
        if (continuous.isEmpty()) {
            throw new DoclingUnavailableException(
                    "Docling 分段解析产出 0 个块（documentId=" + documentId + "）");
        }
        // ⚠️ 分段路径：raw 取最后一段且 wholeDocument=false（不可用于分块桥，见方法注释）
        return new ParseWithRaw(documentId, continuous,
                lastRaw == null ? null : mapper.extractJsonContent(lastRaw), false);
    }

    /**
     * 解析结果 + 原始 DoclingDocument（RAG-D26 §2.4）。
     *
     * @param documentId    文档 ID
     * @param blocks        块序列
     * @param rawJson       最后一次响应提取出的 json_content（无则 null）
     * @param wholeDocument rawJson 是否为「整册单响应」（分段路径为 false，
     *                      此时不具备交给分块桥的前提）
     */
    public record ParseWithRaw(
            @NonNull String documentId,
            @NonNull List<LayoutBlock> blocks,
            String rawJson,
            boolean wholeDocument) {

        /**
         * @return 本结果对应的 ParsedDocument
         */
        public @NonNull ParsedDocument parsed() {
            return new ParsedDocument(documentId, blocks);
        }
    }

    /**
     * 探测 PDF 总页数。
     *
     * <p>⚠️ 用最轻量的方式探测（只读 PDF 尾部与对象），<b>不引入 PDF 解析依赖</b>：
     * 本项目的依赖面已经不小，为「数页数」引入 pdfbox 并不划算。
     * 探测失败时返回 0 → 调用方按「整册提交」处理，行为退化但不报错。
     *
     * @param documentId 文档 ID
     * @param content    PDF 字节
     * @return 总页数；探测失败返回 0
     */
    private int probePageCount(String documentId, byte[] content) {
        try {
            String raw = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/Type\\s*/Pages[^>]*?/Count\\s+(\\d+)")
                    .matcher(raw);
            int max = 0;
            while (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
            if (max > 0) {
                return max;
            }
            // 兜底：数 /Type /Page（排除 /Pages）
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("/Type\\s*/Page[^s]")
                    .matcher(raw);
            int count = 0;
            while (m2.find()) {
                count++;
            }
            return count;
        } catch (RuntimeException e) {
            log.warn("[RAG] 探测 {} 页数失败，按整册提交处理", documentId, e);
            return 0;
        }
    }

    /**
     * 把各段独立映射出的块合并成<b>字符区间连续</b>的序列。
     *
     * <p><b>⚠️ 为什么必须做这一步</b>：每段映射时 {@code charStart/charEnd} 都从 0 开始，
     * 直接拼接会得到「0-100, 0-250, 0-180…」这样互相重叠的区间。
     * 字符区间是「可回取原文」这条门禁证据（D09 G1 ③）的依据，
     * 重叠的区间会让回取逻辑取到错误位置甚至越界。
     *
     * @param blocks 各段的块（按段顺序）
     * @return 字符区间连续的新块序列
     */
    private List<LayoutBlock> rebaseCharOffsets(List<LayoutBlock> blocks) {
        List<LayoutBlock> out = new java.util.ArrayList<>(blocks.size());
        int cursor = 0;
        for (LayoutBlock b : blocks) {
            int len = Math.max(0, b.charEnd() - b.charStart());
            out.add(new LayoutBlock(
                    b.type(), b.text(), b.pageNumber(), b.headingLevel(), b.sectionPath(),
                    b.exerciseNo(), b.inAnswerZone(), cursor, cursor + len));
            cursor += len;
        }
        return out;
    }

    /**
     * 调用 Docling 的 {@code POST /v1/convert/file}。
     *
     * <p><b>⚠️ 三个实测确认的细节，缺一都会失败</b>：
     * <ol>
     *   <li>路径是 {@code /v1/convert/file}（<b>不是</b> {@code /v1/document/convert}）；</li>
     *   <li>multipart 字段名是 <b>{@code files}</b>（复数）；</li>
     *   <li>必须带 {@code to_formats=json}，否则响应里没有 {@code json_content}。</li>
     * </ol>
     *
     * @param documentId 文档 ID（仅用于日志）
     * @param content    PDF 字节
     * @param pageFrom   起始页（1 基；≤0 表示不限）
     * @param pageTo     结束页（1 基）
     * @return 响应体
     */
    private String callDocling(String documentId, byte[] content, int pageFrom, int pageTo) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String uri = base + "/v1/convert/file";
        String boundary = "----RagDocling" + java.util.UUID.randomUUID().toString().replace("-", "");

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            // 字段①：文件
            bos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write(("Content-Disposition: form-data; name=\"files\"; filename=\""
                    + documentId + ".pdf\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write("Content-Type: application/pdf\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write(content);
            bos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 字段②：输出格式（⚠️ 不加就拿不到 json_content）
            bos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write("Content-Disposition: form-data; name=\"to_formats\"\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write("json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 字段③：页范围（⚠️ 实测：page_range 是**两个独立字段**，
            //         写成 "1-20" 会 422 —— 服务端要的是 int 数组 [from, to]）
            if (pageFrom > 0) {
                bos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write("Content-Disposition: form-data; name=\"page_range\"\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write(String.valueOf(pageFrom).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write("Content-Disposition: form-data; name=\"page_range\"\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write(String.valueOf(Math.max(pageFrom, pageTo))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            bos.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new DoclingUnavailableException("构造 multipart 请求失败", e);
        }

        java.net.http.HttpRequest request;
        try {
            request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(uri))
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                    .build();
        } catch (RuntimeException e) {
            throw new DoclingUnavailableException("Docling 地址非法：" + uri, e);
        }

        try {
            java.net.http.HttpResponse<String> resp = http.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                // ⚠️ 4xx/5xx 都算不可用：重试策略由上游决定，本类只如实报告。
                throw new DoclingUnavailableException(
                        "Docling 返回 HTTP " + resp.statusCode() + "：" + truncate(resp.body()));
            }
            return resp.body();
        } catch (java.io.IOException e) {
            // ⚠️ 连接/超时是**瞬时类**，上游可重试；但要带上原始异常类型便于判断。
            throw new DoclingUnavailableException(
                    "调用 Docling 失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoclingUnavailableException("调用 Docling 被中断", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    /**
     * Docling 不可用（未配置或调用失败）。
     *
     * <p>⚠️ 独立异常类型而非 {@code IllegalStateException}：
     * 编排器需要区分「瞬时故障（可重试）」与「配置缺失（重试也不会好）」，
     * 两者在失败处置区的提示文案完全不同。
     */
    public static class DoclingUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message 原因
         */
        public DoclingUnavailableException(String message) {
            super(message);
        }

        /**
         * @param message 原因
         * @param cause   底层异常
         */
        public DoclingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
