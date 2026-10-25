package cn.studykid.multiagent.core.orchestration;

/**
 * AgentInvocation 状态（07 变更补充「状态与失败语义」）。
 *
 * <p>⚠️ <b>{@link #UNKNOWN} 仅在「子操作/委派结果确实不确定且无法证明未执行」时使用</b>：
 * 普通失败（{@link #FAILED}）与结果未知（{@link #UNKNOWN}）必须严格区分 ——
 * 前者可按失败策略重试/降级，后者必须走对账流程，不得盲目重放（MA-T13）。
 */
public enum AgentInvocationStatus {

    /** 已受理，尚未开始执行（重复投递时不得重复创建逻辑调用 · MA-T10）。 */
    QUEUED,

    /** 执行中。 */
    RUNNING,

    /** 成功结束，且结果通过输出契约校验（MA-T16）。 */
    SUCCEEDED,

    /** <b>确定</b>失败（结果已知，无需对账）。 */
    FAILED,

    /** 被取消（协作式取消；不承诺撤销已发出的外部副作用）。 */
    CANCELLED,

    /** 超时终止。 */
    TIMED_OUT,

    /** 结果不确定 —— 进入对账，不盲目重试。 */
    UNKNOWN;

    /**
     * @return 是否为终态
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED
                || this == TIMED_OUT || this == UNKNOWN;
    }
}
