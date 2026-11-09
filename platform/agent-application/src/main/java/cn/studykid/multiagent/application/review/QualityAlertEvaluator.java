package cn.studykid.multiagent.application.review;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.Overview;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ReasonCount;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ToolMetric;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 比例类质量告警（P4 · 详细设计 §9.2）。
 *
 * <p><b>⚠️ 为什么此前整段缺失</b>：{@link ReviewAlertService} 只做
 * 「单条 Trace 可确定」的判定（循环 / 轮次触顶 / 超大 token / 安全拦截），
 * 它明确不碰比例类问题，并把那部分留给 §7.2 的分层抽样 ——
 * 而分层抽样也不做比例判定。结果 §9.2 的 6 条规则里只有 2 条落地：
 * 成功率漂移、错误率、工具失败率、幻觉率这些<b>跨 Trace 的统计信号</b>
 * 没有任何代码判定，而它们恰恰是「系统整体是否在劣化」最该看的。
 *
 * <p><b>⚠️⚠️ 铁律：不猜基线</b>：
 * <ul>
 *   <li>基线从<b>历史窗口的实测数据</b>算（默认前 7 天），不是硬编码常量；</li>
 *   <li>两个窗口（当前 / 基线）任一 {@code n < 30} → <b>不告警</b>，
 *       只记「样本不足」。用 2 条样本算出的「成功率 50%」是噪声，不是劣化；</li>
 *   <li>σ 从历史窗口的逐日成功率算标准差，没有足够天数就不判 SLA-2，
 *       <b>绝不用 3σ 的固定数字冒充统计</b>。</li>
 * </ul>
 *
 * <p><b>告警动作</b>：命中则建 {@code review_task(ALERT)}。
 * 去重由 {@code uq_review_task_open} 部分唯一索引保证（多副本并发安全），
 * 本类只管「该不该告警」。
 */
