package cn.studykid.multiagent.api.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRolePermissionMatrix;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * 平台角色目录端点（重规划 §3.1 / §9）。
 *
 * <p>返回 6 个最小特权平台角色及其权限范围（来自冻结矩阵），
 * 供 admin-console 展示与授权自检。角色赋权本身仍走 out-of-band 通道。
 */
@RestController
@RequestMapping("/api/platform/v1/roles")
public class PlatformRoleController {

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            PlatformRoles.SUPER_ADMIN,
            "平台超级管理员（最高权限，敏感操作强 MFA/重认证）",
            PlatformRoles.TENANT_LIFECYCLE_ADMIN,
            "租户生命周期管理员（创建/启停/套餐/配额/能力授权）",
            PlatformRoles.RESOURCE_ADMIN,
            "平台资源管理员（全局 Tool/MCP/模型目录/资源可用性）",
            PlatformRoles.SECURITY_ADMIN,
            "平台安全管理员（认证/访问/授权策略与风险处置）",
            PlatformRoles.AUDITOR,
            "平台审计员（只读平台审计与脱敏租户生命周期）",
            PlatformRoles.SUPPORT,
            "平台支持人员（只读脱敏诊断与运维信息）");

    private final PlatformSecurity security;

    public PlatformRoleController(PlatformSecurity security) {
        this.security = security;
    }

    /**
     * 列出平台角色及其权限（需 {@code platform.role.read}）。
     */
    @GetMapping
    public List<RoleSummary> list(@RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
                                  PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.ROLE_READ, null, null);

        return PlatformRoles.ALL.stream()
                .map(role -> new RoleSummary(
                        role,
                        DESCRIPTIONS.getOrDefault(role, ""),
                        PlatformRolePermissionMatrix.permissionsOf(role)))
                .toList();
    }

    public record RoleSummary(String name, String description, Set<String> permissions) {
    }
}
