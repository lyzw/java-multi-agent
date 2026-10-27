package cn.studykid.multiagent.api.platform;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRolePermissionMatrix;

/**
 * 平台权限目录端点（重规划 §3.1 / §9）。
 *
 * <p>返回平台权限全集及其中文说明（来自冻结矩阵 {@link PlatformRolePermissionMatrix}），
 * 供 admin-console「权限矩阵」页渲染可读的中文释义。权限矩阵运行期不可修改，
 * 角色赋权仍走 out-of-band 通道。
 */
@RestController
@RequestMapping("/api/platform/v1/permissions")
public class PlatformPermissionController {

    private final PlatformSecurity security;

    public PlatformPermissionController(PlatformSecurity security) {
        this.security = security;
    }

    /**
     * 列出平台权限及其中文说明（需 {@code platform.role.read}）。
     */
    @GetMapping
    public List<PermissionSummary> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.ROLE_READ, null, null);

        return PlatformRolePermissionMatrix.allPlatformPermissions().stream()
                .sorted()
                .map(code -> new PermissionSummary(
                        code,
                        PlatformRolePermissionMatrix.descriptionOf(code)))
                .toList();
    }

    public record PermissionSummary(String code, String description) {
    }
}
