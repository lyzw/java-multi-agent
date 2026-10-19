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
}
