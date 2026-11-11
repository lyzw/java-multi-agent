package cn.studykid.multiagent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.StructuredState.Artifact;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.context.StructuredState.ToolCall;

/**
 * {@link StructuredState} 的<b>确定性 JSON 编解码 + 摘要</b>（《方案》§5.6 / §4.4，M5-c）。
 *
 * <p>⚠️ <b>零外部依赖</b>：{@code agent-context} 只许依赖 {@code agent-ports} 与 jspecify
 * （C1 / ADR-011），故本类手写固定 schema 的编解码，绝不引 Jackson / Gson。
 * 好处是「同一状态恒等字节」由字段顺序与转义规则唯一决定，不依赖任何库的迭代顺序。
 *
 * <p>⚠️ <b>确定性（digest 的前提）</b>：字段按声明顺序写；{@code facts} 额外按 key 排序；
 * 其余数组按存储顺序（语义顺序，不可排序）。同一逻辑状态 ⇒ 同一 JSON ⇒ 同一 SHA-256 ⇒
 * {@code compactionId} / 摘要结果缓存命中（§5.6）。
 *
 * <p>⚠️ <b>解析 fail-closed</b>：缺字段 / 多字段 / 类型不符 / 尾随垃圾 / 非法转义一律抛
 * {@link IllegalArgumentException}，绝不「宽松解析」把脏状态当真值续写 checkpoint。
 * SSE carry-over 的 state 是<b>不可信数据</b>（§4.4），验签后仍须经本解析器重建。
 */
public final class StructuredStateCodec {

    private StructuredStateCodec() {
    }

    // ── 序列化 ──────────────────────────────────────────────────────

    /** 确定性序列化：字段顺序固定，facts 按 key 排序，转义规则唯一。 */
    public static @NonNull String toJson(@NonNull StructuredState state) {
        StringBuilder b = new StringBuilder(256);
        b.append('{');
        b.append("\"goal\":");
        appendString(b, state.goal());
        b.append(",\"constraints\":");
        appendStringList(b, state.constraints());
        b.append(",\"userCorrections\":");
        appendStringList(b, state.userCorrections());
        b.append(",\"decisions\":");
        appendStringList(b, state.decisions());
        b.append(",\"rejectedApproaches\":");
        appendStringList(b, state.rejectedApproaches());
        b.append(",\"openItems\":");
        appendStringList(b, state.openItems());
        b.append(",\"artifacts\":");
        appendObjects(b, state.artifacts(), a -> {
            b.append("{\"refId\":");
            appendString(b, a.refId());
            b.append(",\"kind\":");
            appendString(b, a.kind());
            b.append(",\"oneLine\":");
            appendString(b, a.oneLine());
            b.append('}');
        });
        b.append(",\"facts\":");
        List<Fact> sortedFacts = new ArrayList<>(state.facts());
        sortedFacts.sort((x, y) -> x.key().compareTo(y.key()));
        appendObjects(b, sortedFacts, f -> {
            b.append("{\"key\":");
            appendString(b, f.key());
            b.append(",\"value\":");
            appendString(b, f.value());
            b.append('}');
        });
        b.append(",\"toolCalls\":");
        appendObjects(b, state.toolCalls(), t -> {
            b.append("{\"name\":");
            appendString(b, t.name());
            b.append(",\"resultRef\":");
            appendString(b, t.resultRef());
            b.append(",\"oneLine\":");
            appendString(b, t.oneLine());
            b.append('}');
        });
        b.append(",\"fromSeq\":").append(state.fromSeq());
        b.append(",\"upToSeq\":").append(state.upToSeq());
        b.append(",\"policyVersion\":");
        appendString(b, state.policyVersion());
        b.append('}');
        return b.toString();
    }

