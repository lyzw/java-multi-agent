package cn.studykid.rag.api.retrieval;

import java.util.List;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;

/**
 * 检索结果 DTO（RAG-D00 §2.9）。
 *
 * <p>⚠️ 每个命中携带「为什么被召回」的解释（通道 + 命中原因），
 * 使召回错误可审计（RAG-D00 D-R11）。{@link HitDto#locator} 即面向学生的引用文本，
 * 使回答可回溯到「哪一册、第几页、第几题」。
 */
public record RetrievalResultDto(
        List<HitDto> hits,
        boolean versionMismatchDetected,
        boolean answerContentSuppressed,
        String primaryEdition
) {

    public static RetrievalResultDto from(RetrievalResult result) {
        List<HitDto> hits = result.chunks().stream().map(HitDto::from).toList();
        return new RetrievalResultDto(hits, result.versionMismatchDetected(),
                result.answerContentSuppressed(), result.primaryEdition().display());
    }

    /**
     * 单个召回片段（含可展示引用与召回解释）。
     *
     * @param chunkId 片段 ID
     * @param text 片段正文
     * @param role 片段角色（决定是否在讲解模式下被召回）
     * @param score 综合得分（0~1，低于门禁的已被编排层过滤）
     * @param edition 版本展示名
     * @param locator 面向学生的引用文本（册/章/页/题）
     * @param channel 召回通道（稠密/词法/知识点聚合…）
     * @param reason 命中原因
     */
    public record HitDto(
            String chunkId, String text, String role, double score,
            String edition, String locator, String channel, String reason
    ) {

        static HitDto from(ScoredChunk scored) {
            Chunk c = scored.chunk();
            return new HitDto(
                    c.id(), c.text(), c.role().name(), scored.score(),
                    c.edition().display(), c.locator().display(),
                    scored.channel().name(), scored.reason().name());
        }
    }
}
