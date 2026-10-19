package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PasswordHasher;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PrincipalRecord;

/**
 * D-001 IAM 认证链测试。
 *
 * <p><b>本类刻意包含大量「主动绕过尝试」</b>。安全防护的正向测试
 * 只能证明「正常路径可用」——真正要证伪的是「异常路径能否穿透」。
 */
class JwtAndAuthenticationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String ISSUER = "https://auth.studykid.cn";
    private static final String AUDIENCE = "agent-platform";
    private static final Set<String> KNOWN_ROLES = Set.of(
            "STUDENT", "PARENT", "TEACHER", "TENANT_ADMIN", "VIEWER");

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static JwtTokenService service;

    @BeforeAll
    static void setUp() {
        keyPair = JwtTokenService.generateKeyPair();
        otherKeyPair = JwtTokenService.generateKeyPair();
        service = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                ISSUER, AUDIENCE, Duration.ofMinutes(15), "k1");
    }

    private static TenantContext context() {
        return new TenantContext(TENANT, "p-1", Set.of("STUDENT"), 3L);
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("签发与校验的基本往返")
    class RoundTrip {

        @Test
        @DisplayName("✅ 签发的令牌能被自身校验，claims 正确还原")
        void roundTrip() {
            String token = service.issue(context(), NOW);

            TenantContext parsed = service.verify(token, NOW, KNOWN_ROLES);

            assertThat(parsed.tenantId()).isEqualTo(TENANT);
            assertThat(parsed.principalId()).isEqualTo("p-1");
            assertThat(parsed.roles()).containsExactly("STUDENT");
            assertThat(parsed.authzVersion())
                    .as("authz_version 必须透传，否则执行时重新授权无法识别陈旧上下文")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("✅ 令牌是三段式，载荷不含明文口令")
        void tokenShape() {
            String token = service.issue(context(), NOW);
            assertThat(token.split("\\.")).hasSize(3);
            assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1])))
                    .doesNotContain("password")
                    .doesNotContain("secret");
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("🔴 证伪尝试：令牌伪造")
    class Falsification {

        @Test
        @DisplayName("⛔ alg=none（去掉签名）被拒")
        void algNoneRejected() {
            // ⚠️ 必须带一个<b>非空</b>签名段，使令牌成为合法的三段式。
            //    若留空，Java 的 split("\.") 会丢弃末尾空串 → 只有 2 段，
            //    于是被「格式非法」拦下而非 alg 校验 —— 断言虽过，
            //    但测的是另一条路径，<b>alg 校验失效也不会被发现</b>。
            String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
            String payload = base64Url(
                    "{\"iss\":\"" + ISSUER + "\",\"sub\":\"attacker\","
                            + "\"aud\":\"" + AUDIENCE + "\",\"exp\":9999999999,"
                            + "\"tenant_id\":\"" + TENANT + "\",\"roles\":[\"TENANT_ADMIN\"],"
                            + "\"authz_version\":1}");
            String forged = header + "." + payload + "." + base64Url(new byte[32]);

            assertThatThrownBy(() -> service.verify(forged, NOW, KNOWN_ROLES))
                    .as("alg=none 是 JWT 最经典的漏洞：不校验 alg 即等于不校验签名")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class)
                    .hasMessageContaining("算法");
        }

        @Test
        @DisplayName("⛔ 算法混淆（HS256 用公钥当密钥）被拒")
        void algorithmConfusionRejected() {
            // 攻击者拿到公钥后，用 HS256 以公钥字节为密钥重新签名
            String payload = base64Url(
                    "{\"iss\":\"" + ISSUER + "\",\"sub\":\"attacker\","
                            + "\"aud\":\"" + AUDIENCE + "\",\"exp\":9999999999,"
                            + "\"tenant_id\":\"" + TENANT + "\",\"roles\":[\"TENANT_ADMIN\"],"
                            + "\"authz_version\":1}");
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String forged = header + "." + payload + "."
                    + base64Url(new byte[32]);

            assertThatThrownBy(() -> service.verify(forged, NOW, KNOWN_ROLES))
                    .as("若按 token 头的 alg 动态选择算法，攻击者可降级为对称算法")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class)
                    .hasMessageContaining("算法");
        }

        @Test
        @DisplayName("⛔ 签名无效（内容被篡改）被拒")
        void tamperedPayloadRejected() {
            String token = service.issue(context(), NOW);
            String[] parts = token.split("\\.");

            // 篡改租户
            String tampered = parts[0] + "." + base64Url(
                    "{\"iss\":\"" + ISSUER + "\",\"sub\":\"p-1\","
                            + "\"aud\":\"" + AUDIENCE + "\",\"exp\":9999999999,"
                            + "\"tenant_id\":\"99999999-9999-9999-9999-999999999999\","
                            + "\"roles\":[],\"authz_version\":1}") + "." + parts[2];

            assertThatThrownBy(() -> service.verify(tampered, NOW, KNOWN_ROLES))
                    .as("换租户必须破坏签名")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 另一密钥签发的令牌被拒")
        void foreignSignatureRejected() {
            JwtTokenService attacker = new JwtTokenService(
                    otherKeyPair.getPrivate(), keyPair.getPublic(),
                    ISSUER, AUDIENCE, Duration.ofMinutes(15), "k1");
            String forged = attacker.issue(context(), NOW);

            assertThatThrownBy(() -> service.verify(forged, NOW, KNOWN_ROLES))
                    .as("用自己密钥签名、拿服务端公钥当验签公钥 —— 必须失败")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 伪造的 TENANT_ADMIN 角色被白名单拦截")
        void forgedRoleFiltered() {
            // 前提：假设攻击者拿到了签发能力（密钥泄露等），
            // 若服务端信任 JWT 里的 roles，他就能自封管理员。
            String token = service.issue(
                    new TenantContext(TENANT, "p-1", Set.of("SUPER_ROOT"), 1L), NOW);

            TenantContext parsed = service.verify(token, NOW, KNOWN_ROLES);

            assertThat(parsed.roles())
                    .as("未在库中登记的角色必须被忽略，不能因令牌声明而生效")
                    .isEmpty();
        }

        @Test
        @DisplayName("⛔ 已登记角色正常生效（白名单不是一刀切）")
        void knownRoleStillWorks() {
            String token = service.issue(
                    new TenantContext(TENANT, "p-1", Set.of("TENANT_ADMIN"), 1L), NOW);

            assertThat(service.verify(token, NOW, KNOWN_ROLES).roles())
                    .as("白名单只拦未登记角色，不应误伤合法角色")
                    .containsExactly("TENANT_ADMIN");
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("⛔ 证伪尝试：声明校验")
    class Claims {

        @Test
        @DisplayName("⛔ 过期令牌被拒")
        void expiredRejected() {
            String token = service.issue(context(), NOW);
            Instant muchLater = NOW.plus(Duration.ofHours(2));

            assertThatThrownBy(() -> service.verify(token, muchLater, KNOWN_ROLES))
                    .as("过期是令牌有效性的核心边界")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 错误的签发方被拒")
        void wrongIssuerRejected() {
            JwtTokenService other = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                    "https://evil.example.com", AUDIENCE, Duration.ofMinutes(15), "k1");
            String token = other.issue(context(), NOW);

            assertThatThrownBy(() -> service.verify(token, NOW, KNOWN_ROLES))
                    .as("不校验 iss 则其他系统的令牌可被重放")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 错误的接收方被拒")
        void wrongAudienceRejected() {
            JwtTokenService other = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                    ISSUER, "some-other-service", Duration.ofMinutes(15), "k1");
            String token = other.issue(context(), NOW);

            assertThatThrownBy(() -> service.verify(token, NOW, KNOWN_ROLES))
                    .as("不校验 aud 则为其他服务签发的令牌可被冒用")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 缺少 tenant_id 的令牌被拒")
        void missingTenantRejected() {
            String payload = base64Url(
                    "{\"iss\":\"" + ISSUER + "\",\"sub\":\"p-1\","
                            + "\"aud\":\"" + AUDIENCE + "\",\"exp\":9999999999,"
                            + "\"roles\":[\"STUDENT\"],\"authz_version\":1}");
            String token = service.issue(context(), NOW);
            String[] parts = token.split("\\.");
            String forged = parts[0] + "." + payload + "." + parts[2];

            assertThatThrownBy(() -> service.verify(forged, NOW, KNOWN_ROLES))
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 非三段式的畸形令牌被拒")
        void malformedRejected() {
            assertThatThrownBy(() -> service.verify("not-a-token", NOW, KNOWN_ROLES))
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
            assertThatThrownBy(() -> service.verify("", NOW, KNOWN_ROLES))
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }

        @Test
        @DisplayName("⛔ 真实形态的 alg=none 空签名令牌被拒（两段）")
        void algNoneEmptySignatureRejected() {
            // 攻击者实际会构造的这种形态：签名段为空。
            // split 丢弃末尾空串 → 只有 2 段，由「格式非法」拦下。
            String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
            String payload = base64Url(
                    "{\"iss\":\"" + ISSUER + "\",\"sub\":\"attacker\","
                            + "\"aud\":\"" + AUDIENCE + "\",\"exp\":9999999999,"
                            + "\"tenant_id\":\"" + TENANT + "\",\"roles\":[\"TENANT_ADMIN\"]}");
            String forged = header + "." + payload + ".";

            assertThatThrownBy(() -> service.verify(forged, NOW, KNOWN_ROLES))
                    .as("真实形态的空签名令牌同样必须被拒")
                    .isInstanceOf(JwtTokenService.JwtValidationException.class);
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("配置 fail-closed")
    class FailClosed {

        @Test
        @DisplayName("⛔ 签发器不接受空 issuer / audience / 零有效期")
        void rejectsBadConfig() {
            KeyPair kp = keyPair;
            assertThatThrownBy(() -> new JwtTokenService(
                    kp.getPrivate(), kp.getPublic(), "", AUDIENCE, Duration.ofMinutes(15), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JwtTokenService(
                    kp.getPrivate(), kp.getPublic(), ISSUER, "  ", Duration.ofMinutes(15), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JwtTokenService(
                    kp.getPrivate(), kp.getPublic(), ISSUER, AUDIENCE, Duration.ZERO, null))
                    .as("零有效期会让所有令牌立即失效，属配置错误")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("口令哈希")
    class Password {

        @Test
        @DisplayName("✅ 哈希可验证，且不以明文存储")
        void hashRoundTrip() {
            String hash = PasswordHasher.hash("correct-horse");

            assertThat(hash).startsWith("$pbkdf2-sha256$").doesNotContain("correct-horse");
            assertThat(PasswordHasher.verify("correct-horse", hash)).isTrue();
            assertThat(PasswordHasher.verify("wrong", hash)).isFalse();
        }

        @Test
        @DisplayName("✅ 相同口令两次哈希不同（盐随机）")
        void salted() {
            assertThat(PasswordHasher.hash("same-password"))
                    .as("相同口令必须产生不同哈希，否则彩虹表可命中")
                    .isNotEqualTo(PasswordHasher.hash("same-password"));
        }

        @Test
        @DisplayName("⛔ 明文/空串/弱哈希格式均被拒")
        void weakFormatsRejected() {
            assertThat(PasswordHasher.verify("pw", "plain-text")).isFalse();
            assertThat(PasswordHasher.verify("pw", "$argon2id$xxx")).isFalse();
            assertThat(PasswordHasher.verify("pw", "$pbkdf2-sha256$i=1$c2FsdA$aGFzaA")).isFalse();
            assertThat(PasswordHasher.verify("pw", "$pbkdf2-sha256$garbage")).isFalse();
        }

        @Test
        @DisplayName("✅ needsRehash 识别旧参数")
        void rehashDetection() {
            String current = PasswordHasher.hash("pw");
            assertThat(PasswordHasher.needsRehash(current)).isFalse();
            assertThat(PasswordHasher.needsRehash("$pbkdf2-sha256$i=1000$c2FsdA$aGFzaA"))
                    .as("迭代次数低于当前参数时应重算")
                    .isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("⛔ 账号枚举防护")
    class Enumeration {

        @Test
        @DisplayName("✅ 账号不存在与密码错误的耗时差异不显著")
        void constantTimeForMissingAccount() {
            String hash = PasswordHasher.hash("some-password");
            PrincipalRecord existing = new PrincipalRecord(
                    "p-1", TENANT, "alice", hash, null, "ACTIVE", 0, null, 1L);
            PrincipalRepository repo = new PrincipalRepository(null);

            // 分别预热，避免 JIT 影响首次测量
            PasswordHasher.verify("warmup-a", hash);
            PasswordHasher.verify("warmup-b", hash);
            repo.verifyPassword(null, "warmup-c", NOW);
            repo.verifyPassword(existing, "warmup-d", NOW);

            long missingNanos = measure(() -> repo.verifyPassword(null, "guess", NOW));
            long wrongNanos = measure(() -> repo.verifyPassword(existing, "guess", NOW));

            double ratio = (double) missingNanos / wrongNanos;

            assertThat(ratio)
                    .as("耗时比接近 1 才能防枚举；偏离过大说明「不存在」路径更短，"
                            + "攻击者据此判断哪些账号真实存在")
                    .isBetween(0.6, 1.7);
        }

        @Test
        @DisplayName("⛔ 账号不存在也返回 false（不泄露存在性）")
        void missingAccountReturnsFalse() {
            PrincipalRepository repo = new PrincipalRepository(null);
            assertThat(repo.verifyPassword(null, "any", NOW)).isFalse();
        }

        private long measure(Runnable action) {
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                action.run();
            }
            return (System.nanoTime() - start) / 5;
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("⛔ 锁定判定")
    class Lockout {

        @Test
        @DisplayName("⛔ 锁定窗口内即使密码正确也拒绝")
        void lockedRejectsEvenWithCorrectPassword() {
            String hash = PasswordHasher.hash("correct");
            PrincipalRecord locked = new PrincipalRecord("p-1", TENANT, "bob", hash, null,
                    "LOCKED", 0, NOW.plusSeconds(60), 1L);

            PrincipalRepository repo = new PrincipalRepository(null);
            assertThat(repo.verifyPassword(locked, "correct", NOW))
                    .as("锁定期间放行会使锁定形同虚设")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 锁定到期后恢复")
        void unlockedAfterExpiry() {
            String hash = PasswordHasher.hash("correct");
            PrincipalRecord expired = new PrincipalRecord("p-1", TENANT, "bob", hash, null,
                    "ACTIVE", 0, NOW.minusSeconds(60), 1L);

            PrincipalRepository repo = new PrincipalRepository(null);
            assertThat(PrincipalRepository.isLocked(expired, NOW)).isFalse();
            assertThat(repo.verifyPassword(expired, "correct", NOW)).isTrue();
        }

        @Test
        @DisplayName("✅ 正确密码且未锁定则通过")
        void normalPath() {
            String hash = PasswordHasher.hash("correct");
            PrincipalRecord record = new PrincipalRecord("p-1", TENANT, "carol", hash, null,
                    "ACTIVE", 0, null, 1L);

            assertThat(new PrincipalRepository(null).verifyPassword(record, "correct", NOW))
                    .isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("公钥输出")
    class Jwks {

        @Test
        @DisplayName("✅ 公钥可导出且可重新载入")
        void publicKeyExport() {
            String b64 = service.publicKeyBase64();
            assertThat(b64).isNotBlank();

            java.security.PublicKey reloaded = JwtTokenService.loadPublicKey(b64);
            assertThat(reloaded).isEqualTo(keyPair.getPublic());
        }

        @Test
        @DisplayName("⛔ 非法密钥串被拒而非静默接受")
        void badKeyRejected() {
            assertThatThrownBy(() -> JwtTokenService.loadPublicKey("!!!not-base64!!!"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> JwtTokenService.loadPrivateKey("!!!"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}