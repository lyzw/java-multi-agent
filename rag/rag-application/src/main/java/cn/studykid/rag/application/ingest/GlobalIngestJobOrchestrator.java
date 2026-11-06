package cn.studykid.rag.application.ingest;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.application.ingest.IngestJobOrchestrator.IngestSourceView;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 平台档（全局桶）摄取任务编排器（G1 / RAG-D11 §1.3）。
 *
 * <p><b>⚠️ 与 {@link IngestJobOrchestrator} 的关系</b>：职责完全镜像——
 * 「把 job 的一次尝试跑完并如实回写状态」——但桶固定为<b>全局</b>：
 * 所有落库与回写都走<b>不带租户参数</b>的全局方法
 * （{@code *InGlobalBucket} / {@code *Global}），由实现在运维会话（{@code rag_ops}）
 * 上钉死 {@code rag_global_tenant_id()}。调用方连「写进别的桶」都拼不出来。
 *
 * <p><b>⚠️ 为什么不复用租户编排器 + 传一个哨兵租户 ID</b>：
 * 应用角色对全局行是「静默 0 行」而非报错（那是越权闸门本身），
 * 传哨兵租户会让 {@code set_config('app.tenant_id', 哨兵)} 直接被
 * {@code rag_current_tenant()} 的越权封堵（SQLSTATE 42501）拦下——全局写入
 * <b>只能</b>走 BYPASSRLS 的运维会话，方法签名上就不应出现租户形态。
 *
 * <p>核心职责（同租户侧）：① 阶段推进；② 整册原子落库；③ 异常转 FAILED + 可读原因。
 */
public class GlobalIngestJobOrchestrator {

    private final IngestJobPort jobPort;
    private final IngestionPipeline pipeline;
    private final CorpusRepository corpusRepository;
    private final EmbeddingPort embeddingPort;
    private final GlobalIngestSource sourcePort;
    private final java.util.function.Function<String, String> tokenizer;

    public GlobalIngestJobOrchestrator(@NonNull IngestJobPort jobPort,
                                       @NonNull IngestionPipeline pipeline,
                                       @NonNull CorpusRepository corpusRepository,
                                       @NonNull EmbeddingPort embeddingPort,
                                       @NonNull GlobalIngestSource sourcePort,
                                       java.util.function.Function<String, String> tokenizer) {
        this.jobPort = jobPort;
        this.pipeline = pipeline;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.sourcePort = sourcePort;
        this.tokenizer = tokenizer;
    }

