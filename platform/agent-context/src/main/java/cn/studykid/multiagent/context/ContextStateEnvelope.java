package cn.studykid.multiagent.context;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;

/**
 * L2 SSE client carry-over 的<b>确定性 HMAC 信封</b>（ADR-013 D49 / S3-M8-a）。
 *
 * <p>⚠️ <b>纯算法内核</b>：零 Spring / 零 JDBC / 零 JSON 库 / 零 IO，时间、密钥、策略指纹、
 * 租户 id、历史轮数全部由调用方注入；本类不读环境变量、不读系统时钟、不做网络访问。
 *
 * <p>⚠️ <b>信封格式（冻结）</b>：
 * <pre>
 *   domain   = "l2cs1"
 *   payload  = exp \u001F policyVersion \u001F tenantDigest \u001F upToTurn \u001F stateDigest \u001F stateJson
 *   envelope = base64url(UTF-8(payload)) + "." + base64url(HMAC-SHA256(key, domain + "." + payloadPart))
 * </pre>
 * {@code upToTurn} 为空串表示「未用轮次坐标」；其余字段一律非空。\u001F 只做分界，
 * 字段内出现裸控制字符即判为 {@code MALFORMED}。base64url 强制无 padding 且规范可逆，
 * 避免同一字节串多种文本表示绕过摘要/签名口径。
 *
 * <p>⚠️ <b>不可信校验（D49 六项）</b>：{@code max-state-chars} 先验长度、HMAC 常量时间比对、
 * {@code exp}、{@code policyVersion}、tenantId digest、{@code upToTurn ≤ historyTurns}。
 * 验签通过后仍用 {@link StructuredStateCodec#fromJson(String)} 严格重建状态，并核对规范 JSON
 * 与摘要，防重复键 / 非规范整数 / 状态与摘要错配。
 *
 * <p>⚠️ <b>不加密、不含原始对话正文</b>：{@code stateJson} 是 {@code StructuredState}
 * 的 12 字段确定性 JSON；{@code tenantDigest} 是域分离 HMAC，不放租户明文。
 * ⛔ 绝不复用 JWT 私钥，⛔ 绝不把信封内容直接拼进 {@code systemPrompt}。
 */
public final class ContextStateEnvelope {

    private static final String DOMAIN = "l2cs1";
    private static final String TENANT_DOMAIN = DOMAIN + ".tenant.v1";
    private static final String TENANT_DIGEST_PREFIX = "v1:";
    private static final int FIELD_COUNT = 6;
    private static final int HMAC_BYTES = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 固定字段分界；包可见供同包单测构造负例，生产语义以本类为准。 */
    static final char FIELD_SEPARATOR = '\u001F';

    private static final String SEPARATOR = String.valueOf(FIELD_SEPARATOR);
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern STATE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private ContextStateEnvelope() {
    }

    /** 生产端最小载荷：来自 {@code CheckpointDelta} 的压缩态快照，不含原始正文。 */
    public record SealInput(
            @NonNull String stateJson,
            @NonNull String policyVersion,
            @NonNull String tenantId,
            @Nullable Integer upToTurn,
            @NonNull String digest,
            int ttlSeconds) {

        public SealInput {
            if (stateJson == null || stateJson.isBlank()) {
                throw new IllegalArgumentException("SealInput.stateJson 不得为空");
            }
            if (policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("SealInput.policyVersion 不得为空");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("SealInput.tenantId 不得为空");
            }
            if (upToTurn != null && upToTurn < 0) {
                throw new IllegalArgumentException("SealInput.upToTurn 不得为负");
            }
            if (digest == null || digest.isBlank()) {
                throw new IllegalArgumentException("SealInput.digest 不得为空");
            }
            if (ttlSeconds <= 0) {
                throw new IllegalArgumentException("SealInput.ttlSeconds 必须为正");
            }
        }
    }

    /** 校验端上下文：策略指纹 / 租户 / 历史轮数均由调用方从已裁决事实注入。 */
    public record VerifyInput(
            @NonNull String expectedPolicyVersion,
            @NonNull String tenantId,
            int historyTurns) {

        public VerifyInput {
            if (expectedPolicyVersion == null || expectedPolicyVersion.isBlank()) {
                throw new IllegalArgumentException("VerifyInput.expectedPolicyVersion 不得为空");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("VerifyInput.tenantId 不得为空");
            }
            if (historyTurns < 0) {
                throw new IllegalArgumentException("VerifyInput.historyTurns 不得为负");
            }
        }
    }