    /** 确定性摘要（SHA-256 of canonical JSON），供幂等键 / checkpoint  digest 使用。 */
    public static @NonNull String digest(@NonNull StructuredState state) {
        try {
            byte[] bytes = toJson(state).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte x : hash) {
                hex.append(Character.forDigit((x >> 4) & 0xF, 16));
                hex.append(Character.forDigit(x & 0xF, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须支持 SHA-256", e);
        }
    }

    private interface Appender<T> {
        void append(@NonNull T value);
    }

    private static <T> void appendObjects(@NonNull StringBuilder b, @NonNull List<T> items,
                                          @NonNull Appender<T> appender) {
        b.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appender.append(items.get(i));
        }
        b.append(']');
    }

    private static void appendStringList(@NonNull StringBuilder b, @NonNull List<String> items) {
        appendObjects(b, items, v -> appendString(b, v));
    }

    /** JSON 字符串转义：控制字符用 4 位十六进制 Unicode 转义，引号/反斜杠用短转义，CJK 原样保留。 */
    private static void appendString(@NonNull StringBuilder b, @NonNull String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append("\\u00");
                        b.append(Character.forDigit((c >> 4) & 0xF, 16));
                        b.append(Character.forDigit(c & 0xF, 16));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    // ── 反序列化 ────────────────────────────────────────────────────

    /** 严格解析：任何 schema 不符都抛 {@link IllegalArgumentException}（fail-closed）。 */
    public static @NonNull StructuredState fromJson(@NonNull String json) {
        Object root = new JsonReader(json).read();
        Map<String, Object> obj = asObject(root, "root");
        requireKeys(obj, "goal", "constraints", "userCorrections", "decisions",
                "rejectedApproaches", "openItems", "artifacts", "facts", "toolCalls",
                "fromSeq", "upToSeq", "policyVersion");

        // ⛔ checkpoint 游标必须结构自洽：否则 corrupt/upToSeq 越界会让后续所有增量被
        //    静默跳过（「没有新消息」但其实是游标坏了），属「不报错、功能空转」失真。
        //    游标真实性由 SSE carry-over 的 HMAC 与 DB checkpoint 的租户边界负责，
        //    这里只挡结构上不可能出现的组合（RV-31）。
        long fromSeq = asLong(obj.get("fromSeq"), "fromSeq");
        long upToSeq = asLong(obj.get("upToSeq"), "upToSeq");
        if (fromSeq < 0 || upToSeq < -1 || (upToSeq >= 0 && fromSeq > upToSeq)) {
            throw new IllegalArgumentException("JSON checkpoint 游标非法: fromSeq=" + fromSeq
                    + " upToSeq=" + upToSeq + " → fail-closed");
        }

        return new StructuredState(
                asString(obj.get("goal"), "goal"),
                asStringList(obj.get("constraints"), "constraints"),
                asStringList(obj.get("userCorrections"), "userCorrections"),
                asStringList(obj.get("decisions"), "decisions"),
                asStringList(obj.get("rejectedApproaches"), "rejectedApproaches"),
                asStringList(obj.get("openItems"), "openItems"),
                asObjectList(obj.get("artifacts"), "artifacts", item -> {
                    Map<String, Object> a = asObject(item, "artifact");
                    requireKeys(a, "refId", "kind", "oneLine");
                    return new Artifact(asString(a.get("refId"), "artifact.refId"),
                            asString(a.get("kind"), "artifact.kind"),
                            asString(a.get("oneLine"), "artifact.oneLine"));
                }),
                asObjectList(obj.get("facts"), "facts", item -> {
                    Map<String, Object> f = asObject(item, "fact");
                    requireKeys(f, "key", "value");
                    return new Fact(asString(f.get("key"), "fact.key"),
                            asString(f.get("value"), "fact.value"));
                }),
                asObjectList(obj.get("toolCalls"), "toolCalls", item -> {
                    Map<String, Object> t = asObject(item, "toolCall");
                    requireKeys(t, "name", "resultRef", "oneLine");
                    return new ToolCall(asString(t.get("name"), "toolCall.name"),
                            asString(t.get("resultRef"), "toolCall.resultRef"),
                            asString(t.get("oneLine"), "toolCall.oneLine"));
                }),
                fromSeq,
                upToSeq,
                asString(obj.get("policyVersion"), "policyVersion"));
    }

    private interface StructMapper<T> {
        T map(@NonNull Object value);
    }

    private static void requireKeys(@NonNull Map<String, Object> obj, @NonNull String... keys) {
        if (obj.size() != keys.length) {
            throw new IllegalArgumentException("JSON 对象字段数不符: 期望 " + keys.length
                    + " 实际 " + obj.size() + " → fail-closed");
        }
        for (String k : keys) {
            if (!obj.containsKey(k)) {
                throw new IllegalArgumentException("JSON 缺字段: " + k + " → fail-closed");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> asObject(Object v, @NonNull String field) {
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("字段 " + field + " 必须是对象 → fail-closed");
        }
        return (Map<String, Object>) v;
    }

    private static @NonNull String asString(Object v, @NonNull String field) {
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("字段 " + field + " 必须是字符串 → fail-closed");
        }
        return (String) v;
    }

    private static long asLong(Object v, @NonNull String field) {
        if (!(v instanceof Long)) {
            throw new IllegalArgumentException("字段 " + field + " 必须是整数 → fail-closed");
        }
        return (Long) v;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull List<String> asStringList(Object v, @NonNull String field) {
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("字段 " + field + " 必须是数组 → fail-closed");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("字段 " + field + " 含非字符串元素 → fail-closed");
            }
            out.add((String) item);
        }
        return List.copyOf(out);
    }

    private static <T> @NonNull List<T> asObjectList(Object v, @NonNull String field,
                                                     @NonNull StructMapper<T> mapper) {
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("字段 " + field + " 必须是数组 → fail-closed");
        }
        List<T> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(mapper.map(item));
        }
        return List.copyOf(out);
    }

