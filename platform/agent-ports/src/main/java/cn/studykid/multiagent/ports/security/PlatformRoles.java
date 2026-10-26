package cn.studykid.multiagent.ports.security;

/**
 * 平台角色常量（重规划 §3.1）。
 *
 * <p>6 个<b>最小特权</b>平台角色，按职责分离设计。
 * 与租户角色（{@code TENANT_*}）物理隔离，互不可混、不可共用一套角色表。
 * 实际授权支持多个角色组合，并通过禁止权限、职责分离与高风险审批控制组合风险。
 */
public final class PlatformRoles {

    private PlatformRoles() {
    }

    /** 平台超级管理员：最高权限，敏感操作强 MFA/重认证；不因此获得租户业务数据。 */
    public static final String SUPER_ADMIN = "PLATFORM_SUPER_ADMIN";

    /** 租户生命周期管理员：创建/启停/套餐/配额/能力授权；不管理租户内部用户角色。 */
    public static final String TENANT_LIFECYCLE_ADMIN = "TENANT_LIFECYCLE_ADMIN";

    /** 平台资源管理员：全局 Tool/MCP/模型目录/资源可用性；不直接读租户私有配置。 */
    public static final String RESOURCE_ADMIN = "PLATFORM_RESOURCE_ADMIN";

    /** 平台安全管理员：认证/访问/授权策略与风险处置；普通情况不能改自身角色或建超级管理员。 */
    public static final String SECURITY_ADMIN = "PLATFORM_SECURITY_ADMIN";

    /** 平台审计员：只读平台审计与脱敏租户生命周期；敏感字段脱敏。 */
    public static final String AUDITOR = "PLATFORM_AUDITOR";

    /** 平台支持人员：只读脱敏诊断与运维信息；不读业务内容或凭据明文。 */
    public static final String SUPPORT = "PLATFORM_SUPPORT";

    /** 全部内置平台角色（用于角色白名单自检与枚举）。 */
    public static final java.util.Set<String> ALL = java.util.Set.of(
            SUPER_ADMIN, TENANT_LIFECYCLE_ADMIN, RESOURCE_ADMIN,
            SECURITY_ADMIN, AUDITOR, SUPPORT);
}
