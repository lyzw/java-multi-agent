package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.version.EditionScope;

/**
 * 检索端口（RAG-D02 §3.2）。
 *
 * <p><b>⚠️ 设计要点：端口暴露的是「检索能力」而非「向量库 API」</b>。
 * 领域层不关心底层是 pgvector、Elasticsearch 还是专用向量库——
 * 这与主平台「领域层不依赖供应商 SDK」的约束同构。
 *
 * <p><b>⚠️ 端口契约包含「过滤」而非仅「打分」</b>：
 * {@link #search} 的实现<b>必须</b>在返回前应用
 * {@link cn.studykid.rag.core.retrieval.RetrievalPolicy} 的版本与角色过滤。
 * 理由：过滤若由调用方事后做，答案类内容已流入应用层内存，
 * 在多组件协作下极易被误用。
 */
public interface RetrievalPort {

    /**
     * 执行检索。
     *
     * <p>⚠️ 返回<b>带分数</b>的片段而非裸片段：分数门禁必须作用于
     * <b>检索器给出的真实相关性分数</b>。若由编排层用「排名」反推分数，
     * 则 top-1 恒为满分，门禁形同虚设——「找不到依据」将永远不可能发生。
     *
     * @param request 检索请求（含模式与版本范围）
     * @return 检索结果（按相关性倒序）
     */
    @NonNull List<ScoredChunk> search(@NonNull RetrievalRequest request);

    /**
     * 按知识点取其全部片段（用于碎片化补全）。
     *
     * @param knowledgePointId 知识点 ID
     * @param editionScope 版本范围
     * @return 该知识点下的片段
     */
    @NonNull List<Chunk> findByKnowledgePoint(@NonNull String knowledgePointId,
                                              @NonNull EditionScope editionScope);

    /**
     * 取先修知识点下的片段（用于解释「为什么不会」）。
     *
     * @param knowledgePointId 知识点 ID
     * @param editionScope 版本范围
     * @return 先修知识点片段
     */
    @NonNull List<Chunk> findPrerequisites(@NonNull String knowledgePointId,
                                            @NonNull EditionScope editionScope);

    /**
     * 取同章节邻接片段（用于补全被切碎的上下文）。
     *
     * @param chunk 锚点片段
     * @param limit 最多取几条
     * @return 邻接片段
     */
    @NonNull List<Chunk> findSectionNeighbors(@NonNull Chunk chunk, int limit);

    /**
     * 解析查询到知识点。
     *
     * @param query 查询串
     * @param subject 学科（可空表示不限）
     * @return 命中的知识点，按置信度倒序
     */
    @NonNull List<KnowledgePoint> resolveKnowledgePoints(@NonNull String query,
                                                          @Nullable String subject);
}
