package cn.studykid.rag.application.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 摄取任务编排器（方案 §3.1 ③ / 设计 §3.2 / §4.2）。
 *
 * <p><b>⚠️ 本类的核心职责是「把 job 的一次尝试跑完并如实回写状态」</b>：
 * 它<b>不</b>决定用什么解析器（那是装配方的事），
 * <b>不</b>做审核判定（自动化终点必须是 PENDING，RD-02）。它只做三件事：
 * <ol>
 *   <li>按阶段推进 job 状态（PARSING → CHUNKING → EMBEDDING → INDEXING → SUCCEEDED）；</li>
 *   <li>把管道产出的片段<b>整册原子落库</b>；</li>
 *   <li>任何异常都转成 job FAILED + 可读的 {@code error_detail}，绝不吞掉。</li>
 * </ol>
 *
 * <p><b>⚠️ 整册原子性（方案硬约束「整册失败，不部分入库」）</b>：
 * 片段落库在<b>单次编排调用</b>内完成，任一环节抛异常即整册放弃——
 * 半本教材入库会把「数据缺失」伪装成「知识边界」，这是比失败更坏的结局。
 * 因 {@link CorpusRepository#saveChunkAndEmbedding} 逐条提交，
 * 编排器在失败时<b>不做补偿删除</b>（片段尚无 {@code ver_status}，
 * 来源仍 PENDING、{@code retrievable=false}，不会进入检索），
 * 但会把 job 置 FAILED 并在 {@code error_detail} 标注「可能残留部分片段」，
 * 供运维在 S3 的失败处置区识别。
 *
 * <p><b>⚠️ S1 阶段的诚实边界</b>：解析器是占位实现
 * （{@code LocalDeterministicDocumentParserPort}），产出的是糙结构；
 * 本阶段的目标是<b>把 job 全生命周期跑通</b>（骨架验收），
 * 切分质量由 S2 的 Docling + 规则移植决定。
 */
public class IngestJobOrchestrator {

    private final IngestJobPort jobPort;
    private final IngestionPipeline pipeline;
    private final CorpusRepository corpusRepository;
    private final EmbeddingPort embeddingPort;
    private final TenantScopedIngestSource sourcePort;

    /**
     * 分词函数（由装配方注入）。
     *
     * <p><b>⚠️ 为什么用 {@code Function} 而不是直接调实现类</b>：
     * 分词器住在 {@code rag-infrastructure}，而本类在 {@code rag-application}——
     * 后者<b>不依赖</b>前者（依赖方向是 infrastructure → application）。
     * 用函数注入既保持方向不变，又能让装配方保证「入库分词」与
     * 「检索分词」是同一个实现（口径同源，见 {@code ChineseTokenizer}）。
     */
    private final java.util.function.Function<String, String> tokenizer;

    public IngestJobOrchestrator(@NonNull IngestJobPort jobPort,
                                 @NonNull IngestionPipeline pipeline,
                                 @NonNull CorpusRepository corpusRepository,
                                 @NonNull EmbeddingPort embeddingPort,
                                 @NonNull TenantScopedIngestSource sourcePort,
                                 java.util.function.Function<String, String> tokenizer) {
        this.jobPort = jobPort;
        this.pipeline = pipeline;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.sourcePort = sourcePort;
        this.tokenizer = tokenizer;
    }

    /**
     * 执行一次 job（由消费循环调用）。
     *
     * <p>⚠️ 本方法<b>不抛出</b>业务异常：所有失败都转成 job FAILED 回写，
     * 让消费循环继续处理下一条，而不是被一条坏数据卡死整条队列。
     *
     * @param job 已被领取（处于 PARSING）的 job
     */
    public void run(@NonNull IngestJob job) {
        String jobId = job.jobId();
        String tenantId = job.tenantId();
        // ⚠️ 载体 ID 提到 try 外：失败时若已取到载体，须把载体也标成 FAILED
        //    （否则「解析失败的载体」面板永远为空 —— 没人置 FAILED）。
        String documentId = null;
        try {
            // ① 取载体内容（解析输入）
            IngestSourceView src = sourcePort.load(tenantId, job.sourceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "来源不存在或不可见：" + job.sourceId()));
            documentId = src.documentId();
            byte[] content = src.content()
                    .orElseThrow(() -> new IllegalStateException(
                            "来源下无可用载体内容（object_ref 缺失或不可读）：" + job.sourceId()));

            // ② PARSING：解析 + 切分（占位解析器在 S1 承担此段）
            jobPort.updateStage(tenantId, jobId, "PARSING 30");
            List<Chunk> chunks = pipeline.ingest(
                    src.documentId(), src.title(), src.edition(), content, src.knowledgePoints());
            if (chunks.isEmpty()) {
                // ⚠️ 空产出视为失败而非成功：切出 0 片段意味着解析/清洗把内容全丢了，
                //    报 SUCCEEDED 会让「这本教材录完了」成为假象。
                throw new IllegalStateException("解析后产出 0 个片段（内容可能全部被判为噪声）");
            }
            jobPort.advance(tenantId, jobId, IngestJobStatus.CHUNKING, "CHUNKING 60");

            // ③ EMBEDDING：逐片段嵌入 + 原子落库（片段与向量同事务）
            jobPort.advance(tenantId, jobId, IngestJobStatus.EMBEDDING, "EMBEDDING 0");
            int done = 0;
            for (Chunk chunk : chunks) {
                float[] vector = embedWithRetry(chunk.text());
                corpusRepository.saveChunkAndEmbedding(
                        tenantId, chunk, src.editionId(), vector, embeddingPort.modelVersion());
                done++;
                if (done % 20 == 0) {
                    jobPort.updateStage(tenantId, jobId, "EMBEDDING " + (done * 100 / chunks.size()));
                }
            }

            // ④ INDEXING：词法通道回填（向量已在落库时写入，此处补分词列）。
            //    ⚠️ 必须传**已分词的空白分隔串**，而非原文：检索期的
            //       `to_tsvector('simple', text_jieba)` 直接对「空格切好的词序列」建索引，
            //       塞原文进去会让中文整句变成一个 token，词法通道等于失效。
            //    ⚠️ 分词器与检索期同源（{@code ChineseTokenizer}），不得另写一套切词逻辑。
            jobPort.advance(tenantId, jobId, IngestJobStatus.INDEXING, "INDEXING");
            for (Chunk chunk : chunks) {
                corpusRepository.updateJiebaTokens(tenantId, chunk.id(), tokenizer.apply(chunk.text()));
            }

            // ⑤ 载体状态回写：片段与向量都已落库 → 载体解析完成。
            //    ⚠️ 此前**从不回写** rag_document.parse_status：载体行永远停在 PENDING，
            //    管理面「载体按解析状态」恒为 PENDING、「解析失败的载体」列表恒为空，
            //    等于管理面看不到真实解析状态（2026-11-03 实测）。
            corpusRepository.markDocumentParseStatus(tenantId, src.documentId(), "CHUNKED");

            jobPort.markSucceeded(tenantId, jobId, chunks.size(),
                    src.parserVersion(), embeddingPort.modelVersion());
        } catch (Exception e) {
            // ⚠️ 失败必须带可读原因（IngestJob 不变式），且明确标注可能的部分残留。
            String detail = "阶段 " + stageOf(jobId) + " 失败：" + rootMessage(e)
                    + "（整册未完成；若为 EMBEDDING 之后失败，可能残留部分片段，"
                    + "来源仍为 PENDING 且 retrievable=false，不会进入检索）";
            jobPort.markFailed(tenantId, jobId, detail);
            // ⚠️ 载体也标 FAILED：管理面的「解析失败的载体」面板以 parse_status='FAILED' 为口径，
            //    没人置 FAILED 那个面板就永远是空的。失败回写失败不应掩盖原始错误。
            if (documentId != null) {
                try {
                    corpusRepository.markDocumentParseStatus(tenantId, documentId, "FAILED");
                } catch (RuntimeException ignored) {
                    // 载体状态回写失败不影响主流程（原始错误已写进 job.error_detail）。
                }
            }
        }
    }

    /**
     * 嵌入调用：瞬时失败指数退避重试 3 次（方案 §4 Z11 / §7.1 瞬时类）。
     *
     * <p>⚠️ 只重试<b>瞬时类</b>（超时/连接失败），不对参数错误重试——
     * 重试注定失败的错误只是把失败推迟 3 倍时间。
     */
    private float[] embedWithRetry(String text) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                // ⚠️ EmbeddingPort.embed 是**批量**接口（List<String> → List<float[]>）：
                //    逐条调用会丢掉批处理带来的吞吐收益，且空返回需显式拦。
                return embeddingPort.embed(List.of(text)).get(0);
            } catch (RuntimeException e) {
                last = e;
                if (!isTransient(e)) {
                    throw e;
                }
                try {
                    // 100ms → 200ms → 400ms
                    Thread.sleep(100L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("嵌入重试被中断", ie);
                }
            }
        }
        throw new IllegalStateException("嵌入连续失败 3 次", last);
    }

    /**
     * 判定是否瞬时类异常。
     *
     * <p>⚠️ 采用「白名单式」判定：只有明确的超时/连接类才重试，
     * 其余（参数、维度不符、鉴权）一律不重试——它们重试也不会变好。
     */
    private static boolean isTransient(RuntimeException e) {
        String name = e.getClass().getSimpleName().toLowerCase();
        return name.contains("timeout") || name.contains("connect")
                || name.contains("socket") || name.contains("temporar");
    }

    private String stageOf(String jobId) {
        IngestJob current = jobPort.findById(jobId);
        return current == null ? "UNKNOWN" : current.status().name();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * 编排所需的来源视图（由装配方按租户读取后传入）。
     *
     * @param documentId      载体 ID
     * @param title           载体标题
     * @param edition         版本坐标
     * @param editionId       版本坐标主键
     * @param content         载体原始字节
     * @param knowledgePoints 本来源可见的知识点（用于挂接）
     * @param parserVersion   解析器版本标识
     */
    public record IngestSourceView(
            @NonNull String documentId,
            @NonNull String title,
            @NonNull EditionCoordinate edition,
            @NonNull String editionId,
            java.util.Optional<byte[]> content,
            @NonNull List<KnowledgePoint> knowledgePoints,
            @NonNull String parserVersion
    ) {
        /**
         * 便捷构造：纯文本内容。
         *
         * @param text 文本
         * @return 以 UTF-8 编码的载体视图
         */
        public static IngestSourceView ofText(String documentId, String title,
                                              EditionCoordinate edition,
                                              String editionId, String text,
                                              List<KnowledgePoint> kps) {
            return new IngestSourceView(documentId, title, edition, editionId,
                    java.util.Optional.of(text.getBytes(StandardCharsets.UTF_8)), kps, "text-utf8");
        }
    }

    /** 供装配方实现：按租户加载编排所需视图。 */
    @FunctionalInterface
    public interface TenantScopedIngestSource {
        /**
         * 加载来源视图。
         *
         * @param tenantId 租户 ID
         * @param sourceId 来源 ID
         * @return 视图（不存在时返回空）
         */
        java.util.Optional<IngestSourceView> load(@NonNull String tenantId,
                                                  @NonNull String sourceId);
    }
}
