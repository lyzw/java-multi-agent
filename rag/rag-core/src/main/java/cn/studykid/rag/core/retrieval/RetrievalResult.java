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
     * <p><b>⚠️ 为什么是两个分数而不是一个</b>（2026-10-23 修复）：
     * 这两个分数<b>量纲不同</b>，混用会直接废掉分数门禁。
     * <ul>
     *   <li>{@code score}——<b>相关性分</b>（0~1）：检索器给出的真实相关性，
     *       稠密通道是余弦相似度、词法通道是 {@code ts_rank}。
     *       <b>分数门禁作用在它上面</b>（门禁阈值按 0~1 相关性标定，
     *       见 RD-Q2）：低于阈值即「未找到依据」。</li>
     *   <li>{@code fusionScore}——<b>融合排序分</b>：RRF 值，
     *       只用排名、跨语料稳定，量纲极小（双通道命中时最大约 2/61 ≈ 0.033）。
     *       <b>排序与版本降权作用在它上面</b>。</li>
     * </ul>
     * ⚠️ <b>曾经的缺陷</b>：JDBC 实现把 RRF 融合分塞进 {@code score}，
     * 于是门禁（0.2/0.35）永远高于 RRF 上限（≈0.033），
     * 检索<b>恒定返回「未找到依据」</b>——语料再全也召不回任何东西。
     * 这正是端口契约所说的「门禁必须作用于检索器给出的真实相关性分数」
     * 被实现层违反的后果。
     *
     * @param chunk 被召回片段
     * @param score 相关性分（0~1，门禁作用于此）
     * @param fusionScore 融合排序分（RRF，排序作用于此）
     * @param channel 召回通道
     * @param reason 命中原因
     */
    public record ScoredChunk(
            @NonNull Chunk chunk,
            double score,
            double fusionScore,
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
            if (fusionScore < 0.0 || fusionScore > 1.0) {
                throw new IllegalArgumentException("fusionScore 必须落在 [0,1]");
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
