package cn.studykid.multiagent.application.backpressure;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * 背压控制器（P4-D05 / 关闭 D03 的 L-6）。
 *
 * <p><b>要解决的问题</b>：D03 的 Worker 只有消费并发度一个上限，
 * 它限制的是「本机同时跑几个 Run」，而不是「队列里堆了多少」。
 * 生产快于消费时队列无界增长，且<b>无任何异常信号</b>。
 *
 * <p><b>⚠️ 三条关键语义</b>：
 * <ol>
 *   <li><b>滞回（hysteresis）</b>：≥ 高水位停发，≤ 低水位才恢复，
 *       两者之间<b>维持上一状态</b>。无滞回会在阈值处抖动
 *       （发布—停止—发布—停止），比恒定停发更难排障。</li>
 *   <li><b>探测失败 = 超限</b>（fail-closed）：探测异常时停发。
 *       放行等于「背压在最需要它的时候失效」，
 *       与 D8「限流组件故障反成 DoS 入口」同源。</li>
 *   <li><b>降级后拉长轮询间隔</b>：队列已过载时高频探测只是给 broker 加压。</li>
 * </ol>
 *
 * <p><b>可测性</b>：深度来自 {@link QueueDepthProbe} 端口，
 * 单测注入固定深度即可断言状态机，无需真实 broker。
 */
public final class BackpressureController {

    private static final Logger log = LoggerFactory.getLogger(BackpressureController.class);

    private final QueueDepthProbe probe;
    private final String queueName;
    private final int highWatermark;
    private final int lowWatermark;
    private final int cooldownMultiplier;

    /** 当前是否处于降级（停发）状态。 */
    private final AtomicBoolean throttled = new AtomicBoolean(false);

    /** 处于降级状态的轮次数（供告警与人工判断）。 */
    private final AtomicLong throttledRounds = new AtomicLong();

    /** 探测失败次数（供告警：持续失败即 broker 或拓扑有问题）。 */
    private final AtomicLong probeFailures = new AtomicLong();

    /** 最近一次成功探测到的深度（-1 表示尚未探测成功过）。 */
    private volatile int lastDepth = -1;

    public BackpressureController(@NonNull QueueDepthProbe probe,
                                  @NonNull String queueName,
                                  int highWatermark,
                                  int lowWatermark,
                                  int cooldownMultiplier) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("背压探测的队列名不得为空");
        }
        if (highWatermark <= 0 || lowWatermark <= 0) {
            throw new IllegalArgumentException(
                    "水位线必须为正数: high=" + highWatermark + ", low=" + lowWatermark);
        }
        if (lowWatermark >= highWatermark) {
            // ⚠️ 滞回区间为空 → 阈值处抖动，必须在构造期拒绝
            throw new IllegalArgumentException(
                    "低水位线必须小于高水位线（否则在阈值处抖动）: low="
                            + lowWatermark + ", high=" + highWatermark);
        }
        if (cooldownMultiplier < 1) {
            throw new IllegalArgumentException(
                    "降级间隔倍数不得小于 1: " + cooldownMultiplier);
        }
        this.probe = probe;
        this.queueName = queueName;
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.cooldownMultiplier = cooldownMultiplier;
    }

    /**
     * 本轮是否允许发布。
     *
     * <p>⚠️ <b>探测失败返回 false</b>（fail-closed）：见类注释第 2 条。
     *
     * @return true 表示本轮可发布
     */
    public boolean allowPublish() {
        int depth;
        try {
            depth = probe.depthOf(queueName);
        } catch (RuntimeException e) {
            // ⚠️⚠️ fail-closed：探测不到深度时**当作超限**。
            //    返回 true 会让「背压失效」与「队列正常」无法区分——
            //    这正是监控盲区最危险的形态。
            probeFailures.incrementAndGet();
            throttled.set(true);
            throttledRounds.incrementAndGet();
            log.error("⚠️ 队列深度探测失败，按『超限』停发（fail-closed）: queue={}, 累计失败={}",
                    queueName, probeFailures.get(), e);
            return false;
        }
        lastDepth = depth;

        if (depth >= highWatermark) {
            setThrottled(true, depth);
            return false;
        }
        if (depth <= lowWatermark) {
            setThrottled(false, depth);
            return true;
        }
        // ⚠️ 滞回区间：维持上一状态，不抖动
        if (throttled.get()) {
            throttledRounds.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("队列深度仍在滞回区间，维持停发: depth={}, low={}, high={}",
                        depth, lowWatermark, highWatermark);
            }
        }
        return !throttled.get();
    }

    private void setThrottled(boolean next, int depth) {
        boolean previous = throttled.getAndSet(next);
        if (next) {
            throttledRounds.incrementAndGet();
            if (!previous) {
                // ⚠️ 只在「刚进入降级」时告警：每轮都告警会把日志打满
                log.warn("⚠️ 队列深度达高水位，Outbox 中继停止发布: queue={}, depth={}, high={}",
                        queueName, depth, highWatermark);
            }
        } else if (previous) {
            log.info("队列深度已回落到低水位，恢复发布: queue={}, depth={}, low={}",
                    queueName, depth, lowWatermark);
        }
    }

    /** @return 当前是否处于降级（停发）状态。 */
    public boolean isThrottled() {
        return throttled.get();
    }

    /** @return 累计处于停发状态的轮次（供告警）。 */
    public long throttledRounds() {
        return throttledRounds.get();
    }

    /** @return 累计探测失败次数（持续 >0 说明 broker 或拓扑有问题）。 */
    public long probeFailures() {
        return probeFailures.get();
    }

    /** @return 最近一次成功探测到的深度；{@code -1} 表示从未探测成功。 */
    public int lastDepth() {
        return lastDepth;
    }

    /**
     * 本轮结束后应等待的间隔（降级时拉长，避免给已过载的 broker 加压）。
     */
    public @NonNull Duration effectiveInterval(@NonNull Duration baseInterval) {
        return throttled.get() ? baseInterval.multipliedBy(cooldownMultiplier) : baseInterval;
    }
}
