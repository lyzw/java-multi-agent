package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.ContextStateEnvelope.SealInput;
import cn.studykid.multiagent.context.ContextStateEnvelope.VerifyInput;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Absent;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Accepted;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;

/**
 * S3-M8-a client carry-over HMAC 信封的<b>六项不可信校验</b>行为测试（ADR-013 D49）。
 *
 * <p>⚠️ 本类只验纯算法：真实 Controller 接线、守卫升级和撤点反证分别由 #69/#70 覆盖。
 * 每条 reject 都问过「撤掉该校验会红吗」：HMAC / exp / policy / tenant / 长度 /
 * 轮次游标 / 状态规范重建各有独立负例，且负例与正确信封只差一个变量。
 */
class ContextStateEnvelopeTest {

    private static final byte[] KEY =
            "m8a-independent-hmac-key-not-jwt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY =
            "another-independent-hmac-key".getBytes(StandardCharsets.UTF_8);
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String POLICY = "cp_test_v1";
    private static final long NOW = 1_700_000_000L;
    private static final int MAX_STATE_CHARS = 16_384;
    private static final String SEP = String.valueOf(ContextStateEnvelope.FIELD_SEPARATOR);

    @Test
    @DisplayName("✅ seal/verify 往返：base64url + HMAC 信封，不泄露明文 stateJson，轮次游标可空")
    void sealVerifyRoundTripKeepsOpaqueStateAndCanonicalDigest() {
        String stateJson = stateJson(POLICY);
        String digest = StructuredStateCodec.digest(state(POLICY));

        String envelope = ContextStateEnvelope.seal(
                new SealInput(stateJson, POLICY, TENANT, 7, digest, 60), KEY, NOW, MAX_STATE_CHARS);

        assertThat(envelope).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(envelope).doesNotContain(stateJson).doesNotContain(TENANT);

        Accepted accepted = (Accepted) verify(envelope);
        assertThat(accepted.stateJson()).isEqualTo(stateJson);
        assertThat(accepted.policyVersion()).isEqualTo(POLICY);
        assertThat(accepted.upToTurn()).isEqualTo(7);
        assertThat(accepted.digest()).isEqualTo(digest);

        String withoutCursor = ContextStateEnvelope.seal(
                new SealInput(stateJson, POLICY, TENANT, null, digest, 60), KEY, NOW, MAX_STATE_CHARS);
        assertThat(((Accepted) verify(withoutCursor)).upToTurn()).isNull();
    }

    @Test
    @DisplayName("✅ 无 state 与 max-state-chars：空白串是 Absent，超限在验签前静默丢弃")
    void absentAndMaxStateCharsAreCheckedBeforeAnythingElse() {
        assertThat(ContextStateEnvelope.verify("   ", verifyInput(), KEY, NOW, MAX_STATE_CHARS))
                .isInstanceOf(Absent.class);

        String envelope = validEnvelope(null);
        assertThat(ContextStateEnvelope.verify(
                envelope, verifyInput(), KEY, NOW, envelope.length()))
                .isInstanceOf(Accepted.class);
        assertDropped(ContextStateEnvelope.verify(
                envelope, verifyInput(), KEY, NOW, envelope.length() - 1),
                DropReason.TOO_LARGE);
    }

