package cn.studykid.multiagent.worker.retention;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.agentchataudit.ChatAuditRetentionService;
import cn.studykid.multiagent.application.agentchataudit.ChatAuditRetentionService.Report;

/**
 * 对话审计保留期清理任务（PIPL §7）。
 *
 * <p><b>⚠️⚠️ 本类的存在本身就是一条 Amendment</b>：本项目已经三次遇到
 * 「机制正确、测试全绿、但不在链路上」（D01 断链第一层、D03 断链第二层、
 * 以及 {@code run_message} 的删除权限给了却无人调用）。
 * 若只交付 {@link ChatAuditRetentionService} 而不交付驱动它的东西，
 * 就是第四次复制同一错误——而且这次更隐蔽：
 * 它表现为「原文按承诺被保留，实际从未被删除」，系统无报错、健康检查全绿。
 *
 * <p><b>为什么不能直接塞进 {@code RetentionJob}</b>：
 * <ol>
 *   <li><b>窗口来源不同</b>：本任务的保留天数来自<b>前端可改的</b>
 *       {@code retention.days}，而 {@code RetentionJob} 用 yml 的
 *       {@code RetentionSettings}。合在一起会出现「页面写 7 天、实际按 90 天删」
 *       这类无法从任何单一位置解释的偏差。</li>
 *   <li><b>文件侧清理是全局的</b>：目录布局 {@code {dir}/{date}/audit-*.json}
 *       没有租户维度，塞进「按租户循环」的 {@code RetentionJob} 会被重复执行 N 次。</li>
 * </ol>
 *
 * <p><b>可测性</b>（与 D03 {@code OutboxRelay} / {@code RetentionJob} 同形态）：
 * 本类不自己决定何时被调用——暴露 {@link #purgeOnce()} 供同步断言，
 * 由 {@link #start()} 起后台线程，测试无需 sleep 等定时器。
 *
 * <p><b>⚠️ 失败可见性</b>：{@link Report#failedCategories()} 若无人消费，
 * 就退化为装饰性字段。本类把它累计到 {@link #totalFailedCategories()}
 * 并在非零时打 WARN——这是它唯一的告警出口。
 */
public class ChatAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditRetentionJob.class);

    private static final long STOP_TIMEOUT_MS = 30_000L;

    private final ChatAuditRetentionService service;

    /** 本实例负责清理的租户。⚠️ 逐租户执行，绝不跨租户批量删。 */
    private final List<String> tenantIds;

    private final Duration interval;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final AtomicLong rounds = new AtomicLong();

    private final AtomicLong failedCategories = new AtomicLong();

    private final AtomicLong rowsDeleted = new AtomicLong();

    private final AtomicLong filesDeleted = new AtomicLong();

    private volatile boolean shuttingDown = false;

    private volatile Thread worker;

    public ChatAuditRetentionJob(@NonNull ChatAuditRetentionService service,
                                 @NonNull List<String> tenantIds,
                                 @NonNull Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "对话审计清理轮询间隔必须为正数（0 会导致忙等打满数据库）: " + interval);
        }
        if (interval.toMillis() < 60_000) {
            // ⚠️ 保留期是天级的，分钟级轮询没有收益，只有磁盘扫描与数据库压力。
            throw new IllegalArgumentException(
                    "对话审计清理间隔过小（<60s 无实际收益，只增加库与磁盘压力）: " + interval);
        }
        if (tenantIds.isEmpty()) {
            // ⚠️ fail-closed：没有租户 = 「什么都不清理」，
            //    而这与「配置错误导致从不清理」在表现上完全一样。
            //    宁可拒绝启动，也不要以空列表安静地跑起来。
            throw new IllegalArgumentException(
                    "对话审计清理任务必须至少指定一个租户：空列表会让本实例静默地从不清理");
        }
        this.service = service;
        this.tenantIds = List.copyOf(tenantIds);
        this.interval = interval;
    }

    /**
     * 执行一轮清理（同步）。
     *
     * <p>⚠️ 停机后返回空报告：停机窗口内不再产生新的、可能跨越停机边界的工作。
     */
    public @NonNull Report purgeOnce() {
        if (stopped.get()) {
            log.debug("对话审计清理任务已停止，跳过本轮");
            return EMPTY;
        }
        Report report;
        try {
            report = service.runOnce(tenantIds);
        } catch (RuntimeException e) {
            if (shuttingDown) {
                // 停机窗口内 DataSource 正在关闭，这不是真实的清理失败。
                log.debug("停机窗口内对话审计清理异常（忽略）", e);
            } else {
                failedCategories.incrementAndGet();
                log.error("对话审计清理整轮失败", e);
            }
            rounds.incrementAndGet();
            return EMPTY;
        }
        rounds.incrementAndGet();
        rowsDeleted.addAndGet(report.rowsDeleted());
        filesDeleted.addAndGet(report.filesDeleted());
        if (report.failedCategories() > 0) {
            failedCategories.addAndGet(report.failedCategories());
            if (!shuttingDown) {
                // ⚠️ 消费失败类别——否则该字段只是装饰。
                //    「清理失败」必须可见：静默失败等于长期合规违约。
                log.warn("对话审计清理存在失败类别: failedCategories={}, 保留期={}天",
                        report.failedCategories(), report.retentionDays());
            }
        }
        if (report.hasChanges()) {
            log.info("对话审计清理本轮完成: rows={}, files={}, retentionDays={}",
                    report.rowsDeleted(), report.filesDeleted(), report.retentionDays());
        }
        return report;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log.info("对话审计保留期清理任务启动: interval={}, tenants={}",
                    interval, tenantIds.size());
            while (running.get() && !stopped.get()) {
                purgeOnce();
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("对话审计清理任务被中断，退出循环");
                    return;
                }
            }
            log.info("对话审计清理任务退出");
        }, "chat-audit-retention-job");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停止清理任务。⚠️ 先置 stopped 再中断，理由同 D03 D-4。 */
    public void stop() {
        shuttingDown = true;
        stopped.set(true);
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                // ⚠️ 必须等 worker 真正退出：否则守护线程会在 DataSource 关闭后
                //    仍在跑，下一次 getConnection 命中已关闭的池。
                t.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待对话审计清理线程退出被中断（超时 {}ms），"
                        + "线程将以守护线程身份随 JVM 退出", STOP_TIMEOUT_MS);
            }
        }
        log.info("对话审计清理任务已停止（累计 {} 轮，删除 {} 行 / {} 文件，失败类别 {} 次）",
                rounds.get(), rowsDeleted.get(), filesDeleted.get(), failedCategories.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 累计执行轮数（连通性测试用：证明「它真的被调用了」）。 */
    public long rounds() {
        return rounds.get();
    }

    /** 累计失败类别数（告警出口）。 */
    public long totalFailedCategories() {
        return failedCategories.get();
    }

    /** 累计删除行数（诊断用）。 */
    public long totalRowsDeleted() {
        return rowsDeleted.get();
    }

    /** 累计删除文件数（诊断用）。 */
    public long totalFilesDeleted() {
        return filesDeleted.get();
    }

    private static final Report EMPTY = new Report(0, 0, 0, 0);
}
