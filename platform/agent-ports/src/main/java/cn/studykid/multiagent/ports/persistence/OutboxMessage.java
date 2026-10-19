package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Outbox 消息体（ADR-001 §4.2）。
 *
 * <p><b>载荷最小化原则</b>：消息只承载「唤醒信号」所需的最小信息，
 * Worker 收到后<b>按 runId 回数据库查询权威状态</b>。
 *
 * <p><b>消息中禁止包含</b>：
 * <ul>
 *   <li>Prompt 内容 / 系统提示词；</li>
 *   <li>工具调用参数（含知识点等业务细节）；</li>
 *   <li>学生数据、原始提交内容；</li>
 *   <li>任何未成年人可识别信息（P0-D00 §4 白名单）。</li>
 * </ul>
 *
 * <p><b>为何如此严格</b>：消息一旦进入 MQ 就难以<b>精确删除</b>，
 * 携带敏感数据会扩大泄露面且难以追溯。最小化同时降低合规审计成本
 * （P0-D05 R-015）。
 *
 * <p>本类在<b>类型层面</b>限制可写字段——新增字段必须显式声明，
 * 而每个字段都经过 {@link #ALLOWED_KEYS} 校验，杜绝「顺手把整个 payload 塞进去」。
 */
public record OutboxMessage(
        @NonNull String runId,
        @NonNull String tenantId,
        int attempt,
        long fencingToken,
        @NonNull Instant enqueuedAt
) {

    /** 允许出现在消息体中的字段名（白名单）。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "runId", "tenantId", "attempt", "fencingToken", "enqueuedAt");

    /** 明确禁止出现的字段（双重保险，防止误传）。 */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "prompt", "systemPrompt", "userContent", "toolArguments",
            "studentId", "studentName", "studentIdCard", "answerContent",
            "modelResponse", "resultPayload");

    public OutboxMessage {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负");
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken 不能为负");
        }
        if (enqueuedAt == null) {
            throw new IllegalArgumentException("enqueuedAt 不能为空");
        }
    }

    /**
     * 序列化为消息体。
     *
     * @throws IllegalArgumentException 出现白名单外的字段
     */
    public @NonNull Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("tenantId", tenantId);
        payload.put("attempt", attempt);
        payload.put("fencingToken", fencingToken);
        payload.put("enqueuedAt", enqueuedAt.toString());
        return payload;
    }

    /**
     * 校验任意载荷是否可安全进入消息体。
     *
     * <p>供发布前兜底调用——即便调用方绕过了本类型构造，
     * 也能在入库前拦下敏感字段。
     *
     * @throws IllegalArgumentException 含禁止字段
     */
    public static void assertPayloadSafe(@NonNull Map<String, Object> payload) {
        payload.keySet().forEach(k -> {
            if (FORBIDDEN_KEYS.contains(k)) {
                throw new IllegalArgumentException(
                        "消息体禁止携带敏感字段: " + k
                                + "（MQ 消息难以精确删除，携带即扩大泄露面）");
            }
            if (!ALLOWED_KEYS.contains(k)) {
                throw new IllegalArgumentException(
                        "消息体出现白名单外字段: " + k + "，允许的字段: " + ALLOWED_KEYS);
            }
        });
    }

    /** 从消息体还原（消费侧使用）。 */
    public static @NonNull OutboxMessage fromPayload(@NonNull Map<String, Object> payload) {
        Object runId = payload.get("runId");
        Object tenantId = payload.get("tenantId");
        if (runId == null || tenantId == null) {
            throw new IllegalArgumentException("消息体缺少 runId 或 tenantId");
        }
        return new OutboxMessage(
                String.valueOf(runId),
                String.valueOf(tenantId),
                intOf(payload.get("attempt")),
                longOf(payload.get("fencingToken")),
                parseInstant(payload.get("enqueuedAt")));
    }

    private static int intOf(@Nullable Object v) {
        if (v == null) {
            return 0;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    private static long longOf(@Nullable Object v) {
        if (v == null) {
            return 0L;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static Instant parseInstant(@Nullable Object v) {
        if (v == null) {
            return Instant.EPOCH;
        }
        return v instanceof Instant i ? i : Instant.parse(String.valueOf(v));
    }
}