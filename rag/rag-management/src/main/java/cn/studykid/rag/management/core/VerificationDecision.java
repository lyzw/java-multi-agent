package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 校验决定命令（审核员/法务对来源作出的判定）。
 *
 * <p><b>⚠️ 每次状态转移都必须留痕</b>（RAG-D07 §2.5）：
 * 「谁在什么时候基于什么证据做了什么决定」必须可回溯。
 * 因此本命令强制要求：<b>目标状态 + 证据引用 + 决定人</b>，
 * 缺一不可——否则「已校验」只是一个<b>无法复核的断言</b>。
 *
 * <p><b>⚠️ 目标为 VERIFIED 时的额外约束</b>：
 * <ul>
 *   <li>必须有 {@code evidenceRef}（V1 的 CHECK 约束
 *       {@code rag_source_verified_requires_evidence_chk} 同样强制）；</li>
 *   <li>必须给出 {@code trustTier}——审核通过必须同时判定权威等级，
 *       否则来源「已校验」却仍是 {@code UNVERIFIED}，
 *       依然过不了 G-3 门槛，审核就<b>没有产生实际效果</b>；</li>
 *   <li>必须给出 {@code verificationMethod}——ISBN / DOI / MANUAL_REVIEW /
 *       OFFICIAL_HASH 决定了证据的可复核方式。</li>
 * </ul>
 *
 * @param targetStatus 目标校验状态
 * @param evidenceRef 校验依据引用（建议 {@code method:ref@timestamp} 形式）
 * @param decidedBy 决定人（审核员/法务/负责人）
 * @param verificationMethod 校验手段
 * @param trustTier 判定后的权威等级（仅 VERIFIED 时必填）
 * @param validDays 校验结论有效天数
 */
public record VerificationDecision(
        @NonNull VerificationStatus targetStatus,
        @NonNull String evidenceRef,
        @NonNull String decidedBy,
        @NonNull VerificationMethod verificationMethod,
        @Nullable TrustTier trustTier,
        int validDays
) {

    public VerificationDecision {
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
        if (targetStatus == VerificationStatus.VERIFIED && trustTier == null) {
            throw new IllegalArgumentException("判定 VERIFIED 必须同时给出权威等级"
                    + "（否则来源通过校验却仍被 G-3 门槛拦下，审核形同虚设）");
        }
    }

    /**
     * 便捷构造：判定通过。
     *
     * @param tier 判定后的权威等级
     * @param method 校验手段
     * @param evidence 证据引用
     * @param decider 决定人
     * @param days 有效天数
     * @return 校验决定
     */
    public static VerificationDecision approve(@NonNull TrustTier tier,
                                              @NonNull VerificationMethod method,
                                              @NonNull String evidence,
                                              @NonNull String decider,
                                              int days) {
        return new VerificationDecision(VerificationStatus.VERIFIED, evidence, decider, method, tier, days);
    }

    /**
     * 便捷构造：判定拒绝（信任等级维持不变，由调用方按需降级）。
     *
     * @param evidence 拒绝理由
     * @param decider 决定人
     * @return 校验决定
     */
    public static VerificationDecision reject(@NonNull String evidence, @NonNull String decider) {
        return new VerificationDecision(VerificationStatus.REJECTED, evidence, decider,
                VerificationMethod.NONE, null, 365);
    }

    /**
     * 便捷构造：撤回（任意阶段可触发，RAG-D07 §2.4 T-5/T-6/T-7/T-8）。
     *
     * @param evidence 撤回依据
     * @param decider 执行人（法务/负责人）
     * @return 校验决定
     */
    public static VerificationDecision revoke(@NonNull String evidence, @NonNull String decider) {
        return new VerificationDecision(VerificationStatus.REVOKED, evidence, decider,
                VerificationMethod.NONE, null, 365);
    }
}
