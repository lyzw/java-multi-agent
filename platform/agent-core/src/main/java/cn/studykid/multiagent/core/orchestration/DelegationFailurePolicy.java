package cn.studykid.multiagent.core.orchestration;

/**
 * 委派失败策略（FR-027 · 07「状态与失败语义」）。
 *
 * <p>⚠️ <b>只描述「子调用失败后如何继续」，不描述「是否撤销已发生的副作用」</b>：
 * 父 Run 失败<b>不得</b>被解释为所有已发起副作用均已撤销（07 变更补充）。
 * 已发出的外部副作用只能按 UNKNOWN 对账流程处置。
 */
public enum DelegationFailurePolicy {

    /**
     * 首个子调用失败即停止后续委派（缺省）。
     *
     * <p>V1 的保守默认：串行编排下，后续子调用的输入通常依赖前一个的结果，
     * 前序失败时继续执行只会放大成本且必然失败。
     */
    FAIL_FAST,

    /**
     * 继续执行剩余子调用，失败结果按输出契约聚合返回父 Agent。
     *
     * <p>⚠️ 仅在子调用之间<b>无数据依赖</b>时才有意义；聚合结果必须通过
     * 父 Agent 的输出 Schema 校验，否则不得作为成功结果交回（MA-T16）。
     */
    CONTINUE_ON_ERROR
}
