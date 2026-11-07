package cn.studykid.multiagent.infrastructure.persistence.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AesGcmCipher · AES-256-GCM 加密原语")
class AesGcmCipherTest {

    private final AesGcmCipher cipher = AesGcmCipher.fromMasterSecret("test-master-secret-2026");

    @Test
    @DisplayName("✅ 加密→解密 可还原明文")
    void roundTrip() {
        String plain = "sk-secret-1234567890";
        String stored = cipher.encrypt(plain);
        assertThat(stored).isNotEqualTo(plain);
        assertThat(cipher.decrypt(stored)).isEqualTo(plain);
    }

    @Test
    @DisplayName("✅ 相同明文两次加密密文不同（随机 IV）")
    void randomIv() {
        String plain = "sk-secret-1234567890";
        String a = cipher.encrypt(plain);
        String b = cipher.encrypt(plain);
        assertThat(a).isNotEqualTo(b);
        // 但两者都能解密回同一明文
        assertThat(cipher.decrypt(a)).isEqualTo(plain);
        assertThat(cipher.decrypt(b)).isEqualTo(plain);
    }

    @Test
    @DisplayName("✅ 篡改密文 → 解密失败（GCM 认证）")
    void tamperDetected() {
        String stored = cipher.encrypt("top-secret-value");
        // 翻转一个 base64 字符，制造损坏
        String tampered = stored.substring(0, stored.length() - 1)
                + (stored.charAt(stored.length() - 1) == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("✅ 不同主密钥无法互解")
    void keyMismatch() {
        AesGcmCipher other = AesGcmCipher.fromMasterSecret("different-secret");
        String stored = cipher.encrypt("hello");
        assertThatThrownBy(() -> other.decrypt(stored))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("✅ 主密钥为空 → fail-closed 拒绝派生")
    void emptyMasterFails() {
        assertThatThrownBy(() -> AesGcmCipher.fromMasterSecret(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmCipher.fromMasterSecret(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ checksum 对明文稳定、对差异敏感")
    void checksumStable() {
        String a = AesGcmCipher.checksum("abc");
        String b = AesGcmCipher.checksum("abc");
        String c = AesGcmCipher.checksum("abd");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("✅ 生成的主密钥非空、可派生")
    void generatedMasterUsable() {
        String secret = AesGcmCipher.generateMasterSecret();
        assertThat(secret).isNotBlank();
        AesGcmCipher.fromMasterSecret(secret).encrypt("x");
    }
}
