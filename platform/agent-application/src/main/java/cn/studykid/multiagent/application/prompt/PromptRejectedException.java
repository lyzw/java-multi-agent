package cn.studykid.multiagent.application.prompt;

/**
 * Prompt 拒绝异常（P3-D09 / FR-016）。
 *
 * <p><b>⚠️ 与「注入检测」的刻意区分</b>：
 * 本异常只由<b>可判定的硬规则</b>触发（长度/深度/元素数超限），
 * <b>绝不</b>因为「看起来像注入」而抛出——
 * 后者会误杀合法数学题（「请忽略第 3 小问的负号」）。
 * 注入模式检测走 {@link PromptGuard#detectSuspicious}，<b>只观测不拦截</b>。
 */
public class PromptRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient PromptRejectionReason reason;

    public PromptRejectedException(String message, PromptRejectionReason reason) {
        super(message);
        this.reason = reason;
    }

    public PromptRejectionReason reason() {
        return reason;
    }
}