package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.Nullable;

/**
 * 背压护栏配置（P4-D05 / 关闭 D03 的 L-6）。
 *
 * <p><b>⚠️ 缺失或非法一律拒绝启动</b>（fail-closed）：
 * 退回默认值等于「悄悄用了一个没人审过的水位线」。
 * 水位线的两个方向都危险：
 * <ul>
 *   <li>过高 = 护栏形同虚设（队列已堆积到危险水位仍全速发布）；</li>
 *   <li>过低 = 正常流量被误判为过载，任务调度长期停滞。</li>
 * </ul>
 * 不存在「保守兜底值」，故不存在默认值。
 *
 * <p>与 D3 {@code BudgetPolicy}、D8 {@code RateLimitPolicy}、
 * D03 {@code WorkerPolicy} 同一取舍。
 */
public final class BackpressurePolicy {

    /** 背压开关。关掉 = 退回 D03 的「无背压」状态，生产必须为 true。 */
    public static final String KEY_ENABLED = "agent.backpressure.enabled";

    /** 高水位线：队列深度 ≥ 此值即停止发布（进入降级）。 */
    public static final String KEY_HIGH_WATERMARK = "agent.backpressure.high-watermark";

    /** 低水位线：队列深度 ≤ 此值才恢复发布（滞回，避免抖动）。 */
    public static final String KEY_LOW_WATERMARK = "agent.backpressure.low-watermark";

    /** 降级后的轮询间隔倍数（队列过载时不应再高频打扰 broker）。 */
    public static final String KEY_COOLDOWN_MULTIPLIER = "agent.backpressure.cooldown-multiplier";

    private BackpressurePolicy() {
    }

    public static int requireHighWatermark(@Nullable Object raw) {
        return (int) requirePositive(raw, KEY_HIGH_WATERMARK);
    }

    /**
     * 低水位线。
     *
     * <p>⚠️ <b>必须严格小于高水位线</b>：两者相等会让滞回区间为空，
     * 系统在高水位附近来回切换（抖动），
     * 表现为「发布—停止—发布—停止」的振荡，比恒定停发更难排障。
     */
    public static int requireLowWatermark(@Nullable Object lowRaw, int highWatermark) {
        int low = (int) requirePositive(lowRaw, KEY_LOW_WATERMARK);
        if (low >= highWatermark) {
            throw new IllegalStateException(
                    "低水位线必须小于高水位线（否则滞回区间为空，会在阈值处抖动）："
                            + KEY_LOW_WATERMARK + " = " + low + ", "
                            + KEY_HIGH_WATERMARK + " = " + highWatermark);
        }
        return low;
    }

    public static int requireCooldownMultiplier(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_COOLDOWN_MULTIPLIER);
        // ⚠️ 倍数过大等于「队列恢复后仍长期不发布」——
        //    积压已经清空却没人干活，是另一种形式的静默停摆。
        if (v > 20) {
            throw new IllegalStateException(
                    "降级间隔倍数过大（>20 会导致队列清空后长期不恢复发布）："
                            + KEY_COOLDOWN_MULTIPLIER + " = " + v);
        }
        return (int) v;
    }

    private static long requirePositive(@Nullable Object raw, String key) {
        if (raw == null) {
            throw new IllegalStateException(
                    "背压配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "背压配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value <= 0) {
            throw new IllegalStateException(
                    "背压配置必须为正数（0 会导致行为不可预期）：" + key + " = " + value);
        }
        return value;
    }
}
