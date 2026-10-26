package cn.studykid.multiagent.infrastructure.security;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRolePermissionMatrix;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * 平台授权服务实现（重规划 §4.3 / §7.2）。
 *
 * <p><b>默认拒绝</b>：未在角色权限矩阵中登记的动作，对全部平台角色一律拒绝。
 *
 * <p><b>主体以角色为准</b>：权限由 {@link PlatformPrincipal#roles()}（来自令牌声明、
 * 且仅保留库中登记过的角色）经矩阵解析，不信任客户端传入的任意字段。
 *
 * <p><b>职责分离（SoD）</b>：授予/撤销 {@code PLATFORM_SUPER_ADMIN} 必须由
 * 已持有该角色的主体执行——普通平台角色无法自封超级管理员，杜绝自举回路。
 */
public class PlatformAuthorizationServiceImpl implements PlatformAuthorizationService {

    /** 当前策略版本（用于决策留痕与撤权传播）。 */
    private final long policyVersion;

    public PlatformAuthorizationServiceImpl() {
        this(1L);
    }

    public PlatformAuthorizationServiceImpl(long policyVersion) {
        this.policyVersion = policyVersion;
    }

    @Override
    public @NonNull AuthorizationDecision authorize(
            @NonNull PlatformPrincipal principal,
            @NonNull String action,
            @Nullable String targetType,
            @Nullable String targetId) {

        Set<String> effective = resolvePermissions(principal);

        if (!effective.contains(action)) {
            return AuthorizationDecision.deny("ROLE_LACKS_PERMISSION", policyVersion);
        }

        // ⚠️ 职责分离：超级管理员的授予/撤销须由超级管理员本人执行
        if (isSuperAdminAssignment(action, targetType, targetId)
                && !principal.hasRole(PlatformRoles.SUPER_ADMIN)) {
            return AuthorizationDecision.deny("SOD_SUPER_ADMIN_PROTECTED", policyVersion);
        }

        return AuthorizationDecision.allow(policyVersion);
    }

    /** 解析平台主体经其所有角色聚合后的有效权限集合。 */
    private Set<String> resolvePermissions(PlatformPrincipal principal) {
        Set<String> effective = new LinkedHashSet<>();
        for (String role : principal.roles()) {
            effective.addAll(PlatformRolePermissionMatrix.permissionsOf(role));
        }
        return effective;
    }

    private boolean isSuperAdminAssignment(String action, String targetType, String targetId) {
        if (!Namespaces.Platform.ROLE_ASSIGN.equals(action)
                && !Namespaces.Platform.ROLE_REVOKE.equals(action)) {
            return false;
        }
        if (!"role".equalsIgnoreCase(targetType)) {
            return false;
        }
        return PlatformRoles.SUPER_ADMIN.equals(targetId);
    }
}
