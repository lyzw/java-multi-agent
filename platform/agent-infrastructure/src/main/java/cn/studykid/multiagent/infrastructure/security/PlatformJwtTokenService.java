package cn.studykid.multiagent.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import org.jspecify.annotations.NonNull;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台身份令牌签发与校验（重规划 §4 / §7）。
 *
 * <p><b>与租户侧 {@link JwtTokenService} 完全独立</b>：不同 {@code aud}
 * （{@code admin-console-api} vs {@code agent-platform}）、不同
 * {@code principal_type}（{@code PLATFORM_USER}）、可独立密钥轮转。
 * 校验缺一项即拒 —— 从令牌层实现平台/租户身份隔离，满足验收清单第 1 条。
 *
 * <p>手写 RS256 的理由与 {@link JwtTokenService} 一致：攻击面由我们自己界定，
 * 不依赖第三方库的「便利」默认值（{@code alg=none} / 算法混淆）。
 */
public final class PlatformJwtTokenService {

    /** 唯一允许的签名算法，校验时严格相等，不从 token 头读取。 */
    public static final String ALGORITHM = "RS256";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final Duration tokenTtl;
    private final String kid;

    public PlatformJwtTokenService(PrivateKey privateKey,
                                   PublicKey publicKey,
                                   String issuer,
                                   String audience,
                                   Duration tokenTtl,
                                   String kid) {
        this.privateKey = Objects.requireNonNull(privateKey, "私钥不能为空");
        this.publicKey = Objects.requireNonNull(publicKey, "公钥不能为空");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer 不能为空");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience 不能为空");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("令牌有效期必须为正");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.tokenTtl = tokenTtl;
        this.kid = kid != null && !kid.isBlank()
                ? kid
                : Integer.toHexString(publicKey.hashCode());
    }

    // ── 密钥 ────────────────────────────────────────────────────

    public static @NonNull KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("生成 RSA 密钥对失败", e);
        }
    }

    public static PrivateKey loadPrivateKey(String base64Pkcs8) {
        try {
            byte[] der = DECODER.decode(base64Pkcs8);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥格式非法（须为 Base64 的 PKCS#8 DER）", e);
        }
    }

    public static PublicKey loadPublicKey(String base64X509) {
        try {
            byte[] der = DECODER.decode(base64X509);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥格式非法（须为 Base64 的 X.509 DER）", e);
        }
    }

    public @NonNull String publicKeyBase64() {
        return ENCODER.encodeToString(publicKey.getEncoded());
    }

    public @NonNull String keyId() {
        return kid;
    }

    // ── 签发 ────────────────────────────────────────────────────

    public String issue(PlatformPrincipal principal, Instant now) {
        if (principal == null || now == null) {
            throw new IllegalArgumentException("签发参数不能为空");
        }
        long iat = now.getEpochSecond();
        long exp = now.plus(tokenTtl).getEpochSecond();

        String payload = "{"
                + "\"iss\":\"" + escape(issuer) + "\","
                + "\"sub\":\"" + escape(principal.userId()) + "\","
                + "\"aud\":\"" + escape(audience) + "\","
                + "\"iat\":" + iat + ","
                + "\"exp\":" + exp + ","
                + "\"jti\":\"" + UUID.randomUUID() + "\","
                + "\"principal_type\":\"" + IdentityDomains.PRINCIPAL_TYPE_PLATFORM + "\","
                + "\"roles\":[" + joinRoles(principal.roles()) + "],"
                + "\"authz_version\":" + principal.authzVersion() + ","
                + "\"mfa\":" + principal.mfaVerified()
                + "}";
        return mint(payload);
    }

    /**
     * 治理票据里唯一携带的角色：<b>只表示「可签发全局语料的审核结论」</b>。
     *
     * <p><b>⚠️ 这是资质声明，不是平台角色</b>：
     * <ul>
     *   <li>平台授权矩阵（{@code PlatformRolePermissionMatrix}）<b>不收录</b>它，
     *       所以持票者在平台域任何端点依旧是「默认拒绝」——
     *       票据不会被当成登录态使用（这正是原先签空角色的初衷）；</li>
     *   <li>RAG 侧 {@code SegregationOfDuties} 认它，据此放行 VERIFIED 的角色闸门。</li>
     * </ul>
     *
     * <p>命名刻意带 {@code RAG} 前缀且与平台角色命名空间区分开：
     * 看到 {@code PLATFORM_RESOURCE_ADMIN} 会让人以为是平台角色，
     * 而这里必须是「只对 RAG 审核有意义的一格授权」。
     */
    public static final String RAG_GOVERNANCE_ROLE = "RAG_CORPUS_GOVERNANCE";

    /**
     * 治理票据的作用域 claim 名。
     *
     * <p>⚠️ 与 RAG 侧 {@code RagJwtAuthFilter#TICKET_SCOPE_CLAIM} <b>同名同义、各存一份</b>：
     * 两个 reactor 之间没有共享编译产物（RAG 不依赖平台 jar），
     * 因此这条契约只能靠命名与注释钉住。改一处不改另一处的表现是
     * 「票据签出来了、RAG 说不认识」，而那条 403 读起来像权限不足。
     */
    public static final String RAG_SCOPE_CLAIM = "rag_scope";

    /**
     * 一张刚签出的令牌。
     *
     * <p>{@code token} 是<b>凭据本身</b>：只准即时交付给调用方，
     * 绝不写日志、绝不进审计正文（事后回溯用 {@code jti}）。
     */
    public record Issued(@NonNull String token,
                         @NonNull String jti,
                         @NonNull Instant expiresAt) {
    }

    /**
     * 签出一枚 RAG 全局语料<b>治理票据</b>（RAG-D11 第四刀·第二刀 §2.3）。
     *
     * <p><b>为什么不能直接把登录令牌当票据用</b>：RAG 管理面能区分「调用方是平台域」，
     * 但平台域身份本身不等于「被授权改动全局语料」。票据多带的正是这一格授权声明。
     *
     * <p><b>⚠️ {@code roles} 只签一个专用的权限作用域角色，而不是持有者的真实角色</b>：
     * 票据若带上持有者的真实角色，它就同时是一枚<b>有效期内的平台登录态</b>——
     * 泄露到 RAG 侧访问日志里，等于把「改全局语料」的授权扩成
     * 「持票者角色所能做的全部平台动作」。
     *
     * <p><b>为什么不能签成空集合</b>（曾经就是这样，实测踩到）：
     * RAG 侧签发 VERIFIED 时有一道角色闸门（{@code SegregationOfDuties.checkRole}），
     * 而空角色的票据在那道闸门面前<b>永远 403</b>；同时 RAG 的写上下文又要求
     * 平台全局档必须持票据 —— 于是「有角色的人拿不到票据、有票据的人没有角色」，
     * 平台上<b>任何角色都签不出全局语料的 VERIFIED</b>。
     *
     * <p>修法不是给票据塞真实角色（那会重新打开登录态泄漏面），而是签一个
     * <b>只表示「这张票可签发全局语料审核结论」的专用角色</b>
     * （{@link #RAG_GOVERNANCE_ROLE}）：
     * <ul>
     *   <li>它在平台授权矩阵里<b>不存在</b>，因此在平台域任何端点仍是默认拒绝；</li>
     *   <li>RAG 侧把它识别为「已授权签发审核结论」，但**不**因此获得任何平台权限。</li>
     * </ul>
     * 这两条合起来才能同时满足：票据不能当登录态用 + 授权必须能到达 RAG。
     *
     * <p><b>受众与登录令牌一致</b>（{@code aud=admin-console-api}）：
     * 这是 RAG 侧平台域的既有判据，不是新开的口子——RAG 从不校验平台角色的权限码，
     * 它只校验签名、iss/aud、有效期与这一格 claim。
     *
     * @param principal 持票的平台主体（票据的 {@code sub} 仍指向真人，审计才能归因）
     * @param now       签发时刻
     * @param ttl       票据有效期；由调用方的配置闸门给出，本方法只校验为正
     * @param scope     票据声明的作用域（{@code PLATFORM}／{@code ALL}），须为大写标识符形态
     * @return 票据（含 token、jti、过期时刻）
     * @throws IllegalArgumentException {@code scope} 形态非法（防手写 JSON 的注入面）
     */
    public @NonNull Issued issueRagGovernanceTicket(@NonNull PlatformPrincipal principal,
                                                    @NonNull Instant now,
                                                    @NonNull Duration ttl,
                                                    @NonNull String scope) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("治理票据有效期必须为正，当前 " + ttl);
        }
        String claim = requireScopeShape(scope);
        String jti = UUID.randomUUID().toString();
        long iat = now.getEpochSecond();
        long exp = now.plus(ttl).getEpochSecond();

        String payload = "{"
                + "\"iss\":\"" + escape(issuer) + "\","
                + "\"sub\":\"" + escape(principal.userId()) + "\","
                + "\"aud\":\"" + escape(audience) + "\","
                + "\"iat\":" + iat + ","
                + "\"exp\":" + exp + ","
                + "\"jti\":\"" + jti + "\","
                + "\"principal_type\":\"" + IdentityDomains.PRINCIPAL_TYPE_PLATFORM + "\","
                + "\"roles\":[\"" + RAG_GOVERNANCE_ROLE + "\"],"
                + "\"authz_version\":" + principal.authzVersion() + ","
                + "\"mfa\":" + principal.mfaVerified() + ","
                + "\"" + RAG_SCOPE_CLAIM + "\":\"" + claim + "\""
                + "}";
        return new Issued(mint(payload), jti, Instant.ofEpochSecond(exp));
    }

    /**
     * 票据作用域的形态闸门：大写字母开头的 {@code [A-Z0-9_]} 标识符，长度 ≤ 32。
     *
     * <p><b>为什么在签发器里再校验一遍</b>：payload 是手写拼接的 JSON 串，
     * 值里出现引号就是声明注入。调用链上游当然也该校验，
     * 但「不能拼出第二条声明」这件事必须由<b>唯一能签名的地方</b>兜住。
     */
    private static String requireScopeShape(String scope) {
        if (scope == null || !SCOPE_SHAPE.matcher(scope).matches()) {
            throw new IllegalArgumentException(
                    "治理票据作用域形态非法（须为大写字母开头的 [A-Z0-9_]，长度 ≤ 32）：" + scope);
        }
        return scope;
    }

    private static final java.util.regex.Pattern SCOPE_SHAPE =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    /** 拼头、签名、组装三段式（登录令牌与治理票据共用同一条签名路径，避免两处漂移）。 */
    private String mint(String payload) {
        String header = "{\"alg\":\"" + ALGORITHM + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String signingInput = ENCODER.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    // ── 校验 ────────────────────────────────────────────────────

    public PlatformPrincipal verify(String token, Instant now, Set<String> allowedRoles) {
        if (token == null || token.isBlank() || now == null || allowedRoles == null) {
            throw new PlatformJwtValidationException("参数非法");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new PlatformJwtValidationException("令牌格式非法");
        }

        Map<?, ?> header = parseJsonSegment(parts[0]);
        if (!ALGORITHM.equals(header.get("alg"))) {
            throw new PlatformJwtValidationException("签名算法不被接受");
        }

        byte[] signature;
        try {
            signature = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new PlatformJwtValidationException("签名编码非法");
        }
        if (!verifySignature(parts[0] + "." + parts[1], signature)) {
            throw new PlatformJwtValidationException("签名无效");
        }

        Map<?, ?> claims = parseJsonSegment(parts[1]);

        if (!issuer.equals(claims.get("iss"))) {
            throw new PlatformJwtValidationException("签发方不匹配");
        }
        if (!audience.equals(claims.get("aud"))) {
            // ⚠️ 跨域令牌：租户令牌（aud=agent-platform）在此被拒，反之亦然
            throw new PlatformJwtValidationException("接收方不匹配");
        }
        Object exp = claims.get("exp");
        if (!(exp instanceof Number expNum) || now.getEpochSecond() >= expNum.longValue()) {
            throw new PlatformJwtValidationException("令牌已过期");
        }
        Object nbf = claims.get("nbf");
        if (nbf instanceof Number nbfNum && now.getEpochSecond() < nbfNum.longValue()) {
            throw new PlatformJwtValidationException("令牌尚未生效");
        }

        // ⚠️ 主体类型必须为平台用户，否则即使 aud 巧合匹配也不接受
        Object principalType = claims.get("principal_type");
        if (!IdentityDomains.PRINCIPAL_TYPE_PLATFORM.equals(principalType)) {
            throw new PlatformJwtValidationException("主体类型不匹配");
        }

        String userId = requireString(claims, "sub");

        Set<String> effectiveRoles = new java.util.LinkedHashSet<>();
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof List<?> list) {
            for (Object role : list) {
                if (role instanceof String s && allowedRoles.contains(s)) {
                    effectiveRoles.add(s);
                }
            }
        }

        long authzVersion = 1L;
        Object version = claims.get("authz_version");
        if (version instanceof Number v) {
            authzVersion = v.longValue();
        }

        boolean mfa = Boolean.TRUE.equals(claims.get("mfa"));

        return new PlatformPrincipal(userId, effectiveRoles, authzVersion, mfa);
    }

    // ── 内部 ────────────────────────────────────────────────────

    private byte[] sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private boolean verifySignature(String signingInput, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes);
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    private String requireString(Map<?, ?> claims, String key) {
        Object value = claims.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new PlatformJwtValidationException("令牌缺少必要声明");
        }
        return s;
    }

    private String joinRoles(Set<String> roles) {
        StringBuilder sb = new StringBuilder();
        for (String role : roles) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(role)).append('"');
        }
        return sb.toString();
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<?, ?> parseJsonSegment(String segment) {
        String json;
        try {
            json = new String(DECODER.decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new PlatformJwtValidationException("令牌段编码非法");
        }
        if (json.length() > 8192) {
            throw new PlatformJwtValidationException("令牌段超长");
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new PlatformJwtValidationException("令牌段格式非法");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 平台令牌校验失败。消息不含具体原因细节，统一在 API 层映射为 401。 */
    public static class PlatformJwtValidationException extends RuntimeException {
        public PlatformJwtValidationException(String message) {
            super(message);
        }
    }
}