    @Test
    @DisplayName("✅ HMAC 与租户边界：签名位翻转/换 key 为 HMAC_MISMATCH，跨租户重放为 TENANT_MISMATCH")
    void hmacIsDomainSeparatedAndMismatchesOnAnyTamper() {
        String envelope = validEnvelope(3);

        assertDropped(verify(flipLastChar(envelope)), DropReason.HMAC_MISMATCH);
        assertDropped(ContextStateEnvelope.verify(
                envelope, verifyInput(), OTHER_KEY, NOW, MAX_STATE_CHARS),
                DropReason.HMAC_MISMATCH);
        // 同 key 重放：HMAC 仍真，但租户摘要绑定失败 ⇒ 外层静默丢弃，不是「换 key 攻击」。
        assertDropped(ContextStateEnvelope.verify(
                envelope, new VerifyInput(POLICY, "99999999-8888-7777-6666-555555555555", 10),
                KEY, NOW, MAX_STATE_CHARS),
                DropReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("✅ exp / policyVersion / tenantId digest / upToTurn 四项逐项独立丢弃")
    void expiredPolicyTenantAndTurnCursorAreDroppedSilently() {
        String shortLived = ContextStateEnvelope.seal(
                new SealInput(stateJson(POLICY), POLICY, TENANT, 3, digest(POLICY), 5),
                KEY, NOW, MAX_STATE_CHARS);
        assertDropped(ContextStateEnvelope.verify(
                shortLived, verifyInput(), KEY, NOW + 5, MAX_STATE_CHARS),
                DropReason.EXPIRED);
        assertThat(ContextStateEnvelope.verify(
                shortLived, verifyInput(), KEY, NOW + 4, MAX_STATE_CHARS))
                .isInstanceOf(Accepted.class);

        String envelope = validEnvelope(3);
        assertDropped(ContextStateEnvelope.verify(
                envelope, new VerifyInput("cp_other", TENANT, 10), KEY, NOW, MAX_STATE_CHARS),
                DropReason.POLICY_VERSION_MISMATCH);
        assertDropped(ContextStateEnvelope.verify(
                envelope, new VerifyInput(POLICY, "99999999-8888-7777-6666-555555555555", 10),
                OTHER_KEY, NOW, MAX_STATE_CHARS),
                DropReason.HMAC_MISMATCH);
        assertDropped(ContextStateEnvelope.verify(
                envelope, new VerifyInput(POLICY, "99999999-8888-7777-6666-555555555555", 10),
                KEY, NOW, MAX_STATE_CHARS),
                DropReason.TENANT_MISMATCH);

        String ahead = validEnvelope(11);
        assertDropped(verify(ahead), DropReason.TURN_CURSOR_AHEAD);
    }

    @Test
    @DisplayName("✅ 密钥不可用即 fail-closed：空 key 不进入验签，不静默放行")
    void keyUnavailableIsFailClosed() {
        String envelope = validEnvelope(3);
        assertDropped(ContextStateEnvelope.verify(
                envelope, verifyInput(), new byte[0], NOW, MAX_STATE_CHARS),
                DropReason.KEY_UNAVAILABLE);
        assertDropped(ContextStateEnvelope.verify(
                envelope, verifyInput(), null, NOW, MAX_STATE_CHARS),
                DropReason.KEY_UNAVAILABLE);
    }

    @Test
    @DisplayName("✅ MALFORMED：非法 base64 / 字段数 / 非规范状态 / 摘要错配全部拒，绝不宽松续写")
    void malformedEnvelopesNeverReachAccept() {
        assertDropped(verify("%%%." + "AAAA"), DropReason.MALFORMED);
        assertDropped(verify(validEnvelope(1) + "."), DropReason.MALFORMED);
        assertDropped(verify(ContextStateEnvelope.signPayload("a" + SEP + "b", KEY)),
                DropReason.MALFORMED);

        String tenantDigest = ContextStateEnvelope.tenantDigest(KEY, TENANT);
        String validPayload = String.join(SEP, Long.toString(NOW + 60), POLICY,
                tenantDigest, "3", digest(POLICY), stateJson(POLICY));
        assertThat(verify(ContextStateEnvelope.signPayload(validPayload, KEY)))
                .isInstanceOf(Accepted.class);

        assertDropped(verify(signedPayload(Long.toString(NOW + 60), POLICY, tenantDigest, "3",
                digest(POLICY), "{}")), DropReason.MALFORMED);
        assertDropped(verify(signedPayload(Long.toString(NOW + 60), POLICY, tenantDigest, "3",
                digest("cp_other"), stateJson(POLICY))), DropReason.MALFORMED);
        // 外层策略与被验证期望一致，才能验到「stateJson 内层 policyVersion 与信封不一致」门禁。
        assertDropped(ContextStateEnvelope.verify(
                signedPayload(Long.toString(NOW + 60), "cp_other", tenantDigest, "3",
                        digest(POLICY), stateJson(POLICY)),
                new VerifyInput("cp_other", TENANT, 10), KEY, NOW, MAX_STATE_CHARS),
                DropReason.MALFORMED);
        assertDropped(verify(signedPayload("0700" + (NOW + 60), POLICY, tenantDigest, "3",
                digest(POLICY), stateJson(POLICY))), DropReason.MALFORMED);
        assertDropped(verify(signedPayload(Long.toString(NOW + 60), POLICY, tenantDigest,
                "01", digest(POLICY), stateJson(POLICY))), DropReason.MALFORMED);
    }

    @Test
    @DisplayName("✅ seal 不发射坏 state：非规范 JSON / 摘要错配 / 策略不一致 / 超限一律抛")
    void sealRejectsUnusableInputInsteadOfEmittingBadState() {
        String json = stateJson(POLICY);
        String digest = digest(POLICY);

        assertThatThrownBy(() -> seal(new SealInput(json + " ", POLICY, TENANT, 1, digest, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规范状态");
        assertThatThrownBy(() -> seal(new SealInput(json, POLICY, TENANT, 1,
                digest("cp_other"), 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("摘要");
        assertThatThrownBy(() -> seal(new SealInput(json, "cp_other", TENANT, 1, digest, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyVersion");
        assertThatThrownBy(() -> seal(new SealInput(json, POLICY, TENANT, -1, digest, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upToTurn");
        assertThatThrownBy(() -> seal(new SealInput(json, POLICY, TENANT, 1, digest, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlSeconds");
        assertThatThrownBy(() -> ContextStateEnvelope.seal(
                new SealInput(json, POLICY, TENANT, 1, digest, 60),
                new byte[0], NOW, MAX_STATE_CHARS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HMAC key");
        assertThatThrownBy(() -> ContextStateEnvelope.seal(
                new SealInput(json, POLICY, TENANT, 1, digest, 60), KEY, NOW, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-state-chars");
    }

    private static Result verify(String envelope) {
        return ContextStateEnvelope.verify(envelope, verifyInput(), KEY, NOW, MAX_STATE_CHARS);
    }

    private static void assertDropped(Result result, DropReason expected) {
        assertThat(result).as("⛔ 期望静默丢弃 %s", expected).isInstanceOf(Discarded.class);
        assertThat(((Discarded) result).reason()).isEqualTo(expected);
    }

    private static VerifyInput verifyInput() {
        return new VerifyInput(POLICY, TENANT, 10);
    }

    private static String validEnvelope(Integer upToTurn) {
        return seal(new SealInput(stateJson(POLICY), POLICY, TENANT, upToTurn,
                digest(POLICY), 60));
    }

    private static String seal(SealInput input) {
        return ContextStateEnvelope.seal(input, KEY, NOW, MAX_STATE_CHARS);
    }

    private static String signedPayload(String expiresAt, String policy, String tenantDigest,
                                        String upToTurn, String digest, String stateJson) {
        return ContextStateEnvelope.signPayload(String.join(SEP,
                expiresAt, policy, tenantDigest,
                upToTurn, digest, stateJson), KEY);
    }

    private static String stateJson(String policy) {
        return StructuredStateCodec.toJson(state(policy));
    }

    private static String digest(String policy) {
        return StructuredStateCodec.digest(state(policy));
    }

    private static StructuredState state(String policy) {
        return new StructuredState("目标", List.of("约束"), List.of("纠正"), List.of("决定"),
                List.of("否决"), List.of("待办"), List.of(), List.of(new Fact("k", "v")),
                List.of(), 1, 9, policy);
    }

    private static String flipLastChar(String value) {
        int last = value.length() - 1;
        char replacement = value.charAt(last) == 'A' ? 'B' : 'A';
        return value.substring(0, last) + replacement;
    }
}
