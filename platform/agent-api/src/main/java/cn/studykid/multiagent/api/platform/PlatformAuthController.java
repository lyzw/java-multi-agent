package cn.studykid.multiagent.api.platform;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.infrastructure.security.PlatformCredentialAuthenticationService;
import cn.studykid.multiagent.infrastructure.security.PlatformCredentialAuthenticationService.LoginResult;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台认证端点（重规划 §7 / §9）。
 *
 * <p><b>独立命名空间</b> {@code /api/platform/v1/auth/**}，与租户认证
 * {@code /api/auth/**} 物理隔离；本端点只签发 {@code aud=admin-console-api}
 * 的平台令牌，绝不被租户业务 API 接受。
 *
 * <p><b>三条纪律（与租户侧同构）</b>：
 * <ol>
 *   <li><b>登录失败不区分原因</b> —— 账号不存在 / 口令错 / 状态禁用 返回同一 401，
 *       否则可被用于枚举平台账号。</li>
 *   <li><b>响应最小化</b> —— 不返回 {@code passwordHash} 等敏感字段。</li>
 *   <li><b>无注册/找回端点</b> —— 平台账号经 out-of-band 通道创建。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/platform/v1/auth")
public class PlatformAuthController {

    private final PlatformCredentialAuthenticationService credentials;
    private final PlatformJwtTokenService tokens;
    private final long tokenTtlSeconds;

    public PlatformAuthController(PlatformCredentialAuthenticationService credentials,
                                 PlatformJwtTokenService tokens,
                                 @Value("${auth.platform-jwt.token-ttl-seconds:900}") long tokenTtlSeconds) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * 平台登录换取访问令牌（公开端点）。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || !request.isComplete()) {
            return unauthorized();
        }
        try {
            LoginResult result = credentials.login(request.username(), request.password());
            PlatformPrincipal principal = result.principal();
            return ResponseEntity.ok(new TokenResponse(
                    result.token(), "Bearer", tokenTtlSeconds,
                    principal.userId(), principal.roles()));
        } catch (RuntimeException e) {
            // ⚠️ fail-closed：统一 401，不区分失败原因
            return unauthorized();
        }
    }

    /**
     * 平台 JWT 公钥（JWKS 最小实现，公开端点）。
     */
    @GetMapping("/jwks")
    public ResponseEntity<?> jwks() {
        return ResponseEntity.ok(new JwksResponse(
                tokens.keyId(), "RSA", tokens.publicKeyBase64()));
    }

    /**
     * 当前平台主体（需认证）。前端据此做导航显隐；安全边界仍以执行时重授权为准。
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
                                PlatformPrincipal principal) {
        return ResponseEntity.ok(new MeResponse(
                principal.userId(), principal.roles(),
                principal.authzVersion(), principal.mfaVerified()));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(new ErrorResponse("invalid_credentials"));
    }

    public record LoginRequest(String username, String password) {
        boolean isComplete() {
            return username != null && !username.isBlank()
                    && password != null && !password.isEmpty();
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                               String userId, Set<String> roles) {
    }

    public record JwksResponse(String kid, String alg, String publicKeyBase64) {
    }

    public record MeResponse(String userId, Set<String> roles,
                            long authzVersion, boolean mfaVerified) {
    }

    public record ErrorResponse(String error) {
    }
}
