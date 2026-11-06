package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 语料生命周期条目（交互文档 §4.7）。
 *
 * @param sourceId          来源 ID
 * @param title             书名
 * @param verificationStatus 校验状态
 * @param verifiedAt        校验通过时刻（可空）
 * @param validDays         有效期天数
 * @param expiresAt         到期时刻（可空）
 * @param daysUntilExpiry   距到期天数（负数=已过期；可空）
 * @param stale             是否已过期
 * @param expiringSoon      是否即将到期
 * @param admissible        当前是否可被检索
 * @param description       人话说明（「将于 N 天后过期」/「已过期」）
 */
public record LifecycleEntry(
        @NonNull String sourceId,
        @NonNull String title,
        @NonNull String verificationStatus,
        @Nullable String verifiedAt,
        int validDays,
        @Nullable String expiresAt,
        @Nullable Integer daysUntilExpiry,
        boolean stale,
        boolean expiringSoon,
        boolean admissible,
        @NonNull String description
) {
}
