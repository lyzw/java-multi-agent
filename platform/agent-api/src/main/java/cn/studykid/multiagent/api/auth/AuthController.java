package cn.studykid.multiagent.api.auth;

import java.time.Clock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 认证端点（D-001）。
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
     * 登录换取访问令牌。
     *
     * <p>⚠️ {@code tenantId} 由请求体提供 —— 这是**有意识的取舍**：
     * 客户端可指定租户，但因登录名全局唯一 + 所有失败路径原因不可辨 +
     * 耗时一致，**不产生可利用的枚举面**（设计文档 §3.2）。
     */
    @PostMapping("/token")
    public ResponseEntity<?> issueToken(@RequestBody LoginRequest request) {
        if (request == null || !request.isComplete()) {
            // ⚠️ 参数缺失也返回统一失败，不区分「缺哪个字段」
            //    —— 否则可用差异响应探测接口结构
            return unauthorized();
        }

        AuthenticationService.AuthResult result = authentication.authenticate(
                request.tenantId(), request.loginName(), request.password(), clock.instant());

        if (!result.success()) {
            return unauthorized();
        }
        return ResponseEntity.ok(new TokenResponse(
                result.token(), "Bearer", tokenTtlSeconds()));
    }

    /**
     * 公钥端点（JWKS 的最小实现）。
     *
     * <p>⚠️ 只暴露<b>公钥</b>。RSA 的安全性依赖私钥不出现在任何对外接口中。
     * 公钥公开是设计使然（RS256 的非对称性正是为此）。
     */
    @GetMapping("/jwks")
    public ResponseEntity<?> jwks() {
        return ResponseEntity.ok(new JwksResponse(
                tokens.keyId(), "RSA", tokens.publicKeyBase64()));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(new ErrorResponse("invalid_credentials"));
    }

    private long tokenTtlSeconds() {
        // 本期与配置一致；如需动态可从 JwtTokenService 暴露
        return 900L;
    }

    /**
     * 登录请求。
     *
     * <p>⚠️ <b>无 tenantId 之外的租户字段</b>，也<b>不接受邮箱/手机号</b>
     * —— PIPL 要求平台不采集这些（设计文档 §7）。
     */
    public record LoginRequest(String tenantId, String loginName, String password) {

        boolean isComplete() {
            return tenantId != null && !tenantId.isBlank()
                    && loginName != null && !loginName.isBlank()
                    && password != null && !password.isEmpty();
        }
    }

    /**
     * 令牌响应。
     *
     * <p>⚠️ <b>刻意不包含 principalId 与 tenantId</b> —— 最小化原则。
     * 客户端拿令牌即可调用，无需知道内部标识。
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    /** JWKS 最小形式。 */
    public record JwksResponse(String kid, String alg, String publicKeyBase64) {
    }

    /** 统一错误响应。消息固定，不含失败原因。 */
    public record ErrorResponse(String error) {
    }
}