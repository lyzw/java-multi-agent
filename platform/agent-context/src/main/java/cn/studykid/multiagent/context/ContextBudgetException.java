package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

/**
 * 预算分配 / 硬顶校验失败（fail-closed，M5-a / 《方案》§2.3 HARD / §6.4）。
 *
 * <p>⚠️ <b>绝不解弹、绝不静默截断</b>：固定块已超窗口、或硬裁后仍超窗口时，
 * 唯一正确行为是显式失败，让调用方按既有降级链处理。静默截断用户输入会
 * 丢失「任务锚」，是「不报错、只是答案悄悄跑偏」的典型失真形态。
 *
 * <p>与 {@link ContextPolicyRejectedException} 的分工：后者是<b>配置期</b>拒绝
 * （发布 / Run 创建期），本异常是<b>运行期组装</b>期的预算失败。
 */
public final class ContextBudgetException extends RuntimeException {

    /** 稳定拒因码，供日志 / 指标按维度聚合，禁止只靠 message 判因。 */
    public enum Reason {
        /** {@code pinned + reserve > window}：系统提示 / 工具定义需要瘦身。 */
        CONTEXT_PINNED_OVERFLOW,
        /** 二级硬裁后 {@code after > window - reserve}：绝不静默截断。 */
        CONTEXT_BUDGET_EXCEEDED
    }

    private final @NonNull Reason reason;

    public ContextBudgetException(@NonNull Reason reason, @NonNull String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public @NonNull Reason reason() {
        return reason;
    }
}
