package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 闭包解析失败（§5.7 的 {@code RESOURCE_UNKNOWN} / {@code RESOURCE_VERSION_MISMATCH}）。
 *
 * <p>⚠️ <b>解析失败一律是 fail-closed</b>：闭包算不出来就没有授权依据，
 * 调用方必须拒绝，<b>不得</b>退化为「空闭包」放行。
 * 空闭包（Agent 未启用任何 Skill）是<b>合法</b>状态，
 * 与「解析失败」是<b>两件事</b> —— 混淆二者会让配置缺陷表现为「无人有权限」，
 * 或反之让空闭包被当成配置缺陷。
 */
public class ClosureResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    public ClosureResolutionException(@NonNull ReasonCode reasonCode, @NonNull String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    /**
     * @return 对应的原因码（§5.7）
     */
    public @NonNull ReasonCode reasonCode() {
        return reasonCode;
    }
}
