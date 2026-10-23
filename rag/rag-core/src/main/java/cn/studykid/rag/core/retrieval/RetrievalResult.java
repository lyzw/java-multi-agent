package cn.studykid.rag.core.retrieval;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 检索结果（RAG-D00 §2.9 决策 D-R11）。
 *
 * <p><b>⚠️ 检索结果必须自带「为什么被召回」的解释</b>。
 * 教辅场景的召回错误（串版本、串知识点、混入答案）如果不可解释，
 * 就无法在评测里归因，也无法在上线后被定位。
 *
 * <p>因此 {@link ScoredChunk} 除分数外，还携带
 * {@link RetrievalChannel}（召回通道）与
 * {@link HitReason}（命中原因）——二者共同构成可审计的召回解释。
 */
public record RetrievalResult(
        @NonNull List<ScoredChunk> chunks,
        @NonNull List<KnowledgePoint> knowledgePoints,
        @NonNull EditionCoordinate primaryEdition,
        @NonNull boolean versionMismatchDetected,
        @NonNull boolean answerContentSuppressed
) {

    public RetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        if (primaryEdition == null) {
            throw new IllegalArgumentException("primaryEdition 不能为空");
        }
    }

    /** @return 是否为空结果（上层据此走「依据不足」路径）。 */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * @return 命中的所有版本（用于检测版本串扰）。
     */
    public @NonNull List<EditionCoordinate> editions() {
        return chunks.stream().map(c -> c.chunk().edition()).distinct().toList();
    }

    /**
     * 单个召回片段及其分数与解释。
     *
     * @param chunk 被召回片段
     * @param score 综合得分（0~1）
     * @param channel 召回通道
     * @param reason 命中原因
     */
    public record ScoredChunk(
            @NonNull Chunk chunk,
            double score,
            @NonNull RetrievalChannel channel,
            @NonNull HitReason reason
    ) {

        public ScoredChunk {
            if (chunk == null) {
                throw new IllegalArgumentException("chunk 不能为空");
            }
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score 必须落在 [0,1]");
            }
            if (channel == null) {
                throw new IllegalArgumentException("channel 不能为空");
            }
            if (reason == null) {
                throw new IllegalArgumentException("reason 不能为空");
            }
        }
    }

    /** 召回通道。 */
    public enum RetrievalChannel {
        /** 向量语义召回。 */
        DENSE_VECTOR,
        /** 关键词/BM25 召回。 */
        LEXICAL,
        /** 沿知识点聚合召回。 */
        KNOWLEDGE_POINT,
        /** 沿先修关系扩展召回。 */
        PREREQUISITE_EXPANSION,
        /** 版本内同章节邻接扩展。 */
        SECTION_NEIGHBOR,
        /**
         * 生成期依据（B5）。
         *
         * <p>⚠️ 这不是检索通道：这些片段<b>不是被本端检索出来的</b>，
         * 而是调用方从前一次检索结果里指定后交来生成的。
         * 单列一个值是为了让「这条为什么出现」的审计解释保持诚实——
         * 若复用 {@link #DENSE_VECTOR}，会让人以为本端做过向量召回。
         */
        GENERATION_BASIS
    }

    /** 命中原因。 */
    public enum HitReason {
        /** 查询词直接命中。 */
        DIRECT_MATCH,
        /** 别名/规范名命中。 */
        ALIAS_MATCH,
        /** 知识点聚合带入。 */
        KP_AGGREGATED,
        /** 先修依赖带入。 */
        PREREQUISITE,
        /** 同章节邻接。 */
        NEIGHBOR,
        /** 答案类内容被模式过滤排除（保留在审计中）。 */
        SUPPRESSED_BY_MODE,
        /**
         * 由调用方指定为生成依据（B5）。
         *
         * <p>与 {@link RetrievalChannel#GENERATION_BASIS} 配对：
         * 说明「这条片段的出现是调用方的选择，不是检索器的判断」。
         */
        CITED_AS_BASIS
    }
}
