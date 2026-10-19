package cn.studykid.multiagent.api.run;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 创建请求。
 *
 * <p><b>⚠️ 刻意不包含 tenantId</b>：租户标识一律取自认证上下文，
 * <b>永不信赖客户端输入</b>（P0-D02 NFR-001 / 威胁模型 S-1）。
 * 若请求体中出现 {@code tenantId}，Jackson 会因
 * {@link #UNKNOWN_PROPERTIES_REJECTED} 策略直接拒绝——
 * 这比「悄悄忽略」更好，后者会让人误以为参数生效了。
 */
public record CreateRunRequest(
        @NonNull String idempotencyKey,
        @NonNull String agentId,
        @NonNull Map<String, Object> input,
        @Nullable String requestedBy
) {

    /**
     * 拒绝未知字段。
     *
     * <p><b>为何严格</b>：若宽松忽略，客户端传 {@code tenantId=别人的租户}
     * 会被静默丢弃，看似「安全」实则**掩盖了攻击尝试**。
     * 明确报错才能让越权尝试留下痕迹。
     */
    public static final boolean UNKNOWN_PROPERTIES_REJECTED = true;

    public CreateRunRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey 长度超限");
        }
        input = Map.copyOf(input);
    }

    /** 便捷构造：省略 requestedBy（由认证上下文推导）。 */
    public CreateRunRequest(@NonNull String idempotencyKey,
                            @NonNull String agentId,
                            @NonNull Map<String, Object> input) {
        this(idempotencyKey, agentId, input, null);
    }

    /**
     * 内容合规检查。
     *
     * <p>⚠️ 这是<b>纵深防御</b>：即便零保留闸门已通过，
     * 也不允许请求体携带明显的可识别学生信息。
     * 主防线仍是 {@code ZeroRetentionGate}，此处仅作早期拦截。
     *
     * @throws IllegalArgumentException 含可识别信息时
     */
    public void assertNoIdentifyingContent() {
        for (cn.studykid.multiagent.core.compliance.DataClassification.IdentifyingPattern pattern
                : cn.studykid.multiagent.core.compliance.DataClassification.IdentifyingPattern.values()) {
            for (String value : flatten(input)) {
                if (pattern.detect(value)) {
                    throw new IllegalArgumentException(
                            "请求包含可识别学生信息（命中模式: " + pattern.label() + "）");
                }
            }
        }
    }

    /** 取出所有字符串值（递归）。 */
    private static java.util.List<String> flatten(Map<String, Object> map) {
        java.util.List<String> out = new java.util.ArrayList<>();
        map.forEach((k, v) -> collect(v, out));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object value, java.util.List<String> out) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> collect(v, out));
        } else if (value instanceof Iterable<?> it) {
            it.forEach(v -> collect(v, out));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
    }
}