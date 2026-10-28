package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.OnError;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 路由决策引擎（10 号设计 §6 的阶段②③④）。
 *
 * <p><b>本类只负责「选哪个目标」，不负责「这个目标允不允许」</b>：
 * 拓扑包含、固定版本、逐跳授权、深度/次数/超时/预算属于阶段⑤，
 * 由调用方（{@code DelegationExecutor}）执行并且<b>对兜底目标同样执行</b>
 * —— 延续「降级只作用于选择，不作用于准入」的既有纪律。
 * 把准入塞进引擎会造出第二个准入真相，而两处准入迟早不一致。
 *
 * <p>「候选是否在这份策略点名的目标里」（{@link #adoptCandidate}）<b>不算</b>上面的例外，
 * 它本身就是「选择」的一部分：策略点了名却没判，等于那份内容没参与决策。
 * 分界写在这里是因为它很容易被写成第二份准入 —— 拓扑由调用方判且<b>在引擎之前</b>
 * （{@code DelegationExecutor} 的 3.1 先于 3.2），越出拓扑报
 * {@code DELEGATION_TARGET_NOT_DECLARED}、越出策略报 {@code TARGET_NOT_ALLOWED}，
 * 两个码的排查方向相反（改子 Agent 声明 vs 改策略内容）。
 *
 * <p><b>四条铁律在本类的落点</b>：
 * <ol>
 *   <li>规则按 {@code ordinal} 升序、<b>首个 {@link Truth#TRUE} 即命中</b>；</li>
 *   <li>{@link Truth#ERROR} <b>永不等于命中</b>：它只走 {@link OnError}，
 *       且无论 {@code SKIP} 还是 {@code REJECT} 都在轨迹里留痕；</li>
 *   <li>{@code RULES_ONLY} 下<b>不消费模型候选</b>（该模式的 Schema 根本不发候选）；</li>
 *   <li>纯函数：不读时钟、不读环境、不碰 IO，同输入必同输出（见 {@link PredicateEvaluator}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>引擎不抛「配置错误」</b>：策略文本能进到这儿就说明 V1-V10 已经过了。
 * 运行期真遇上无法判定的输入，出口是 {@code EVAL_ERROR_*} 稳定码而不是异常 ——
 * 异常会让一次委派变成 500，而稳定码让它变成可归因的业务结果。
 */
public final class RoutingEngine {

    private RoutingEngine() {
    }

    /**
     * 决策。
     *
     * @param policy             已通过发布校验的策略
     * @param input              规范化输入（阶段① 的产物）
     * @param candidateFromModel 模型给出的目标（{@code RULES_ONLY} 与无候选时传 {@code null}）
     * @return 决策结果（含轨迹）
     */
    public static @NonNull RoutingDecision decide(@NonNull PolicySpec policy,
                                                  @NonNull RoutingInput input,
                                                  @Nullable String candidateFromModel) {
        List<EvaluationTraceEntry> trace = new ArrayList<>();
        String modelCandidate = policy.mode() == RoutingMode.RULES_ONLY ? null : candidateFromModel;
        RuleMatch match = policy.mode() == RoutingMode.SEMANTIC
                ? new RuleMatch(null, null, null)
                : evaluateRules(policy, input, trace);

        if (match.rule() != null) {
            // ⚠️ 规则命中优先：候选与规则不同时<b>不改变判定</b>，只按裁决选一侧并记冲突。
            //    「RULE_WINS 不改判定」是 S3 的出口条件之一，写成两次 if 就会有一处漏掉。
            boolean conflicts = modelCandidate != null
                    && !modelCandidate.equals(match.rule().target());
            if (conflicts && policy.conflictPolicy() == ConflictPolicy.CANDIDATE_WINS) {
                return adoptCandidate(policy, modelCandidate, true, trace);
            }
            return new RoutingDecision(policy.mode(), Resolution.RULE_MATCH,
                    match.rule().target(), match.rule().ruleId(), match.rule().conditionSignature(),
                    modelCandidate, conflicts, trace, null);
        }
        if (match.rejected() != null) {
            return new RoutingDecision(policy.mode(), Resolution.EVAL_ERROR_REJECTED,
                    null, match.rejected().ruleId(), match.rejected().conditionSignature(),
                    modelCandidate, false, trace, match.error());
        }
        return settleWithoutRuleMatch(policy, modelCandidate, trace);
    }

    /** 规则未命中时的出口：语义引擎（{@code RULE_FIRST}/{@code SEMANTIC}）或无命中处置。 */
    private static @NonNull RoutingDecision settleWithoutRuleMatch(@NonNull PolicySpec policy,
                                                                   @Nullable String modelCandidate,
                                                                   @NonNull List<EvaluationTraceEntry> trace) {
        if (policy.mode() != RoutingMode.RULES_ONLY && modelCandidate != null) {
            return adoptCandidate(policy, modelCandidate, false, trace);
        }
        if (policy.noMatchAction() == NoMatchAction.DEFAULT_TARGET) {
            String fallback = policy.defaultTarget();
            if (fallback == null) {
                // ⚠️ 不可达：PolicySpec 的构造器已强制 DEFAULT_TARGET 必带 default_target。
                //    留一条稳定码而不是 NPE，是为了让「策略不可得」与「代码崩了」在指标上分得开。
                return new RoutingDecision(policy.mode(), Resolution.POLICY_UNAVAILABLE,
                        null, null, null, modelCandidate, false, trace,
                        "策略声明 DEFAULT_TARGET 但没有兜底目标");
            }
            return new RoutingDecision(policy.mode(), Resolution.DEFAULT_TARGET,
                    fallback, null, null, modelCandidate, false, trace, null);
        }
        return new RoutingDecision(policy.mode(), Resolution.NO_MATCH_REJECTED,
                null, null, null, modelCandidate, false, trace,
                "全部规则均未命中且 no_match_action=REJECT");
    }

    /**
     * 采纳模型候选 —— <b>先过「策略点名的目标集」这道收窄</b>（MA-T05a）。
     *
     * <p><b>本方法是采纳候选的唯一出口</b>，两个调用点都走它：无命中时的直接采纳、
     * {@code on_conflict=CANDIDATE_WINS} 时的改判采纳。判据放在收口点而不是各调用点各写一遍，
     * 是因为「两条路径迟早分叉」在本仓反复出现 —— 而漏判的那条在日志与指标上看起来一切正常。
     *
     * <p>⚠️ 本方法<b>不判</b>拓扑：委派链上拓扑在引擎<b>之前</b>判
     * （{@code DelegationExecutor} 的 3.1 先于 3.2，是被用例钉死的评审决议），
     * 越出拓扑由那里报 {@code DELEGATION_TARGET_NOT_DECLARED}。
     * 所以本码的准确读法是「<b>拓扑过了、策略没收</b>」，不是「策略和拓扑都没过」。
     * 引擎被<b>单独</b>调用时（管理台 dry-run、样本回放）没有那道前置，
     * 因此不得把本方法的结论当成「候选过了拓扑」。
     *
     * <p>策略<b>没点名任何目标</b>时不设收窄（{@code SEMANTIC} 且无兜底 = 把选择
     * 完全交给模型，这是该模式的定义）；此时只剩拓扑一道关。
     *
     * <p>{@code matchedRuleId} 恒为 {@code null}：即便走的是改判分支，
     * 命中的那条规则也<b>不是</b>最终目标的依据，留个 ruleId 会让重放报告
     * 把「按候选办事」读成「按规则办事」。
     */
    private static @NonNull RoutingDecision adoptCandidate(@NonNull PolicySpec policy,
                                                           @NonNull String candidate,
                                                           boolean conflict,
                                                           @NonNull List<EvaluationTraceEntry> trace) {
        Set<String> declared = policy.declaredTargets();
        if (!declared.isEmpty() && !declared.contains(candidate)) {
            return new RoutingDecision(policy.mode(), Resolution.TARGET_NOT_ALLOWED,
                    null, null, null, candidate, conflict, trace,
                    "候选 " + candidate + " 不在本策略点名的目标集 " + declared + " 内");
        }
        return new RoutingDecision(policy.mode(), Resolution.SEMANTIC_MATCH,
                candidate, null, null, candidate, conflict, trace, null);
    }

    /** 规则命中/中止的中间结果（{@code rejected} 非空表示某条 {@code onError=REJECT} 求值失败）。 */
    private record RuleMatch(@Nullable RuleSpec rule,
                             @Nullable RuleSpec rejected,
                             @Nullable String error) {
    }

    private static @NonNull RuleMatch evaluateRules(@NonNull PolicySpec policy,
                                                    @NonNull RoutingInput input,
                                                    @NonNull List<EvaluationTraceEntry> trace) {
        for (RuleSpec rule : policy.rules()) {
            PredicateEvaluator.Outcome outcome = PredicateEvaluator.evaluate(rule.when(), input);
            switch (outcome.truth()) {
                case TRUE -> {
                    trace.add(new EvaluationTraceEntry(rule.ordinal(), rule.ruleId(),
                            EvaluationTraceEntry.OUTCOME_MATCH, null));
                    return new RuleMatch(rule, null, null);
                }
                case FALSE -> trace.add(new EvaluationTraceEntry(rule.ordinal(), rule.ruleId(),
                        EvaluationTraceEntry.OUTCOME_NO_MATCH, null));
                case ERROR -> {
                    String reason = outcome.reason() == null ? "求值失败" : outcome.reason();
                    if (rule.onError() == OnError.REJECT) {
                        trace.add(new EvaluationTraceEntry(rule.ordinal(), rule.ruleId(),
                                EvaluationTraceEntry.OUTCOME_ERROR_REJECTED, reason));
                        return new RuleMatch(null, rule, reason);
                    }
                    // SKIP：跳过本条继续，但必须留痕 —— 「跳过」与「不成立」在指标上是两件事。
                    trace.add(new EvaluationTraceEntry(rule.ordinal(), rule.ruleId(),
                            EvaluationTraceEntry.OUTCOME_ERROR_SKIPPED, reason));
                }
                default -> throw new IllegalStateException("未知的三值: " + outcome.truth());
            }
        }
        return new RuleMatch(null, null, null);
    }
}
