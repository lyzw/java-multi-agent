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
}
