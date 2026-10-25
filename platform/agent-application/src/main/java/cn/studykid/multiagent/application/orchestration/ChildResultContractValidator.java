package cn.studykid.multiagent.application.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 委派结果的输出契约校验（多级 Agent v1.1 · P2-MA-04 · MA-T16 · FR-027）。
 *
 * <p>⚠️ <b>子 Agent 的输出不符合契约时，不得作为成功结果交给父 Agent</b>：
 * 父 Agent 会把它当成可信数据继续推理，结构错误就此扩散到最终答案，
 * 而且是以「看起来很正常」的方式扩散。
 *
 * <p>⚠️ <b>「取不到契约」必须 fail-closed</b>：声明了 {@code resultContractRef}
 * 却解析不出 schema 时，<b>拒绝</b>这次委派，而不是「跳过校验当作通过」——
 * 后者等于让「契约写了但没生效」变成常态。
 *
 * <p>⚠️ <b>校验范围是 JSON Schema 的常用子集</b>（type / required / properties /
 * additionalProperties=false / items / enum）。
 * 子集之外的关键字<b>不被支持</b>：与其半懂半不懂地放行，
 * 不如明确不支持并在解析期发现「契约里写了不认识的关键字」。
 */
public final class ChildResultContractValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 支持的关键字白名单（其余关键字视为不支持 → 拒绝契约）。 */
    private static final List<String> SUPPORTED_KEYWORDS = List.of(
            "type", "required", "properties", "additionalProperties", "items", "enum",
            "description", "title", "$schema", "$id");

    private ChildResultContractValidator() {
    }

    /**
     * 校验子 Agent 的输出是否满足契约。
     *
     * @param contractSchema 输出契约 JSON Schema 文本
     * @param output          子 Agent 的输出文本
     * @return 校验结果（通过 / 不通过 + 原因）
     */
    public static @NonNull Result validate(@NonNull String contractSchema,
                                           @NonNull String output) {
        JsonNode schema;
        try {
            schema = MAPPER.readTree(contractSchema);
        } catch (JacksonException e) {
            return Result.invalid("输出契约不是合法 JSON: " + e.getMessage());
        }
        if (schema == null || !schema.isObject()) {
            return Result.invalid("输出契约必须是 JSON 对象");
        }
        List<String> unsupported = unsupportedKeywords(schema);
        if (!unsupported.isEmpty()) {
            // ⚠️ 不认识的关键字 = 可能承载约束（maxLength / pattern / minimum …）。
            //    忽略它等于「假装校验过」，故明确拒绝。
            return Result.invalid("输出契约包含不支持的关键字: " + unsupported);
        }

        JsonNode value;
        try {
            value = MAPPER.readTree(output);
        } catch (JacksonException e) {
            // ⚠️ 输出不是合法 JSON：只有当契约允许字符串时才可能通过
            String type = schema.path("type").asText("");
            if ("string".equals(type)) {
                return Result.ok();
            }
            return Result.invalid("子 Agent 输出不是合法 JSON");
        }
        if (value == null || value.isMissingNode()) {
            return Result.invalid("子 Agent 输出为空");
        }

        List<String> violations = new ArrayList<>();
        check(schema, value, "$", violations);
        if (!violations.isEmpty()) {
            return Result.invalid("子 Agent 输出不符合输出契约: " + violations);
        }
        return Result.ok();
    }

    // ── 递归校验 ────────────────────────────────────────────────

    private static void check(JsonNode schema, JsonNode value, String path,
                              List<String> violations) {
        String type = schema.path("type").asText(null);
        if (type != null && !matchesType(type, value)) {
            violations.add(path + " 类型应为 " + type + "，实际 " + typeOf(value));
            return;
        }
        if (schema.has("enum")) {
            JsonNode enumValues = schema.get("enum");
            boolean hit = false;
            if (enumValues != null && enumValues.isArray()) {
                for (JsonNode candidate : enumValues) {
                    if (candidate.equals(value)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                violations.add(path + " 不在允许取值集合内");
            }
        }
        if (value.isObject()) {
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode name : required) {
                    if (!value.has(name.asText())) {
                        violations.add(path + " 缺少必填字段 " + name.asText());
                    }
                }
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                properties.properties().forEach(entry -> {
                    JsonNode child = value.get(entry.getKey());
                    if (child != null) {
                        check(entry.getValue(), child, path + '.' + entry.getKey(), violations);
                    }
                });
            }
            // ⚠️ additionalProperties=false：多传字段必须拒绝
            //    （否则「多传了越界字段」会被静默忽略，看起来无害实则掩盖越界输入）
            if ("false".equals(schema.path("additionalProperties").asText(null))) {
                List<String> allowed = properties.isObject()
                        ? properties.properties().stream().map(java.util.Map.Entry::getKey).toList()
                        : List.of();
                value.properties().forEach(entry -> {
                    if (!allowed.contains(entry.getKey())) {
                        violations.add(path + " 出现未被接受的字段 " + entry.getKey());
                    }
                });
            }
        }
        if (value.isArray() && schema.has("items")) {
            JsonNode items = schema.get("items");
            int index = 0;
            for (JsonNode element : value) {
                check(items, element, path + '[' + index + ']', violations);
                index++;
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static @NonNull String typeOf(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "missing";
        }
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isString()) {
            return "string";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isNull()) {
            return "null";
        }
        return "unknown";
    }

    private static @NonNull List<String> unsupportedKeywords(JsonNode schema) {
        List<String> unsupported = new ArrayList<>();
        schema.properties().forEach(entry -> {
            if (!SUPPORTED_KEYWORDS.contains(entry.getKey())) {
                unsupported.add(entry.getKey());
            }
        });
        return unsupported;
    }

    /**
     * 校验结果。
     *
     * @param valid  是否通过
     * @param reason 不通过的原因（通过时为空）
     */
    public record Result(boolean valid, @Nullable String reason) {

        public static @NonNull Result ok() {
            return new Result(true, null);
        }

        public static @NonNull Result invalid(@NonNull String reason) {
            return new Result(false, reason);
        }
    }
}
