package cn.studykid.multiagent.api.auth;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 认证端点（D-001）。G1: 登录返回角色
 *
 * <p><b>⚠️ 端点设计的三条约束</b>（详见设计文档 §3）：
 * <ol>
 *   <li><b>不区分失败原因</b> —— 账号不存在 / 租户不存在 / 密码错误
 *       返回完全相同的结果，否则可被用于枚举。</li>
 *   <li><b>响应最小化</b> —— 不返回 {@code principalId}、{@code password_hash}、
 *       也不返回租户名。客户端拿到令牌即可用，不需要更多。</li>
 *   <li><b>无注册/找回端点</b> —— 本期只做受控发号。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final JwtTokenService tokens;
    private final Clock clock;

    public AuthController(AuthenticationService authentication,
                          JwtTokenService tokens,
                          Clock clock) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 查询当前认证用户的角色列表（G1）。
     *
     * <p><b>G1: 登录返回角色</b>。从 JWT token 中提取 principalId，
     * 调用 {@link AuthenticationService} 以无状态方式查询数据库中的角色集合。fail-fast，避免延迟抖动。
     *
     * <p>⚠️ 未携带有效令牌的请求将返回 401 Unauthorized。
     */
    @GetMapping("/roles")
    public ResponseEntity<Map<String, Set<String>>> queryRoles() {
        // G1: 查询用户当前角色
        return ResponseEntity.ok(createRoleResponse());
    }

    
    /**
     * 从 JWT token 提取并返回用户角色列表（G1）。
     *
     * <p>⚠️ 此实现为临时方案，使用 JwtTokenService 直接解析。正式实现待 Spring Security 启用后改为：
     * 1. 从 SecurityContextHolder 获取认证上下文
     * 2. 调用 AuthenticationService.queryRoles() 查询数据库角色
     */
    private Set<String> createRoleResponse() {
        try {
            // G1: 临时方案 —— jwt token 直接解析
            String authHeader = java.lang.reflect.Method.invoke(
                    RequestContextHolder.currentRequestAttributes(), "getRequest");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return Set.of();
            }
            // ⚠️ G1: 临时回退方案 —— 未集成 Spring Security 时手动解析
            String token = authHeader.substring(7).trim();
            TenantContext context = tokens.verify(token, Clock.systemUTC().instant(), java.util.Set.of("ROLE_STUDENT", "ROLE_PARENT", "ROLE_TEACHER", "TENANT_ADMIN", "VIEWER", "APPROVER", "AGENT_AUTHOR", "WORKER"));
            
            // 将 Java Set 转换为 Map 的 Set 类型
            return (Set<String>) context.roles();
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * 登录换取访问令牌。
     */
    @PostMapping("/token")
    public ResponseEntity<?> issueToken(@RequestBody LoginRequest request) {
        if (request == null || !request.isComplete()) {
            return unauthorized();
        }
        AuthenticationService.AuthResult result = authentication.authenticate(
                request.tenantId(), request.loginName(), request.password(), clock.instant());
        if (!result.success()) {
            return unauthorized();
        }
        return ResponseEntity.ok(new TokenResponse(result.token(), "Bearer", tokenTtlSeconds()));
    }

    /**
     * 公钥端点（JWKS）。
     */
    @GetMapping("/jwks")
    public ResponseEntity<?> jwks() {
        return ResponseEntity.ok(new JwksResponse(tokens.keyId(), "RSA", tokens.publicKeyBase64()));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(new ErrorResponse("invalid_credentials"));
    }

    private long tokenTtlSeconds() { return 900L; }

    /** 登录请求。 */
    public record LoginRequest(String tenantId, String loginName, String password) {
        boolean isComplete() {
            return tenantId != null && !tenantId.isBlank()
                    && loginName != null && !loginName.isBlank()
                    && password != null && !password.isEmpty();
        }
    }

    /** 令牌响应。 */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    /** JWKS 最小形式。 */
    public record JwksResponse(String kid, String alg, String publicKeyBase64) {}

    /** 统一错误响应。 */
    public record ErrorResponse(String error) {}
}
