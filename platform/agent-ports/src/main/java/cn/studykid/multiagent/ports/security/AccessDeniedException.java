package cn.studykid.multiagent.ports.security;

import org.jspecify.annotations.NonNull;

/**
 * 访问被明确拒绝。
 *
 * <p>与「系统故障」严格区分：授权拒绝是<b>业务结论</b>，应返回 403；
 * 认证失败返回 401；服务不可用返回 5xx。三者混淆会导致
 * 把拒绝误当故障而重试，进而放大越权风险。
 */
public class AccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String principalId;
    private final transient String action;

    public AccessDeniedException(@NonNull String principalId,
                                 @NonNull String action,
                                 String resourceId) {
        super("访问被拒绝：principal=" + principalId + ", action=" + action
                + (resourceId == null ? "" : ", resource=" + resourceId));
        this.principalId = principalId;
        this.action = action;
    }

    public @NonNull String principalId() {
        return principalId;
    }

    public @NonNull String action() {
        return action;
    }
}