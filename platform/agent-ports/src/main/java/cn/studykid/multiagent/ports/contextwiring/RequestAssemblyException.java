package cn.studykid.multiagent.ports.contextwiring;

import org.jspecify.annotations.NonNull;

/**
 * 统一请求装配的 <b>fail-closed 拒绝异常</b>（M7-b）。
 *
 * <p>⚠️ <b>为什么抛异常而不是返回 rejected() 结果</b>：返回值可以被调用方「先看 request、
 * 忘了看 rejected」而静默放行——那正是本项目反复吃的「不报错、只是静默走错路」形态。
 * 受检/非受检异常强制调用方二选一：显式按稳定原因码拒绝该 Run，或让顶层兜底收敛 FAILED。
 * ⛔ 绝不静默回退 legacy：配错策略若被伪装成「策略正常」，灰度期所有证据都会失真。
 *
 * <p>⚠️ {@link #reason()} 是<b>对外稳定码</b>（只追加、不改名、不复用）：
 * 算法层已有 {@code CONTEXT_POLICY_INVALID} / {@code CONTEXT_WINDOW_EXCEEDED} /
 * {@code CONTEXT_RATIO_INVERTED} / {@code CONTEXT_BUDGET_EXCEEDED} /
 * {@code CONTEXT_PINNED_OVERFLOW}；本类只多一个兜底码
 * {@link Reason#CONTEXT_ASSEMBLY_FAILED}，用于「装配链路本身异常」而非策略非法。
 *
 * <p>⚠️ 中性端口层类型：不引用 agent-context 的异常类，由组合根 adapter 负责把
 * 算法层原因码投影到 {@link Reason}（逐码映射，不吞不猜）。
 */
public final class RequestAssemblyException extends RuntimeException {

    /** 稳定原因码。 */
    public enum Reason {
        CONTEXT_POLICY_INVALID,
        CONTEXT_WINDOW_EXCEEDED,
        CONTEXT_RATIO_INVERTED,
        CONTEXT_BUDGET_EXCEEDED,
        CONTEXT_PINNED_OVERFLOW,
        CONTEXT_ASSEMBLY_FAILED
    }

    private final @NonNull Reason reason;

    public RequestAssemblyException(@NonNull Reason reason, @NonNull String message) {
        this(reason, message, null);
    }

    public RequestAssemblyException(@NonNull Reason reason, @NonNull String message,
                                    Throwable cause) {
        super(message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("RequestAssemblyException.reason 不得为 null");
        }
        this.reason = reason;
    }

    public @NonNull Reason reason() {
        return reason;
    }
}
