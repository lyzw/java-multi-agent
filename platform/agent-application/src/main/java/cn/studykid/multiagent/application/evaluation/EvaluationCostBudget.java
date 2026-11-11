package cn.studykid.multiagent.application.evaluation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ModelPricing;

/**
 * L2 判定器的<b>独立评估科目日预算</b>（§13 第 3 项 · 5% 抽样独立预算）。
 *
 * <p>⚠️⚠️ <b>绝不混入业务成本</b>：本类只在进程内记「评估科目
 * {@value #SUBJECT}」的预留 / 结算，<b>不</b>写 {@code usage_record}、
 * <b>不</b>调 {@code AgentMetricPort.upsert}、<b>不</b>碰 {@code cost_amount}。
 * 业务 Run 的成本账只反映那次 Run 自己的模型 / 工具调用；judge 是平台评估开销，
 * 混进去会让「单 Run 成本」「按模型成本」全部偏高，且评估越勤偏得越多。
 *
 * <p><b>口径</b>：
 * <ol>
 *   <li>调用前按<b>本次最坏上限</b>预留：保守按「system + user 每字符 1 token」估算输入，
 *       输出按 {@code max-tokens} 计。预留偏大只会<b>保守拒绝</b>（fail-closed），
 *       不影响最终成本；</li>
 *   <li>调用后按模型回传的<b>真实 token</b> 结算，释放预留，累计当日已花；</li>
 *   <li>当日「已花 + 未释放预留 + 本次最坏上限」超过日上限 ⇒ 拒绝调用，
 *       L2 直接「不评级」，不硬判、不猜；</li>
 *   <li>按 UTC 自然日归零，重启也归零。</li>
 * </ol>
 *
 * <p>⚠️ <b>当前是单进程预算</b>：多实例部署时每个实例各自一份，总量上限＝
 * {@code 实例数 × dailyLimit}。要跨实例硬封顶需换成共享计数（DB / Redis），
 * 那属于下一步接入外部依赖的工作；骨架阶段如实标注这个边界，不假装「全局封顶」。
 */
public final class EvaluationCostBudget {

    /** 评估科目名：日志与看板都用它，与业务 Run 成本口径物理隔离。 */
    public static final String SUBJECT = "EVAL_JUDGE_COST";

    private final BigDecimal dailyLimit;
    private final Clock clock;
    private final Map<LocalDate, Day> days = new ConcurrentHashMap<>();

    public EvaluationCostBudget(@NonNull BigDecimal dailyLimit, @NonNull Clock clock) {
        if (dailyLimit.signum() < 0) {
            throw new IllegalArgumentException("L2 judge 日预算不能为负: " + dailyLimit);
        }
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * 预留本次调用的最坏成本。
     *
     * @return 允许时带预留额度；超限时 {@code allowed=false}，调用方<b>不得</b>调用模型
     */
    public synchronized @NonNull Decision tryReserve(@NonNull ModelPricing pricing,
                                                      long estimatedInputTokens,
                                                      long maxOutputTokens) {
        if (estimatedInputTokens < 0 || maxOutputTokens < 0) {
            throw new IllegalArgumentException("预留 token 数不能为负");
        }
        BigDecimal worst = pricing.cost(estimatedInputTokens, maxOutputTokens);
        Day day = day();
        if (day.spent.add(day.reserved).add(worst).compareTo(dailyLimit) > 0) {
            return Decision.deny("DAILY_LIMIT_REACHED");
        }
        day.reserved = day.reserved.add(worst);
        return Decision.allow(worst);
    }

    /** 调用成功：按真实 token 结算，并释放本次预留（多退少补）。 */
    public synchronized void settle(@NonNull Decision decision, @NonNull ModelPricing pricing,
                                    long inputTokens, long outputTokens) {
        Day day = day();
        release(day, decision.reserved());
        day.spent = day.spent.add(pricing.cost(inputTokens, outputTokens));
    }

    /** 调用失败 / 被拒 / 结果不可用：释放预留，不计成本。 */
    public synchronized void release(@NonNull Decision decision) {
        release(day(), decision.reserved());
    }

    /** 当前科目账本（供守卫与运维自检；不含任何对话正文）。 */
    public synchronized @NonNull Snapshot snapshot() {
        Day d = day();
        return new Snapshot(LocalDate.now(clock), d.spent, d.reserved, dailyLimit);
    }

    private Day day() {
        return days.computeIfAbsent(LocalDate.now(clock), ignored -> new Day());
    }

    private static void release(Day day, BigDecimal reserved) {
        BigDecimal left = day.reserved.subtract(reserved);
        // ⚠️ 并发结算重入时宁可把预留清零，也不能出现负预留 ——
        //    负值会让后续「已花 + 预留」少算，变相放宽日上限。
        day.reserved = left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    private static final class Day {
        private BigDecimal spent = BigDecimal.ZERO;
        private BigDecimal reserved = BigDecimal.ZERO;
    }

    /** 预留决定。{@code reserved} 为本次最坏上限（元），失败释放时原样交回。 */
    public record Decision(boolean allowed, @NonNull BigDecimal reserved, @NonNull String reason) {
        private static @NonNull Decision allow(@NonNull BigDecimal reserved) {
            return new Decision(true, reserved, "ALLOWED");
        }

        private static @NonNull Decision deny(@NonNull String reason) {
            return new Decision(false, BigDecimal.ZERO, reason);
        }
    }

    /** 某日评估科目的账本快照。 */
    public record Snapshot(@NonNull LocalDate date, @NonNull BigDecimal spent,
                           @NonNull BigDecimal reserved, @NonNull BigDecimal dailyLimit) {
    }
}
