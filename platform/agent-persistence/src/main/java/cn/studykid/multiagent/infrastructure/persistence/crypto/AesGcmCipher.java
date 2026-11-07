package cn.studykid.multiagent.infrastructure.persistence.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.NonNull;

/**
 * AES-256-GCM 对称加密工具（供应商密钥落库用）。
 *
 * <p><b>⚠️ 安全约定</b>：
 * <ul>
 *   <li>主密钥来自环境变量 {@code agent.model-credential.master-key}，
 *       经 SHA-256 派生为 32 字节 AES 密钥（不要求运维记忆 32 字节原文）；</li>
 *   <li>每条密文独立随机 12 字节 IV（GCM 推荐值），密文格式 =
 *       {@code base64( IV(12B) || ciphertext+tag )}；</li>
 *   <li>明文密钥<b>绝不</b>落库，落库仅为密文；解密仅在运行时内存发生。</li>
 * </ul>
 *
 * <p>本类是<b>纯函数式加密原语</b>，不依赖 Spring / 数据库，可直接单测。
 */
public final class AesGcmCipher {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKey key;

    private AesGcmCipher(@NonNull SecretKey key) {
        this.key = key;
    }

    /**
     * 由主密钥口令派生 32 字节 AES 密钥。
     *
     * @param masterSecret 环境变量注入的主密钥（任意长度，经 SHA-256 派生）
     * @throws IllegalArgumentException 主密钥为空
     */
    public static @NonNull AesGcmCipher fromMasterSecret(@NonNull String masterSecret) {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.model-credential.master-key 未配置：密钥无法加密存储（fail-closed）");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derived = sha256.digest(masterSecret.getBytes(StandardCharsets.UTF_8));
            return new AesGcmCipher(new SecretKeySpec(derived, "AES"));
        } catch (Exception e) {
            throw new IllegalStateException("派生主密钥失败", e);
        }
    }

    /** 生成随机主密钥（仅用于初始化 / 轮换，不暴露明文）。 */
    public static @NonNull String generateMasterSecret() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(KEY_LENGTH_BYTES * 8);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(gen.generateKey().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("生成主密钥失败", e);
        }
    }

    /** 加密明文 → base64 密文（含随机 IV）。 */
    public @NonNull String encrypt(@NonNull String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密 base64 密文（含 IV）→ 明文。 */
    public @NonNull String decrypt(@NonNull String stored) {
        try {
            byte[] out = Base64.getDecoder().decode(stored);
            if (out.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(out, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ct = new byte[out.length - GCM_IV_LENGTH];
            System.arraycopy(out, GCM_IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（密钥不匹配或密文损坏）", e);
        }
    }

    /** 明文 SHA-256 校验和（hex），用于 key_checksum 列与篡改检测。 */
    public static @NonNull String checksum(@NonNull String plaintext) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算校验和失败", e);
        }
    }
}
