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

    /**
     * 平台权限中文说明（与 {@link Platform} 常量一一对应）。
     *
     * <p>权限矩阵是<b>代码冻结</b>的单一事实源；此处中文说明供 admin-console「权限矩阵」
     * 页渲染、并作为 {@code platform.platform_permission.description} 列的播种文案
     * （数据库为该字段的治理副本，运行期不以 DB 读回为准）。
     *
     * <p>⚠️ 与 {@link #allPlatformPermissions()} 严格对齐：新增/移除权限时必须同步维护，
     * 否则 {@link #verifyDescribed()} 自检会失败。
     */
    public static final Map<String, String> PERMISSION_DESCRIPTIONS = Map.ofEntries(
            Map.entry(Platform.USER_READ, "查看平台用户基本信息（脱敏）"),
            Map.entry(Platform.USER_MANAGE, "管理平台用户（创建/禁用/重置凭据等写操作）"),
            Map.entry(Platform.ROLE_READ, "查看平台角色与权限矩阵"),
            Map.entry(Platform.ROLE_ASSIGN, "授予平台角色"),
            Map.entry(Platform.ROLE_REVOKE, "撤销平台角色"),
            Map.entry(Platform.TENANT_CREATE, "创建新租户"),
            Map.entry(Platform.TENANT_READ, "查看租户生命周期与配置（脱敏）"),
            Map.entry(Platform.TENANT_UPDATE, "修改租户配置与套餐"),
            Map.entry(Platform.TENANT_SUSPEND, "停用（暂停）租户"),
            Map.entry(Platform.TENANT_RESUME, "恢复（启用）租户"),
            Map.entry(Platform.TENANT_DECOMMISSION, "退役（销毁）租户"),
            Map.entry(Platform.TENANT_QUOTA_READ, "查看租户配额"),
            Map.entry(Platform.TENANT_QUOTA_UPDATE, "调整租户配额"),
            Map.entry(Platform.TENANT_CAPABILITY_GRANT, "授予租户能力（如 Tool/Skill/MCP 授权）"),
            Map.entry(Platform.TENANT_CAPABILITY_REVOKE, "撤销租户能力授权"),
            Map.entry(Platform.TENANT_CAPABILITY_READ, "查看租户已授权能力"),
            Map.entry(Platform.RESOURCE_READ, "查看全局资源目录（Tool/MCP/模型等）"),
            Map.entry(Platform.RESOURCE_MANAGE, "管理全局资源目录可用性"),
            Map.entry(Platform.POLICY_READ, "查看平台安全/访问策略"),
            Map.entry(Platform.POLICY_PUBLISH, "发布平台安全/访问策略"),
            Map.entry(Platform.AUDIT_READ, "查看平台审计日志"),
            Map.entry(Platform.AUDIT_EXPORT, "导出平台审计日志"),
            Map.entry(Platform.SUPPORT_DIAGNOSTICS_READ, "查看脱敏诊断与运维信息"));

    /** 取某权限的中文说明（只读；未登记时返回空串）。 */
    public static @NonNull String descriptionOf(@NonNull String code) {
        return PERMISSION_DESCRIPTIONS.getOrDefault(code, "");
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

    /**
     * 启动期自检：确认 {@link #allPlatformPermissions()} 中的每一项都有中文说明。
     *
     * <p>避免新增权限时遗漏 {@link #PERMISSION_DESCRIPTIONS}，导致矩阵页出现空说明。
     */
    public static void verifyDescribed() {
        Set<String> undescribed = allPlatformPermissions().stream()
                .filter(p -> !PERMISSION_DESCRIPTIONS.containsKey(p)
                        || PERMISSION_DESCRIPTIONS.get(p).isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (!undescribed.isEmpty()) {
            throw new IllegalStateException("平台权限存在未登记中文说明的条目: " + undescribed);
        }
    }
}
