package cn.studykid.multiagent.infra.retrieval.engine;

import java.time.Duration;
import java.time.Instant;

/**
 * G3 检索引擎核心服务（P3-D10）。
 *
 * <p>实现 Authority Triple Gate：RLS 租户隔离 + TTL 时效 + 语义分阈值，
 * 任一未过即拒绝，绝不泄漏跨租户 / 过期 / 低质内容。
 *
 * <p>⚠️ 诚实边界：真实向量库 / BERT 嵌入为外部依赖（被 A-008 阻断），
 * {@link VectorSearchEngine} 以占位打分提供语义分，门控语义可测但集成未闭环。
 */
public final class RetrievalService {

    /** G3 默认语义相似度阈值。 */
    public static final double VECTOR_SIMILARITY_THRESHOLD = 0.75;
    /** G3 默认 TTL：90 天。 */
    public static final long TTL_MAX_AGE_MINUTES = 90L * 24 * 60;

    private final String expectedTenantId;
    private final VectorSearchEngine vectorSearch;
    private final TfIdfRanker tfIdfRanker;

    /**
     * @param expectedTenantId 来自可信认证上下文的租户标识；
     *                         null 表示草稿期不做绑定比较（仅校验非空）
     */
    public RetrievalService(String expectedTenantId,
                            VectorSearchEngine vectorSearch,
                            TfIdfRanker tfIdfRanker) {
        this.expectedTenantId = expectedTenantId;
        this.vectorSearch = vectorSearch;
        this.tfIdfRanker = tfIdfRanker;
    }

    /** 从工作区上下文检索，经 Authority Triple Gate。 */
    public RetrievalResult retrieve(ContextRecord record) {
        GateFailure failure = evaluateGate(record);
        if (failure != null) {
            return RetrievalResult.failed(failure.name());
        }
        double vector = vectorSearch.score(record);
        double tfidf = tfIdfRanker.score(record.terms());
        double hybrid = (vector * 0.5) + (tfidf * 0.5);
        return RetrievalResult.success(record, hybrid);
    }

    /** Authority Triple Gate：三门任一失败即拒绝。 */
    GateFailure evaluateGate(ContextRecord record) {
        if (record.tenantId() == null || record.tenantId().isBlank()) {
            return GateFailure.RLS_MISMATCH;
        }
        if (expectedTenantId != null && !expectedTenantId.equals(record.tenantId())) {
            return GateFailure.RLS_MISMATCH;
        }
        if (record.lastUpdated() == null) {
            return GateFailure.TTL_UNVERIFIABLE;
        }
        long ageMinutes = Duration.between(record.lastUpdated(), Instant.now()).toMinutes();
        if (ageMinutes >= TTL_MAX_AGE_MINUTES) {
            return GateFailure.TTL_EXPIRED;
        }
        if (vectorSearch.score(record) < VECTOR_SIMILARITY_THRESHOLD) {
            return GateFailure.VECTOR_SCORE_BELOW_THRESHOLD;
        }
        return null;
    }

    /** 三门门控失败枚举。 */
    enum GateFailure {
        RLS_MISMATCH,
        TTL_UNVERIFIABLE,
        TTL_EXPIRED,
        VECTOR_SCORE_BELOW_THRESHOLD
    }

    /** 工作区上下文记录（G3 检索输入）。 */
    public record ContextRecord(
            String id,
            String tenantId,
            Instant lastUpdated,
            String terms) {
    }

    /** 检索结果。 */
    public record RetrievalResult(
            boolean passed,
            ContextRecord record,
            double hybridScore,
            String failureReason) {

        static RetrievalResult success(ContextRecord record, double hybridScore) {
            return new RetrievalResult(true, record, hybridScore, null);
        }

        static RetrievalResult failed(String reason) {
            return new RetrievalResult(false, null, 0.0, reason);
        }
    }
}
