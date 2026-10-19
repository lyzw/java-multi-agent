package cn.studykid.multiagent.api.security;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * 从 Bearer 令牌解析可信身份（D-001）。
 *
 * <p><b>执行链</b>：
 * <pre>
 * Authorization 头 → 提取 Bearer → 验签+ 声明校验 → 角色白名单过滤 → 可信 TenantContext
 * </pre>
 *
 * <p>⚠️ <b>三条不可绕过的规则</b>：
 * <ol>
 *   <li><b>无令牌即拒绝</b>，不返回任何默认租户或兜底值。
 *       「兜底」在这里等于「默认放行」。</li>
 *   <li><b>角色以库为准</b> —— 令牌里的 roles 只是声明，
 *       本类用白名单过滤后再交由 {@code AuthenticationService} 复核。</li>
 *   <li><b>错误消息不含原因</b> —— 区分「过期」「签名无效」会给攻击者调试信号。</li>
 * </ol>
 */
public class JwtCurrentPrincipal implements CurrentPrincipal {

    private static final Logger log = LoggerFactory.getLogger(JwtCurrentPrincipal.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationService authentication;
    private final Clock clock;
    private final String authorizationHeader;

    /**
     * @param authorizationHeader 请求头值；可为 null（→ 拒绝）
     */
    public JwtCurrentPrincipal(AuthenticationService authentication,
                               Clock clock,
                               String authorizationHeader) {
        this.authentication = authentication;
        this.clock = clock;
        this.authorizationHeader = authorizationHeader;
    }

    @Override
    public TenantContext require() {
        String token = extractToken()
                .orElseThrow(() -> new UnauthenticatedException("缺少访问令牌"));

        try {
            return authentication.authenticateToken(token, clock.instant());
        } catch (JwtTokenService.JwtValidationException e) {
            // ⚠️ 只记「哪类失败」，不记令牌内容 —— 日志也会被运维看到
            log.warn("令牌校验失败: {}", e.getMessage());
            // 消息统一为「unauthorized」：不告诉调用方是过期还是签名无效
            throw new UnauthenticatedException("unauthorized");
        }
    }

    /**
     * 提取 Bearer 令牌。
     *
     * <p>⚠️ 大小写不敏感（RFC 6750 允许），但<b>必须严格三段式</b> ——
     * 宽松匹配会让「Basic xxx」或拼接畸形值被误当令牌，
     * 掩盖真实的认证失败原因。
     */
    private Optional<String> extractToken() {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        if (authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0,
                        BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}