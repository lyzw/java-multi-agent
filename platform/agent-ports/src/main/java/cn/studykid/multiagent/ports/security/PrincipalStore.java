package cn.studykid.multiagent.ports.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * 租户账号存取契约（认证链路所需的唯一数据库能力）。
 *
 * <p><b>为什么放在端口层</b>：2026-11-06 数据库层收敛后，
 * {@code PrincipalRepository}（JDBC 实现）迁入 {@code agent-persistence}，
 * 而 {@code AuthenticationService} / 接口层仍需认识账号记录与口令哈希工具。
 * 把契约与两个嵌套类型放在 {@code agent-ports}，使业务模块只依赖端口、
 * 不依赖任何数据库实现；实现类 {@code implements PrincipalStore} 后，
 * 嵌套类型经接口继承，{@code PrincipalRepository.PrincipalRecord} /
 * {@code PrincipalRepository.PasswordHasher} 这类既有写法<b>继续可用</b> —— 兼容性不变。
 *
 * <p>⚠️ 本接口不含任何 JDBC / ORM 类型，替换持久化实现时无需改动本文件。
 */
public interface PrincipalStore {

    Optional<PrincipalRecord> findByLogin(String tenantId, String loginName);

    boolean verifyPassword(PrincipalRecord record, String rawPassword, Instant now);

    void recordFailure(PrincipalRecord record, Instant now,
                       int maxAttempts, Duration lockDuration);

    void recordSuccess(PrincipalRecord record, Instant now);

    void revokeAuthorization(PrincipalRecord record, Instant now);

    Set<String> findRoles(String tenantId, String principalId);

public record PrincipalRecord(String principalId,
                              String tenantId,
                              String loginName,
                              String passwordHash,
                              @Nullable String displayName,
                              String status,
                              int failedAttempts,
                              @Nullable Instant lockedUntil,
                              long authzVersion) {
}

/**
 * 口令哈希（PBKDF2-HMAC-SHA256）。
 *
 * <p><b>⚠️ 为何不是 Argon2id</b>：设计文档初稿选Argon2id（抗 GPU 更优），
 * 但<b>实测 JDK 21.0.12 不提供 Argon2id 的 SecretKeyFactory</b>
 * （该支持要到 JDK 25+）。设计假设与运行环境不符，
 * 实施时以实测为准并回写文档。
 *
 * <p>PBKDF2 抗 GPU 弱于 Argon2，因此参数取 OWASP 2024 推荐下限
 * <b>600,000 次迭代</b>（实测约 110–130ms）。这一取舍须记录：
 * <b>成本转嫁为登录延迟</b>，是可用性与抗爆破的权衡，不是「安全」。
 *
 * <p>格式：{@code $pbkdf2-sha256$i=<iterations>$<salt-b64>$<hash-b64>}
 *
 * <p>⚠️ 参数写入哈希串：日后调高迭代次数时，旧哈希仍可验证，
 * 用户下次登录成功后自动重算（rehash on success）。
 */
public static final class PasswordHasher {

    /** OWASP 2024 推荐下限。自建认证无外部方兜底，宁可慢。 */
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "$pbkdf2-sha256$";

    private static final java.security.SecureRandom RANDOM =
            new java.security.SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        try {
            return PREFIX + "i=" + ITERATIONS + "$" + base64(salt) + "$"
                    + base64(derive(rawPassword, salt, ITERATIONS));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("口令哈希失败", e);
        }
    }

    /**
     * 校验口令。
     *
     * <p><b>格式非法时返回 false 而非抛异常</b> —— 异常与耗时差异
     * 本身都是攻击者可利用的信号。
     *
     * <p>比对用 {@link java.security.MessageDigest#isEqual}（常量时间），
     * 避免短路比较泄露前缀信息。
     */
    public static boolean verify(String rawPassword, String encodedHash) {
        if (rawPassword == null || rawPassword.isEmpty() || encodedHash == null) {
            return false;
        }
        try {
            if (!encodedHash.startsWith(PREFIX)) {
                return false;
            }
            String[] parts = encodedHash.substring(PREFIX.length()).split("\\$");
            if (parts.length != 3) {
                return false;
            }
            if (!parts[0].startsWith("i=")) {
                return false;
            }
            int iterations = Integer.parseInt(parts[0].substring(2));
            if (iterations < 100_000) {
                // ⚠️ 迭代次数异常低说明哈希串被篡改或来自弱实现，
                //    宁可拒绝也不要「兼容」
                return false;
            }
            byte[] salt = unbase64(parts[1]);
            byte[] expected = unbase64(parts[2]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** 该哈希串是否需要按当前参数重算（日后调参后旧哈希自动升级）。 */
    public static boolean needsRehash(String encodedHash) {
        return encodedHash == null || !encodedHash.startsWith(PREFIX + "i=" + ITERATIONS + "$");
    }

    private static byte[] derive(String rawPassword, byte[] salt, int iterations)
            throws java.security.GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            // ⚠️ 清除 char[] 中的口令，否则长时间驻留内存
            spec.clearPassword();
        }
    }

    private static String base64(byte[] raw) {
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] unbase64(String encoded) {
        return java.util.Base64.getDecoder().decode(encoded);
    }
}
}
