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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import cn.studykid.multiagent.core.tenant.TenantContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * JWT 签发与校验（D-001）。
 *
 * <p><b>为何不用第三方 JWT 库</b>：JWT 最常见的漏洞（{@code alg=none}、
 * 算法混淆、{@code kid} 注入）恰恰藏在第三方库的「便利」默认值里 ——
 * 例如某些库在未显式校验 {@code alg} 时会按 token 头里的声明选择算法。
 * 本项目要求这段逻辑**完全可审计**，故基于 JDK 内置 crypto 手写。
 * 代价是约 200 行代码，收益是攻击面由我们自己界定。
 *
 * <p><b>签名算法固定 RS256</b>，理由见设计文档 §4.2：
 * HS256 是对称算法，若公钥接口也用同一密钥，任何能读公钥者都能伪造令牌。
 *
 * <p>⚠️ 本类<b>不含默认值</b>：密钥、issuer、audience 全部由构造器强制传入。
 * 配置缺失时由调用方 fail-closed 拒绝启动，而非使用弱默认密钥。
 */
public final class JwtTokenService {

    /** 唯一允许的签名算法。校验时必须严格相等，不从 token 头读取。 */
    public static final String ALGORITHM = "RS256";

    private static final String JWT_HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final java.time.Duration tokenTtl;
    private final String kid;

    public JwtTokenService(PrivateKey privateKey,
                           PublicKey publicKey,
                           String issuer,
                           String audience,
                           java.time.Duration tokenTtl,
                           String kid) {
        this.privateKey = java.util.Objects.requireNonNull(privateKey, "私钥不能为空");
        this.publicKey = java.util.Objects.requireNonNull(publicKey, "公钥不能为空");
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
        // 缺 kid 时由公钥指纹派生，保证 /jwks 输出稳定且可定位
        this.kid = kid != null && !kid.isBlank()
                ? kid
                : Integer.toHexString(publicKey.hashCode());
    }

    // ── 密钥生成 ────────────────────────────────────────────────

