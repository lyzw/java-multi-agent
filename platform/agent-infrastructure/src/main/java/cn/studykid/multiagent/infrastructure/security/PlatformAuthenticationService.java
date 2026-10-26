package cn.studykid.multiagent.infrastructure.security;

import java.time.Instant;
import java.util.Set;

import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import org.jspecify.annotations.NonNull;

/**
 * 平台认证服务（重规划 §7）。
 *
 * <p>负责平台令牌 → 可信 {@link PlatformPrincipal} 的校验。与租户侧
 * {@link AuthenticationService} 独立：使用平台专属密钥与 {@code aud}，
 * 不触碰租户用户表。
 *
 * <p>⚠️ fail-closed：平台角色白名单为空会让所有平台令牌角色被丢弃 → 全站 403，
 * 属配置错误，必须在启动时暴露。
 */
public class PlatformAuthenticationService {

    private final PlatformJwtTokenService tokens;
    private final Set<String> knownRoles;

    public PlatformAuthenticationService(PlatformJwtTokenService tokens,
                                         @NonNull Set<String> knownRoles) {
        this.tokens = tokens;
        if (knownRoles == null || knownRoles.isEmpty()) {
            throw new IllegalArgumentException("平台角色白名单不得为空");
        }
        this.knownRoles = Set.copyOf(knownRoles);
    }

    /** 校验平台访问令牌并构造可信平台主体。 */
    public PlatformPrincipal authenticateToken(String token, Instant now) {
        return tokens.verify(token, now, knownRoles);
    }

    public PlatformJwtTokenService tokenService() {
        return tokens;
    }
}
