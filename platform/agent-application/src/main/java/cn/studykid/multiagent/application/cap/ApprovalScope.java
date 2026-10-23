package cn.studykid.multiagent.application.cap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审批范围定义（W-PERM-P3-T1 · §5.3.1 审批绑定契约）。
 *
 * <p>「审批允许的操作范围与参数约束」的规范化表示。
 * 它有三个用途：
 * <ol>
 *   <li>生成时算出 {@code approvalScopeHash}（范围定义摘要）；</li>
 *   <li>消费时用<b>同一份范围定义</b>重新校验实际参数是否仍落在范围内；</li>
 *   <li>作为<b>可复原</b>的定义全文随审批落库（§5.3.1「记录保存完整范围 JSON + hash」），
 *       使消费方不必依赖「当初那个进程还在」即可重验。</li>
 * </ol>
 *
 * <p>⚠️ <b>范围哈希只覆盖「范围定义」本身，不覆盖参数</b>：
 * 参数由 {@code effectiveArgsHash} 覆盖。二者缺一不可 ——
 * 只有范围哈希，则参数可任意替换；只有参数哈希，则范围语义无处复核。
 *
 * <p>⚠️ <b>未在范围中出现的参数不得被视为「可以自由变化」</b>（§5.3.1 明文）：
 * 只要该参数在<b>有效参数集</b>内，它的取值就已被 {@code effectiveArgsHash} 绑定；
 * 范围里的显式约束只是<b>额外</b>的语义边界（如「收件域必须是 example.com」）。
 *
 * <p>⚠️ <b>规范形式必须是 JSON，不能是自研的行式文本</b>：
 * 行式文本里「参数值」与「分隔符」无法无歧义分离 ——
 * 值里含空格或换行时，解析回来的定义与原定义不同，
 * 于是「同一份范围」在授予侧与消费侧算出不同哈希，审批永远匹配不上。
 * JSON 天然无歧义，且键序与数组序可完全固定。
 */
