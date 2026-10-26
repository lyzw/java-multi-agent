package cn.studykid.multiagent.ports.security;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.security.Namespaces.Platform;

/**
 * 平台角色 → 权限冻结矩阵（重规划 §3.1 / §4.1）。
 *
 * <p><b>数据而非代码分支</b>：新增权限只需扩充本表，不会遗漏 if 判断。
 * 未在本表登记的动作对所有角色<b>默认拒绝</b>（见 {@code PlatformAuthorizationServiceImpl}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>最小特权</b>：每个平台角色只持有完成其职责所需的最小权限集合，
 *       绝不把 {@code platform.*} 全量一次性授予普通平台角色。</li>
 *   <li><b>职责分离</b>：审计员/支持人员只读；安全管理员不能改自身角色或创建超级管理员
 *       （后者由 {@code PlatformAuthorizationServiceImpl} 的 SoD 规则强制）。</li>
 *   <li><b>权限命名空间隔离</b>：此处仅含 {@code platform.*}，与 {@code tenant.*} 互不可混。</li>
 * </ul>
 */
public final class PlatformRolePermissionMatrix {

    private PlatformRolePermissionMatrix() {
    }

    /** 平台权限全集（与 {@link Platform} 常量一一对应，用于自检与超级管理员推断）。 */
    public static @NonNull Set<String> allPlatformPermissions() {
        return Set.of(
                Platform.USER_READ, Platform.USER_MANAGE,
                Platform.ROLE_READ, Platform.ROLE_ASSIGN, Platform.ROLE_REVOKE,
                Platform.TENANT_CREATE, Platform.TENANT_READ, Platform.TENANT_UPDATE,
                Platform.TENANT_SUSPEND, Platform.TENANT_RESUME, Platform.TENANT_DECOMMISSION,
                Platform.TENANT_QUOTA_READ, Platform.TENANT_QUOTA_UPDATE,
                Platform.TENANT_CAPABILITY_GRANT, Platform.TENANT_CAPABILITY_REVOKE,
                Platform.TENANT_CAPABILITY_READ,
                Platform.RESOURCE_READ, Platform.RESOURCE_MANAGE,
                Platform.POLICY_READ, Platform.POLICY_PUBLISH,
                Platform.AUDIT_READ, Platform.AUDIT_EXPORT,
                Platform.SUPPORT_DIAGNOSTICS_READ);
    }

    /** 角色 → 允许的动作集合。 */
    public static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            // 超级管理员：持有全部平台权限（含敏感操作），但受 SoD 与 MFA/重认证约束
            PlatformRoles.SUPER_ADMIN, allPlatformPermissions(),

            // 租户生命周期管理员：租户创建/配置/启停/退役 + 配额 + 能力授权
            PlatformRoles.TENANT_LIFECYCLE_ADMIN, Set.of(
                    Platform.TENANT_CREATE, Platform.TENANT_READ, Platform.TENANT_UPDATE,
                    Platform.TENANT_SUSPEND, Platform.TENANT_RESUME,
                    Platform.TENANT_DECOMMISSION,
                    Platform.TENANT_QUOTA_READ, Platform.TENANT_QUOTA_UPDATE,
                    Platform.TENANT_CAPABILITY_GRANT, Platform.TENANT_CAPABILITY_REVOKE,
                    Platform.TENANT_CAPABILITY_READ),

            // 平台资源管理员：全局 Tool/MCP/模型目录/资源可用性（含查看已授权能力）
            PlatformRoles.RESOURCE_ADMIN, Set.of(
                    Platform.RESOURCE_READ, Platform.RESOURCE_MANAGE,
                    Platform.TENANT_CAPABILITY_READ),

            // 平台安全管理员：安全/认证/访问策略与风险处置可见性（不含用户/角色写、不含建超级管理员）
            PlatformRoles.SECURITY_ADMIN, Set.of(
                    Platform.POLICY_READ, Platform.POLICY_PUBLISH,
                    Platform.AUDIT_READ, Platform.SUPPORT_DIAGNOSTICS_READ),

            // 平台审计员：只读审计与脱敏平台元数据 + 租户生命周期可见
            PlatformRoles.AUDITOR, Set.of(
                    Platform.AUDIT_READ, Platform.AUDIT_EXPORT,
                    Platform.TENANT_READ, Platform.TENANT_QUOTA_READ,
                    Platform.TENANT_CAPABILITY_READ, Platform.RESOURCE_READ),

            // 平台支持人员：只读脱敏诊断与运维信息
            PlatformRoles.SUPPORT, Set.of(
                    Platform.SUPPORT_DIAGNOSTICS_READ,
                    Platform.TENANT_READ, Platform.TENANT_QUOTA_READ,
                    Platform.TENANT_CAPABILITY_READ, Platform.RESOURCE_READ)
    );

    /** 取某角色被允许的动作集合（只读）。 */
    public static @NonNull Set<String> permissionsOf(@NonNull String role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of());
    }

    /**
     * 启动期自检：确认所有平台受控动作至少被一个角色允许。
     *
     * <p>若某个动作没有任何角色可执行，说明权限矩阵存在缺口——避免上线后才发现动作无人可执行。
     */
    public static void verifyComplete() {
        Set<String> granted = ROLE_PERMISSIONS.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> missing = allPlatformPermissions().stream()
                .filter(p -> !granted.contains(p))
                .collect(java.util.stream.Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("平台权限矩阵存在缺口，以下动作无任何角色可执行: " + missing);
        }
    }
}
