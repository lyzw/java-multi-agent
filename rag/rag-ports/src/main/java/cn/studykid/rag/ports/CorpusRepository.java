package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 语料持久化端口（RAG-D04 §3 / RAG-D02 §3.2）。
 *
 * <p>入库流水线的落库出口：把切分产物（{@link Chunk}）与其向量<b>原子</b>写入。
 * 与 {@link RetrievalPort} 同层但职责相反——一个写、一个读。
 *
 * <p><b>⚠️ 为什么 chunk 与 embedding 必须同事务</b>（RAG-D03 §1）：
 * 否则会出现「片段已入库但向量未写入」→ 该片段永远召不回（静默漏召回），
 * 或反之「向量已写入但片段不存在」→ 脏数据。原子写入是选 pgvector 的核心原因。
 *
 * <p><b>⚠️ 租户上下文</b>：方法显式接收 {@code tenantId}，由实现在事务内
 * {@code set_config('app.tenant_id', ...)}，使 RLS FORCE 生效。
 * RAG 不自行解析令牌，租户由调用方（platform 工具执行上下文）传入。
 */
public interface CorpusRepository {

    /**
     * 原子写入一个片段与其向量。
     *
     * @param tenantId 租户 ID（写入前 set_config）
     * @param chunk 片段（含 {@link cn.studykid.rag.core.version.EditionCoordinate}；editionId 由调用方解析后传入）
     * @param editionId 版本坐标对应的 {@code rag_edition} 主键
     * @param embedding 向量（维度须与 embedModel 一致）
     * @param embedModel 嵌入模型标识（写入索引元数据，用于代际管理）
     */
    void saveChunkAndEmbedding(@NonNull String tenantId, @NonNull Chunk chunk,
                               @NonNull String editionId, float[] embedding,
                               @NonNull String embedModel);

    /**
     * 回填词法通道分词列（入库流水线在切分后调用，见 RAG-D02 §3.1.2）。
     *
     * @param tenantId 租户 ID
     * @param chunkId 片段 ID
     * @param jiebaTokens 应用层预分词结果（空格分隔）
     */
    void updateJiebaTokens(@NonNull String tenantId, @NonNull String chunkId,
                           @NonNull String jiebaTokens);

    /**
     * 回写**载体**的解析状态（{@code rag_document.parse_status}）。
     *
     * <p>⚠️⚠️ 为什么必须有它：摄入流水线此前只更新「任务」（{@code rag_ingest_job}）的状态，
     * <b>从不回写载体行</b> —— 于是载体行永远停在 {@code PENDING}，
     * 管理面的「载体按解析状态」恒显示 PENDING、「解析失败的载体」列表也永远为空
     * （没人把 {@code parse_status} 置 FAILED），
     * 即<b>管理面看不到真实解析状态</b>（2026-11-03 实测：1589 片段全部入库的教材，
     * 载体行仍是 PENDING）。
     *
     * <p>取值域由 DDL 的 {@code rag_document_parse_status_chk} 约束：
     * {@code PENDING / PARSED / CHUNKED / FAILED}。
     *
     * @param tenantId 租户 ID
     * @param documentId 载体 ID
     * @param parseStatus 目标解析状态（上述四值之一）
     */
    void markDocumentParseStatus(@NonNull String tenantId, @NonNull String documentId,
                                 @NonNull String parseStatus);

    /**
     * 写入或更新片段向量（RAG-D09 R2-1：历史片段向量缺失重索引 / 换模型代际重建）。
     *
     * <p>语义：{@code chunk_id} 已存在则更新其向量与模型标识，不存在则插入。
     * 与 {@link #saveChunkAndEmbedding}（整片段+向量原子插入）互补——
     * 本方法针对「片段已入库、仅向量待补/待重建」的场景，避免整行重插造成的冲突。
     *
     * <p>⚠️ 租户隔离：实现在事务内 {@code set_config('app.tenant_id', ..., true)}，
     * 使 RLS FORCE 对 {@code rag_chunk_embedding} 的 INSERT/UPDATE 同时生效。
     *
     * @param tenantId 租户 ID
     * @param chunkId 片段 ID
     * @param embedding 向量（维度须与 embedModel 一致）
     * @param embedModel 嵌入模型标识（写入索引元数据，用于代际管理）
     */
    void upsertEmbedding(@NonNull String tenantId, @NonNull String chunkId,
                         @NonNull float[] embedding, @NonNull String embedModel);

    /**
     * 写入指定**索引代际**的向量（代际构建器专用，RAG-D13 §3.5.2）。
     *
     * <p><b>⚠️ 为什么需要它而不是复用 {@link #upsertEmbedding}</b>：
     * 后者的语义是「写进当前 ACTIVE 代际」——那是**日常入库**的正确行为。
     * 但代际构建器要写的是一个**尚未激活的 BUILDING 代际**：
     * 若它也写 ACTIVE，重建会污染正在服务的索引（新旧向量混在一个空间，
     * 余弦相似度给出无意义的分数，且无任何报错）。
     *
     * <p>⚠️ 代际号必须由调用方显式给出：构建器知道自己在建第几代，
     * 而仓储层不应去猜——猜错就是写进错误的空间。
     *
     * @param tenantId   租户 ID
     * @param chunkId    片段 ID
     * @param embedding  向量
     * @param embedModel 嵌入模型标识
     * @param generation 目标代际号
     */
    void upsertEmbeddingForGeneration(@NonNull String tenantId, @NonNull String chunkId,
                                      @NonNull float[] embedding, @NonNull String embedModel,
                                      int generation);

