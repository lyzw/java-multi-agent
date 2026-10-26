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

        String header = "{\"alg\":\"" + ALGORITHM + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
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
