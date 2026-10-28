package cn.studykid.multiagent.routing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 决策轨迹的一条记录（10 号设计 §6.1 的 {@code evaluationTrace} 元素）。
 *
 * <p>⚠️ <b>只记「实际判过的」规则</b>：短路后未执行的规则不出现在轨迹里，
 * 因此轨迹本身就能回答「为什么这条规则没生效」（前一条命中了 / 本条被判永假遮蔽 / 根本没轮到）。
 * 把未执行的规则也写成 {@code NO_MATCH} 会让重放报告说谎。
 *
 * @param ordinal 规则序号
 * @param ruleId  规则标识
 * @param outcome 该条的判定出口，取 {@link #OUTCOME_MATCH}/{@link #OUTCOME_NO_MATCH}/
 *                {@link #OUTCOME_ERROR_SKIPPED}/{@link #OUTCOME_ERROR_REJECTED}
 * @param error   {@code ERROR_*} 时的原因（来自 {@link PredicateEvaluator}，<b>不含堆栈</b>）
 */
public record EvaluationTraceEntry(int ordinal,
                                   @NonNull String ruleId,
                                   @NonNull String outcome,
                                   @Nullable String error) {

    /** 命中。 */
    public static final String OUTCOME_MATCH = "MATCH";

    /** 不命中。 */
    public static final String OUTCOME_NO_MATCH = "NO_MATCH";

    /** 求值异常，按 {@code onError=SKIP} 跳过。 */
    public static final String OUTCOME_ERROR_SKIPPED = "ERROR_SKIPPED";

    /** 求值异常，按 {@code onError=REJECT} 中止整次路由。 */
    public static final String OUTCOME_ERROR_REJECTED = "ERROR_REJECTED";
}
