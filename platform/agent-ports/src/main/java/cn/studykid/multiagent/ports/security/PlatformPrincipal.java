package cn.studykid.multiagent.ports.security;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 平台身份主体（重规划 §2.1）。
 *
 * <p>与租户侧 {@code TenantContext} 完全独立：平台主体<b>不持有 {@code tenantId}</b>，
 * 不属于任何租户。其权限仅在平台管理域（{@code platform.*}）生效，
 * 绝不能替代租户成员关系进入租户业务授权。
 */
public record PlatformPrincipal(
        @NonNull String userId,
        @NonNull Set<String> roles,
        long authzVersion,
        boolean mfaVerified
) {

    public PlatformPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (authzVersion < 0) {
            throw new IllegalArgumentException("authzVersion 不能为负");
        }
    }

    public boolean hasRole(@NonNull String role) {
        return roles.contains(role);
    }

    /** 审计友好的表示，不含敏感信息。 */
    public @NonNull String auditString() {
        return "platformUser=" + userId
                + ", roles=" + roles
                + ", authzVersion=" + authzVersion
                + ", mfa=" + mfaVerified;
    }
}
