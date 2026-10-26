package cn.studykid.multiagent.ports.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台授权服务（重规划 §7.2）。
 *
 * <p>与租户侧 {@code TenantAuthorizationService}（后续引入）是<b>两个独立接口</b>，
 * 各自校验不同的主体类型、权限命名空间与资源范围。不允许通过通用的
 * {@code isAdmin()} 完成跨域授权。
 */
public interface PlatformAuthorizationService {

    /**
     * 判定平台主体是否可在目标资源上执行指定动作。
     *
     * @param principal  平台主体（来自可信认证上下文，不得取自客户端）
     * @param action     平台权限命名空间动作，如 {@link Namespaces.Platform#TENANT_SUSPEND}
     * @param targetType 目标资源类型（如 "tenant"），可空表示仅动作级校验
     * @param targetId   目标资源标识，可空
     * @return 明确授权结果（含拒绝原因与决策标识）
     */
    AuthorizationDecision authorize(
            @NonNull PlatformPrincipal principal,
            @NonNull String action,
            @Nullable String targetType,
            @Nullable String targetId);
}
