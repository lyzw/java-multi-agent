package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 载体级校验决定命令（审核员/法务对某个<b>物理载体</b>作出的判定）。
 *
 * <p><b>⚠️ 与 {@link VerificationDecision}（来源级）的区别</b>：
 * 来源级决定会改写「作品的权威等级」；载体级决定<b>不改写</b>权威等级——
 * 权威等级是<b>出版层面</b>的属性，一份扫描件再可靠也不会让整部教材升级。
 * 故本命令虽然也接收 {@code trustTier} 以保持两份 HTTP 契约同形，
 * 但它<b>不产生</b>任何等级变更效果。
 *
 * <p><b>⚠️ VERIFIED 必须显式声明版权状态</b>：载体版权轴是 G-4 的独立一侧。
 * 改造前登记 SQL 硬编码 {@code PENDING} 且没有写入端点，
 * 于是「来源 VERIFIED + 载体 VERIFIED」仍可能因版权轴停在 PENDING 而语义不明。
 * 本命令要求放行时必须给出 {@code LICENSED} / {@code FAIR_USE_QUOTATION}；
 * 非 VERIFIED 流转不得携带版权声明。
 *
 * <p><b>⚠️ 诚实边界：证据引用当前无处持久化</b>。
 * {@code rag_document} 只有 {@code revoked_reason} 一个可写依据的列，
 * {@code rag_access_audit} 也<b>没有</b>证据列（只有 {@code request_digest}，
 * 语义是「检索请求摘要」）。
 * 因此：
 * <ul>
 *   <li>撤回（REVOKED）——{@code evidenceRef} 落 {@code revoked_reason}，
 *       满足 DB 的 {@code CHECK (ver_status <> 'REVOKED' OR revoked_reason IS NOT NULL)}；</li>
 *   <li>通过 / 拒绝——{@code evidenceRef} 仅随请求进入审计上下文，
 *       <b>不会</b>落库。这是 RAG-D07 §2.6 尚未关闭的既有缺口，
 *       本命令<b>不</b>因此改变 HTTP 契约（前端契约以任务给定为准），
 *       但也<b>不</b>假装「证据已留痕」。</li>
 * </ul>
 *
 * @param targetStatus 目标校验状态（不得为 PENDING）
 * @param evidenceRef 校验依据引用（撤回时落 {@code revoked_reason}）
 * @param decidedBy 决定人（审计留痕，RAG-D07 §2.5）
 * @param verificationMethod 校验手段
 * @param trustTier 判定后的权威等级（载体级不改变来源等级，仅保持契约同形）
 * @param validDays 有效天数（载体级无独立有效期字段，仅保持契约同形）
 * @param licenseStatus 版权声明（仅 VERIFIED，且必须可放行）
 */
public record DocumentVerificationDecision(
        @NonNull VerificationStatus targetStatus,
        @NonNull String evidenceRef,
        @NonNull String decidedBy,
        @NonNull VerificationMethod verificationMethod,
        @Nullable TrustTier trustTier,
        int validDays,
        @Nullable LicenseStatus licenseStatus
) {

    public DocumentVerificationDecision {
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus 不能为空");
        }
        if (evidenceRef == null || evidenceRef.isBlank()) {
            throw new IllegalArgumentException("evidenceRef 不能为空"
                    + "（RAG-D07 §2.5：每次转移必须有证据留痕）");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy 不能为空"
                    + "（RAG-D07 §2.5：必须可回溯「谁」作出了决定）");
        }
        if (verificationMethod == null) {
            throw new IllegalArgumentException("verificationMethod 不能为空");
        }
        if (validDays <= 0) {
            throw new IllegalArgumentException("validDays 必须 > 0");
        }
        // ⚠️ 目标状态合法性在**命令构造期**就拦一次：
        //    「PENDING 不是任何审核动作的目标」是入参形态问题（400），
        //    不该留到落库阶段才发现。
        DocumentVerification.requireAssignableTarget(targetStatus);
        if (targetStatus == VerificationStatus.VERIFIED) {
            if (licenseStatus == null) {
                throw new IllegalArgumentException("载体判定 VERIFIED 必须显式声明版权状态"
                        + "（LICENSED / FAIR_USE_QUOTATION）");
            }
            if (!licenseStatus.permitsRetrieval()) {
                throw new IllegalArgumentException("版权状态 " + licenseStatus.name()
                        + " 不支持载体 VERIFIED 放行；只允许 LICENSED / FAIR_USE_QUOTATION");
            }
        } else if (licenseStatus != null) {
            throw new IllegalArgumentException(
                    "版权声明只允许 VERIFIED 决定携带；" + targetStatus + " 不得声明 licenseStatus");
        }
    }

    /** 兼容构造：<b>仅供非 VERIFIED 流转使用</b>；VERIFIED 必须走全参构造。 */
    public DocumentVerificationDecision(@NonNull VerificationStatus targetStatus,
                                        @NonNull String evidenceRef,
                                        @NonNull String decidedBy,
                                        @NonNull VerificationMethod verificationMethod,
                                        @Nullable TrustTier trustTier,
                                        int validDays) {
        this(targetStatus, evidenceRef, decidedBy, verificationMethod,
                trustTier, validDays, null);
    }

    /**
     * 便捷构造：判定通过。
     *
     * @param method 校验手段
     * @param evidence 证据引用
     * @param decider 决定人
     * @param license 显式版权声明（LICENSED / FAIR_USE_QUOTATION）
     * @param days 有效天数
     * @return 载体级校验决定
     */
    public static DocumentVerificationDecision approve(@NonNull VerificationMethod method,
                                                       @NonNull String evidence,
                                                       @NonNull String decider,
                                                       @NonNull LicenseStatus license,
                                                       int days) {
        return new DocumentVerificationDecision(VerificationStatus.VERIFIED, evidence, decider,
                method, null, days, license);
    }

    /**
     * 便捷构造：撤回（任意阶段可触发，RAG-D07 §2.4 T-5/T-6/T-7/T-8）。
     *
     * @param evidence 撤回依据
     * @param decider 执行人（法务/负责人）
     * @return 载体级校验决定
     */
    public static DocumentVerificationDecision revoke(@NonNull String evidence,
                                                      @NonNull String decider) {
        return new DocumentVerificationDecision(VerificationStatus.REVOKED, evidence, decider,
                VerificationMethod.NONE, null, 365);
    }
}