    /**
     * 执行一次全局 job（由全局消费循环调用）。
     *
     * <p>⚠️ 与租户侧同样<b>不抛出</b>业务异常：所有失败都转成 job FAILED 回写，
     * 让消费循环继续处理下一条，而不是被一条坏数据卡死整条队列。
     *
     * @param job 已被领取（处于 PARSING）的全局 job
     */
    public void run(@NonNull IngestJob job) {
        String jobId = job.jobId();
        // ⚠️ 本地跟踪当前阶段：全局 job 不能用租户侧那种「读回 job 状态」的方式报阶段
        //    （findById 在应用角色下读不到全局行），故用局部变量精确记录到哪一步失败。
        String stage = "CLAIMED";
        String documentId = null;
        try {
            // ① 取载体内容（解析输入）——全局来源加载器走运维会话读全局桶。
            Optional<IngestSourceView> maybe = sourcePort.load(job.sourceId());
            IngestSourceView src = maybe.orElseThrow(() -> new IllegalStateException(
                    "全局来源不存在或不可见：" + job.sourceId()));
            documentId = src.documentId();
            byte[] content = src.content()
                    .orElseThrow(() -> new IllegalStateException(
                            "全局来源下无可用载体内容（object_ref 缺失或不可读）：" + job.sourceId()));

            // ② PARSING：解析 + 切分
            stage = "PARSING";
            jobPort.updateStageGlobal(jobId, "PARSING 30");
            List<Chunk> chunks = pipeline.ingest(
                    src.documentId(), src.title(), src.edition(), content, src.knowledgePoints());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("解析后产出 0 个片段（内容可能全部被判为噪声）");
            }
            stage = "CHUNKING";
            jobPort.advanceGlobal(jobId, IngestJobStatus.CHUNKING, "CHUNKING 60");

            // ③ EMBEDDING：逐片段嵌入 + 原子落库（整片段 + 向量写入全局桶）
            stage = "EMBEDDING";
            jobPort.advanceGlobal(jobId, IngestJobStatus.EMBEDDING, "EMBEDDING 0");
            int done = 0;
            for (Chunk chunk : chunks) {
                float[] vector = embedWithRetry(chunk.text());
                corpusRepository.saveChunkAndEmbeddingInGlobalBucket(
                        chunk, src.editionId(), vector, embeddingPort.modelVersion());
                done++;
                if (done % 20 == 0) {
                    jobPort.updateStageGlobal(jobId, "EMBEDDING " + (done * 100 / chunks.size()));
                }
            }

            // ④ INDEXING：词法通道回填（全局桶）
            stage = "INDEXING";
            jobPort.advanceGlobal(jobId, IngestJobStatus.INDEXING, "INDEXING");
            for (Chunk chunk : chunks) {
                corpusRepository.updateJiebaTokensInGlobalBucket(
                        chunk.id(), tokenizer.apply(chunk.text()));
            }

            // ⑤ 载体状态回写：片段与向量都已落入全局桶 → 载体解析完成。
            corpusRepository.markDocumentParseStatusInGlobalBucket(src.documentId(), "CHUNKED");

            // ⑥ 切分留痕（RAG-D26 阶段 3.2）：同租户侧口径——premise 模式写策略，
            //    旧内置切分传 null（「未采集」，不伪造）。
            jobPort.markSucceededGlobal(jobId, chunks.size(),
                    src.parserVersion(), embeddingPort.modelVersion(),
                    pipeline.chunkPolicyTrace(), pipeline.chunkPolicyConfigTrace());
        } catch (Exception e) {
            // ⚠️ 失败必须带可读原因（IngestJob 不变式），且明确标注可能的部分残留。
            String detail = "全局摄入阶段 " + stage + " 失败：" + rootMessage(e)
                    + "（整册未完成；若为 EMBEDDING 之后失败，可能残留部分全局片段，"
                    + "来源仍为 PENDING 且 retrievable=false，不会进入检索）";
            jobPort.markFailedGlobal(jobId, detail);
            // ⚠️ 载体也标 FAILED：管理面的「解析失败的载体」面板以 parse_status='FAILED' 为口径。
            if (documentId != null) {
                try {
                    corpusRepository.markDocumentParseStatusInGlobalBucket(documentId, "FAILED");
                } catch (RuntimeException ignored) {
                    // 载体状态回写失败不影响主流程（原始错误已写进 job.error_detail）。
                }
            }
        }
    }

    /**
     * 嵌入调用：瞬时失败指数退避重试 3 次（同租户侧语义）。
     */
    private float[] embedWithRetry(String text) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return embeddingPort.embed(List.of(text)).get(0);
            } catch (RuntimeException e) {
                last = e;
                if (!isTransient(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(100L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("嵌入重试被中断", ie);
                }
            }
        }
        throw new IllegalStateException("嵌入连续失败 3 次", last);
    }

    private static boolean isTransient(RuntimeException e) {
        String name = e.getClass().getSimpleName().toLowerCase();
        return name.contains("timeout") || name.contains("connect")
                || name.contains("socket") || name.contains("temporar");
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
     * 全局来源加载契约：按<b>全局</b>来源 ID 加载编排所需视图（走运维会话）。
     *
     * <p>⚠️ <b>没有租户参数</b>：全局桶的读取在 {@code rag_ops} 会话上做，
     * 谓词写死 {@code tenant_id = rag_global_tenant_id()}——方法签名上不暴露租户形态。
     */
    @FunctionalInterface
    public interface GlobalIngestSource {
        /**
         * 加载全局来源视图。
         *
         * @param sourceId 全局来源 ID
         * @return 视图（不存在时返回空）
         */
        Optional<IngestSourceView> load(@NonNull String sourceId);
    }
}
