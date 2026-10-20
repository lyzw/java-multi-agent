package cn.studykid.rag.core.retrieval;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.version.EditionScope;

/**
 * 检索请求（RAG-D00 §2.8 决策 D-R10）。
 *
 * <p><b>⚠️ 为什么检索必须是「带模式的请求」而不是「一个查询串」</b>：
 * 教辅场景中，<b>同一个问题在不同模式下应该召回不同的内容</b>：
 * <ul>
 *   <li>「二次函数的顶点怎么求」→ 讲解模式：召回<b>定义 + 配方过程</b>；</li>
 *   <li>「第 12 题的答案」→ 核对模式：召回<b>题目 + 标准答案</b>；</li>
 *   <li>「二次函数考什么」→ 概览模式：召回<b>考纲 + 题型分布</b>。</li>
 * </ul>
 * 若只用一个查询串，答案册内容会被混进讲解型回答——这是泄题事故。
 * 因此 {@link RetrievalMode} 是<b>检索期过滤条件</b>，不是生成期提示。
 *
 * <p>另两个关键字段：
 * <ul>
 *   <li>{@code editionScope}：版本范围（见 {@link EditionScope}）；</li>
 *   <li>{@code knowledgePointId}：已知知识点时走<b>知识点聚合</b>，
 *       解决碎片化导致的「只召回一半」问题。</li>
 * </ul>
 */
public record RetrievalRequest(
        @NonNull String query,
        @NonNull RetrievalMode mode,
        @NonNull EditionScope editionScope,
        @Nullable String knowledgePointId,
        @Nullable String subject,
        @Nullable String grade,
        @NonNull int topK
) {

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (editionScope == null) {
            throw new IllegalArgumentException("editionScope 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正");
        }
    }

    /**
     * @return 是否为「知识点聚合」模式（已知知识点 ID）。
     */
    public boolean knowledgePointDirected() {
        return knowledgePointId != null && !knowledgePointId.isBlank();
    }

    /** 检索模式。 */
    public enum RetrievalMode {
        /**
         * <b>讲解模式</b>——回答「这个知识点是什么/怎么讲」。
         *
         * <p>⚠️ <b>禁止</b>召回答案类内容（{@code EXAMPLE_SOLUTION} /
         * {@code EXERCISE_ANSWER}）。这是防止泄题的第一道闸门。
         */
        EXPLAIN,

        /**
         * <b>解题模式</b>——回答「这道题怎么做」。
         *
         * <p>⚠️ 允许召回<b>提示</b>（{@code EXERCISE_HINT}）与例题解答，
         * 但<b>不</b>直接给最终答案；答案须由模型在<b>引用提示</b>后自行推导，
         * 这样既可溯源，又避免「抄答案」。
         */
        SOLVE,

        /**
         * <b>核对模式</b>——回答「标准答案是什么/我这样对不对」。
         *
         * <p>唯一允许召回标准答案的模式。
         */
        VERIFY,

        /** <b>概览模式</b>——回答「这个章节讲了什么/考什么」。 */
        OVERVIEW
    }
}
