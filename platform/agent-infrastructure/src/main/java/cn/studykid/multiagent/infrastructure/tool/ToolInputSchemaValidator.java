package cn.studykid.multiagent.infrastructure.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;

/**
 * 工具入参的 <b>Schema 闭集校验</b>（第三十七批抽取）。
 *
 * <p>⚠️ <b>为什么抽成公共实现</b>：
 * MCP-backed 工具与远程 HTTP 工具<b>都要</b>按 {@code cap_tool_def.input_schema}
 * 校验入参（§6.1 的版本化 Schema 是权威口径）。
 * 两处各写一遍，迟早漂移 —— 而漂移的方向通常是
 * 「某一处忘了校验 {@code additionalProperties:false}」，
 * 那会让未声明的参数<b>被放行</b>。
 *
 * <p>⚠️ <b>只做「有没有」这一层校验，不做类型强制</b>：
 * 类型不符由参数策略引擎与下游共同负责（§4.3），
 * 本类只保证「必填的在、没声明的不在」。
 * 这与 {@code ToolHandler} 契约一致：**Schema 错误必须拒绝，不得「尽力解析」**。
 */
public final class ToolInputSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInputSchemaValidator() {
    }

    /**
     * 按 JSON Schema 校验入参。
     *
     * <p>⚠️ <b>Schema 本身不是合法 JSON 时直接拒绝</b>：
     * 「解析不了约束」若被当作「没有约束」，等于把闭集校验整个关掉。
     *
     * @param schemaJson 版本化的入参 Schema 文本
     * @param arguments  实际入参
     * @throws IllegalArgumentException 校验失败
     */
    public static void validate(@NonNull String schemaJson,
                                @NonNull Map<String, Object> arguments) {
        JsonNode schema;
        try {
            schema = MAPPER.readTree(schemaJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("工具输入 Schema 不是合法 JSON");
        }
        if (schema == null || !schema.isObject()) {
            // 空 Schema ≠ 「允许任意参数」：参数域由 paramPolicies 收紧，
            // 而这里连「结构」都没有 —— 无结构即不施加结构约束（§6.1 的缺省语义）。
            return;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode item : required) {
                String name = item.asString();
                if (!arguments.containsKey(name)) {
                    throw new IllegalArgumentException("缺少必填参数: " + name);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        boolean closed = additional != null && additional.isBoolean()
                && !additional.asBoolean(Boolean.TRUE);
        if (closed) {
            List<String> accepted = new ArrayList<>();
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                    accepted.add(entry.getKey());
                }
            }
            for (String key : arguments.keySet()) {
                if (!accepted.contains(key)) {
                    throw new IllegalArgumentException("不被接受的参数: " + key);
                }
            }
        }
    }
}
