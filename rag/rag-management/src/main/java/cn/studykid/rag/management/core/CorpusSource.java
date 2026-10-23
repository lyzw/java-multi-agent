package cn.studykid.rag.management.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 语料来源的管理视图（对应 {@code rag_source} 表行，RAG-D03 §2 / RAG-D07 §2）。
 *
 * <p>本类型是<b>管理面</b>的读模型：它把「登记信息 + 权威等级 + 校验状态 + 版权状态」
 * 聚合成一个不可变快照，使 Controller 不必关心各列的拼装细节。
 *
 * <p><b>⚠️ 为什么「过期」是计算属性而不是状态位</b>（RAG-D07 §3.4）：
 * {@code rag_verification_status} 只有 5 个取值，过期<b>不在其中</b>。
 * 过期由 {@code verified_at + ver_valid_days < now()} 决定，
 * 因此本模型提供 {@link #stale(Instant)} 而非把「过期」存成字段——
 * 若把过期持久化成状态，就会出现「状态字段说 VERIFIED、但实际已过期」
 * 这种<b>自相矛盾</b>的记录，且无法表达「何时过期」。
 *
 * @param sourceId 来源 ID（UUID 字符串）
 * @param sourceType 来源类型（教材/教辅/论文等）
 * @param title 书名/标题
 * @param publisher 出版社/出版方
 * @param isbn ISBN（教材/教辅必填，与 V1 的 CHECK 约束一致）
 * @param doi DOI（论文必填）
 * @param trustTier 权威等级
 * @param verificationStatus 校验状态
 * @param verificationMethod 校验手段
 * @param verificationEvidence 校验证据引用（VERIFIED 时必填）
 * @param verifiedAt 校验决定时间
 * @param verifiedBy 校验决定人
 * @param validDays 校验结论有效天数
 * @param licenseStatus 版权状态（LICENSED / FAIR_USE_QUOTATION / PENDING / RESTRICTED）
 * @param objectRef 正文在对象存储中的引用（正文不入库）
 * @param contentHash 整册内容校验和
 * @param createdAt 登记时间
 * @param updatedAt 更新时间
 */
public record CorpusSource(
        @NonNull String sourceId,
        @NonNull String sourceType,
        @NonNull String title,
        @NonNull String publisher,
        @Nullable String isbn,
        @Nullable String doi,
        @NonNull TrustTier trustTier,
        @NonNull VerificationStatus verificationStatus,
        @NonNull VerificationMethod verificationMethod,
        @Nullable String verificationEvidence,
        @Nullable Instant verifiedAt,
        @Nullable String verifiedBy,
        int validDays,
        @NonNull String licenseStatus,
        @Nullable String objectRef,
        @Nullable String contentHash,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
) {

    public CorpusSource {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (publisher == null || publisher.isBlank()) {
            throw new IllegalArgumentException("publisher 不能为空");
        }
        if (trustTier == null) {
            throw new IllegalArgumentException("trustTier 不能为空");
        }
        if (verificationStatus == null) {
            throw new IllegalArgumentException("verificationStatus 不能为空");
        }
        if (verificationMethod == null) {
            throw new IllegalArgumentException("verificationMethod 不能为空");
        }
        if (licenseStatus == null || licenseStatus.isBlank()) {
            throw new IllegalArgumentException("licenseStatus 不能为空");
        }
        if (validDays <= 0) {
            throw new IllegalArgumentException("validDays 必须 > 0");
        }
    }

    /**
     * 判断校验结论是否已过期。
     *
     * <p>⚠️ 语义与 {@code SourceVerification.isStale} 一致：
     * <ul>
     *   <li>非 VERIFIED 状态一律视为「不可用」（{@code true}）——
     *       校验都没通过，谈不上「结论过期」；</li>
     *   <li>VERIFIED 但缺 {@code verifiedAt} 也视为过期（数据不完整即不可信）；</li>
     *   <li>否则按 {@code verifiedAt + validDays < now} 判定。</li>
     * </ul>
     *
     * @param now 判定基准时刻（便于测试注入固定时钟）
     * @return 已过期返回 {@code true}
     */
    public boolean stale(@NonNull Instant now) {
        if (verificationStatus != VerificationStatus.VERIFIED) {
            return true;
        }
        if (verifiedAt == null) {
            return true;
        }
        return verifiedAt.plus(validDays, ChronoUnit.DAYS).isBefore(now);
    }

    /**
     * 版权状态是否允许进入可检索索引。
     *
     * <p>⚠️ RAG-D07 §2.3.1 G-4：只有 {@code LICENSED} 与
     * {@code FAIR_USE_QUOTATION} 放行；{@code PENDING}（未取得授权）<b>不放行</b>，
     * {@code RESTRICTED} 禁止。
     *
     * @return 版权状态允许使用返回 {@code true}
     */
    public boolean licensePermitsRetrieval() {
        return "LICENSED".equals(licenseStatus) || "FAIR_USE_QUOTATION".equals(licenseStatus);
    }

    /**
     * 综合准入判定（管理面视角，对应 RAG-D07 §2.3.1 的 G-1/G-2/G-4）。
     *
     * <p>⚠️ 此处<b>不含</b> G-3（租户权威门槛）与 G-5（角色）——
     * 前者需租户上下文、后者是检索期职责，不属于来源自身的属性。
     *
     * @param minTrustTier 租户最低权威门槛
     * @param now          判定基准时刻
     * @return 允许进入可检索索引返回 {@code true}
     */
    public boolean admissible(@NonNull TrustTier minTrustTier, @NonNull Instant now) {
        return verificationStatus == VerificationStatus.VERIFIED
                && !stale(now)
                && trustTier.meets(minTrustTier)
                && licensePermitsRetrieval();
    }
}
