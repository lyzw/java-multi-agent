package cn.studykid.rag.bootstrap.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import cn.studykid.rag.application.observability.LogContext;

/**
 * RAG 服务 JWT 鉴权过滤器（评审 P0-2 · §5.1）。
 *
 * <p><b>修复的漏洞</b>：RAG 此前<b>无任何鉴权</b>——能触达 8081 者改一个
 * {@code X-Tenant-Id} 头即可对<b>任意租户</b>登记/校验/撤回语料、检索全部内容。
 * 租户头仅做 {@code UUID.fromString()} 形态校验，<b>从不校验「该主体是否属于该租户」</b>。
 *
 * <p><b>机制</b>：纯 JDK RS256 验签（无新增运行时依赖），支持两个令牌域：
 * <ul>
 *   <li><b>租户域</b>（aud=agent-platform）：携带 {@code tenant_id} claim ——
 *       <b>强制与 {@code X-Tenant-Id} 归属绑定</b>（不一致 → 403）。
 *       这就是「改头访问任意租户」的关闭点；</li>
 *   <li><b>平台域</b>（aud=admin-console-api）：平台控制台跨租户治理属设计内行为
 *       （其自身有 PLATFORM_* 权限闸门与审计），不做租户绑定。</li>
 * </ul>
 *
 * <p><b>fail-closed 纪律</b>：
 * <ul>
 *   <li>公钥未配置（默认）→ 业务端点一律 <b>503</b>（「鉴权未配置」），
 *       绝不静默放行；健康探针（{@code /actuator/health**}）不受影响；</li>
 *   <li>缺令牌 / 坏签名 / 过期 / aud 不符 → <b>401</b>；</li>
 *   <li>租户归属不符 → <b>403</b>（已认证但不该碰这个租户）；</li>
 *   <li>{@code rag.security.enabled=false} 是显式 dev 逃生门：启动即打 ERROR 横幅，
 *       绝不允许默认关闭。</li>
 * </ul>
 *
 * <p><b>⚠️ 令牌域判定顺序</b>：按「验签成功 + iss/aud 匹配」确定域，而非先看 aud 再验签
 * —— 攻击者可自签一个 aud 正确的令牌，必须先过签名关。
 *
 * <p><b>⚠️ X-Tenant-Min-Trust-Tier 仍可伪造</b>（只影响门槛松紧，不影响租户隔离）；
 * 服务端权威门槛的落地属评审 P0-7（检索路径 G-3 闸门），不在本过滤器范围。
 */
public final class RagJwtAuthFilter implements Filter {

    /** 健康探针前缀（liveness/readiness 不做令牌校验）。 */
    static final String HEALTH_PREFIX = "/actuator/health";

    private static final String BEARER_PREFIX = "Bearer ";
    /** 时钟偏移容忍（秒）：公网 NTP 漂移不应造成误杀。 */
    private static final long CLOCK_LEEWAY_SECONDS = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TokenDomain> domains;
    private final boolean enabled;

    /** 一个令牌域：一套验签公钥 + 期望的 iss/aud。 */
    record TokenDomain(String name, RSAPublicKey publicKey,
                       String expectedIssuer, String expectedAudience) {
    }

    public RagJwtAuthFilter(boolean enabled, List<TokenDomain> domains) {
        if (enabled && domains.isEmpty()) {
            // ⚠️ 构造期拒绝「开启鉴权却没有任何公钥」的组合：
            //    让 503 兜底没有意义 —— 这是配置错误，应在本机启动时立即暴露。
            throw new IllegalArgumentException(
                    "RAG 鉴权已启用但未配置任何验签公钥（rag.security.tenant/platform.public-key）。"
                            + "fail-closed：请配置公钥，或显式设置 rag.security.enabled=false（仅限本地开发）。");
        }
        this.enabled = enabled;
        this.domains = List.copyOf(domains);
    }

