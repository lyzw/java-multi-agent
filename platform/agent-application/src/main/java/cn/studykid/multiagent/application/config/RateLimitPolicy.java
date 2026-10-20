package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 限流配置（P3-D08 / D-2）。
 *
 * <p><b>⚠️ 缺失或非法一律拒绝启动</b>（fail-closed）：退回默认值等于
 * 「悄悄用了一个没人审过的限额」——与 D4 {@code BudgetPolicy} 同一取舍。
 *
 * <p>初值取<b>保守</b>档，接入真实流量后按实测调整（P4）。
 */
public final class RateLimitPolicy {

    /** 每租户每窗口的最大请求数。 */
    public static final String KEY_LIMIT = "agent.rate-limit.limit-per-window";

    /** 窗口长度（秒）。 */
    public static final String KEY_WINDOW_SECONDS = "agent.rate-limit.window-seconds";

    /**
     * 治理类查询桶的限额（P4-D13）。
     *
     * <p>⚠️ 与全局限额<b>分开</b>：全局限额服务的是「单个学生的个人动作」
     * （建 Run / SSE / 状态轮询），而治理桶是<b>一个租户的多个运维共享</b>。
     * 用同一个阈值会让运维之间互相限流。
     */
    public static final String KEY_GOVERNANCE_QUERY_LIMIT =
            "agent.rate-limit.governance-query-limit-per-window";

    /** 治理类决策桶的限额（P4-D13）；低于查询桶——写操作后果不可逆。 */
    public static final String KEY_GOVERNANCE_DECIDE_LIMIT =
            "agent.rate-limit.governance-decide-limit-per-window";

    private static final String BUCKET_GOVERNANCE_QUERY = "GOVERNANCE_QUERY";
    private static final String BUCKET_GOVERNANCE_DECIDE = "GOVERNANCE_DECIDE";
    private static final long DEFAULT_GOVERNANCE_LIMIT = 60L;

    private RateLimitPolicy() {
    }

    /**
     * 解析限额。
     *
     * @throws IllegalStateException 缺失或非正数（fail-closed）
     */
    public static long requireLimit(@Nullable Object raw) {
        return requirePositive(raw, KEY_LIMIT);
    }

    /**
     * 解析窗口长度。
     *
     * @throws IllegalStateException 缺失或非正数（fail-closed）
     */
    public static long requireWindowSeconds(@Nullable Object raw) {
        return requirePositive(raw, KEY_WINDOW_SECONDS);
    }

    private static long requirePositive(@Nullable Object raw, String key) {
        if (raw == null) {
            throw new IllegalStateException(
                    "限流配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "限流配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value <= 0) {
            throw new IllegalStateException(
                    "限流配置必须为正数（0 会导致一律拒绝）：" + key + " = " + value);
        }
        return value;
    }

    /** @return 非空标注断言（保持与 BudgetPolicy 同源的风格）。 */
    public static @NonNull String describe() {
        return KEY_LIMIT + " / " + KEY_WINDOW_SECONDS;
    }

    /**
     * 治理桶限额缺省值。
     *
     * <p>⚠️ 与 {@link #requireLimit} 的 fail-closed 取舍<b>刻意不同</b>：
     * 治理桶阈值配错只会让运维台变慢/变严，<b>不会导致越权</b>；
     * 但它也绝不能取 0（「0 = 一律拒绝」会把治理台彻底锁死）。
     * 故取一个保守但可用的缺省值，允许通过 yml 调整。
     *
     * @param bucketKey 桶名；决定用哪个缺省值
     */
    public static long governanceLimitFor(@NonNull String bucketKey) {
        if (BUCKET_GOVERNANCE_QUERY.equals(bucketKey)) {
            return 120L;
        }
        if (BUCKET_GOVERNANCE_DECIDE.equals(bucketKey)) {
            return 30L;
        }
        return DEFAULT_GOVERNANCE_LIMIT;
    }
}
