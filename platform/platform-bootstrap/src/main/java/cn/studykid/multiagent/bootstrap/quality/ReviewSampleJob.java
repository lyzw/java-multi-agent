package cn.studykid.multiagent.bootstrap.quality;

import java.time.Clock;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.application.review.QualityAlertEvaluator;
import cn.studykid.multiagent.application.review.ReviewSampler;

/**
 * 分层抽样的<b>定时驱动</b>（P3 · §7.2）。
 *
 * <p><b>⚠️ 为什么单独一个类，而不是把 {@code @Scheduled} 挂在 {@code ReviewSampler} 上</b>：
 * <ul>
 *   <li>{@code ReviewSampler} 在 {@code agent-application}，那是纯应用层，
 *       <b>不该感知 Spring 的调度</b>（调度是部署期关注点）。挂在那里会让单测
 *       必须拉起调度容器，也会污染「应用层无框架依赖」的边界；</li>
 *   <li>驱动必须<b>可测</b>：暴露 {@link #sweepOnce()} 供同步断言，
 *       不必 sleep 等定时器（与 {@code RunRecoverySweeper} / {@code RetentionJob} 同纪律）。</li>
 * </ul>
 *
 * <p><b>⚠️⚠️ 本类存在的理由是「不重蹈覆辙」</b>：
 * 本仓已三次出现「机制正确、测试全绿、但不在链路上」——
 * D01（领了租约不执行）、D03（没人调用执行器）、F-14（failsafe 从未配置，
 * 14 个 IT 一条不跑而构建全绿）。若只交付 {@link ReviewSampler} 而没有本类，
 * 就是第四次复制同一个错误：表现是<b>队列里永远只有采集时的内联告警，
 * 分层抽样一条都不进，而系统毫无异常</b>。
 *
 * <p><b>失败可见性</b>：抽样失败不冒泡（一个周期失败不该拖垮调度线程），
 * 但必须 ERROR 级 —— 静默失败的抽样等于<本期没有做分层抽样>，而看板上看不出来。
 */
@Component
public class ReviewSampleJob {

    private static final Logger log = LoggerFactory.getLogger(ReviewSampleJob.class);

    private final ReviewSampler sampler;
    private final QualityAlertEvaluator alerts;
    private final Clock clock;

    public ReviewSampleJob(@NonNull ReviewSampler sampler,
                           @NonNull QualityAlertEvaluator alerts,
                           @NonNull Clock clock) {
        this.sampler = sampler;
        this.alerts = alerts;
        this.clock = clock;
    }

    /**
     * 跑一轮抽样（供定时器与测试调用）。
     *
     * @return 本轮统计；失败时返回 {@link ReviewSampler.Result#empty()}
     */
    public ReviewSampler.Result sweepOnce() {
        try {
            return sampler.sampleOnce(clock.instant());
        } catch (RuntimeException e) {
            // ⚠️ 不重抛：调度线程被异常打断会让后续周期全部不再执行。
            //    但必须 ERROR —— 否则「抽样停了」与「没有候选」在日志上无法区分。
            log.error("分层抽样本轮失败（下个周期重试）", e);
            return new ReviewSampler.Result(0, 0, 0, 0, 0);
        }
    }

    /**
     * 一轮「分层抽样 + 比例类告警」（定时器实际走的入口）。
     *
     * <p>⚠️ <b>两段必须在同一轮里跑</b>：分层抽样把「该审哪些 Trace」推进队列，
     * 比例告警把「系统整体是否在劣化」推进队列。拆成两个定时器会让两者的
     * 时间窗口错开，排障时无法把「某天错误率飙升」与「那批被抽中的样本」对上。
     *
     * <p>⚠️ 抽样失败不阻断告警（反之亦然）：一段抛异常只记 ERROR，
     * 另一段照跑。不能让「抽样器偶发失败」把整个质量告警链一起带掉。
     */
    public void sweepWithAlerts() {
        ReviewSampler.Result sampled = sweepOnce();
        if (sampled.queued() > 0) {
            log.info("分层抽样本轮入队 {} 条（异常 {} / 缺口 {} / 基线 {}，扫描 {}）",
                    sampled.queued(), sampled.anomalyQueued(), sampled.gapQueued(),
                    sampled.baselineQueued(), sampled.scanned());
        }
        try {
            QualityAlertEvaluator.Result alerted = alerts.evaluateOnce(clock.instant());
            if (alerted.fired() > 0) {
                log.warn("比例类质量告警告警 {} 条：{}", alerted.fired(), alerted.reasons());
            } else if (alerted.insufficientSample() > 0) {
                log.info("比例类告警本轮不判（样本不足）：{}", alerted.reasons());
            }
        } catch (RuntimeException e) {
            // ⚠️ 不重抛：调度线程被打断会让后续周期全部不执行。
            log.error("比例类质量告警告警本轮失败（下个周期重试）", e);
        }
    }

    /**
     * 周期扫描。
     *
     * <p>⚠️ {@code fixedDelay}（不是 {@code fixedRate}）：上轮结束后再等一个间隔，
     * 避免与长扫描重叠堆积（与 {@code RunRecoverySweeper} 同理由）。
     * <p>⚠️ 默认 10 分钟：抽样的价值是「持续覆盖」，不是「实时」；
     * 过密只会让队列噪声变大。
     */
    @Scheduled(fixedDelayString = "${agent.quality.sampler.interval-ms:600000}")
    public void sweep() {
        sweepWithAlerts();
    }
}
