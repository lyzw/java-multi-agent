package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

/**
 * 压缩策略被拒绝（fail-closed）。
 *
 * <p>⚠️ 项目铁律：配置非法必须在<b>配置期</b>拒绝，绝不在运行期悄悄夹紧。
 * 若把超窗改成「自动 clamp 到模型窗口」，运维会以为配置生效了，
 * 实际跑的是另一个值 —— 典型「不报错、只是数字悄悄错」形态。
 * 本异常只由 {@link ContextPolicyParser}（发布/读取期）与
 * {@link ContextPolicyResolver}（Run 创建期）抛出，主链路捕获后必须显式失败，
 * 不得降级成默认策略（降级会让 checkpoint 在两套策略间串用）。
 */
public final class ContextPolicyRejectedException extends RuntimeException {

    /** 稳定的拒因码，供日志 / 指标按维度聚合，禁止只靠 message 判因。 */
    public enum Reason {
        /** {@code generation_params.context} 含非法值或未知键。 */
        CONTEXT_POLICY_INVALID,
        /** 输入硬顶 + 输出上限超过模型总窗口。 */
        CONTEXT_WINDOW_EXCEEDED,
        /** targetRatio ≥ triggerRatio，压完立刻再触发。 */
        CONTEXT_RATIO_INVERTED
    }

    private final @NonNull Reason reason;

    public ContextPolicyRejectedException(@NonNull Reason reason, @NonNull String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public @NonNull Reason reason() {
        return reason;
    }
}
