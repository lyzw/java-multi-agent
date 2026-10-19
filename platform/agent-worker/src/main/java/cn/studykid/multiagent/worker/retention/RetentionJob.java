package cn.studykid.multiagent.worker.retention;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.retention.RetentionPurgeService;
import cn.studykid.multiagent.application.retention.RetentionPurgeService.PurgeReport;

/**
 * 保留期清理任务（P4-D04）。
 *
 * <p>⚠️⚠️ <b>本类的存在本身就是一条 Amendment</b>：
 * 本项目已两次遇到「机制正确、测试全绿、但不在链路上」——
 * <ul>
 *   <li>D01 断链第一层：{@code RunDispatchConsumer} 领到租约后不执行；</li>
 *   <li>D03 断链第二层：{@code RunDispatchConsumer} <b>根本没人调用</b>。</li>
 * </ul>
 * 本片若只交付 {@link RetentionPurgeService} 而不交付驱动它的东西，
 * 就是<b>第三次复制同一个错误</b>，且这次更隐蔽：
 * 它表现为「数据按承诺被保留，实际从未被删除」，
 * 而系统无报错、无告警、健康检查全部正常。
 *
 * <p><b>可测性</b>（与 D03 {@code OutboxRelay} 同形态）：
 * 本类不自己决定何时被调用——暴露 {@link #purgeOnce()} 供同步断言，
 * 由 {@link #start()} 起后台线程。这样测试无需 sleep 等待定时器。
 *
 * <p><b>⚠️ 失败可见性</b>：PurgeReport 的失败类别数若无人消费，
 * 就退化为一个装饰性字段。本类把它累计到 {@link #totalFailedCategories()}
 * 并在非零时打 WARN——这是它唯一的告警出口。
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionPurgeService purgeService;

    /** 本实例负责清理的租户。⚠️ 逐租户执行，绝不跨租户批量删。 */
    private final List<String> tenantIds;

    private final Duration interval;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile Thread worker;

    private final AtomicLong rounds = new AtomicLong();

    private final AtomicLong failedCategories = new AtomicLong();

    public RetentionJob(@NonNull RetentionPurgeService purgeService,
                        @NonNull List<String> tenantIds,
                        @NonNull Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "保留期清理轮询间隔必须为正数（0 会导致忙等打满数据库）：" + interval);
        }
        if (tenantIds.isEmpty()) {
            // ⚠️ fail-closed：没有租户 = 「什么都不清理」，
            //    而这与「配置错误导致从不清理」在表现上完全一样。
            //    宁可拒绝启动，也不要以一个空列表安静地跑起来。
            throw new IllegalArgumentException(
                    "清理任务必须至少指定一个租户：空列表会让本实例静默地从不清理");
        }
        if (interval.toMillis() < 60_000) {
            // ⚠️ 保留期是天/月级的，分钟级轮询没有收益，只有数据库压力。
            throw new IllegalArgumentException(
                    "保留期清理间隔过小（<60s 无实际收益，只增加数据库压力）：" + interval);
        }
        this.purgeService = purgeService;
        this.tenantIds = List.copyOf(tenantIds);
        this.interval = interval;
    }

    /**
     * 执行一轮清理（同步）。
     *
     * <p>⚠️ <b>停机后返回空报告</b>：与 D03 I-5 同一语义——
     * 停机窗口内不再产生新的、可能跨越停机边界的工作。
     *
     * @return 本轮结果；已停止时返回空报告
     */
    public PurgeReport purgeOnce() {
        if (stopped.get()) {
            log.debug("清理任务已停止，跳过本轮");
            return EMPTY_REPORT;
        }
        PurgeReport merged = EMPTY_REPORT;
        for (String tenantId : tenantIds) {
            try {
                PurgeReport report = purgeService.runOnce(tenantId);
                merged = aggregate(merged, report);
                if (report.failedCategories() > 0) {
                    // ⚠️ 消费失败类别——否则该字段只是装饰。
                    //    「清理失败」必须可见：静默失败等于长期合规违约。
                    failedCategories.addAndGet(report.failedCategories());
                    log.warn("清理类别失败: tenant={}, failedCategories={}",
                            tenantId, report.failedCategories());
                }
            } catch (RuntimeException e) {
                // ⚠️ 单租户失败不终止整轮：一个租户的坏数据不应拖累其余租户履约。
                failedCategories.incrementAndGet();
                log.error("租户清理整轮失败（继续处理其余租户）: tenant={}", tenantId, e);
            }
        }
        rounds.incrementAndGet();
        if (merged.hasChanges()) {
            log.info("清理本轮完成: profiles={}, prompts={}, traces={}, audits={}, withdrawals={}",
                    merged.profilesDeleted(), merged.promptsBlanked(),
                    merged.tracesDeleted(), merged.auditsDeleted(),
                    merged.withdrawalsErased());
        }
        return merged;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log.info("保留期清理任务启动: interval={}, tenants={}", interval, tenantIds.size());
            while (running.get() && !stopped.get()) {
                purgeOnce();
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("保留期清理任务被中断，退出循环");
                    return;
                }
            }
            log.info("保留期清理任务退出");
        }, "retention-job");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停止清理任务。⚠️ 先置 stopped 再中断，理由同 D03 D-4。 */
    public void stop() {
        stopped.set(true);
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        log.info("保留期清理任务已停止（累计 {} 轮，失败类别 {} 次）",
                rounds.get(), failedCategories.get());
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

    private static PurgeReport aggregate(PurgeReport base, PurgeReport next) {
        return new PurgeReport(
                base.profilesDeleted() + next.profilesDeleted(),
                base.promptsBlanked() + next.promptsBlanked(),
                base.tracesDeleted() + next.tracesDeleted(),
                base.auditsDeleted() + next.auditsDeleted(),
                base.withdrawalsErased() + next.withdrawalsErased(),
                base.failedCategories() + next.failedCategories());
    }

    private static final PurgeReport EMPTY_REPORT = new PurgeReport(0, 0, 0, 0, 0, 0);
}
