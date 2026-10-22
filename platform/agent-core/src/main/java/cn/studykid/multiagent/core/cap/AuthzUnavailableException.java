package cn.studykid.multiagent.core.cap;

/**
 * 授权版本不可用（fail-closed 拒绝的信号）。
 *
 * <p><b>为什么要有独立的异常类型</b>（06 §1.5 第 6 条 / 04 §A9）：
 * 「明确拒绝」与「系统故障」必须<b>可区分</b>——
 * 前者是对主体的正确裁决，后者是**系统自己不知道答案**。
 * 两者若都折叠成同一个布尔值，调用方很容易把「查不到」当成「没权限」而
 * 继续往下走，或把「没权限」当成「系统故障」而反复重试。
 *
 * <p>⚠️ <b>绝不允许降级放行</b>：本异常被抛出即意味着
 * <b>该次调用必须被拒绝</b>（对应原因码 {@code AUTHZ_UNAVAILABLE}）。
 * 版本服务不可用时的正确行为是拒绝并告警，而不是"按上次读到的版本放行"——
 * 后者等于把撤权 SLA 降级成「最终一致」。
 */
public class AuthzUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final String principalId;

    public AuthzUnavailableException(@org.jspecify.annotations.NonNull String tenantId,
                                     @org.jspecify.annotations.NonNull String principalId,
                                     @org.jspecify.annotations.Nullable String message,
                                     @org.jspecify.annotations.Nullable Throwable cause) {
        super(message, cause);
        this.tenantId = tenantId;
        this.principalId = principalId;
    }

    public @org.jspecify.annotations.NonNull String tenantId() {
        return tenantId;
    }

    public @org.jspecify.annotations.NonNull String principalId() {
        return principalId;
    }
}
