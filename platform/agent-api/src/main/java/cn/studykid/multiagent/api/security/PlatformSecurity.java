package cn.studykid.multiagent.api.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import org.springframework.stereotype.Component;

/**
 * 平台管理面授权入口（重规划 §4.3 / §7.2）。
 *
 * <p>控制器在执行业务前显式调用本类，而非仅依赖前端隐藏按钮——
 * 授权在<b>执行入口</b>强制，与租户侧 {@code AuthorizationService} 的纪律一致。
 *
 * <p>拒绝时抛 {@code AccessDeniedException}（已由
 * {@code ApiExceptionHandler} 归一为 403），消息固定、不回显资源标识。
 */
@Component
public class PlatformSecurity {

    private final PlatformAuthorizationService authorization;

    public PlatformSecurity(PlatformAuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * 校验平台主体是否可在目标资源上执行动作。
     *
     * @throws AccessDeniedException 无权限（→ 403）
     */
    public @NonNull PlatformPrincipal require(@NonNull PlatformPrincipal principal,
                                              @NonNull String action,
                                              @Nullable String targetType,
                                              @Nullable String targetId) {
        AuthorizationDecision decision =
                authorization.authorize(principal, action, targetType, targetId);
        if (!decision.allowed()) {
            throw new AccessDeniedException(principal.userId(), action, targetId);
        }
        return principal;
    }
}
