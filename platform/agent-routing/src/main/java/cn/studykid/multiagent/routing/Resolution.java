package cn.studykid.multiagent.routing;

import cn.studykid.multiagent.core.cap.ReasonCode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 路由决策的<b>稳定码</b>（10 号设计 §6.1）。
 *
 * <p>⚠️ <b>对外只暴露稳定码，不暴露异常文案或中文描述</b>：
 * 这是本仓既有纪律（{@code ReasonCode} 同口径）。描述文字会随排错需要改写，
 * 而指标、告警规则与回归断言必须能在码值上长期成立 ——
 * 一旦把描述当契约，改一个形容词就能把线上告警判空。
 *
 * <p>⚠️ <b>码值只增不改</b>：新增一档语义就加一个枚举值，
 * 不要复用一个既有码去表达相近但不同的事 —— 复用会让历史指标口径断裂，
 * 而断裂通常是很久以后才被发现的。
 *
 * <p>⚠️ <b>本枚举是 §6.1 九个稳定码的唯一真相</b>，{@code ReasonCode} 里
 * <b>不再抄一份</b>：{@code ReasonCode} 的契约是「失败/终止原因」，
 * 而九个码里只有四个是失败。把 {@code RULE_MATCH} 这类成功出口也塞进
 * {@code ReasonCode}，等于让一次<b>成功</b>委派在拒绝指标里计数，
 * 且「快照里的 resolution」与「reason_code」两处迟早对不上。
 * 成功出口的落点是 {@code agent_invocation.routing_decision} 的
 * {@code resolution} 键，失败出口才同时写 {@code reason_code} ——
 * 映射只在 {@link #reasonCode()} 写一遍。
 */
public enum Resolution {

    /** 规则命中（{@code RULES_ONLY} 与 {@code RULE_FIRST} 的正常出口）。 */
    RULE_MATCH,

    /** 语义引擎采纳模型候选（仅 {@code RULE_FIRST} 无命中或 {@code SEMANTIC}）。 */
    SEMANTIC_MATCH,

    /** 无命中后走 {@code default_target} 兜底。 */
    DEFAULT_TARGET,

    /** 无命中且 {@code no_match_action=REJECT}（<b>规则没覆盖这类输入</b>，与「候选越出允许集」不同因）。 */
    NO_MATCH_REJECTED,

    /** 某条规则求值异常，按 {@code onError=SKIP} 跳过（异常本身不是结果）。 */
    EVAL_ERROR_SKIPPED,

    /** 某条规则求值异常，按 {@code onError=REJECT} 中止整次路由。 */
    EVAL_ERROR_REJECTED,

    /** 策略不可得 / 读失败 / 能力未装配 —— fail-closed，不猜目标。 */
    POLICY_UNAVAILABLE,

    /**
     * 模型候选<b>在这份策略自己点名的目标集之外</b>（MA-T05a）—— 策略比拓扑窄时才有意义。
     *
     * <p>⚠️ 与 {@code DELEGATION_TARGET_NOT_DECLARED} <b>必须分列</b>，两者排查方向相反：
     * 本码 = 目标越出<b>策略内容</b>（改策略）；那个码 = 目标越出<b>父的委派声明</b>（改子 Agent 声明）。
     * 混用的症状是「查了半天那份配置，判它的其实是另一份」。
     *
     * <p>⚠️ <b>判定顺序由调用方保证，不由本码保证</b>：委派链先判拓扑、后跑引擎
     * （{@code DelegationExecutor} 的 3.1 在 3.2 之前，这是被用例钉死的评审决议），
     * 所以走到本码时候选<b>已经</b>在拓扑之内。顺序若反过来，「越出委派边界」会被报成
     * 「越出策略」，MA-T05 的语义就被改了 —— 因此本码的准确读法是
     * <b>「拓扑过了、策略没收」</b>，而不是「策略和拓扑都没过」。
     */
    TARGET_NOT_ALLOWED,

    /** 规则命中且与模型候选不同，已按 {@code on_conflict} 裁决。 */
    ROUTING_CONFLICT;

    /**
     * @return 本稳定码代表的<b>对外原因码</b>；
     *         {@code null} = 这是<b>成功出口</b>（委派继续，只在决策快照里留痕）
     *
     * <p>⚠️ <b>映射只在这里写一遍</b>：调用方若各自 {@code if (resolution == ...)}
     * 翻译一遍，漏掉一个失败码的后果是「带着失败结论继续委派」，
     * 而漏掉的码在测试里往往只覆盖到被写出来的那几条分支。
     */
    public @Nullable ReasonCode reasonCode() {
        return switch (this) {
            case POLICY_UNAVAILABLE -> ReasonCode.ROUTING_POLICY_UNAVAILABLE;
            case TARGET_NOT_ALLOWED -> ReasonCode.ROUTING_TARGET_NOT_ALLOWED;
            case NO_MATCH_REJECTED -> ReasonCode.ROUTING_NO_MATCH_REJECTED;
            case EVAL_ERROR_REJECTED -> ReasonCode.ROUTING_EVAL_ERROR_REJECTED;
            case RULE_MATCH, SEMANTIC_MATCH, DEFAULT_TARGET, EVAL_ERROR_SKIPPED,
                    ROUTING_CONFLICT -> null;
        };
    }

    /**
     * 该码是否代表「本次路由<b>没有</b>产生可执行目标」。
     *
     * <p>⚠️ 判定必须集中在这里，不能各处写 {@code == NO_MATCH_REJECTED || == ...}：
     * 漏掉一个失败码的后果是「带着空目标继续委派」。
     */
    public boolean isFailure() {
        return reasonCode() != null;
    }
}
