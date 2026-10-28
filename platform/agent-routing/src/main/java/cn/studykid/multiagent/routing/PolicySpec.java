package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一份<b>完整、已结构合法</b>的路由策略（10 号设计 §4.2 的对象形态）。
 *
 * <p>⚠️ <b>构造即校验</b>，且只校「结构」这一层：
 * 序号连续、ruleId 唯一、兜底目标存在、目标都在候选里 ——
 * 这些都是不成立就不可能有意义的事实，放进构造器是为了让
 * <b>不合法的策略对象根本无法被造出来</b>，而不是靠每个使用方记得先检查。
 *
 * <p>语义守卫（矛盾、遮蔽、死目标、样本回放）<b>不在这里</b>，见 {@link PublishGuards}：
 * 它们要产出<b>可解释的拒绝理由</b>（V4 是哪条规则、跟哪条冲突），
 * 用异常表达会把多个理由压成第一个，配置者改一处才发现下一处。
 *
 * <p><b>{@code allowedTargets}</b> 来自父的 {@code childAgentRefs}，
 * 与策略一起构造正是为了封死「策略指向了父没声明的子」：
 * 该集合是准入的下界，任何目标（含兜底）都在它之内才可能被放行。
 *
 * @param policyId       策略标识（进决策快照的 {@code policyRef}）
 * @param version        策略版本（进 {@code policyRef} 的 {@code @}{@code version}）
 * @param mode           路由模式，见 {@link RoutingMode}
 * @param inputSchema    路由输入词汇表（求值视野的边界）
 * @param rules          规则列表，按 {@code ordinal} 严格递增
 * @param defaultTarget  兜底目标（{@code noMatchAction=DEFAULT_TARGET} 时必填）
 * @param noMatchAction  无命中处置
 * @param conflictPolicy 规则与模型候选冲突时的裁决
 * @param samples        回放样本（守卫 V8/V9 的输入；可为空，但 V9 会拒）
 * @param allowedTargets 父声明的可委派目标
 * @param requiredTargets 父声明的<b>必须被委派到</b>的子（{@code ChildAgentRef.required=true}），
 *                        守卫 V7 用它判「有必选子却没有任何规则覆盖」
 */
public record PolicySpec(@NonNull String policyId,
                         int version,
                         @NonNull RoutingMode mode,
                         @NonNull InputSchema inputSchema,
                         @NonNull List<RuleSpec> rules,
                         @Nullable String defaultTarget,
                         @NonNull NoMatchAction noMatchAction,
                         @NonNull ConflictPolicy conflictPolicy,
                         @NonNull List<SampleSpec> samples,
                         @NonNull Set<String> allowedTargets,
                         @NonNull Set<String> requiredTargets) {

    public PolicySpec {
        rules = List.copyOf(rules);
        samples = List.copyOf(samples);
        allowedTargets = Set.copyOf(allowedTargets);
        requiredTargets = Set.copyOf(requiredTargets);
        if (policyId.isBlank()) {
            throw new IllegalArgumentException("policyId 不能为空：决策快照的 policyRef 要有可读标识");
        }
        if (version < 1) {
            throw new IllegalArgumentException("策略版本必须 > 0: " + version);
        }
        if (mode.requiresNonEmptyRules() && rules.isEmpty()) {
            throw new IllegalArgumentException(mode + " 策略必须带至少一条规则："
                    + "空规则等于「每次都走兜底」，那要显式配 SEMANTIC 或只留 default_target，"
                    + "不该伪装成一个在跑规则的策略");
        }
        if (noMatchAction == NoMatchAction.DEFAULT_TARGET
                && (defaultTarget == null || defaultTarget.isBlank())) {
            throw new IllegalArgumentException("no_match_action=DEFAULT_TARGET 必须声明 default_target"
                    + "（与 DDL 的 default_target_ck 同口径，两处都得拒才不会出现库能存、代码造不出）");
        }
        if (defaultTarget != null && !allowedTargets.contains(defaultTarget)) {
            throw new IllegalArgumentException("兜底目标 " + defaultTarget
                    + " 不在父声明的可委派目标里：准入对兜底路径不打折");
        }
        if (!requiredTargets.isEmpty() && !allowedTargets.containsAll(requiredTargets)) {
            Set<String> orphans = new LinkedHashSet<>(requiredTargets);
            orphans.removeAll(allowedTargets);
            throw new IllegalArgumentException("必选子 " + orphans + " 不在可委派目标里："
                    + "「必须委派到」与「允许委派给」互相矛盾，这种策略没有任何可满足的执行序");
        }
        Set<String> seenRuleIds = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleSpec rule = rules.get(i);
            if (rule.ordinal() != i + 1) {
                throw new IllegalArgumentException("规则序号必须从 1 起严格递增，第 " + (i + 1)
                        + " 条的序号是 " + rule.ordinal() + "（序号由服务端按声明序回填）");
            }
            if (!seenRuleIds.add(rule.ruleId())) {
                duplicates.add(rule.ruleId());
            }
            if (!allowedTargets.contains(rule.target())) {
                throw new IllegalArgumentException("规则 " + rule.ruleId() + " 的目标 " + rule.target()
                        + " 不在父声明的可委派目标里");
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("ruleId 重复: " + duplicates
                    + "（决策快照按 ruleId 回指规则，重名会让两条规则的命中无法区分）");
        }
    }

    /** {@code policyRef}：决策快照与重放不变式的主键半边（{@code 策略标识@版本}）。 */
    public @NonNull String policyRef() {
        return policyId + "@" + version;
    }

    /**
     * 策略<b>自己点名</b>的目标集 = 规则目标 ∪ 兜底目标。
     *
     * <p>⚠️ <b>与 {@link #allowedTargets} 不是一回事，别混用</b>：
     * 前者是「这份策略提到过哪些目标」（<b>选择</b>层的收窄），
     * 后者是「父允许委派给哪些目标」（<b>准入</b>层，由调用方判）。
     * 运行期与 dry-run 都走 {@link PolicyCodec#parseStandalone}，而它把
     * <b>点名集本身</b>当 {@code allowedTargets} 传进来 —— 所以「用 allowedTargets 判候选」
     * 在这两个入口里<b>恒为真</b>：代码写了、测试全绿、收窄<b>永不触发」。
     * 这正是本仓反复出现的「看起来生效、实际不生效」，必须用本方法。
     *
     * <p>空集 = 策略没有点过任何目标（{@code SEMANTIC} 且无兜底），
     * 此时不存在比拓扑更窄的约束，选择完全交给模型。
     */
    public @NonNull Set<String> declaredTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (RuleSpec rule : rules) {
            targets.add(rule.target());
        }
        if (defaultTarget != null) {
            targets.add(defaultTarget);
        }
        return Collections.unmodifiableSet(targets);
    }

    /** 目标的可读列表（守卫失败信息里回显，避免打印内部 Set 顺序）。 */
    public @NonNull List<String> targetsInOrder() {
        return rules.stream().map(RuleSpec::target).distinct().sorted().toList();
    }
}