    /**
     * 从 Base64（X.509 SubjectPublicKeyInfo DER）解析 RSA 公钥。
     *
     * <p>⚠️ **标准字母表与 URL-safe 字母表都接受**：唯一喂这个配置的上游是平台的
     * JWKS 端点（{@code GET /api/auth/jwks}、{@code GET /api/platform/v1/auth/jwks}），
     * 它回带的 {@code publicKeyBase64} 是 base64url（含 {@code -}／{@code _}）。
     * 2026-10-28 端到端实测：只认标准字母表时 RAG 服务**根本起不来**，
     * 报「令牌域 tenant 公钥不是合法 Base64 / Illegal base64 character 5f」——
     * 运维只能自己转码，等于把可用性问题藏在配置里。两种字母表在字符集上互斥，
     * 归一化不会引入歧义。
     *
     * @param name   域名（用于错误定位）
     * @param base64 公钥 Base64 串（标准或 URL-safe，可缺尾填充）
     * @param issuer 期望 iss
     * @param audience 期望 aud
     */
    public static TokenDomain parseKey(String name, String base64,
                                       String issuer, String audience) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("令牌域 " + name + " 的公钥为空");
        }
        try {
            byte[] der = Base64.getDecoder().decode(toStandardBase64(base64));
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            return new TokenDomain(name, (RSAPublicKey) key, issuer, audience);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("令牌域 " + name + " 公钥不是合法 Base64", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌域 " + name + " 公钥解析失败（须为 X.509 SPKI 的 RSA 公钥）", e);
        }
    }

    /** 去掉空白；含 URL-safe 字符时换成标准字母表并补齐尾部填充。 */
    private static String toStandardBase64(String raw) {
        String s = raw.replaceAll("\\s", "");
        if (s.indexOf('-') < 0 && s.indexOf('_') < 0) {
            return s;
        }
        String std = s.replace('-', '+').replace('_', '/');
        int pad = (4 - std.length() % 4) % 4;
        return std + "=".repeat(pad);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        String path = req.getRequestURI();
        if (path.startsWith(HEALTH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (!enabled) {
            // 显式逃生门（仅限本地开发）：放行但绝不静默
            // 日志身份取自 X-Tenant-Id —— 这不是「已验证的身份」，
            // 但下游确实按它执行（业务端点自己读该头），日志要反映实际生效的租户。
            LogContext.setIdentity(req.getHeader("X-Tenant-Id"), null);
            chain.doFilter(request, response);
            return;
        }
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(res, HttpServletResponse.SC_UNAUTHORIZED, "缺少 Bearer 令牌");
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        AuthenticatedToken auth = verify(token);
        if (auth == null) {
            reject(res, HttpServletResponse.SC_UNAUTHORIZED, "令牌无效、过期或受众不符");
            return;
        }
        // ── 租户归属绑定（评审 P0-2 核心）─────────────────────────
        if (auth.tenantBinding() && auth.tenantId() != null) {
            String requested = req.getHeader("X-Tenant-Id");
            String normalized = normalizeUuid(requested);
            if (normalized == null || !normalized.equalsIgnoreCase(auth.tenantId())) {
                reject(res, HttpServletResponse.SC_FORBIDDEN,
                        "租户上下文与令牌主体不匹配");
                return;
            }
        }
        // 日志上下文：只写验签通过后的 claim，不写请求头。
        // 平台域令牌无租户 claim（跨租户治理是设计内行为）→ 日志租户位保持 "-"。
        LogContext.setIdentity(auth.tenantId(), auth.principalId());
        chain.doFilter(request, response);
    }

    /** 验签 + 校验 iss/aud/exp；全部通过则返回已认证身份（含域信息），否则 null。 */
    AuthenticatedToken verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        byte[] payload;
        JsonNode claims;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            claims = mapper.readTree(payload);
        } catch (IllegalArgumentException | JacksonException e) {
            return null;
        }
        for (TokenDomain domain : domains) {
            if (!verifySignature(parts, domain.publicKey())) {
                continue;
            }
            String iss = text(claims, "iss");
            String aud = text(claims, "aud");
            if (!domain.expectedIssuer().equals(iss)
                    || !domain.expectedAudience().equals(aud)) {
                continue;
            }
            long exp = claims.path("exp").asLong(0);
            if (exp > 0 && Instant.now().getEpochSecond() > exp + CLOCK_LEEWAY_SECONDS) {
                return null;
            }
            boolean tenantDomain = "tenant".equals(domain.name());
            return new AuthenticatedToken(
                    text(claims, "sub"),
                    tenantDomain ? text(claims, "tenant_id") : null,
                    tenantDomain);
        }
        return null;
    }

    private boolean verifySignature(String[] parts, RSAPublicKey key) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return sig.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String normalizeUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + status + "\",\"message\":\"" + message + "\"}");
    }

    /** 验签通过的调用方身份（域 + 可绑定租户）。 */
    record AuthenticatedToken(String principalId, String tenantId, boolean tenantBinding) {
    }
}
