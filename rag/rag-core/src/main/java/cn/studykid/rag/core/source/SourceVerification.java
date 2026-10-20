package cn.studykid.rag.core.source;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 语料来源的权威性校验状态（RAG-D00 §2.1 决策 D-R01 / D-R02）。
 *
 * <p><b>⚠️ fail-closed 语义</b>：本状态的语义是
 * <b>「能否进入可检索索引」</b>，不是「内容是否好看」。
 * 校验未通过的语料<b>不进入索引</b>——不是「降权后还能被搜到」。
 *
 * <p>理由：教辅场景下，一个盗版扫描件或篡改过的答案册被召回，
 * 其危害远大于「召回不到」。检索不到只是功能缺失，
 * 召回错的内容是<b>教学事故</b>（P0-D00 §3.4 失败判据第 1、2 条）。
 *
 * <p><b>校验方式可追溯</b>：{@link #method()} 记录校验手段，
 * {@link #verifiedAt()} 记录时间。二者共同决定该结论是否过期
 * （见 {@link #isStale}）。
 */
public record SourceVerification(
        @NonNull VerificationStatus status,
        @NonNull VerificationMethod method,
        @Nullable String evidenceRef,
        @Nullable Instant verifiedAt,
        @Nullable String verifier
) {

    public SourceVerification {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (method == null) {
            throw new IllegalArgumentException("method 不能为空");
        }
        if (status == VerificationStatus.VERIFIED && evidenceRef == null) {
            // ⚠️ 「已校验」必须能指出校验依据。否则「已校验」只是一个无法复核的断言。
            throw new IllegalArgumentException("VERIFIED 必须提供 evidenceRef");
        }
        if (status == VerificationStatus.VERIFIED && verifiedAt == null) {
            throw new IllegalArgumentException("VERIFIED 必须提供 verifiedAt");
        }
    }

    /** 未校验：默认状态，不可进入索引。 */
    public static @NonNull SourceVerification unverified() {
        return new SourceVerification(VerificationStatus.PENDING, VerificationMethod.NONE, null, null, null);
    }

    /** @return 是否允许进入可检索索引。 */
    public boolean indexable() {
        return status == VerificationStatus.VERIFIED;
    }

    /**
     * @param validDays 校验结论的有效天数
     * @return 校验结论是否已过期（过期后须重新校验）
     */
    public boolean isStale(int validDays) {
        if (status != VerificationStatus.VERIFIED || verifiedAt == null) {
            return true;
        }
        return verifiedAt.plus(java.time.Duration.ofDays(validDays)).isBefore(Instant.now());
    }

    /** 校验状态。 */
    public enum VerificationStatus {
        /** 待校验（默认）。不可索引。 */
        PENDING,
        /** 校验中。不可索引。 */
        IN_REVIEW,
        /** 校验通过。可索引。 */
        VERIFIED,
        /** 校验失败。不可索引，且记录失败原因。 */
        REJECTED,
        /** 已撤回（如版权方要求下架）。必须<b>从索引中移除</b>。 */
        REVOKED
    }

    /** 校验手段。 */
    public enum VerificationMethod {
        /** 无（未校验）。 */
        NONE,
        /** ISBN/ISSN 校验（出版物）。 */
        ISBN,
        /** DOI 解析校验（论文）。 */
        DOI,
        /** 人工审核（逐份比对原件）。 */
        MANUAL_REVIEW,
        /** 官方渠道哈希比对（出版社提供的电子版）。 */
        OFFICIAL_HASH
    }
}
