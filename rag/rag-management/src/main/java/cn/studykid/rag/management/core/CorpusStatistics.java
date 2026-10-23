package cn.studykid.rag.management.core;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 租户语料索引的管理面统计（管理看板数据源）。
 *
 * <p><b>⚠️ 为什么统计口径必须显式区分「已入库」与「可检索」</b>：
 * 管理方最常见的误判是「片段数 = 可用知识量」。但 RAG-D07 §2.3.1 的准入闸门
 * 意味着<b>大量已入库片段是不可检索的</b>——来源未 VERIFIED、校验过期、
 * 版权未授权、命中部分索引（被撤回的载体）等。
 * 若只报「片段总数」，运维会看到数量健康却检索不出内容，且无从判断原因。
 *
 * <p>因此本模型同时给出：<b>总量</b>、<b>按来源状态分组</b>、<b>可检索量</b>、
 * 以及<b>缺向量片段数</b>（片段已入库但向量缺失 → 静默漏召回）。
 *
 * @param totalSources 来源总数
 * @param verifiedSources 已通过校验的来源数
 * @param admissibleSources 当前准入（可检索）的来源数
 * @param totalDocuments 文档总数
 * @param totalChunks 片段总数
 * @param retrievableChunks 可检索片段数（按来源准入闸门推导）
 * @param chunksMissingEmbedding 缺向量的片段数（静默漏召回风险）
 * @param chunksByRole 按角色分组的片段数
 * @param sourcesByVerificationStatus 按校验状态分组的来源数
 * @param documentsByParseStatus 按解析状态分组的文档数
 */
public record CorpusStatistics(
        long totalSources,
        long verifiedSources,
        long admissibleSources,
        long totalDocuments,
        long totalChunks,
        long retrievableChunks,
        long chunksMissingEmbedding,
        @NonNull Map<String, Long> chunksByRole,
        @NonNull Map<String, Long> sourcesByVerificationStatus,
        @NonNull Map<String, Long> documentsByParseStatus
) {

    public CorpusStatistics {
        if (totalSources < 0 || verifiedSources < 0 || admissibleSources < 0
                || totalDocuments < 0 || totalChunks < 0 || retrievableChunks < 0
                || chunksMissingEmbedding < 0) {
            throw new IllegalArgumentException("统计计数不能为负");
        }
        chunksByRole = chunksByRole == null ? Map.of() : Map.copyOf(chunksByRole);
        sourcesByVerificationStatus =
                sourcesByVerificationStatus == null ? Map.of() : Map.copyOf(sourcesByVerificationStatus);
        documentsByParseStatus =
                documentsByParseStatus == null ? Map.of() : Map.copyOf(documentsByParseStatus);
    }

    /**
     * @return 缺向量片段占片段总数的比例（0～1）；无片段时返回 0
     */
    public double missingEmbeddingRatio() {
        if (totalChunks == 0) {
            return 0.0;
        }
        return (double) chunksMissingEmbedding / (double) totalChunks;
    }
}