    /**
     * 封包。任何输入不合法都抛异常（生产端必须 catch 并选择「不发射 checkpoint」降级），
     * ⛔ 绝不静默产出客户端必然丢弃的坏 state。
     */
    public static @NonNull String seal(@NonNull SealInput input,
                                       @NonNull byte[] key,
                                       long nowEpochSeconds,
                                       int maxStateChars) {
        if (input == null) {
            throw new IllegalArgumentException("seal.input 不得为空");
        }
        byte[] safeKey = requireKey(key);
        if (maxStateChars <= 0) {
            throw new IllegalArgumentException("maxStateChars 必须为正");
        }
        if (nowEpochSeconds < 0) {
            throw new IllegalArgumentException("nowEpochSeconds 不得为负");
        }
        if (!STATE_DIGEST.matcher(input.digest()).matches()) {
            throw new IllegalArgumentException("SealInput.digest 必须是 sha256:<64 hex>");
        }

        StructuredState state = canonicalState(input.stateJson());
        if (!state.policyVersion().equals(input.policyVersion())) {
            throw new IllegalArgumentException("stateJson.policyVersion 与信封 policyVersion 不一致");
        }
        if (!StructuredStateCodec.digest(state).equals(input.digest())) {
            throw new IllegalArgumentException("stateJson 摘要与 SealInput.digest 不一致");
        }

        long expiresAt = Math.addExact(nowEpochSeconds, input.ttlSeconds());
        String payload = String.join(SEPARATOR,
                Long.toString(expiresAt),
                input.policyVersion(),
                tenantDigest(safeKey, input.tenantId()),
                input.upToTurn() == null ? "" : Integer.toString(input.upToTurn()),
                input.digest(),
                input.stateJson());
        String envelope = signPayload(payload, safeKey);
        if (envelope.length() > maxStateChars) {
            throw new IllegalArgumentException("checkpoint 信封超过 max-state-chars="
                    + maxStateChars + "，必须丢弃本轮 checkpoint 而非发射必然被丢弃的 state");
        }
        return envelope;
    }