public record ApprovalScope(
        @NonNull String toolId,
        @NonNull Map<String, Constraint> constraints,
        @Nullable Set<String> effectfulParams,
        @Nullable Map<String, Object> schemaDefaults,
        @Nullable Set<String> setValuedParams) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ApprovalScope {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
        effectfulParams = effectfulParams == null ? null : Set.copyOf(effectfulParams);
        schemaDefaults = schemaDefaults == null ? null : Map.copyOf(new TreeMap<>(schemaDefaults));
        setValuedParams = setValuedParams == null ? null : Set.copyOf(setValuedParams);
    }

    /**
     * 构造范围（全部参数参与摘要、无默认值、无集合语义）。
     *
     * @param toolId      目标工具
     * @param constraints 参数约束
     * @return 范围
     */
    public static @NonNull ApprovalScope of(@NonNull String toolId,
                                            @NonNull Map<String, Constraint> constraints) {
        return new ApprovalScope(toolId, constraints, null, null, null);
    }

    /**
     * 单条参数约束。
     *
     * @param op    约束算子：{@code eq} / {@code neq} / {@code in} / {@code lte} / {@code gte}
     *              / {@code exists} / {@code not_exists}
     * @param value 约束值（{@code exists} / {@code not_exists} 时为 {@code null}）
     */
    public record Constraint(@NonNull String op, @Nullable Object value) {

        public Constraint {
            if (op == null || op.isBlank()) {
                throw new IllegalArgumentException("op 不能为空");
            }
        }
    }

    /**
     * 范围的规范化表示（用于计算范围哈希与落库）。
     *
     * <p>⚠️ 键序固定、集合按字典序 —— 否则同一份范围会因构造顺序不同
     * 算出不同哈希，审批永远匹配不上。
     *
     * @return 规范化 JSON 文本
     */
    public @NonNull String canonicalText() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tool", toolId);

        ArrayNode constraintArray = MAPPER.createArrayNode();
        for (Map.Entry<String, Constraint> entry : new TreeMap<>(constraints).entrySet()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("param", entry.getKey());
            node.put("op", entry.getValue().op());
            node.set("value", toNode(entry.getValue().value()));
            constraintArray.add(node);
        }
        root.set("constraints", constraintArray);

        // ⚠️ null（= 全部参数参与）与空集合（= 一个参数都不参与）语义不同，
        //    故显式区分：null → 不写该键；空集合 → 写空数组。
        if (effectfulParams != null) {
            ArrayNode array = MAPPER.createArrayNode();
            effectfulParams.stream().sorted().forEach(array::add);
            root.set("effectfulParams", array);
        }
        if (schemaDefaults != null) {
            ObjectNode defaults = MAPPER.createObjectNode();
            new TreeMap<>(schemaDefaults).forEach((k, v) -> defaults.set(k, toNode(v)));
            root.set("schemaDefaults", defaults);
        }
        if (setValuedParams != null) {
            ArrayNode array = MAPPER.createArrayNode();
            setValuedParams.stream().sorted().forEach(array::add);
            root.set("setValuedParams", array);
        }
        return MAPPER.writeValueAsString(root);
    }

    /** @return 范围的 SHA-256 摘要（小写 hex，无算法前缀）。 */
    public @NonNull String hash() {
        return sha256Hex(canonicalText());
    }

    /**
     * 从规范化文本复原范围（消费侧使用）。
     *
     * <p>⚠️ <b>解析失败必须抛异常，绝不返回「空范围」</b>：
     * 空范围 = 无约束 = 任何参数都通过 —— 那等于把范围校验降级为空操作，
     * 与 §5.3.1 的防参数替换重放意图完全相反。
     *
     * @param canonicalText 规范化 JSON 文本
     * @return 复原的范围
     */
    public static @NonNull ApprovalScope parse(@NonNull String canonicalText) {
        JsonNode root;
        try {
            root = MAPPER.readTree(canonicalText);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("审批范围定义不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("审批范围定义不是 JSON 对象");
        }
        String toolId = text(root, "tool");
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("审批范围定义缺少 tool");
        }

        Map<String, Constraint> constraints = new TreeMap<>();
        JsonNode constraintArray = root.get("constraints");
        if (constraintArray != null && constraintArray.isArray()) {
            for (JsonNode node : constraintArray) {
                String param = text(node, "param");
                String op = text(node, "op");
                if (param == null || op == null) {
                    throw new IllegalArgumentException("审批范围定义中的约束缺少 param/op");
                }
                constraints.put(param, new Constraint(op, fromNode(node.get("value"))));
            }
        }

        Set<String> effectful = readStringSet(root.get("effectfulParams"));
        Set<String> setValued = readStringSet(root.get("setValuedParams"));

        Map<String, Object> defaults = null;
        JsonNode defaultsNode = root.get("schemaDefaults");
        if (defaultsNode != null && defaultsNode.isObject()) {
            // ⚠️ 不能在 lambda 里直接 put 到「随后还要赋值」的局部变量 ——
            //    它不是事实上不可变的，编译期就会拒绝。
            Map<String, Object> collected = new TreeMap<>();
            defaultsNode.properties().forEach(entry ->
                    collected.put(entry.getKey(), fromNode(entry.getValue())));
            defaults = collected;
        }

        return new ApprovalScope(toolId, constraints, effectful, defaults, setValued);
    }

    /**
     * 本范围对应的参数规范化选项。
     *
     * <p>⚠️ <b>授予侧与消费侧必须用同一份</b>：否则「审批绑定的参数」
     * 与「实际执行的参数」会算出两个哈希，防重放成了摆设。
     * 故规范化依据随范围定义一起落库，消费侧直接取用。
     */
    public ArgsNormalizer.@NonNull Options normalizationOptions() {
        return new ArgsNormalizer.Options(effectfulParams, schemaDefaults, setValuedParams);
    }

    /**
     * 范围重验：用本范围定义校验实际参数是否仍落在批准范围内。
     *
     * @param arguments 实际参数
     * @return 不满足的约束（空集合 = 全部满足）
     */
    public @NonNull Set<String> violatedConstraints(@NonNull Map<String, Object> arguments) {
        Set<String> violated = new LinkedHashSet<>();
        for (Map.Entry<String, Constraint> entry : new TreeMap<>(constraints).entrySet()) {
            String name = entry.getKey();
            Constraint constraint = entry.getValue();
            boolean present = arguments.containsKey(name);
            Object actual = arguments.get(name);
            switch (constraint.op()) {
                case "exists" -> {
                    if (!present) {
                        violated.add(name);
                    }
                }
                case "not_exists" -> {
                    if (present) {
                        violated.add(name);
                    }
                }
                case "eq" -> {
                    if (!present || !valuesEqual(actual, constraint.value())) {
                        violated.add(name);
                    }
                }
                case "neq" -> {
                    if (present && valuesEqual(actual, constraint.value())) {
                        violated.add(name);
                    }
                }
                case "in" -> {
                    if (!present || !inAllowed(actual, constraint.value())) {
                        violated.add(name);
                    }
                }
                case "lte" -> {
                    if (!present || !numericAtMost(actual, constraint.value())) {
                        violated.add(name);
                    }
                }
                case "gte" -> {
                    if (!present || !numericAtLeast(actual, constraint.value())) {
                        violated.add(name);
                    }
                }
                default -> {
                    // ⚠️ 未知算子 = 配置缺陷 → 判为不满足（fail-closed），
                    //    绝不「忽略这条约束」—— 忽略等于把范围校验降级为空操作。
                    violated.add(name);
                }
            }
        }
        return violated;
    }

    private static boolean valuesEqual(@Nullable Object actual, @Nullable Object expected) {
        if (actual == null || expected == null) {
            return Objects.equals(actual, expected);
        }
        if (actual instanceof Number a && expected instanceof Number e) {
            return new java.math.BigDecimal(ArgsNormalizer.canonicalNumber(a))
                    .compareTo(new java.math.BigDecimal(ArgsNormalizer.canonicalNumber(e))) == 0;
        }
        if (actual instanceof Boolean a && expected instanceof Boolean e) {
            return a.equals(e);
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private static boolean inAllowed(@Nullable Object actual, @Nullable Object allowed) {
        if (!(allowed instanceof Iterable<?> iterable) || actual == null) {
            return false;
        }
        for (Object candidate : iterable) {
            if (valuesEqual(actual, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean numericAtMost(@Nullable Object actual, @Nullable Object bound) {
        java.math.BigDecimal a = toDecimal(actual);
        java.math.BigDecimal b = toDecimal(bound);
        return a != null && b != null && a.compareTo(b) <= 0;
    }

    private static boolean numericAtLeast(@Nullable Object actual, @Nullable Object bound) {
        java.math.BigDecimal a = toDecimal(actual);
        java.math.BigDecimal b = toDecimal(bound);
        return a != null && b != null && a.compareTo(b) >= 0;
    }

    private static java.math.@Nullable BigDecimal toDecimal(@Nullable Object value) {
        if (value instanceof Number number) {
            return new java.math.BigDecimal(ArgsNormalizer.canonicalNumber(number));
        }
        if (value instanceof String text) {
            try {
                return new java.math.BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── JSON 互转 ──────────────────────────────────────────────────

    @Nullable
    private static String text(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    @Nullable
    private static Set<String> readStringSet(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode child : node) {
            out.add(child.asString());
        }
        return out;
    }

    private static @NonNull JsonNode toNode(@Nullable Object value) {
        // ⚠️ 采用**带类型标签**的编码，而不是裸 JSON 值：
        //   · 裸数字会让 1 / 1.0 / 1.00 落成不同文本 → 同一份范围算出不同哈希；
        //   · 而把数字统一写成文本又会**丢失类型** → 往返后 Integer 变 String，
        //     与 Double 实际值比较时字符串兜底判不等，范围重验失效。
        //   标签编码同时解决这两点：值用十进制规范形式（哈希稳定），
        //   类型显式记录（比较语义不变）。
        ObjectNode node = MAPPER.createObjectNode();
        if (value == null) {
            node.put("type", "null");
            return node;
        }
        if (value instanceof Boolean bool) {
            node.put("type", "boolean");
            node.put("value", bool);
            return node;
        }
        if (value instanceof Number number) {
            node.put("type", "number");
            node.put("value", ArgsNormalizer.canonicalNumber(number));
            return node;
        }
        if (value instanceof Map<?, ?> map) {
            node.put("type", "object");
            ObjectNode inner = MAPPER.createObjectNode();
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sorted.forEach((k, v) -> inner.set(k, toNode(v)));
            node.set("value", inner);
            return node;
        }
        if (value instanceof Iterable<?> iterable) {
            node.put("type", "array");
            ArrayNode inner = MAPPER.createArrayNode();
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            items.forEach(item -> inner.add(toNode(item)));
            node.set("value", inner);
            return node;
        }
        node.put("type", "string");
        node.put("value", ArgsNormalizer.canonicalString(String.valueOf(value)));
        return node;
    }

    @Nullable
    private static Object fromNode(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        // ⚠️ 兼容两种形态：带标签（新格式）与裸值（旧格式）。
        //    旧格式只在历史数据里出现；读不出标签时按裸值推断。
        if (!node.isObject() || !node.has("type")) {
            return bareValue(node);
        }
        String type = node.get("type").asString();
        JsonNode value = node.get("value");
        return switch (type) {
            case "null" -> null;
            case "boolean" -> value != null && value.asBoolean();
            case "number" -> value == null ? null : new java.math.BigDecimal(value.asString());
            case "string" -> value == null ? null : value.asString();
            case "array" -> {
                List<Object> items = new ArrayList<>();
                if (value != null && value.isArray()) {
                    value.forEach(child -> items.add(fromNode(child)));
                }
                yield items;
            }
            case "object" -> {
                Map<String, Object> map = new TreeMap<>();
                if (value != null && value.isObject()) {
                    value.properties().forEach(entry ->
                            map.put(entry.getKey(), fromNode(entry.getValue())));
                }
                yield map;
            }
            // ⚠️ 未知类型 = 定义损坏 → 不得静默当成 null（那等于放弃约束）
            default -> throw new IllegalArgumentException("审批范围定义含未知值类型: " + type);
        };
    }

    @Nullable
    private static Object bareValue(@NonNull JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(child -> items.add(bareValue(child)));
            return items;
        }
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), bareValue(entry.getValue())));
            return map;
        }
        return null;
    }

    private static @NonNull String sha256Hex(@NonNull String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
