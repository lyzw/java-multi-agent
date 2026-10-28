package cn.studykid.multiagent.routing;

import java.util.List;

import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一次路由决策的<b>完整结果</b>（10 号设计 §6.1 决策快照的对象形态）。
 *
 * <p>⚠️ <b>目标与「为什么是这个目标」必须同批产出</b>：
 * 只返回 {@code target} 的实现无法回答重放不变式
 * （{@code (policyRef, canonicalInputSha256) → finalTarget} 唯一）里的「为什么」，
 * 而事后补记的日志一定会漂移 —— 所以 {@link #trace()} 是返回值的一部分，不是可选附件。
 *
 * <p>⚠️ <b>{@link #finalTarget()} 为 {@code null} 当且仅当 {@link #resolution()} 是失败码</b>
 * （见 {@link Resolution#isFailure()}）。任何「目标为空但结果成功」的组合都在
 * {@link RoutingEngine} 的构造收口处被挡住，因为那种组合会直接委派给空目标。
 *
 * @param mode              决策所用的模式（同一份策略在两种模式下结论不同，必须留档）
 * @param resolution        稳定码
 * @param finalTarget       最终目标（失败时为 {@code null}；<b>准入校验仍由调用方执行</b>）
 * @param matchedRuleId     命中的规则标识（{@link Resolution#RULE_MATCH} 时非空）
 * @param matchedRuleSignature 命中规则的谓词签名（决策快照里 {@code matchedRuleSha256} 的输入）
 * @param candidateFromModel 模型给出的候选（{@code RULES_ONLY} 下恒为 {@code null}：模型无权选）
 * @param conflict          规则命中且与模型候选不同（已按 {@link ConflictPolicy} 裁决）
 * @param trace             求值轨迹（按 {@code ordinal} 升序，只含实际判过的规则）
 * @param error             失败时的可读原因（<b>不进对外稳定码契约</b>，只给排错看）
 */
public record RoutingDecision(@NonNull RoutingMode mode,
                              @NonNull Resolution resolution,
                              @Nullable String finalTarget,
                              @Nullable String matchedRuleId,
                              @Nullable String matchedRuleSignature,
                              @Nullable String candidateFromModel,
                              boolean conflict,
                              @NonNull List<EvaluationTraceEntry> trace,
                              @Nullable String error) {

    public RoutingDecision {
        trace = List.copyOf(trace);
        if (finalTarget == null && !resolution.isFailure()) {
            throw new IllegalArgumentException("成功决策必须带目标，否则等于委派给空目标: "
                    + resolution);
        }
        if (finalTarget != null && resolution.isFailure()) {
            throw new IllegalArgumentException("失败决策不得带目标（目标会被下游误当成可执行结论）: "
                    + resolution + " → " + finalTarget);
        }
    }

    /** 本次决策是否给出了可委派目标。 */
    public boolean hasTarget() {
        return finalTarget != null;
    }
}
