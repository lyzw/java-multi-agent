package cn.studykid.rag.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.studykid.rag.bootstrap.security.RagJwtAuthFilter.TokenDomain;

/**
 * 评审 P0-2 过滤器行为测试：fail-closed、验签、iss/aud/exp、租户归属绑定。
 */
class RagJwtAuthFilterTest {

    static KeyPair tenantKeys;
    static KeyPair otherKeys;
    static TokenDomain tenantDomain;
    static TokenDomain platformDomain;
    static final long EXP_FUTURE = Instant.now().plusSeconds(600).getEpochSecond();
    static final long EXP_PAST = Instant.now().minusSeconds(600).getEpochSecond();

    static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    @BeforeAll
    static void keys() throws Exception {
        tenantKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        otherKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        tenantDomain = new TokenDomain("tenant",
                (RSAPublicKey) tenantKeys.getPublic(),
                "https://auth.studykid.cn", "agent-platform");
        platformDomain = new TokenDomain("platform",
                (RSAPublicKey) tenantKeys.getPublic(),
                "https://auth.studykid.cn", "admin-console-api");
    }

    // ── 令牌构造 ──

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @param signKey 签名私钥；传 otherKeys 私钥即可构造「坏签名」令牌 */
    private static String token(String iss, String aud, long exp,
                                String tenantId, java.security.PrivateKey signKey) {
        String header = b64("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String tenant = tenantId == null ? "null" : "\"" + tenantId + "\"";
        String payload = b64(("{\"iss\":\"" + iss + "\",\"aud\":\"" + aud
                + "\",\"exp\":" + exp + ",\"sub\":\"u-1\",\"tenant_id\":" + tenant + "}")
                .getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(signKey);
            sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RagJwtAuthFilter filter(TokenDomain... domains) {
        return new RagJwtAuthFilter(true, List.of(domains));
    }

    private static MockHttpServletRequest request(String token, String tenantHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/retrieval");
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        if (tenantHeader != null) {
            req.addHeader("X-Tenant-Id", tenantHeader);
        }
        return req;
    }

    /** @return 放行则 true；被拒则 false（并记录 status） */
    private static boolean through(RagJwtAuthFilter f, MockHttpServletRequest req,
                                   MockHttpServletResponse res) throws Exception {
        boolean[] chained = {false};
        f.doFilter(req, res, (rq, rs) -> chained[0] = true);
        return chained[0];
    }

    // ── fail-closed ──

    @Test
    @DisplayName("⛔ 启用鉴权但未配置公钥 → 构造期失败（不许静默裸奔）")
    void enabledWithoutKeysFailsFast() {
        assertThatThrownBy(() -> new RagJwtAuthFilter(true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    @DisplayName("显式关闭（enabled=false）允许构造——本地开发逃生门")
    void explicitDisabledAllowed() {
        new RagJwtAuthFilter(false, List.of());
    }

    @Test
    @DisplayName("缺 Authorization 头 → 401")
    void missingTokenRejected() throws Exception {
        var res = new MockHttpServletResponse();
        assertThat(through(filter(tenantDomain), request(null, TENANT_A), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("健康探针跳过鉴权")
    void healthSkipped() throws Exception {
        var f = filter(tenantDomain);
        var req = new MockHttpServletRequest("GET", "/actuator/health");
        var res = new MockHttpServletResponse();
        assertThat(through(f, req, res)).isTrue();
    }

    // ── 验签与 claims ──

    @Test
    @DisplayName("签名不符（他钥签发）→ 401")
    void wrongKeyRejected() throws Exception {
        var f = filter(tenantDomain);
        var bad = token("https://auth.studykid.cn", "agent-platform", EXP_FUTURE,
                TENANT_A, otherKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(bad, TENANT_A), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("aud 不符（为其他服务签发）→ 401")
    void wrongAudienceRejected() throws Exception {
        var f = filter(tenantDomain);
        var bad = token("https://auth.studykid.cn", "some-other-service", EXP_FUTURE,
                TENANT_A, tenantKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(bad, TENANT_A), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("过期令牌 → 401")
    void expiredRejected() throws Exception {
        var f = filter(tenantDomain);
        var bad = token("https://auth.studykid.cn", "agent-platform", EXP_PAST,
                TENANT_A, tenantKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(bad, TENANT_A), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ── 租户归属绑定（P0-2 核心）──

    @Test
    @DisplayName("✅ 租户令牌 + 匹配的 X-Tenant-Id → 放行")
    void matchingTenantPasses() throws Exception {
        var f = filter(tenantDomain);
        var ok = token("https://auth.studykid.cn", "agent-platform", EXP_FUTURE,
                TENANT_A, tenantKeys.getPrivate());
        assertThat(through(f, request(ok, TENANT_A), new MockHttpServletResponse())).isTrue();
    }

    @Test
    @DisplayName("⛔ 租户令牌 + 他人租户头 → 403（改头访问任意租户被关闭）")
    void tenantMismatchForbidden() throws Exception {
        var f = filter(tenantDomain);
        var ok = token("https://auth.studykid.cn", "agent-platform", EXP_FUTURE,
                TENANT_A, tenantKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(ok, TENANT_B), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("⛔ 租户令牌 + 缺租户头 → 403（不因缺头而放行）")
    void missingTenantHeaderForbidden() throws Exception {
        var f = filter(tenantDomain);
        var ok = token("https://auth.studykid.cn", "agent-platform", EXP_FUTURE,
                TENANT_A, tenantKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(ok, null), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("✅ 平台令牌（admin-console-api）跨租户治理放行，不做租户绑定")
    void platformTokenExemptFromBinding() throws Exception {
        var f = filter(tenantDomain, platformDomain);
        var ok = token("https://auth.studykid.cn", "admin-console-api", EXP_FUTURE,
                null, tenantKeys.getPrivate());
        assertThat(through(f, request(ok, TENANT_B), new MockHttpServletResponse())).isTrue();
    }

    @Test
    @DisplayName("租户令牌冒充平台域 aud → 401（aud 必须与验签域匹配）")
    void tenantKeyWithPlatformAudienceRejected() throws Exception {
        var f = filter(tenantDomain, platformDomain);
        // 用租户钥签 aud=admin-console-api：平台域验签失败（钥匙不对），
        // 租户域验签成功但 aud 不符 → 整体拒绝
        var forged = token("https://auth.studykid.cn", "admin-console-api", EXP_FUTURE,
                null, otherKeys.getPrivate());
        var res = new MockHttpServletResponse();
        assertThat(through(f, request(forged, TENANT_A), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }
}
