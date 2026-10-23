package cn.studykid.multiagent.api.auth;

import java.time.Clock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.core.tenant.TenantContext;
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
        // ⚠️ 回带 tenantId（2026-10-22）：认证层**本就持有**权威租户 ID
        //    （AuthResult.tenantId 来自 tenant 表），此前未透出，
        //    导致前端进入 RAG 管理面时只能让用户**手填**租户 UUID——
        //    而 RAG 侧的租户 ID 空间与 platform 不同，用户无从得知该填什么，
        //    表现为「登录后 RAG 管理页不可用」。
        //    回带的是**权威租户标识**（非敏感），不构成信息泄露：
        //    调用方本就需按该租户授权。
        // ⚠️ 回带角色（B1：登录返角色，2026-10-23 增补）：
        //    角色取自库中登记值，与 JWT 声明同源，但此处直接透出，
        //    前端无需再调 /api/auth/roles，一次往返即得身份用于导航显隐。
        //    安全边界仍由执行时重授权保证（红线 9），本响应仅作 UI 提示。
        return ResponseEntity.ok(new TokenResponse(
                result.token(), "Bearer", tokenTtlSeconds(),
                result.tenantId(), result.principalId(), result.roles()));
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

    /**
     * 当前令牌的生效角色（G1：登录返角色）。
     *
     * <p>⚠️ <b>角色不取 JWT 声明</b>：{@code tokens.verify(token, now, knownRoles)}
     * 会把令牌里的角色与库中登记角色取交集，<b>只有登记过的才生效</b>——
     * 否则拿到签发能力的一方可自签带 {@code TENANT_ADMIN} 的令牌提权。
     *
     * <p>⚠️ 所有失败路径（缺头/格式错/验签失败/过期/角色不在白名单）一律返回
     * 同一个 401，且不区分原因——与 {@code /token} 的账号枚举防护同构。
     */
    @GetMapping("/roles")
    public ResponseEntity<?> queryRoles(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return unauthorized();
        }
        try {
            TenantContext context = authentication.authenticateToken(token, clock.instant());
            return ResponseEntity.ok(new RolesResponse(context.roles()));
        } catch (RuntimeException e) {
            // ⚠️ fail-closed：异常类型可能泄露验签内部细节，故不区分、不透传
            return unauthorized();
        }
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
     * <p>⚠️ 含 <b>tenantId / principalId / roles</b>（均在 2026-10-22～10-23 增补）：
     * 三者皆非敏感——调用方本就按该租户/主体授权，角色仅用于前端导航显隐。
     * 最小化原则在此让位于「一次往返即得身份」，避免前端为拿角色多打一次
     * {@code /api/auth/roles}（该端点仍保留，供令牌续期后的角色重校验）。
     * <b>安全边界不以本响应为准</b>：执行时仍按红线 9 重新授权。
     *
     * @param accessToken 访问令牌
     * @param tokenType 令牌类型
     * @param expiresIn 有效期（秒）
     * @param tenantId <b>权威租户 ID</b>（2026-10-22 新增）——
     *                 来自认证层持有的租户记录，供前端按租户调用下游服务
     * @param principalId 主体 ID（2026-10-22 新增）——审核留痕需要「谁操作的」
     * @param roles 主体在本租户内的角色集合（B1：2026-10-23 新增），仅用于导航显隐
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String tenantId, String principalId, java.util.Set<String> roles) {
    }

    /** JWKS 最小形式。 */
    public record JwksResponse(String kid, String alg, String publicKeyBase64) {
    }

    /** 统一错误响应。消息固定，不含失败原因。 */
    public record ErrorResponse(String error) {
    }

    /**
     * 角色响应（G1）。
     *
     * <p>⚠️ 只含角色集合，不含 principalId / tenantId / authzVersion——
     * 最小化原则，与 {@code TokenResponse} 一致。
     */
    public record RolesResponse(java.util.Set<String> roles) {
    }
}