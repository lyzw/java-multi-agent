package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * 路由输入的<b>词汇表</b>（10 号设计 §4.2 的 {@code input_schema}）。
 *
 * <p>⚠️ 它同时干三件事，且必须是<b>同一份</b>事实：
 * <ol>
 *   <li>决定 {@code in.*} 能引用哪些路径（求值视野的边界，见 {@link RoutingInput#of}）；</li>
 *   <li>决定下发给模型的 {@code input} JSON Schema（模型必须先产出可路由的结构化意图）；</li>
 *   <li>供守卫 V3 判「策略引用的路径存在吗、类型相容吗」。</li>
 * </ol>
 * 若这三处各取一份，就会出现「规则引用了一个模型产不出来的字段」——
 * 那条规则<b>永远不命中</b>，而配置者以为它生效了（§8 G-4 的同一缺陷形态）。
 *
 * <p><b>支持的形态是有意的窄</b>：对象嵌套（点分展平）+ 标量数组，
 * 标量类型只有 string / integer / number / boolean。
 * 没有 {@code oneOf}/{@code patternProperties} —— 那些形态会让 V3 的类型相容判定
 * 退化成启发式，而本模块的全部价值在于判定是<b>确定</b>的。
 */
public record InputSchema(@NonNull Map<String, FieldType> fields,
                          @NonNull Set<String> required,
                          @NonNull Map<String, List<Object>> enums) {

    /** 标量种类。 */
    public enum Scalar {
        /** 字符串。 */
        STRING,
        /** 整数（{@code number} 的子集，比较时与 {@link #NUMBER} 不互通）。 */
        INTEGER,
        /** 数值。 */
        NUMBER,
        /** 布尔。 */
        BOOLEAN
    }

    /**
     * 一个路径的类型。
     *
     * @param scalar 元素种类
     * @param array  是否为标量数组（{@code anyOf} 只接受数组路径）
     */
    public record FieldType(@NonNull Scalar scalar, boolean array) {
    }

    public InputSchema {
        fields = Map.copyOf(fields);
        required = Set.copyOf(required);
        Map<String, List<Object>> enumCopy = new LinkedHashMap<>();
        enums.forEach((path, values) -> enumCopy.put(path, List.copyOf(values)));
        enums = Map.copyOf(enumCopy);
    }

    /**
     * 从 JSON Schema 文本解析。
     *
     * @param node 顶层必须是 {@code {"type":"object", ...}}
     * @return 词汇表
     * @throws RoutingConfigException 形态不支持（含未知关键字、非对象根）
     */
    public static @NonNull InputSchema from(@NonNull JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RoutingConfigException("input_schema", "input_schema 必须是 JSON 对象");
        }
        JsonNode type = node.get("type");
        if (type == null || !"object".equals(type.asString())) {
            throw new RoutingConfigException("input_schema",
                    "input_schema 顶层必须是 type=object，收到: " + (type == null ? "缺 type" : type.asString()));
        }
        Map<String, FieldType> fields = new LinkedHashMap<>();
        Map<String, List<Object>> enums = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        flatten("", node, fields, enums, required);
        if (fields.isEmpty()) {
            throw new RoutingConfigException("input_schema",
                    "input_schema 没有声明任何字段：那等于策略只能引用 ctx.*，"
                            + "「按输入内容转发」就无从谈起");
        }
        return new InputSchema(fields, required, enums);
    }

    /**
     * @return 全部可引用路径（点分），按声明序
     */
    public @NonNull List<String> paths() {
        List<String> paths = new ArrayList<>(fields.keySet());
        paths.sort(String::compareTo);
        return List.copyOf(paths);
    }

    public @NonNull Optional<FieldType> typeOf(@NonNull String path) {
        return Optional.ofNullable(fields.get(path));
    }

    public boolean isRequired(@NonNull String path) {
        return required.contains(path);
    }

    /**
     * @param path 点分路径
     * @return 该路径声明的枚举取值；未声明枚举时为 {@link Optional#empty()}
     */
    public @NonNull Optional<List<Object>> enumOf(@NonNull String path) {
        return Optional.ofNullable(enums.get(path));
    }

    /**
     * 判断一个字面量与该路径的类型是否相容（守卫 V3 的判据）。
     *
     * @param path    点分路径
     * @param literal 策略里出现的字面量
     * @return 是否相容；路径不存在时也返回 {@code false}（由调用方区分报错文案）
     */
    public boolean isCompatible(@NonNull String path, @Nullable Object literal) {
        FieldType type = fields.get(path);
        if (type == null || literal == null) {
            return false;
        }
        if (literal instanceof String) {
            return type.scalar() == Scalar.STRING && !type.array();
        }
        if (literal instanceof Boolean) {
            return type.scalar() == Scalar.BOOLEAN && !type.array();
        }
        if (literal instanceof Integer || literal instanceof Long) {
            return (type.scalar() == Scalar.INTEGER || type.scalar() == Scalar.NUMBER)
                    && !type.array();
        }
        if (literal instanceof java.math.BigDecimal || literal instanceof Double
                || literal instanceof Float) {
            return type.scalar() == Scalar.NUMBER && !type.array();
        }
        if (literal instanceof List<?> items) {
            if (!type.array()) {
                return false;
            }
            return items.stream().allMatch(item -> {
                InputSchema probe = new InputSchema(Map.of("x", type), Set.of(), Map.of());
                return probe.isCompatible("x", item);
            });
        }
        return false;
    }

    private static void flatten(@NonNull String prefix, @NonNull JsonNode object,
                                @NonNull Map<String, FieldType> fields,
                                @NonNull Map<String, List<Object>> enums,
                                @NonNull Set<String> required) {
        JsonNode requiredNode = object.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            requiredNode.forEach(item -> required.add(prefix + item.asString()));
        }
        JsonNode properties = object.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        properties.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode spec = entry.getValue();
            String path = prefix.isEmpty() ? name : prefix + name;
            if (!spec.isObject()) {
                throw new RoutingConfigException("input_schema." + path,
                        "字段定义必须是对象（含 type）");
            }
            String type = spec.get("type") == null ? null : spec.get("type").asString();
            if ("object".equals(type)) {
                flatten(path + ".", spec, fields, enums, required);
                return;
            }
            FieldType fieldType = toFieldType(path, type, spec.get("items"));
            fields.put(path, fieldType);
            JsonNode enumNode = spec.get("enum");
            if (enumNode != null && enumNode.isArray()) {
                List<Object> values = new ArrayList<>();
                enumNode.forEach(item -> values.add(scalarOf(path, item)));
                enums.put(path, List.copyOf(values));
            }
        });
    }

    private static @NonNull FieldType toFieldType(@NonNull String path,
                                                  @Nullable String type,
                                                  @Nullable JsonNode items) {
        if ("array".equals(type)) {
            if (items == null || !items.isObject()) {
                throw new RoutingConfigException("input_schema." + path,
                        "数组字段必须声明 items 的标量类型（无类型数组让比较口径无从判定）");
            }
            JsonNode itemTypeName = items.get("type");
            return new FieldType(scalarOfTypeName(path + "[]",
                    itemTypeName == null || itemTypeName.isNull() ? null : itemTypeName.asString()), true);
        }
        return new FieldType(scalarOfTypeName(path, type == null ? null
                : type), false);
    }

    private static @NonNull Scalar scalarOfTypeName(@NonNull String path, @Nullable String type) {
        if (type == null) {
            throw new RoutingConfigException("input_schema." + path, "字段缺少 type");
        }
        return switch (type) {
            case "string" -> Scalar.STRING;
            case "integer" -> Scalar.INTEGER;
            case "number" -> Scalar.NUMBER;
            case "boolean" -> Scalar.BOOLEAN;
            // ⚠️ 未知/不支持的 type 直接拒：放过去就等于允许一个「词汇表里类型不明」的字段，
            //    而 V3 的类型相容判定会对它给出假阳性结论。
            default -> throw new RoutingConfigException("input_schema." + path,
                    "不支持的字段类型: " + type);
        };
    }

    private static @NonNull Object scalarOf(@NonNull String path, @NonNull JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        throw new RoutingConfigException("input_schema." + path,
                "枚举项只允许 JSON 标量");
    }
}