    /**
     * 验包。携带了 state 但任一校验失败一律返回 {@code Discarded}，
     * 调用方静默降级全量历史；⛔ 不抛给 HTTP 层、不发 error 帧。
     */
    public static ContextStateVerifier.Result verify(@NonNull String envelope,
                                                              @NonNull VerifyInput input,
                                                              @NonNull byte[] key,
                                                              long nowEpochSeconds,
                                                              int maxStateChars) {
        if (envelope == null || envelope.isBlank()) {
            return new ContextStateVerifier.Absent();
        }
        if (maxStateChars <= 0) {
            return discard(ContextStateVerifier.DropReason.TOO_LARGE);
        }
        if (envelope.length() > maxStateChars) {
            return discard(ContextStateVerifier.DropReason.TOO_LARGE);
        }
        if (key == null || key.length == 0) {
            return discard(ContextStateVerifier.DropReason.KEY_UNAVAILABLE);
        }
        if (nowEpochSeconds < 0) {
            return discard(ContextStateVerifier.DropReason.EXPIRED);
        }
        byte[] safeKey = key.clone();

        try {
            int dot = envelope.indexOf('.');
            if (dot <= 0 || dot != envelope.lastIndexOf('.')) {
                throw new IllegalArgumentException("信封必须恰有一个 '.' 分界");
            }
            String payloadPart = envelope.substring(0, dot);
            String signaturePart = envelope.substring(dot + 1);
            byte[] payloadBytes = decodeBase64Url(payloadPart);
            byte[] signature = decodeBase64Url(signaturePart);
            if (signature.length != HMAC_BYTES) {
                throw new IllegalArgumentException("HMAC 长度不符");
            }
            byte[] expected = hmac(safeKey, (DOMAIN + "." + payloadPart)
                    .getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(signature, expected)) {
                return discard(ContextStateVerifier.DropReason.HMAC_MISMATCH);
            }

            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(payload.getBytes(StandardCharsets.UTF_8), payloadBytes)) {
                throw new IllegalArgumentException("payload 不是合法 UTF-8");
            }
            String[] fields = payload.split(Pattern.quote(SEPARATOR), -1);
            if (fields.length != FIELD_COUNT) {
                throw new IllegalArgumentException("payload 字段数不符");
            }
            for (String field : fields) {
                rejectControlChars(field);
            }

            long expiresAt = parseLongStrict(fields[0]);
            String policyVersion = fields[1];
            String tenantDigest = fields[2];
            String upToTurnText = fields[3];
            String digest = fields[4];
            String stateJson = fields[5];
            if (expiresAt <= 0 || policyVersion.isBlank() || tenantDigest.isBlank()
                    || digest.isBlank() || stateJson.isBlank()
                    || !STATE_DIGEST.matcher(digest).matches()) {
                throw new IllegalArgumentException("payload 必填字段缺失或格式非法");
            }

            if (nowEpochSeconds >= expiresAt) {
                return discard(ContextStateVerifier.DropReason.EXPIRED);
            }
            if (!input.expectedPolicyVersion().equals(policyVersion)) {
                return discard(ContextStateVerifier.DropReason.POLICY_VERSION_MISMATCH);
            }
            String expectedTenantDigest = tenantDigest(safeKey, input.tenantId());
            if (!MessageDigest.isEqual(expectedTenantDigest.getBytes(StandardCharsets.UTF_8),
                    tenantDigest.getBytes(StandardCharsets.UTF_8))) {
                return discard(ContextStateVerifier.DropReason.TENANT_MISMATCH);
            }

            Integer upToTurn = null;
            if (!upToTurnText.isEmpty()) {
                upToTurn = parseIntStrict(upToTurnText);
                if (upToTurn > input.historyTurns()) {
                    return discard(ContextStateVerifier.DropReason.TURN_CURSOR_AHEAD);
                }
            }

            StructuredState state = canonicalState(stateJson);
            if (!policyVersion.equals(state.policyVersion())
                    || !StructuredStateCodec.digest(state).equals(digest)) {
                throw new IllegalArgumentException("stateJson 与信封 policyVersion/digest 不一致");
            }
            return new ContextStateVerifier.Accepted(
                    stateJson, policyVersion, upToTurn, digest);
        } catch (IllegalArgumentException malformed) {
            return discard(ContextStateVerifier.DropReason.MALFORMED);
        } catch (IllegalStateException cryptoUnavailable) {
            return discard(ContextStateVerifier.DropReason.KEY_UNAVAILABLE);
        }
    }

    /** 包可见测试接缝：用同一算法对任意 payload 签名，构造脏状态负例（不暴露给生产模块）。 */
    static @NonNull String signPayload(@NonNull String payload, @NonNull byte[] key) {
        byte[] safeKey = requireKey(key);
        rejectControlCharsOutsideSeparator(payload);
        String payloadPart = encodeBase64Url(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signature = hmac(safeKey, (DOMAIN + "." + payloadPart)
                .getBytes(StandardCharsets.UTF_8));
        return payloadPart + "." + encodeBase64Url(signature);
    }

    /** 包可见测试接缝：构造域分离租户摘要负例；生产封包也复用同一派生。 */
    static @NonNull String tenantDigest(@NonNull byte[] key, @NonNull String tenantId) {
        byte[] safeKey = requireKey(key);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantDigest.tenantId 不得为空");
        }
        byte[] digest = hmac(safeKey, (TENANT_DOMAIN + SEPARATOR + tenantId)
                .getBytes(StandardCharsets.UTF_8));
        return TENANT_DIGEST_PREFIX + encodeBase64Url(digest);
    }

    private static @NonNull StructuredState canonicalState(@NonNull String stateJson) {
        try {
            StructuredState state = StructuredStateCodec.fromJson(stateJson);
            if (!StructuredStateCodec.toJson(state).equals(stateJson)) {
                throw new IllegalArgumentException("stateJson 不是规范 JSON");
            }
            return state;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("stateJson 必须是通过 StructuredStateCodec 的规范状态", e);
        }
    }

    private static @NonNull byte[] requireKey(@Nullable byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("HMAC key 不得为空（独立 AGENT_CONTEXT_STATE_HMAC_KEY）");
        }
        return key.clone();
    }

    private static @NonNull byte[] hmac(@NonNull byte[] key, @NonNull byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("JDK 必须支持 HmacSHA256", e);
        }
    }

    private static @NonNull String encodeBase64Url(@NonNull byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static @NonNull byte[] decodeBase64Url(@NonNull String value) {
        if (value.isEmpty() || !BASE64_URL.matcher(value).matches()) {
            throw new IllegalArgumentException("base64url 非法");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!encodeBase64Url(decoded).equals(value)) {
            throw new IllegalArgumentException("base64url 非规范");
        }
        return decoded;
    }

    private static long parseLongStrict(@NonNull String value) {
        if (!DIGITS.matcher(value).matches() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IllegalArgumentException("非规范十进制整数: " + value);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("十进制整数溢出: " + value, e);
        }
    }

    private static int parseIntStrict(@NonNull String value) {
        if (!DIGITS.matcher(value).matches() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IllegalArgumentException("非规范十进制整数: " + value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("十进制整数溢出: " + value, e);
        }
    }

    private static void rejectControlChars(@NonNull String field) {
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("字段含裸控制字符 @" + i);
            }
        }
    }

    /** signPayload 只检验「不含额外分隔符」；字段内其它控制字符由 verify 逐字段拒。 */
    private static void rejectControlCharsOutsideSeparator(@NonNull String payload) {
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == FIELD_SEPARATOR) {
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("payload 含裸控制字符 @" + i);
            }
        }
    }

    private static ContextStateVerifier.Discarded discard(
            ContextStateVerifier.DropReason reason) {
        return new ContextStateVerifier.Discarded(reason);
    }
}
