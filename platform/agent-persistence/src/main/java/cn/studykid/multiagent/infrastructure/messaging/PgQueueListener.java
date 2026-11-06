package cn.studykid.multiagent.infrastructure.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * PostgreSQL 队列消费端（P4-D17 · RabbitMQ 的可切换替代）。
 *
 * <p><b>⚠️ 三态映射必须与 Rabbit 通道逐条对齐</b>——
 * 否则「切换通道」就等于「改变消息语义」，那不是切换，是换 bug：
 * <ul>
 *   <li>{@code CLAIMED} → 删除消息（含 UNKNOWN，<b>不得重投</b>）；</li>
 *   <li>{@code RETRY} → 退回 PENDING 并退避；次数耗尽 → 进死信；</li>
 *   <li>{@code REJECTED} → 进死信（不重投）。</li>
 * </ul>
 *
 * <p><b>⚠️ UNKNOWN 必须丢弃</b>（全项目最危险的一行映射）：
 * UNKNOWN 表示「不确定副作用是否已发生」。重投 = 同一个
 * {@code record_weakness} 再执行 = <b>重复写入</b>（Sev-0）。
 * 对账由 D3 的工具台账负责，<b>不靠消息重投</b>。
 *
 * <p><b>⚠️ 消费在「领取事务之外」执行</b>：领取只做「置 INFLIGHT 并提交」，
 * 真正的执行循环在事务外跑。否则一次长执行会长时间持有行锁，
 * 既阻塞其他实例领取，也会让「领取」这一动作退化成全局串行。
 * 代价是「领取后进程崩溃」会让行停在 INFLIGHT——
 * 由<b>可见性超时</b>回收（见 {@code PgQueueRepository#claim}）。
 *
 * <p><b>⚠️ 重复消费是正常形态</b>（at-least-once）：可见性超时回收、
 * 进程崩溃、网络分区都会导致同一条消息被处理两次。
 * 安全性由既有的<b>租约 + fencing</b> 保证：
 * 第二个消费者领取同一 Run 时会被 fencing 拒绝（副作用不会重复发生）。
 * 故本类<b>不追求「恰好一次」</b>，那在分布式下不可得。
 *
 * <p><b>可测性</b>：本类<b>不自己开线程</b>，只暴露
 * {@link #pollOnce()}（跑一轮）与 {@link #start()}/{@link #stop()}（生命周期），
 * 单测可同步断言「一轮处理了 N 条」，不必 sleep（flaky 测试的主要来源）。
 */
public class PgQueueListener {

    private static final Logger log = LoggerFactory.getLogger(PgQueueListener.class);

    /** 本监听器负责的租户（**仅 DB 模式的租户**）。 */
    private final List<String> tenantIds;

    private final PgQueueRepository repository;

    private final MessageHandler handler;

    /** 轮询间隔。 */
    private final Duration interval;

    /** 单轮单租户领取上限。 */
    private final int batchSize;

    /** 重试次数上限（超过即进死信；对齐 Rabbit 的 delivery-limit=3）。 */
    private final int maxAttempts;

    /** 可见性超时：超过即认为持有者失联，行被回收重投。 */
    private final Duration visibilityTimeout;

    /** 重试退避基数（指数退避的底数）。 */
    private final Duration retryBackoffBase;

    /** 退避上限。 */
    private final Duration retryBackoffMax;

    /**
     * 本进程的 Worker 身份。
     *
     * <p>⚠️ 这个字符串会被 {@code RunDispatchConsumer} 原样写进
     * {@code run.lease_owner}，而后续续租与提交终态由
     * {@code RunExecutionLoop} 用它<b>自己</b>构造时的身份去匹配
     * ——两者必须来自<b>同一个配置源</b>（{@code agent.worker.id}）。
     *
     * <p><b>历史事故</b>（2026-10-28 真实模型端到端实测）：本字段曾固定为
     * 「{@code pg-worker-<pid>}」，而执行循环用配置值「worker-local」，
     * 于是每一次续租的 {@code WHERE lease_owner = ?} 都匹配 0 行，
     * 任何带工具调用的 Run（≥2 轮）都在第一个工具<b>执行成功之后</b>
     * 被判成「已被接管」而放弃提交 —— Run 永久卡在 RUNNING，
     * 副作用却已经落地。故身份必须由装配层显式注入，不得在本类自造。
     */
    private final String workerId;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已停止后置位：用于验证「停机后不再领取新任务」。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * 停机标志（区别于 {@code stopped}）。
     *
     * <p>{@code stopped} 用于「停机后不再领取新任务」的语义判断；
     * {@code shuttingDown} 用于「把停机窗口内的 DB 异常降级为 DEBUG」——
     * 否则 DataSource 关闭导致的 {@code HikariDataSource has been closed}
     * 会被误判成一次真实的消费失败，污染告警。
     */
    private volatile boolean shuttingDown = false;

    private volatile Thread worker;

    /** 停机时等待工作线程退出的最长时间（避免无限阻塞上下文关闭）。 */
    private static final long STOP_TIMEOUT_MS = 30_000L;

    /** 累计处理条数（供测试与指标读取）。 */
    private volatile long totalProcessed;

    /** 累计进入死信条数。 */
    private volatile long totalDeadLettered;

    public PgQueueListener(@NonNull List<String> tenantIds,
                            @NonNull PgQueueRepository repository,
                            @NonNull MessageHandler handler,
                            @NonNull Duration interval,
                            int batchSize,
                            int maxAttempts,
                            @NonNull Duration visibilityTimeout,
                            @NonNull Duration retryBackoffBase,
                            @NonNull Duration retryBackoffMax) {
        this(tenantIds, repository, handler, interval, batchSize, maxAttempts,
                visibilityTimeout, retryBackoffBase, retryBackoffMax, null);
    }

    /**
     * @param workerIdOverride 本进程的 Worker 身份；{@code null}/空白时退回
     *                         「{@code pg-worker-<pid>}」占位（仅供单测与遗留装配）
     */
    public PgQueueListener(@NonNull List<String> tenantIds,
                            @NonNull PgQueueRepository repository,
                            @NonNull MessageHandler handler,
                            @NonNull Duration interval,
                            int batchSize,
                            int maxAttempts,
                            @NonNull Duration visibilityTimeout,
                            @NonNull Duration retryBackoffBase,
                            @NonNull Duration retryBackoffMax,
                            @Nullable String workerIdOverride) {
        if (tenantIds == null) {
            throw new IllegalArgumentException("PG 队列监听器租户清单不得为 null");
        }
        // ⚠️ 空清单**允许**（纯 Rabbit 部署下 DB 通道无人使用），
        //    但此时 start() 不启线程——否则会起一个空转轮询线程白白打库。
        //    注意：空清单 + 误判「DB 通道在用」会导致消息永远滞留，
        //    故装配层用「DB 模式租户数 > 0」作为启动依据（见 WorkerLoopConfig）。
        if (batchSize <= 0) {
            throw new IllegalArgumentException("单轮领取上限必须为正数：" + batchSize);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("重试次数上限必须为正数：" + maxAttempts);
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("轮询间隔必须为正数（0 会导致忙等打满 CPU）：" + interval);
        }
        if (visibilityTimeout.isZero() || visibilityTimeout.isNegative()) {
            throw new IllegalArgumentException("可见性超时必须为正数：" + visibilityTimeout);
        }
        this.tenantIds = List.copyOf(tenantIds);
        this.repository = repository;
        this.handler = handler;
        this.interval = interval;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.visibilityTimeout = visibilityTimeout;
        this.retryBackoffBase = retryBackoffBase;
        this.retryBackoffMax = retryBackoffMax;
        this.workerId = workerIdOverride == null || workerIdOverride.isBlank()
                ? "pg-worker-" + ProcessHandle.current().pid()
                : workerIdOverride.trim();
    }

    /**
     * 执行一轮消费（同步，可直接断言）。
     *
     * @return 本轮<b>完成处理</b>（CLAIMED）的条数
     */
    public int pollOnce() {
        if (stopped.get()) {
            return 0;
        }
        int processed = 0;
        for (String tenantId : tenantIds) {
            List<PgQueueRepository.QueuedMessage> batch;
            try {
                batch = repository.claim(tenantId, batchSize, visibilityTimeout, workerId);
            } catch (RuntimeException e) {
                if (shuttingDown) {
                    // ⚠️ 停机窗口内 DataSource 已关闭，领取失败是预期后果，降级为 DEBUG。
                    log.debug("停机窗口内 PG 队列领取失败（忽略，DataSource 正在关闭）: "
                            + "tenantId={}, error={}", tenantId, e.getMessage());
                } else {
                    // ⚠️ 单租户失败不得终止本轮其他租户（同 OutboxRelay 的隔离要求）
                    log.error("PG 队列领取失败（继续下一个租户）: tenantId={}, error={}",
                            tenantId, e.getMessage(), e);
                }
                continue;
            }
            for (PgQueueRepository.QueuedMessage message : batch) {
                if (handleOne(tenantId, message)) {
                    processed++;
                }
            }
        }
        if (processed > 0) {
            totalProcessed += processed;
        }
        return processed;
    }

    /** @return 本条消息是否已完成（CLAIMED） */
    private boolean handleOne(String tenantId, PgQueueRepository.QueuedMessage message) {
        // 一条消息 = 一条系统链路（DB 队列模式没有 MQ 消息头可沿用，就地生成）。
        // ⚠️ 作用域必须包住整个方法体包括错误分支：本方法有多个 return，
        //    逐个手动 clear 迟早漏一条路径。
        try (LogContext.Scope ignored = LogContext.push(new LogContext.Snapshot(
                LogContext.newTraceId(), tenantId, LogContext.SYSTEM))) {
            return doHandle(tenantId, message);
        }
    }

    private boolean doHandle(String tenantId, PgQueueRepository.QueuedMessage message) {
        Map<String, Object> payload = message.payload();

        // ⚠️⚠️ **已达上限则直接进死信**（P1-13 修复的完整化）：
        //    消息的 {@code attempts} 现在计入了「因持有者失联而被回收」的次数（回收时 +1）。
        //    此前这部分消息永远不会进死信（下面的上限检查只在「处理者返回」的路径上）：
        //    消费者卡死/卡住时，消息被无限回收重投 → 永不进死信，也从不告警。
        //    这里直接判死信，与 {@code retryOrDead} 的上限口径一致。
        if (message.attempts() >= maxAttempts) {
            log.error("消息已达重试上限，直接进死信: queueId={}, attempts={}",
                    message.queueId(), message.attempts());
            deadLetter(tenantId, message,
                    "重试 " + message.attempts() + " 次仍失败（进死信）");
            return false;
        }

        // ⚠️ I-1 纵深防御：消费侧也校验，堵住「绕过 Publisher 直接入队」的路径
        try {
            OutboxMessage.assertPayloadSafe(payload);
        } catch (RuntimeException e) {
            // ⚠️ 解析失败直接进死信：同一坏消息重投多少次都一样，
            //    重投只会耗尽次数后才进死信，白白延迟。
            log.error("消息不安全，直接进死信: queueId={}, error={}",
                    message.queueId(), e.getMessage());
            deadLetter(tenantId, message, e.getMessage());
            return false;
        }

        MessageHandler.Outcome outcome;
        try {
            outcome = handler.handle(message.eventType(), payload, workerId);
        } catch (RuntimeException e) {
            // ⚠️ 处理器异常 → 重试（可重试故障）。
            //    注意：**不是** UNKNOWN 语义——UNKNOWN 由 handler 显式返回，
            //    异常路径无法判定副作用，故按可重试处理并留日志。
            log.error("消费处理异常，退回重试: queueId={}, error={}",
                    message.queueId(), e.getMessage(), e);
            retryOrDead(tenantId, message, e.getMessage());
            return false;
        }

        switch (outcome) {
            case CLAIMED -> {
                repository.ack(tenantId, message.queueId(), workerId);
                return true;
            }
            case RETRY -> {
                log.warn("可重试失败，退回重试: queueId={}", message.queueId());
                retryOrDead(tenantId, message, "消费端判定为可重试失败");
                return false;
            }
            case REJECTED -> {
                log.error("消费被拒，进死信: queueId={}", message.queueId());
                deadLetter(tenantId, message, "消费端判定为不可恢复");
                return false;
            }
            default -> {
                // ⚠️ 枚举新增值时的兜底：**默认丢弃而非重投**（同 Rabbit 通道）。
                //    新增枚举值若忘记映射，「重投」会造成重复副作用（Sev-0），
                //    「丢弃」只是丢一次执行——安全方向的默认值。
                log.error("未知 Outcome={}，按丢弃处理（安全默认值）", outcome);
                repository.ack(tenantId, message.queueId(), workerId);
                return true;
            }
        }
    }

    /**
     * 退回重试；次数耗尽则进死信。
     *
     * <p>⚠️ 与 Rabbit 通道的等价关系：Rabbit 的 {@code x-delivery-limit=3}
     * 统计的是「投递次数」，本实现的 {@code attempts} 统计的是「已重试次数」，
     * 上限含义一致（连续失败达上限即进死信，须人工介入）。
     */
    private void retryOrDead(String tenantId, PgQueueRepository.QueuedMessage message,
                              String error) {
        int nextAttempts = message.attempts() + 1;
        if (nextAttempts >= maxAttempts) {
            deadLetter(tenantId, message, error + "（重试 " + nextAttempts + " 次仍失败）");
            return;
        }
        repository.retry(tenantId, message.queueId(), error,
                Instant.now().plus(backoffFor(nextAttempts)), workerId);
    }

    /** 指数退避（基数 × 2^(n-1)，封顶）。 */
    private Duration backoffFor(int attempts) {
        long base = retryBackoffBase.toMillis();
        long capped = Math.min(retryBackoffMax.toMillis(), base * (1L << Math.max(0, attempts - 1)));
        return Duration.ofMillis(capped);
    }

    private void deadLetter(String tenantId, PgQueueRepository.QueuedMessage message, String error) {
        repository.reject(tenantId, message.queueId(), message.eventType(),
                message.runId(), message.payload(), message.attempts() + 1, error, workerId);
        totalDeadLettered++;
        log.error("消息已进死信: queueId={}, runId={}, error={}",
                message.queueId(), message.runId(), error);
    }

    /** 启动后台轮询。 */
    public void start() {
        if (tenantIds.isEmpty()) {
            // ⚠️ 无 DB 模式租户 → 不启线程（空转轮询只会白白打库）
            log.info("PG 队列监听器未启动（无 database 模式租户）");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log.info("PG 队列监听器启动: tenants={}, interval={}", tenantIds.size(), interval);
            while (running.get() && !stopped.get()) {
                try {
                    pollOnce();
                } catch (RuntimeException e) {
                    if (shuttingDown) {
                        // ⚠️ 停机窗口内 DataSource 已关闭，轮次异常是预期后果，降级为 DEBUG。
                        log.debug("停机窗口内 PG 队列消费轮次异常（忽略，DataSource 正在关闭）: {}",
                                e.getMessage());
                    } else {
                        // ⚠️ 单轮异常不得终止循环：否则一次偶发故障就永久停消费
                        //    （症状与「消息永不消费」完全相同，且更难察觉）。
                        log.error("PG 队列消费轮次异常（继续下一轮）: {}", e.getMessage(), e);
                    }
                }
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("PG 队列监听器被中断，退出循环");
                    return;
                }
            }
            log.info("PG 队列监听器退出");
        }, "pg-queue-listener");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停止监听器（先置 stopped 再中断：顺序反了会在停机窗口内继续领取新任务）。 */
    public void stop() {
        shuttingDown = true;
        stopped.set(true);
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                // ⚠️ 关键修复：必须等 worker 线程真正退出，否则守护线程会在
                //    DataSource 关闭后仍在跑，下一次 getConnection 命中已关闭的池 →
                //    "HikariDataSource has been closed"。受 Spring LIFO 析构顺序保证，
                //    本方法返回时 DataSource 尚未关闭，故 join 期间在途消费面对的是
                //    仍打开的池，可干净收尾。
                t.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待 PG 队列监听线程退出被中断（超时 {}ms）", STOP_TIMEOUT_MS);
            }
        }
        log.info("PG 队列监听器已停止（累计处理 {} 条，死信 {} 条）",
                totalProcessed, totalDeadLettered);
    }

    /** @return 本实例负责的租户数（可观测性） */
    public int tenantCount() {
        return tenantIds.size();
    }

    /** @return 累计完成处理的条数 */
    public long totalProcessed() {
        return totalProcessed;
    }

    /** @return 累计进入死信的条数 */
    public long totalDeadLettered() {
        return totalDeadLettered;
    }
}
