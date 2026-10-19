package cn.studykid.multiagent.worker.dispatch;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbox 中继（P4-D03 / D-1）。
 *
 * <p>⚠️ <b>本类解决的是「谁驱动 {@link OutboxPublisher}」</b>——
 * 在此之前 {@code publishOnce()} 逻辑完整、测试全绿，
 * 却<b>没有任何调用方</b>：{@code @RabbitListener} 与定时任务均为零。
 * 结果是 Run 创建后消息永远躺在 {@code outbox_event} 表里，
 * 而系统表面完全正常（API 返回 201、无报错、无告警）。
 *
 * <p><b>为何用轮询而非「创建 Run 后直接发 MQ」</b>：
 * Outbox 模式的全部价值在于「Run 与消息在同一事务落库」。
 * 改为直发即失去该保证（DB 提交失败但消息已发 → 幽灵任务）。
 *
 * <p><b>代价</b>：发布延迟 = 轮询间隔（本片默认 200ms）。
 * 这是有意的取舍——用 ≤200ms 延迟换「不丢消息」，教育场景完全可接受。
 *
 * <p><b>可测性</b>：本类<b>不自己开线程</b>，只暴露
 * {@link #relayOnce()}（跑一轮）与 {@link #start()}/{@link #stop()}（生命周期）。
 * 这样单测可同步断言「一轮发了 N 条」，
 * 而不必 sleep 等待定时器——后者是 flaky 测试的主要来源。
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPublisher publisher;

    /** 轮询间隔。 */
    private final Duration interval;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已停止后置位：用于验证「停机后不再领取新任务」（I-5）。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile Thread worker;

    /** 累计已发布条数（供测试与指标读取）。 */
    private volatile long totalPublished;

    public OutboxRelay(@NonNull OutboxPublisher publisher, @NonNull Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            // ⚠️ fail-closed：0 间隔会变成忙等，把 CPU 打满并压垮下游 MQ。
            // 这与 D8 RateLimitPolicy「0 会导致一律拒绝」是同一类防护。
            throw new IllegalArgumentException(
                    "Outbox 中继轮询间隔必须为正数（0 会导致忙等打满 CPU）：" + interval);
        }
        this.publisher = publisher;
        this.interval = interval;
    }

    /**
     * 执行一轮发布（同步，可直接断言）。
     *
     * <p>⚠️ <b>停机后立即返回 0</b>：这是 I-5「中继停机后不再领取新任务」
     * 的执行点——若停机后仍领取，等于在优雅停机窗口内不断产生新工作。
     *
     * @return 本轮发布条数；已停止时恒为 0
     */
    public int relayOnce() {
        if (stopped.get()) {
            log.debug("中继已停止，跳过本轮");
            return 0;
        }
        try {
            int n = publisher.publishOnce();
            if (n > 0) {
                totalPublished += n;
                if (log.isDebugEnabled()) {
                    log.debug("Outbox 中继发布 {} 条，累计 {}", n, totalPublished);
                }
            }
            return n;
        } catch (RuntimeException e) {
            // ⚠️ 单轮失败**不得终止循环**——否则一条坏消息会让整个 Worker 停摆。
            // 这是与 D8「限流组件故障反成 DoS 入口」同源的可用性陷阱。
            log.error("Outbox 中继本轮失败（继续下一轮，不终止循环）: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 启动后台轮询。
     *
     * <p>⚠️ 用<b>守护线程</b>：进程退出时不因本线程挂住。
     * 真正的优雅停机在 {@link #stop()}。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log.info("Outbox 中继启动: interval={}", interval);
            while (running.get() && !stopped.get()) {
                relayOnce();
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Outbox 中继被中断，退出循环");
                    return;
                }
            }
            log.info("Outbox 中继退出");
        }, "outbox-relay");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /**
     * 停止中继（D-4 优雅停机第 ① 步）。
     *
     * <p>⚠️ <b>先置 stopped 再中断线程</b>：
     * 若顺序相反，中断信号可能在 {@code running} 仍为 true 时丢失，
     * 线程会再跑一轮——停机窗口内产生新工作。
     */
    public void stop() {
        stopped.set(true);
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        log.info("Outbox 中继已停止（累计发布 {} 条）", totalPublished);
    }

    /** @return 中继是否处于运行态（供测试断言停机语义）。 */
    public boolean isRunning() {
        return running.get();
    }

    /** @return 累计已发布条数。 */
    public long totalPublished() {
        return totalPublished;
    }
}