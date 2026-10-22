package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.Nullable;

/**
 * 消息通道配置策略（P4-D17 · 可切换消息通道）。
 *
 * <p><b>⚠️ 为什么单独成类</b>：消息通道的配置项与 Worker 主循环的配置项
 * （并发度、预取数）<b>校验规则不同</b>——
 * Worker 的并发度有「上限」（压垮下游），而通道的时长项只有「下限」（0 = 忙等）。
 * 混进 {@link WorkerPolicy} 会让「哪个键走哪条校验路径」变得不可追溯。
 *
 * <p><b>⚠️ 一律 fail-closed</b>：缺失、非法、越界都拒绝启动，
 * 绝不回落默认值。理由与全项目一致——
 * 回落默认值等于「悄悄用了一个没人审过的配置」。
 */
public final class MessagingPolicy {

    /** 全局消息通道模式（{@code rabbitmq} / {@code database}）。 */
    public static final String KEY_MODE = "agent.messaging.mode";

    /** 租户通道覆盖（{@code slug=mode,slug2=mode2}）。 */
    public static final String KEY_TENANT_MODES = "agent.messaging.tenant-modes";

    /** DB 队列消费轮询间隔（毫秒）。 */
    public static final String KEY_POLL_INTERVAL_MS = "agent.messaging.poll-interval-ms";

    /** 单轮单租户领取上限。 */
    public static final String KEY_BATCH_SIZE = "agent.messaging.batch-size";

    /** 重试次数上限（超过即进死信）。 */
    public static final String KEY_MAX_ATTEMPTS = "agent.messaging.max-attempts";

    /** 可见性超时（毫秒）：超过即认为持有者失联，行被回收重投。 */
    public static final String KEY_VISIBILITY_TIMEOUT_MS = "agent.messaging.visibility-timeout-ms";

    /** 重试退避基数（毫秒）。 */
    public static final String KEY_RETRY_BACKOFF_MS = "agent.messaging.retry-backoff-ms";

    /** 重试退避上限（毫秒）。 */
    public static final String KEY_RETRY_BACKOFF_MAX_MS = "agent.messaging.retry-backoff-max-ms";

    private MessagingPolicy() {
    }

    /**
     * 取正整数毫秒（fail-closed）。
     *
     * @param raw    配置原值
     * @param key    配置键（用于报错定位）
     * @param minMs  下限（含）
     * @return 毫秒数
     * @throws IllegalStateException 缺失、非整数、非正或低于下限
     */
    public static long requireMillis(@Nullable Object raw, String key, long minMs) {
        if (raw == null) {
            throw new IllegalStateException(
                    "消息通道配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long v;
        try {
            v = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "消息通道配置不是合法整数：" + key + " = " + raw, e);
        }
        if (v < minMs) {
            throw new IllegalStateException(
                    "消息通道配置过小：" + key + " = " + v
                            + "（下限 " + minMs + "ms；过小等于忙等，会打满 CPU 并压垮数据库）");
        }
        return v;
    }

    /**
     * 取正整数（fail-closed）。
     *
     * @param raw 配置原值
     * @param key 配置键
     * @param max 上限（含）
     * @return 整数
     */
    public static int requireBoundedInt(@Nullable Object raw, String key, int max) {
        if (raw == null) {
            throw new IllegalStateException(
                    "消息通道配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long v;
        try {
            v = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "消息通道配置不是合法整数：" + key + " = " + raw, e);
        }
        if (v <= 0) {
            throw new IllegalStateException(
                    "消息通道配置必须为正数：" + key + " = " + v);
        }
        if (v > max) {
            throw new IllegalStateException(
                    "消息通道配置过大：" + key + " = " + v + "（上限 " + max + "）");
        }
        return (int) v;
    }
}
