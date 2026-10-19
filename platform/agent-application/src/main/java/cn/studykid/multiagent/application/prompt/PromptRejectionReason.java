package cn.studykid.multiagent.application.prompt;

/**
 * Prompt 拒绝原因（P3-D09 / FR-016）。
 *
 * <p>⚠️ <b>枚举而非 boolean</b>（与 D4 {@code ReserveOutcome} /
 * D5 {@code ApprovalOutcome} / D8 {@code Decision} 同源）：
 * 「超长」「过深」「元素过多」在客户端提示与产品决策上完全不同，
 * 折叠成一个 false 会让排查方向全错。
 */
public enum PromptRejectionReason {

    /** 输入总长度超限。 */
    TOO_LONG,

    /** 嵌套深度超限。 */
    TOO_DEEP,

    /** 单层元素数超限。 */
    TOO_MANY_ELEMENTS
}