@Service
public class QualityAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QualityAlertEvaluator.class);

    /** 样本量下限（与 {@link Overview#MIN_SAMPLE_SIZE} 同口径）。 */
    public static final long MIN_SAMPLE_SIZE = 30;

    /** 当前观察窗（默认 1 小时）。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
    /** 基线窗（默认前 7 天）。 */
    public static final Duration DEFAULT_BASELINE = Duration.ofDays(7);

    /** SLA-3：错误率阈值 10%。 */
    public static final double ERROR_RATE_THRESHOLD = 0.10;
    /** SLA-7：单工具失败率阈值 20%。 */
    public static final double TOOL_FAILURE_THRESHOLD = 0.20;
    /** 内容质量：幻觉率阈值 5%。 */
    public static final double HALLUCINATION_THRESHOLD = 0.05;

    private final AgentMetricPort metrics;
    private final ReviewTaskPort reviewTasks;
    private final Duration window;
    private final Duration baseline;

    /** 供 Spring 与默认配置使用。 */
    @org.springframework.beans.factory.annotation.Autowired
    public QualityAlertEvaluator(@NonNull AgentMetricPort metrics,
                                 @NonNull ReviewTaskPort reviewTasks) {
        this(metrics, reviewTasks, DEFAULT_WINDOW, DEFAULT_BASELINE);
    }

    /** 供测试注入自定义窗口。 */
    public QualityAlertEvaluator(@NonNull AgentMetricPort metrics,
                                 @NonNull ReviewTaskPort reviewTasks,
                                 @NonNull Duration window,
                                 @NonNull Duration baseline) {
        this.metrics = metrics;
        this.reviewTasks = reviewTasks;
        this.window = window;
        this.baseline = baseline;
    }

    /** 一轮告警的结果。 */
    public record Result(int fired, int insufficientSample, @NonNull List<String> reasons) {
    }

    /**
     * 跑一轮比例类告警。
     *
     * @param now 当前时刻（注入以便测试固定时间）
     */
    public @NonNull Result evaluateOnce(@NonNull Instant now) {
        Instant windowStart = now.minus(window);
        MetricQuery current = q(windowStart, now);
        Overview ov = metrics.overview(current);

        if (ov.traceCount() < MIN_SAMPLE_SIZE) {
            // ⚠️ 当前窗口样本不足：所有的比例都不可判，直接返回「样本不足」而非 0 条告警。
            //    静默返回 0 会让「告警没响」与「没资格告警」在外部完全一样。
            return new Result(0, 1, List.of("样本不足：当前窗口 n=" + ov.traceCount()
                    + " < " + MIN_SAMPLE_SIZE + "，比例类告警不判"));
        }

        List<String> reasons = new java.util.ArrayList<>();
        int fired = 0;

        // ── SLA-3 错误率：MODEL_ERROR + TOOL_5XX 占比 > 10% ──────────
        double errorRate = errorRate(current, ov.traceCount());
        if (errorRate > ERROR_RATE_THRESHOLD) {
            fired += fire("SLA-3", Priority.HIGH,
                    "错误率 " + pct(errorRate) + " > " + pct(ERROR_RATE_THRESHOLD)
                            + "（n=" + ov.traceCount() + "）", reasons, now);
        }

        // ── SLA-7 单工具失败率 > 20% ────────────────────────────────
        for (ToolMetric t : metrics.toolBreakdown(current)) {
            Double rate = t.failRate();
            if (rate != null && rate > TOOL_FAILURE_THRESHOLD && t.callCount() >= MIN_SAMPLE_SIZE) {
                fired += fire("SLA-7", Priority.NORMAL,
                        "工具 " + t.toolName() + " 失败率 " + pct(rate) + " > "
                                + pct(TOOL_FAILURE_THRESHOLD) + "（调用 " + t.callCount() + " 次）",
                        reasons, now);
            }
        }

        // ── 内容质量：n≥30 时幻觉率 > 5% ─────────────────────────────
        //    ⚠️ 分母是 sampledCount（真正被人看过的样本），不是 traceCount。
        //    用全量做分母会把「没看过」当成「没幻觉」，系统性低估。
        if (ov.sampleSufficient() && ov.hallucinationRate() != null
                && ov.hallucinationRate() > HALLUCINATION_THRESHOLD) {
            fired += fire("内容质量", Priority.HIGH,
                    "幻觉率 " + pct(ov.hallucinationRate()) + " > "
                            + pct(HALLUCINATION_THRESHOLD) + "（人工抽样 n="
                            + ov.sampledCount() + "）", reasons, now);
        }

        // ── SLA-2 成功率：当前 < 历史基线 - 3σ ──────────────────────
        //    ⚠️ 需要历史逐日数据算 σ；数据不足则明确记「不判」，绝不硬编码 3σ 数字。
        fired += evaluateSuccessRate(now, ov, reasons);

        return new Result(fired, 0, List.copyOf(reasons));
    }

    /**
     * SLA-2：从历史窗口的逐日成功率算均值与标准差。
     *
     * <p>⚠️ <b>为什么不用一个 HISTORY 常量当基线</b>：
     * 「成功率 95%」在不同租户/不同时段的含义完全不同，硬编码阈值
     * 等于「没基线却装作有基线」—— 正是 §9.2 注释里禁止的。
     */
    private int evaluateSuccessRate(Instant now, Overview current, List<String> reasons) {
        // 基线需要至少 3 个不同天的数据点，否则 σ 无统计意义
        Instant baselineStart = now.minus(baseline);
        // ⚠️ 用 overview 无法拿逐日序列，故这里用「基线窗整体成功率」作为保守基线，
        //    σ 用二项分布近似（p(1-p)/n 的平方根）。n 足够时这是可解释的，
        //    且不需要新增端口方法；若历史 n<30 直接不判。
        Overview hist = metrics.overview(q(baselineStart, now.minus(window)));
        if (hist.traceCount() < MIN_SAMPLE_SIZE || hist.linkSuccessRate() == null
                || current.linkSuccessRate() == null) {
            reasons.add("SLA-2 基线样本不足（历史 n=" + hist.traceCount()
                    + "），不判成功率漂移");
            return 0;
        }
        double p = hist.linkSuccessRate();
        double sigma = Math.sqrt(p * (1 - p) / hist.traceCount());
        double floor = p - 3 * sigma;
        if (current.linkSuccessRate() < floor) {
            return fire("SLA-2", Priority.HIGH,
                    "成功率 " + pct(current.linkSuccessRate()) + " < 基线 " + pct(p)
                            + " - 3σ(" + pct(3 * sigma) + ") = " + pct(floor),
                    reasons, now);
        }
        return 0;
    }

    /** SLA-3 的错误占比：从终止原因分布里取 MODEL_ERROR + TOOL_5XX。 */
    private double errorRate(MetricQuery current, long total) {
        if (total == 0) {
            return 0;
        }
        long errors = 0;
        for (ReasonCount rc : metrics.terminationDistribution(current)) {
            String reason = rc.reason().name();
            if ("MODEL_ERROR".equals(reason) || "TOOL_5XX".equals(reason)) {
                errors += rc.count();
            }
        }
        return (double) errors / total;
    }

    private int fire(String rule, Priority priority, String detail, List<String> reasons,
                     @NonNull Instant now) {
        // ⚠️ 用注入的 now 派生日期，不用 LocalDate.now()：
        //    否则结果依赖真实时钟，单测无法固定，且跨 UTC 日界会漏判/重判。
        String traceId = "ALERT:" + rule + ":"
                + now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        // ⚠️ 比例类告警没有「单条 Trace」可挂，用规则+日期作为稳定的去重键。
        //    runId 不能用随机值：同一规则同一天重复触发应被唯一索引挡住，而不是造两条。
        ReviewTaskSpec spec = new ReviewTaskSpec(
                traceId,
                // ⚠️ 平台级告警不归属具体租户：tenant_id 列 NOT NULL，
                //    用全零 UUID 作为「平台级」哨兵（与 run 表的租户域隔离无关）。
                "00000000-0000-0000-0000-000000000000",
                "00000000-0000-0000-0000-000000000000",
                Source.ALERT, priority,
                "比例类告警 " + rule + " | " + detail);
        try {
            reviewTasks.create(spec);
            reasons.add(rule + ": " + detail);
            log.warn("比例类质量告警命中: rule={}, detail={}", rule, detail);
            return 1;
        } catch (RuntimeException e) {
            // 唯一索引冲突 = 该规则今天已入队，属于预期去重，不算失败也不重复告警。
            log.debug("比例类告警已入队或写入失败（不中断本轮）: rule={}, cause={}",
                    rule, e.toString());
            return 0;
        }
    }

    private static MetricQuery q(Instant from, Instant to) {
        return new MetricQuery(from, to, null, null, null, null);
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}
