package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.Nullable;

/**
 * Worker 主循环配置（P4-D03 / D-3）。
 *
 * <p><b>⚠️ 缺失或非法一律拒绝启动</b>（fail-closed）：
 * 退回默认值等于「悄悄用了一个没人审过的并发度」。
 * 容器化环境下默认并发可能直接打垮下游模型服务与 MQ。
 *
 * <p>与 D4 {@code BudgetPolicy}、D8 {@code RateLimitPolicy} 同一取舍。
 */
public final class WorkerPolicy {

    /** 中继轮询间隔毫秒。 */
    public static final String KEY_RELAY_INTERVAL_MS = "agent.worker.relay-interval-ms";

    /** 消费并发度。 */
    public static final String KEY_CONCURRENCY = "agent.worker.concurrency";

    /** 每消费者预取数（越大吞吐越高，但重投时重复处理窗口也越大）。 */
    public static final String KEY_PREFETCH = "agent.worker.prefetch";

    /** 单轮发布的批量上限。 */
    public static final String KEY_OUTBOX_BATCH = "agent.worker.outbox-batch";

    /** 发布失败累计上限（超过则停止重试并告警）。 */
    public static final String KEY_MAX_PUBLISH_ATTEMPTS = "agent.worker.max-publish-attempts";

    private WorkerPolicy() {
    }

    public static long requireRelayIntervalMs(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_RELAY_INTERVAL_MS);
        // ⚠️ 过小的间隔等于忙等，会把 CPU 打满并压垮下游。
        if (v < 10) {
            throw new IllegalStateException(
                    "中继轮询间隔过小（<10ms 会变成忙等）：" + KEY_RELAY_INTERVAL_MS
                            + " = " + v);
        }
        return v;
    }

    public static int requireConcurrency(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_CONCURRENCY);
        if (v > 64) {
            throw new IllegalStateException(
                    "消费并发度过高（>64 会压垮下游模型服务）："
                            + KEY_CONCURRENCY + " = " + v);
        }
        return (int) v;
    }

    public static int requirePrefetch(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_PREFETCH);
        if (v > 100) {
            throw new IllegalStateException(
                    "预取数过高（>100 会放大重投时的重复处理窗口）："
                            + KEY_PREFETCH + " = " + v);
        }
        return (int) v;
    }

    public static int requireOutboxBatch(@Nullable Object raw) {
        return (int) requirePositive(raw, KEY_OUTBOX_BATCH);
    }

    public static int requireMaxPublishAttempts(@Nullable Object raw) {
        return (int) requirePositive(raw, KEY_MAX_PUBLISH_ATTEMPTS);
    }

    private static long requirePositive(@Nullable Object raw, String key) {
        if (raw == null) {
            throw new IllegalStateException(
                    "Worker 配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Worker 配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value <= 0) {
            throw new IllegalStateException(
                    "Worker 配置必须为正数（0 会导致行为不可预期）：" + key + " = " + value);
        }
        return value;
    }
}