package cn.studykid.multiagent.ports.tool;

import org.jspecify.annotations.NonNull;

/**
 * 工具执行失败。
 *
 * <p><b>关键设计</b>：区分「明确失败」与「<b>结果未知</b>」。
 * 二者的处置方式截然不同：
 * <ul>
 *   <li>明确失败（{@link #definitelyFailed}）→ 可按策略重试；</li>
 *   <li>结果未知（{@link #unknownResult()}）→ 外部副作用<b>可能已发生</b>，
 *       必须进入 ToolOperation.UNKNOWN 并对账，<b>禁止盲目重试</b>
 *       （P0-D03 §4 红线 7 / R-003 / Sev-0）。</li>
 * </ul>
 * 混淆二者是本平台最容易出现的 Sev-0 缺陷：把 UNKNOWN 当普通失败重试会造成重复副作用。
 */
public class ToolExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient boolean resultUnknown;

    private ToolExecutionException(String message, Throwable cause, boolean resultUnknown) {
        super(message, cause);
        this.resultUnknown = resultUnknown;
    }

    /**
     * 执行明确失败：可重试。
     * 例：下游返回 4xx 明确拒绝、参数错误。
     */
    public static @NonNull ToolExecutionException definitelyFailed(String message) {
        return new ToolExecutionException(message, null, false);
    }

    public static @NonNull ToolExecutionException definitelyFailed(String message, Throwable cause) {
        return new ToolExecutionException(message, cause, false);
    }

    /**
     * <b>结果未知</b>：请求可能已到达下游但响应丢失。
     * 例：读超时后连接断开、下游崩溃无响应、网络分区。
     *
     * <p>调用方必须将 ToolOperation 置为 {@code UNKNOWN} 并转对账流程，
     * <b>不得</b>直接重试，也<b>不得</b>直接释放预算预留。
     */
    public static @NonNull ToolExecutionException unknownResult(String message, Throwable cause) {
        return new ToolExecutionException(message, cause, true);
    }

    /** @return true 表示外部副作用结果未知，须对账，禁止盲目重试。 */
    public boolean isResultUnknown() {
        return resultUnknown;
    }
}
