package cn.studykid.rag.management.ports;

import org.jspecify.annotations.NonNull;

/**
 * 语料重索引端口（管理面「重跑索引」端点的出口）。
 *
 * <p><b>⚠️ 为什么单独一个端口，而不是把重索引塞进 {@link CorpusManagementPort}</b>：
 * 管理端口的语义是<b>准入治理</b>（登记/审核/撤回/统计），
 * 重索引是<b>索引运维</b>——它写的是 {@code rag_chunk.text_jieba} 与
 * {@code rag_chunk_embedding}，不碰任何准入状态字段。
 * 混在一起会让「审核一次来源」与「重算一遍向量」共用同一套失败语义，
 * 而二者的失败原因完全不同（治理冲突 vs 资源不足）。
 *
 * <p><b>⚠️ 租户隔离由实现方保证</b>：本端口只接收 {@code tenantId}，
 * 实现必须在设置了 {@code app.tenant_id} 的事务内操作
 * （否则 RLS 会把全部行过滤掉，表现为「重索引跑了但一条没处理」且不报错）。
 *
 * <p><b>⚠️ 诚实边界</b>：当前嵌入模型为本地确定性占位
 * （{@code LocalDeterministicEmbeddingPort}），
 * 重索引产出的是<b>链路验证级</b>向量，不是语义检索结论。
 */
public interface CorpusIndexingPort {

    /**
     * 重跑索引：补齐本租户下「已校验来源」中缺切词或缺向量的片段。
     *
     * @param tenantId 租户 ID
     * @param limit 本次最多处理的片段数（必须 > 0）
     * @return 重索引结果
     */
    @NonNull ReindexOutcome reindex(@NonNull String tenantId, int limit);

    /**
     * 重索引结果。
     *
     * <p>⚠️ {@code scannedChunks} 与 {@code indexedChunks} 都要给：
     * 两者不等说明有片段处理失败，运维据此判断「是否需要再跑一次」。
     *
     * @param indexedChunks 实际完成索引的片段数
     * @param scannedChunks 扫描到的待索引片段数
     * @param durationMs 本次耗时（毫秒）
     */
    record ReindexOutcome(int indexedChunks, int scannedChunks, long durationMs) {
    }
}
