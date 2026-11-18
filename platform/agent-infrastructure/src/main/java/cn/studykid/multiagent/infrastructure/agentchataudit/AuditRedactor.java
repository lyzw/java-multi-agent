package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Redaction;

import tools.jackson.databind.ObjectMapper;

/**
 * 审计脱敏器（递归 Map / List，替换敏感字段与强制 Header 黑名单）。
 *
 * <p><b>⚠️ 强制黑名单不可配</b>：{@link ChatAuditConfig#FORCED_REDACTED_HEADERS}
 * 无论前端如何设置都替换成掩码——凭据一旦落库落盘即 P0.5 红线。
 *
 * <p><b>⚠️ 纯函数</b>：不改入参，返回脱敏后的新结构。
 */
public final class AuditRedactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String mask;
    private final Set<String> sensitiveFields;

    public AuditRedactor(@NonNull Redaction redaction) {
        this.mask = redaction.mask();
        Set<String> fields = new java.util.HashSet<>();
        for (String f : redaction.sensitiveFields()) {
            fields.add(f.toLowerCase(Locale.ROOT));
        }
        this.sensitiveFields = Set.copyOf(fields);
    }

    /** 脱敏 Header Map（强制黑名单 + 用户配置字段命中即掩码）。 */
    public @NonNull Map<String, String> redactHeaders(@Nullable Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((k, v) -> {
            String lower = k.toLowerCase(Locale.ROOT);
            boolean forced = ChatAuditConfig.FORCED_REDACTED_HEADERS.contains(lower);
            boolean configured = sensitiveFields.stream().anyMatch(lower::contains);
            out.put(k, (forced || configured) ? mask : v);
        });
        return out;
    }

    /**
     * 脱敏 JSON 文本（请求原文走这里）。
     *
     * <p><b>⚠️ 解析失败时原样返回</b>：非 JSON 的 body 里不存在「字段名」，
     * 递归脱敏无从下手；此时保留原文比整段丢弃更符合「完整记录」的目标，
     * 但调用方必须清楚——<b>脱敏只对结构化字段生效</b>，凭据靠 Header 黑名单兜住。
     *
     * <p><b>⚠️ 不可用于超大文本</b>：会完整解析一次 JSON，调用方须先按
     * {@code limits.maxBodyBytes} 截断（截断后 JSON 可能不完整——那也属于解析失败即原样返回）。
     */
    public @NonNull String redactJsonText(@NonNull String rawJson) {
        if (rawJson.isBlank()) {
            return rawJson;
        }
        try {
            Object parsed = MAPPER.readValue(rawJson, Object.class);
            return MAPPER.writeValueAsString(redact(parsed));
        } catch (RuntimeException e) {
            return rawJson;
        }
    }

    /** 递归脱敏任意 JSON 结构（Map / List / 标量）。 */
    public @Nullable Object redact(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (isSensitive(key)) {
                    out.put(key, mask);
                } else {
                    out.put(key, redact(v));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(redact(item));
            }
            return out;
        }
        return value;
    }

    private boolean isSensitive(@NonNull String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return sensitiveFields.stream().anyMatch(lower::contains);
    }
}