    /** 极简严格 JSON reader：支持对象/数组/字符串/整数/true/false/null，拒绝其它一切。 */
    private static final class JsonReader {
        private final String s;
        private int pos;

        private JsonReader(@NonNull String s) {
            this.s = s;
        }

        private @Nullable Object read() {
            Object v = value();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("JSON 尾随垃圾 @ " + pos + " → fail-closed");
            }
            return v;
        }

        private @Nullable Object value() {
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束 → fail-closed");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new IllegalArgumentException("JSON 非法字符 '" + c + "' @ " + pos + " → fail-closed");
                }
            };
        }

        private @NonNull Object object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                Object parsed = value();
                // ⛔ 重复字段必须拒：LinkedHashMap 会静默覆盖，requireKeys 的字段数检查
                //    仍会通过 ⇒ 同一份 checkpoint 可有两种「真值」（RV-29 反证点）。
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException("JSON 重复字段 '" + key + "' → fail-closed");
                }
                map.put(key, parsed);
                skipWs();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                if (peek('}')) {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("JSON 对象缺 ',' 或 '}' @ " + pos + " → fail-closed");
            }
        }

        private @NonNull List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWs();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                if (peek(']')) {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("JSON 数组缺 ',' 或 ']' @ " + pos + " → fail-closed");
            }
        }

        private @NonNull String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("JSON 转义不完整 → fail-closed");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> b.append(unicode());
                        default -> throw new IllegalArgumentException(
                                "JSON 非法转义 '\\" + e + "' → fail-closed");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("JSON 字符串含裸控制字符 → fail-closed");
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合 → fail-closed");
        }

        private char unicode() {
            if (pos + 4 > s.length()) {
                throw new IllegalArgumentException("JSON \\u 转义长度不足 → fail-closed");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos++), 16);
                if (d < 0) {
                    throw new IllegalArgumentException("JSON \\u 含非十六进制字符 → fail-closed");
                }
                code = (code << 4) | d;
            }
            return (char) code;
        }

        private @NonNull Long number() {
            int start = pos;
            boolean negative = peek('-');
            if (negative) {
                pos++;
            }
            int digitsStart = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            int digits = pos - digitsStart;
            // ⛔ 只接受 toJson 会写出的规范整数：无前导零、无 -0、无空数字、无浮点/指数。
            //    宽松接受 01 / -0 会让「同一状态多种文本表示」破坏 digest 的规范化前提。
            if (digits == 0) {
                throw new IllegalArgumentException("JSON 整数缺数字 @ " + start + " → fail-closed");
            }
            if (digits > 1 && s.charAt(digitsStart) == '0') {
                throw new IllegalArgumentException("JSON 整数含前导零 @ " + start + " → fail-closed");
            }
            if (negative && digits == 1 && s.charAt(digitsStart) == '0') {
                throw new IllegalArgumentException("JSON 整数 -0 非规范 @ " + start + " → fail-closed");
            }
            if (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E'
                        || !(c == ',' || c == '}' || c == ']' || c == ' '
                        || c == '\n' || c == '\r' || c == '\t')) {
                    throw new IllegalArgumentException(
                            "JSON 整数后非法字符 '" + c + "' @ " + pos + " → fail-closed");
                }
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 整数溢出 @ " + start + " → fail-closed", e);
            }
        }

        private @Nullable Object literal(@NonNull String text, @Nullable Object value) {
            if (!s.startsWith(text, pos)) {
                throw new IllegalArgumentException("JSON 非法字面量 @ " + pos + " → fail-closed");
            }
            pos += text.length();
            return value;
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("JSON 期望 '" + c + "' @ " + pos + " → fail-closed");
            }
            pos++;
        }
    }
}
