package cn.studykid.rag.management.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 语料生命周期判定（交互文档 §4.7）。
 *
 * <p><b>为什么由服务端算，而不是前端拿 verifiedAt + validDays 自己算</b>：
 * 过期与否是**检索准入**的一部分（{@code admissiblePredicate} 里
 * {@code verified_at + valid_days >= now()} 决定学生能否搜到）。
 * 前端另算一遍会与检索侧在**边界日、时区、时钟偏差**上漂移——
 * 典型事故是界面显示「还有 1 天」而检索侧当天已经把它排除了。
 *
 * <p>本类只做计算，不查库，故可穷举单测。
 */
public final class LifecycleRules {

    private LifecycleRules() {
    }

    /**
     * 「即将到期」的提前提醒阈值（天）。
     *
     * <p>⚠️ 取 30 天：教材语料续期要走审核流程（含抽检），
     * 少于这个窗口基本来不及在到期前完成续期。
     */
    public static final int EXPIRING_SOON_DAYS = 30;

    /**
     * 一次生命周期计算的结果。
     *
     * @param expiresAt        到期时刻（未通过校验时为 null）
     * @param daysUntilExpiry  距到期天数（负数=已过期；未通过为 null）
     * @param stale            是否已过期
     * @param expiringSoon     是否即将到期
     */
    public record Expiry(
            @Nullable Instant expiresAt,
            @Nullable Integer daysUntilExpiry,
            boolean stale,
            boolean expiringSoon
    ) {
        /** 未通过校验（没有有效期概念）。 */
        public static Expiry neverVerified() {
            return new Expiry(null, null, false, false);
        }
    }

    /**
     * 计算有效期状态。
     *
     * <p>⚠️ **边界口径必须与检索侧一致**：检索侧判定是
     * {@code verified_at + valid_days >= now()}（**大于等于**即仍有效）。
     * 故这里用同一比较方向：到期时刻尚未过去 ⇒ 未过期。
     *
     * @param verifiedAt 校验通过时刻（null = 从未通过，无有效期）
     * @param validDays  有效期天数（≤0 视为无效配置，按未通过处理）
     * @param now        当前时刻（便于单测注入）
     * @return 到期状态
     */
    @NonNull
    public static Expiry compute(@Nullable Instant verifiedAt,
                                 int validDays,
                                 @NonNull Instant now) {
        if (verifiedAt == null || validDays <= 0) {
            return Expiry.neverVerified();
        }
        Instant expiresAt = verifiedAt.plus(Duration.ofDays(validDays));
        // ⚠️ 按「自然日」算剩余天数，而不是按 24 小时整除：
        //    语料有效期是业务概念（"一年有效"），用户看的是日期不是小时。
        long days = Duration.between(now, expiresAt).toDays();

        boolean stale = !expiresAt.isAfter(now) && !expiresAt.equals(now);
        // ⚠️ 恰好等于到期时刻：检索侧 >= 判定为**仍有效**，故此处也不算过期
        if (expiresAt.equals(now)) {
            stale = false;
        }
        boolean expiringSoon = !stale && days >= 0 && days <= EXPIRING_SOON_DAYS;
        return new Expiry(expiresAt, (int) days, stale, expiringSoon);
    }

    /**
     * 剩余天数的展示文案（人话）。
     *
     * @param expiry 到期状态
     * @return 面向用户的一句话；未通过校验时给出「尚未校验」
     */
    @NonNull
    public static String describe(@NonNull Expiry expiry) {
        if (expiry.expiresAt() == null) {
            return "尚未通过校验，没有有效期";
        }
        if (expiry.stale()) {
            return "已过期，学生搜不到；需重新校验后恢复";
        }
        int days = expiry.daysUntilExpiry() == null ? 0 : expiry.daysUntilExpiry();
        if (expiry.expiringSoon()) {
            return "将于 " + days + " 天后过期，请尽快重新校验";
        }
        return "有效期至 " + LocalDate.ofInstant(expiry.expiresAt(), ZoneOffset.UTC)
                + "（剩余 " + days + " 天）";
    }
}
