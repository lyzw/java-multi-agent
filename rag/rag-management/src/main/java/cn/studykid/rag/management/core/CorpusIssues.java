package cn.studykid.rag.management.core;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 维护问题清单（管理台「待办」的读模型）。
 *
 * <p><b>⚠️ 为什么要有这个清单</b>：语料库的维护动作分两类——
 * <b>准入类</b>（审核/拒绝/撤回，走状态机）与<b>运维类</b>
 * （缺向量要重索引、解析失败要重跑解析、来源卡在待审核没人认领）。
 * 后一类没有任何端点会主动通知，只能靠人定期翻库。
 * 本清单把这三类运维缺口<b>一次性</b>暴露出来。
 *
 * <p><b>⚠️ 计算属性必须在服务端算</b>：
 * {@code stale}（结论是否过期）与「是否准入」都依赖
 * <b>租户权威门槛（G-3）</b>与校验有效期，而门槛的权威源是 platform，
 * 前端<b>没有</b>门槛上下文，算不出准入结论。
 * 故这些结论一律在 SQL 里算好后随结果返回，
 * 前端只负责呈现，不做任何推算。
 *
 * @param documentsMissingEmbedding 有片段缺向量的载体
 * @param documentsFailedParse 解析失败的载体
 * @param sourcesPendingReview 仍待审核的来源
 */
public record CorpusIssues(
        @NonNull List<DocumentEmbeddingGap> documentsMissingEmbedding,
        @NonNull List<DocumentParseFailure> documentsFailedParse,
        @NonNull List<SourcePendingReview> sourcesPendingReview
) {

    public CorpusIssues {
        documentsMissingEmbedding = List.copyOf(documentsMissingEmbedding);
        documentsFailedParse = List.copyOf(documentsFailedParse);
        sourcesPendingReview = List.copyOf(sourcesPendingReview);
    }

    /**
     * 各问题类别的条数合计。
     *
     * <p>⚠️ 合计由列表长度派生而不是另查一次 SQL：
     * 两次查询之间库可能变化，两个数对不上会让运维误判
     * 「清单总数与分项不一致 = 有数据竞态」。
     *
     * @return 合计视图
     */
    @NonNull
    public IssueTotals totals() {
        return new IssueTotals(documentsMissingEmbedding.size(),
                documentsFailedParse.size(), sourcesPendingReview.size());
    }

    /**
     * 缺向量的载体（该载体下存在没有向量行的片段）。
     *
     * @param documentId 载体 ID
     * @param sourceId 所属来源 ID
     * @param title 载体标题
     * @param carrier 载体类型
     * @param parseStatus 解析状态
     * @param chunkCount 该载体下的片段总数
     * @param missingEmbeddingCount 其中缺向量的片段数
     */
    public record DocumentEmbeddingGap(
            @NonNull String documentId,
            @NonNull String sourceId,
            @NonNull String title,
            @NonNull String carrier,
            @NonNull String parseStatus,
            long chunkCount,
            long missingEmbeddingCount
    ) {
    }

    /**
     * 解析失败的载体。
     *
     * @param documentId 载体 ID
     * @param sourceId 所属来源 ID
     * @param title 载体标题
     * @param carrier 载体类型
     * @param parseStatus 解析状态（固定为 FAILED）
     * @param chunkCount 该载体下的片段总数（失败时通常为 0）
     */
    public record DocumentParseFailure(
            @NonNull String documentId,
            @NonNull String sourceId,
            @NonNull String title,
            @NonNull String carrier,
            @NonNull String parseStatus,
            long chunkCount
    ) {
    }

    /**
     * 仍待审核的来源（PENDING / IN_REVIEW）。
     *
     * @param sourceId 来源 ID
     * @param title 标题
     * @param verificationStatus 当前校验状态
     * @param stale 校验结论是否已过期（服务端按 SQL 计算）
     * @param trustTier 权威等级
     */
    public record SourcePendingReview(
            @NonNull String sourceId,
            @NonNull String title,
            @NonNull String verificationStatus,
            boolean stale,
            @Nullable String trustTier
    ) {
    }

    /**
     * 问题清单合计。
     *
     * @param documentsMissingEmbedding 缺向量的载体数
     * @param documentsFailedParse 解析失败的载体数
     * @param sourcesPendingReview 待审核的来源数
     */
    public record IssueTotals(
            int documentsMissingEmbedding,
            int documentsFailedParse,
            int sourcesPendingReview
    ) {
    }
}