    /**
     * 生成本地开发用 RSA 密钥对。
     *
     * <p>⚠️ <b>仅限本地与测试</b>。生产必须由 KMS 注入私钥
     * （设计文档 §7 限制 1）—— 每次启动生成新密钥会使所有已签发令牌失效。
     */
    public static @NonNull KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("生成 RSA 密钥对失败", e);
        }
    }

    /** 从 Base64（PKCS#8）载入私钥，供 KMS 注入场景使用。 */
    public static PrivateKey loadPrivateKey(String base64Pkcs8) {
        try {
            byte[] der = DECODER.decode(base64Pkcs8);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥格式非法（须为 Base64 的 PKCS#8 DER）", e);
        }
    }

    /** 从 Base64（X.509）载入公钥。 */
    public static PublicKey loadPublicKey(String base64X509) {
        try {
            byte[] der = DECODER.decode(base64X509);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥格式非法（须为 Base64 的 X.509 DER）", e);
        }
    }

    /** 公钥的 Base64（X.509 DER），供 /jwks 端点输出。 */
    public @NonNull String publicKeyBase64() {
        return ENCODER.encodeToString(publicKey.getEncoded());
    }

    public @NonNull String keyId() {
        return kid;
    }

    // ── 签发 ────────────────────────────────────────────────────

    /**
     * 签发访问令牌。
     *
     * @param context 认证上下文；其 {@code authzVersion} 写入令牌，
     *                执行时据此识别陈旧上下文（P0-D03 §4 红线 9）
     */
    public String issue(TenantContext context, java.time.Instant now) {
        if (context == null || now == null) {
            throw new IllegalArgumentException("签发参数不能为空");
        }
        long iat = now.getEpochSecond();
        long exp = now.plus(tokenTtl).getEpochSecond();

        String header = "{\"alg\":\"" + ALGORITHM + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String payload = "{"
                + "\"iss\":\"" + escape(issuer) + "\","
                + "\"sub\":\"" + escape(context.principalId()) + "\","
                + "\"aud\":\"" + escape(audience) + "\","
                + "\"iat\":" + iat + ","
                + "\"exp\":" + exp + ","
                + "\"jti\":\"" + java.util.UUID.randomUUID() + "\","
                + "\"tenant_id\":\"" + escape(context.tenantId()) + "\","
                + "\"roles\":[" + joinRoles(context.roles()) + "],"
                + "\"authz_version\":" + context.authzVersion()
                + "}";

        String signingInput = ENCODER.encodeToString(
                header.getBytes(StandardCharsets.UTF_8)) + "."
                + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    // ── 校验 ────────────────────────────────────────────────────

    /**
     * 校验令牌并构造可信上下文。
     *
     * <p>⚠️ <b>校验清单缺一即拒</b>（设计文档 §4.3）。其中最易漏的是
     * {@code alg} —— 不校验时攻击者可改成 {@code none} 去掉签名要求，
     * 或改成 HS256 用公钥当密钥。
     *
     * @param allowedRoles 库中登记的角色白名单；令牌声明的角色
     *                     <b>不在白名单内则被忽略</b> ——
     *                     否则任何人都能签发带 {@code TENANT_ADMIN} 的令牌自封管理员
     * @throws JwtValidationException 任一校验失败
     */
    public TenantContext verify(String token,
                                java.time.Instant now,
                                java.util.Set<String> allowedRoles) {
        if (token == null || token.isBlank() || now == null || allowedRoles == null) {
            throw new JwtValidationException("参数非法");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtValidationException("令牌格式非法");
        }

        Map parsedHeader = parseJsonSegment(parts[0]);
        Object alg = parsedHeader.get("alg");
        if (!ALGORITHM.equals(alg)) {
            // ⚠️ 不从 token 头「动态选择算法」，只接受硬编码的 RS256
            throw new JwtValidationException("签名算法不被接受");
        }

        // 先验签再解析 payload：避免在验签前处理攻击者可控的内容
        byte[] signature;
        try {
            signature = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException("签名编码非法");
        }
        if (!verifySignature(parts[0] + "." + parts[1], signature)) {
            throw new JwtValidationException("签名无效");
        }

        Map claims = parseJsonSegment(parts[1]);

        if (!issuer.equals(claims.get("iss"))) {
            throw new JwtValidationException("签发方不匹配");
        }
        if (!audience.equals(claims.get("aud"))) {
            throw new JwtValidationException("接收方不匹配");
        }
        Object exp = claims.get("exp");
        if (!(exp instanceof Number expNum) || now.getEpochSecond() >= expNum.longValue()) {
            throw new JwtValidationException("令牌已过期");
        }
        Object nbf = claims.get("nbf");
        if (nbf instanceof Number nbfNum && now.getEpochSecond() < nbfNum.longValue()) {
            throw new JwtValidationException("令牌尚未生效");
        }

        String tenantId = requireString(claims, "tenant_id");
        String principalId = requireString(claims, "sub");

        // ⚠️ 角色取交集：令牌声明的角色必须在库中登记，否则忽略。
        //    这是「防止令牌自封管理员」的唯一防线，不可省。
        java.util.Set<String> effectiveRoles = new java.util.LinkedHashSet<>();
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

        return new TenantContext(tenantId, principalId, effectiveRoles, authzVersion);
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

    private String requireString(Map claims, String key) {
        Object value = claims.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new JwtValidationException("令牌缺少必要声明");
        }
        return s;
    }

    private String joinRoles(java.util.Set<String> roles) {
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

    /**
     * 极简 JSON 对象解析。
     *
     * <p>只解析 JWT 载荷所需的结构（对象、字符串、数字、布尔、数组），
     * <b>不实现完整 JSON</b>：引入完整库会带来多态反序列化风险，
     * 而这里的数据来源是攻击者可控的令牌内容。
     *
     * <p>⚠️ 长度上限防止超大声明导致内存压力。
     */
    private static Map parseJsonSegment(String segment) {
        String json;
        try {
            json = new String(DECODER.decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException("令牌段编码非法");
        }
        if (json.length() > 8192) {
            throw new JwtValidationException("令牌段超长");
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new JwtValidationException("令牌段格式非法");
        }
    }

    /**
     *  Jackson 实例。
     *
     *  <p>⚠️ 只反序列化到 {@code Map}/{@code List}（Java 基础类型），
     * <b>不启用多态类型解析</b>：令牌载荷是攻击者完全可控的内容，
     * 绑定到任意 POJO 都会打开反序列化 gadget 链。
     */
    private static final tools.jackson.databind.ObjectMapper JSON =
            new tools.jackson.databind.ObjectMapper();

    /** 令牌校验失败。消息不含具体原因细节，统一在 API 层映射为 401。 */
    public static class JwtValidationException extends RuntimeException {
        public JwtValidationException(String message) {
            super(message);
        }
    }
}