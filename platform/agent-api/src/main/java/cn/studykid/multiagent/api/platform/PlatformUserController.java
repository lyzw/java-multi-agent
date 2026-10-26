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
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;

/**
 * 平台用户管理端点（重规划 §8 / §9）。
 *
 * <p>仅暴露平台用户的管理元数据（id/用户名/状态/认证源/是否强制 MFA），
 * <b>绝不返回 password_hash</b>。平台账号与租户账号不混用、不互查。
 */
@RestController
@RequestMapping("/api/platform/v1/users")
public class PlatformUserController {

    private final PlatformPrincipalRepository users;
    private final PlatformSecurity security;

    public PlatformUserController(PlatformPrincipalRepository users,
                                 PlatformSecurity security) {
        this.users = users;
        this.security = security;
    }

    /**
     * 列出全部平台用户（需 {@code platform.user.read}）。
     */
    @GetMapping
    public List<UserSummary> list(@RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
                                  PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.USER_READ, null, null);

        return users.listUsers().stream()
                .map(r -> new UserSummary(
                        r.id(), r.username(), r.displayName(), r.status(),
                        r.authSource(), r.mfaRequired(), r.authzVersion(),
                        r.lastLoginAt() == null ? null : r.lastLoginAt().toString()))
                .toList();
    }

    /** 平台用户摘要（无敏感字段）。 */
    public record UserSummary(String id, String username, String displayName, String status,
                             String authSource, boolean mfaRequired, long authzVersion,
                             String lastLoginAt) {
    }
}
