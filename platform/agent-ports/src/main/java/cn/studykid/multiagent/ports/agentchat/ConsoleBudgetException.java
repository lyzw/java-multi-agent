package cn.studykid.multiagent.ports.agentchat;

import org.jspecify.annotations.NonNull;

/**
 * L3 ConsoleChat 预算保护的 <b>fail-closed 拒绝异常</b>（ADR-013 D52 / M9）。
 *
 * <p>⚠️ <b>为什么抛异常而不是返回 rejected 结果</b>：返回值可以被调用方「先取 request、
 * 忘了看 rejected」而静默放行——那正是本项目反复吃的「不报错、只是悄悄超窗」形态。
 * 本异常强制调用方二选一：按稳定原因码显式拒绝该 HTTP 请求，或让顶层兜底收敛错误。
 * ⛔ 绝不静默回退 legacy：配错策略若被伪装成「策略正常」，灰度期所有证据都会失真。
 *
 * <p>⚠️ {@link #reason()} 是<b>对外稳定码</b>（只追加、不改名、不复用）：
 * 前五个与算法层 / M7 端口层保持同名，便于日志按同一维度聚合；
 * {@link Reason#CONSOLE_BUDGET_INPUT_INVALID} 指 L3 请求级输入（如输出上限 ≤0）非法，
 * 属请求问题 → 4xx；{@link Reason#CONSOLE_BUDGET_FAILED} 是本端口兜底码，
 * 专指「L3 预算装配链路本身异常」→ 5xx。
 *
 * <p>⚠️ 中性端口层类型：不引用 {@code agent-context} 异常类，
 * 由组合根 adapter 负责把算法层原因码逐码投影过来（不吞、不猜）。
 */
public final class ConsoleBudgetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 稳定原因码。 */
    public enum Reason {
        /** 策略字段 / 档位非法（含 L3 非 TRIM_ONLY 的越界档位）。 */
        CONTEXT_POLICY_INVALID,
        /** 输入窗口 + 输出上限超过模型总窗口。 */
        CONTEXT_WINDOW_EXCEEDED,
        /** targetRatio ≥ triggerRatio，压完立刻再触发。 */
        CONTEXT_RATIO_INVERTED,
        /** 固定块超窗或二级硬裁后仍超窗。 */
        CONTEXT_BUDGET_EXCEEDED,
        /** pinned + reserve 已超过窗口。 */
        CONTEXT_PINNED_OVERFLOW,
        /** L3 请求级输入非法（如 maxOutputTokens ≤ 0）；请求问题，4xx。 */
        CONSOLE_BUDGET_INPUT_INVALID,
        /** L3 预算装配链路自身异常（唯一兜底码），5xx。 */
        CONSOLE_BUDGET_FAILED
    }

    private final @NonNull Reason reason;

    public ConsoleBudgetException(@NonNull Reason reason, @NonNull String message) {
        this(reason, message, null);
    }

    public ConsoleBudgetException(@NonNull Reason reason, @NonNull String message,
                                  Throwable cause) {
        super(reason + ": " + message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("ConsoleBudgetException.reason 不得为 null");
        }
        this.reason = reason;
    }

    public @NonNull Reason reason() {
        return reason;
    }
}
