package cn.studykid.multiagent.core.compliance;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 零保留确认注册表（DEC-10 GATE-2）。
 *
 * <p><b>为什么必须按供应商分别登记</b>：
 * 零保留条款是与<b>特定供应商</b>签订的。用 A 家的确认去放行 B 家的调用，
 * 是典型的合规漏洞 —— B 家可能完全没有对应条款，或条款内容根本不同。
 * <b>因此本类型刻意不提供「全局已确认」的单状态</b>：
 * 未登记的供应商一律视为未确认（fail-closed）。
 *
 * <p><b>供应商标识与模型标识的关系</b>：一个供应商通常提供多个模型
 * （如 deepseek 的 chat 与 reasoner）。条款约束的是<b>服务关系</b>而非单个模型，
 * 故按<b>供应商</b>登记即可，无需为每个模型重复。
 */
public final class ZeroRetentionRegistry {

    private final Map<String, ZeroRetentionStatus> byProvider;

    private ZeroRetentionRegistry(Map<String, ZeroRetentionStatus> byProvider) {
        this.byProvider = Collections.unmodifiableMap(new LinkedHashMap<>(byProvider));
    }

    /** 空注册表：所有供应商均未确认（GATE-1 全面生效）。 */
    public static @NonNull ZeroRetentionRegistry empty() {
        return new ZeroRetentionRegistry(Map.of());
    }

    /** 建造注册表。 */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * 查询指定供应商的当前状态。
     *
     * @return 未登记时返回 {@link ZeroRetentionStatus#pending()}，
     *         而非 {@code null} —— 调用方不必处理 null，
     *         且「未登记」自然落入 fail-closed 路径
     */
    public @NonNull ZeroRetentionStatus statusOf(@NonNull String providerId) {
        return byProvider.getOrDefault(providerId, ZeroRetentionStatus.pending());
    }

    /** 该供应商是否允许接入真实学生数据。 */
    public boolean allows(@NonNull String providerId, @NonNull Instant now) {
        return statusOf(providerId).allowsRealStudentData(now);
    }

    /** 已登记的供应商标识。 */
    public @NonNull Set<String> registeredProviders() {
        return byProvider.keySet();
    }

    /**
     * @return 全部供应商中是否<b>都</b>允许接入真实学生数据。
     *         空注册表返回 false（GATE-1 仍生效）
     */
    public boolean allAllow(@NonNull Instant now) {
        return !byProvider.isEmpty()
                && byProvider.values().stream().allMatch(s -> s.allowsRealStudentData(now));
    }

    /** 已确认但即将/已经过期的供应商标识。用于告警。 */
    public @NonNull Set<String> expiredOrExpiring(@NonNull Instant now, long warnDays) {
        Set<String> result = new LinkedHashSet<>();
        Instant threshold = now.plusSeconds(warnDays * 86400L);
        byProvider.forEach((id, status) -> {
            ZeroRetentionStatus.State effective = status.effectiveState(now);
            if (effective == ZeroRetentionStatus.State.EXPIRED) {
                result.add(id);
            } else if (effective == ZeroRetentionStatus.State.CONFIRMED
                    && status.expiresAt() != null
                    && status.expiresAt().isBefore(threshold)) {
                result.add(id);
            }
        });
        return result;
    }

    /** 注册表建造器。 */
    public static final class Builder {

        private final Map<String, ZeroRetentionStatus> map = new LinkedHashMap<>();

        private Builder() {
        }

        /** 登记一个未确认的供应商（记录存在本身也有价值，便于追踪待办）。 */
        public @NonNull Builder pending(@NonNull String providerId) {
            map.put(providerId, ZeroRetentionStatus.pending());
            return this;
        }

        /** 登记一个已明确拒绝的供应商。 */
        public @NonNull Builder rejected(@NonNull String providerId) {
            map.put(providerId, ZeroRetentionStatus.pending().reject(providerId));
            return this;
        }

        /**
         * 登记一个已确认的供应商。
         *
         * @param providerId  供应商标识
         * @param evidenceRef 证据出处（合同条款号 / 工单号 / 邮件存档编号）
         * @param confirmedAt 确认时间
         * @param validFor    条款有效期；null 表示无到期
         */
        public @NonNull Builder confirmed(@NonNull String providerId,
                                          @NonNull String evidenceRef,
                                          @NonNull Instant confirmedAt,
                                          @Nullable Duration validFor) {
            Instant expiresAt = validFor == null ? null : confirmedAt.plus(validFor);
            map.put(providerId,
                    ZeroRetentionStatus.pending().confirm(providerId, evidenceRef, confirmedAt, expiresAt));
            return this;
        }

        /** 以绝对到期时刻登记。 */
        public @NonNull Builder confirmedUntil(@NonNull String providerId,
                                               @NonNull String evidenceRef,
                                               @NonNull Instant confirmedAt,
                                               Instant expiresAt) {
            map.put(providerId,
                    ZeroRetentionStatus.pending().confirm(providerId, evidenceRef, confirmedAt, expiresAt));
            return this;
        }

        public @NonNull ZeroRetentionRegistry build() {
            return new ZeroRetentionRegistry(map);
        }
    }
}