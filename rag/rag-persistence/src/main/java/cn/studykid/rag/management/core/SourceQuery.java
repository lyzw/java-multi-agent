package cn.studykid.rag.management.core;

import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 管理查询条件（来源列表查询）。
 *
 * <p><b>⚠️ 全部字段可空 = 不筛选</b>：这是「管理面」的宽查询语义，
 * 与检索面（{@code RetrievalRequest} 必须给 query/topK）不同。
 * 管理方要的是「按状态捞出待办」，因此状态筛选是可选的。
 *
 * @param verificationStatus 按校验状态筛选
 * @param trustTier 按权威等级筛选
 * @param keyword 按标题/ISBN/DOI 模糊匹配
 * @param includeStale 是否包含校验已过期的来源（默认 false，因为过期源需重新校验）
 * @param limit 返回条数上限
 * @param offset 偏移量
 */
public record SourceQuery(
        @Nullable VerificationStatus verificationStatus,
        @Nullable TrustTier trustTier,
        @Nullable String keyword,
        boolean includeStale,
        int limit,
        int offset
) {

    /** 默认查询：最多 50 条，从第 0 条开始，不含过期源。 */
    public static final int DEFAULT_LIMIT = 50;

    public SourceQuery {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        if (limit > 500) {
            throw new IllegalArgumentException("limit 不能超过 500（防止管理端全表拉取）");
        }
    }

    /**
     * @param verificationStatus 按校验状态筛选（可为 null）
     * @return 默认条数与偏移的查询条件
     */
    public static SourceQuery of(@Nullable VerificationStatus verificationStatus) {
        return new SourceQuery(verificationStatus, null, null, false, DEFAULT_LIMIT, 0);
    }
}