    /**
     * 写入**全局桶**指定代际的向量（代际构建器的全局通道）。
     *
     * <p>⚠️ 与 {@link #upsertEmbeddingInGlobalBucket} 的区别同租户侧：
     * 后者写「当前 ACTIVE 代际」（日常入库），本方法写**指定的 BUILDING 代际**（换模型）。
     *
     * <p>⚠️ 无 tenantId 参数：全局写通道跑在 BYPASSRLS 的运维角色上，
     * 桶谓词写死 {@code rag_global_tenant_id()}。
     *
     * @param chunkId    片段 ID（须属全局桶）
     * @param embedding  向量
     * @param embedModel 嵌入模型标识
     * @param generation 目标代际号
     */
    void upsertEmbeddingForGenerationInGlobalBucket(@NonNull String chunkId,
                                                    @NonNull float[] embedding,
                                                    @NonNull String embedModel,
                                                    int generation);

    // ---------------------------------------------------------------------
    // 平台全局桶（RAG-D11 §1.3 / 第二刀 2.5）
    //
    // ⚠️⚠️ 下面两个方法<b>刻意没有 tenantId 参数</b>：
    //   全局桶的写通道跑在 BYPASSRLS 的运维角色上，数据库层不再限制它写哪个桶。
    //   若这里留一个租户参数，「把别的桶写进去」就是一句合法的调用；
    //   现在它在方法签名上就没有形态——桶由方法名决定，不由入参决定。
    //   （与 RagOpsSessionPort#inGlobalCorpusSession 同一个结构性护栏思路。）
    // ---------------------------------------------------------------------

    /**
     * 回填<b>全局桶</b>片段的词法分词列。
     *
     * <p>实现必须走运维数据源会话（{@code rag_ops}）：应用角色 {@code rag_app}
     * 对全局行是「静默 0 行」而非报错，用它写会把「索引成功」变成假报告。
     *
     * @param chunkId 片段 ID（须属全局桶；租户片段不受影响）
     * @param jiebaTokens 应用层预分词结果（空格分隔）
     */
    void updateJiebaTokensInGlobalBucket(@NonNull String chunkId,
                                         @NonNull String jiebaTokens);

    /**
     * 写入或更新<b>全局桶</b>片段的向量。
     *
     * @param chunkId 片段 ID（须属全局桶）
     * @param embedding 向量（维度须与 embedModel 一致）
     * @param embedModel 嵌入模型标识
     */
    void upsertEmbeddingInGlobalBucket(@NonNull String chunkId,
                                       @NonNull float[] embedding,
                                       @NonNull String embedModel);

    // ---------------------------------------------------------------------
    // 平台档摄入：全局桶「整片段 + 向量」原子写入（G1 / RAG-D11 §1.3）
    //
    // ⚠️ 下面两个方法与上面的全局桶方法同源护栏：<b>没有 tenantId 参数</b>。
    //   桶由方法名钉死为全局，SQL 文本里的租户列写死
    //   {@code rag_global_tenant_id()}、{@code scope='PLATFORM'}，
    //   调用方连「把别的桶写进去」这句话都拼不出来。
    //
    // ⚠️ 为什么需要它们而不仅仅是 upsertEmbeddingInGlobalBucket：
    //   全局文档摄入流水线要落<b>整片段</b>（rag_chunk）——含版面事实、切分坐标、
    //   kp_ids 等——而非只补向量。租户侧由 saveChunkAndEmbedding 承担同一职责，
    //   全局侧此前缺位（只有 embedding 回填），是 G1 链路断点的直接原因。
    // ---------------------------------------------------------------------

    /**
     * 原子写入一个<b>全局桶</b>片段与其向量（平台档摄入专用）。
     *
     * <p>语义与 {@link #saveChunkAndEmbedding} 完全镜像，但桶固定为<b>全局</b>：
     * 实现跑在 BYPASSRLS 的运维会话（{@code rag_ops}）上，
     * {@code tenant_id} 写为 {@code rag_global_tenant_id()}、{@code scope='PLATFORM'}，
     * 两表写入包在同一事务内，保证「片段 + 向量」要么都成、要么都无
     * （否则会出现静默漏召回或脏数据）。
     *
     * <p>⚠️ {@code scope} 必须<b>显式</b>写 {@code 'PLATFORM'}：该列 NOT NULL 且默认
     * {@code 'TENANT'}，而 {@code rag_chunk_scope_ck} 要求
     * 「{@code scope='PLATFORM' ⇔ tenant_id=哨兵}」——省略这一列，
     * 全局插入会因「租户是哨兵、作用域却是 TENANT」被 CHECK <b>恒拒</b>。
     *
     * @param chunk 片段（含版本坐标；editionId 由调用方解析后传入）
     * @param editionId 版本坐标对应的 {@code rag_edition} 主键
     * @param embedding 向量（维度须与 embedModel 一致）
     * @param embedModel 嵌入模型标识
     */
    void saveChunkAndEmbeddingInGlobalBucket(@NonNull Chunk chunk,
                                             @NonNull String editionId, float[] embedding,
                                             @NonNull String embedModel);

    /**
     * 回写<b>全局桶</b>载体的解析状态（{@code rag_document.parse_status}）。
     *
     * <p>镜像 {@link #markDocumentParseStatus}，但桶固定为全局：
     * 实现跑在运维会话上，UPDATE 带 {@code AND tenant_id = rag_global_tenant_id()} 收口，
     * 防止一个传错的 document_id 改到<b>别的租户</b>的载体（BYPASSRLS 下无 RLS 兜底）。
     *
     * @param documentId 载体 ID（须属全局桶）
     * @param parseStatus 目标解析状态（PENDING / PARSED / CHUNKED / FAILED）
     */
    void markDocumentParseStatusInGlobalBucket(@NonNull String documentId,
                                              @NonNull String parseStatus);
}
