package cn.studykid.multiagent.application.review;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 告警 → 审查任务联动（P2 · §9.2 告警规则）。
 *
 * <p><b>⚠️ 为什么必须有这一步</b>：{@code review_task} 只是一张空表时，
 * 审查流程是<b>空转</b>——队列永远为空、reviewer 无事可做、
 * 而看板上的「审查覆盖」恒为 0%。本类把<b>已采集的指标</b>转成
 * <b>待审任务</b>，让「发现异常 → 人工复盘」这条链真正闭合。
 *
 * <p><b>⚠️ 阈值纪律（P7 铁律：不猜基线）</b>：
 * 成功率类告警需要<b>历史基线</b>（均值 - 3σ），本类<b>不做</b>这类判断——
 * 没有基线就把「今天成功率 95%」判成异常，只会制造噪声告警、训练 reviewer 忽略队列。
 * 本类只做<b>与单条 Trace 自身有关、可确定判定</b>的规则：
 * <ul>
 *   <li><b>BEH-4 循环</b>：{@code loop_suspected_count > 0} → HIGH / ANOMALY；</li>
 *   <li><b>COST-4 超大 token</b>：{@code coalesce(in,out) > 20000} → NORMAL / ANOMALY；</li>
 *   <li><b>MAX_ROUNDS 触顶</b>：终态原因命中 → HIGH / ANOMALY；</li>
 *   <li><b>SAFETY_BLOCK</b>：安全拦截 → HIGH / ALERT。</li>
 * </ul>
 * 比例类（错误率 > 10%、单工具失败率 > 20%）需要<b>分母</b>，
 * 而单条 Trace 的样本量不足以下结论——留给 §7.2 的分层抽样（周期性扫宽表），
 * 不在这里凭单条数据造百分比。
 *
 * <p><b>⚠️ 去重</b>：同一条 Trace 已存在未终结任务时不再重复建
 * （否则重试与重复采集会堆出一摞同源任务，reviewer 要挨个点开才发现是同一个）。
 */
@Service
public class ReviewAlertService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAlertService.class);

    /** 超大 token 阈值（与采集器 OVERSIZED_TOKEN_THRESHOLD 同口径）。 */
    public static final long OVERSIZED_TOKEN_THRESHOLD = 20_000L;

    private final ReviewTaskPort reviewTasks;

    public ReviewAlertService(@NonNull ReviewTaskPort reviewTasks) {
        this.reviewTasks = reviewTasks;
    }

    /**
     * 对一条已采集的 Trace 做规则判定，命中则建待审任务。
     *
     * @param tenantId 租户
     * @param traceId  Trace 标识
     * @param facts    该 Trace 的可判定事实（由调用方从指标行读出）
     * @return 建成的任务标识；未命中或已存在同源任务时返回空
     */
    public @NonNull Optional<String> evaluate(@NonNull String tenantId,
                                              @NonNull String traceId,
                                              @NonNull ReviewableFacts facts) {
        try {
            if (reviewTasks.hasOpenTask(traceId)) {
                return Optional.empty();
            }
            Optional<Verdict> verdict = judge(facts);
            if (verdict.isEmpty()) {
                return Optional.empty();
            }
            Verdict v = verdict.get();
            String taskId = reviewTasks.create(new ReviewTaskSpec(
                    traceId, tenantId, facts.runId(), v.source(), v.priority(), v.reason()));
            log.info("已生成待审任务: trace={}, task={}, source={}, priority={}, 原因={}",
                    traceId, taskId, v.source(), v.priority(), v.reason());
            return Optional.of(taskId);
        } catch (RuntimeException e) {
            // ⚠️ fail-soft：告警联动失败不得让采集链路失败（Run 已终态，指标已落库）。
            //    只记日志 —— 漏一条待审任务好过把已成功的采集打成失败。
            log.warn("告警联动失败（已忽略，不影响采集结果）: trace={}, cause={}",
                    traceId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 规则判定（纯函数，便于单测穷举）。
     *
     * @return 命中的规则；无命中返回空
     */
    public @NonNull Optional<Verdict> judge(@NonNull ReviewableFacts facts) {
        // ⚠️ 顺序即优先级：安全 > 循环 > 轮次触顶 > 成本。
        //    一条 Trace 只建一个任务（reviewer 一次只处理一件事），
        //    故先命中先算，其余规则的信号并入 reason 文本。
        if (facts.safetyBlocked()) {
            return Optional.of(new Verdict(Source.ALERT, Priority.HIGH,
                    "安全拦截：本次 Run 触发了内容安全阻断"));
        }
        if (facts.loopSuspectedCount() > 0) {
            return Optional.of(new Verdict(Source.ANOMALY, Priority.HIGH,
                    "疑似循环调用：" + facts.loopSuspectedCount() + " 组工具重复调用"));
        }
        if (facts.maxRoundsReached()) {
            return Optional.of(new Verdict(Source.ANOMALY, Priority.HIGH,
                    "触达最大轮次上限，Run 未完成即被截断"));
        }
        long total = facts.totalTokens();
        if (total > OVERSIZED_TOKEN_THRESHOLD) {
            return Optional.of(new Verdict(Source.ANOMALY, Priority.NORMAL,
                    "单次 token 超大：" + total + " > " + OVERSIZED_TOKEN_THRESHOLD));
        }
        return Optional.empty();
    }

    /** 命中的规则结论。 */
    public record Verdict(@NonNull Source source,
                          @NonNull Priority priority,
                          @NonNull String reason) {
    }

    /**
     * 可判定事实（由指标行读出，刻意不暴露整个指标对象）。
     *
     * @param runId              Run 标识（建任务时回填）
     * @param loopSuspectedCount 疑似循环组数
     * @param maxRoundsReached   是否触达最大轮次
     * @param safetyBlocked      是否被安全拦截
     * @param inputTokens        输入 token（可为 null = 未采集）
     * @param outputTokens       输出 token（可为 null = 未采集）
     */
    public record ReviewableFacts(@NonNull String runId,
                                  int loopSuspectedCount,
                                  boolean maxRoundsReached,
                                  boolean safetyBlocked,
                                  @Nullable Long inputTokens,
                                  @Nullable Long outputTokens) {

        /** token 合计：未采集按 0（不因缺数据就判「超大」）。 */
        public long totalTokens() {
            return (inputTokens == null ? 0L : inputTokens)
                    + (outputTokens == null ? 0L : outputTokens);
        }
    }
}
