package cn.studykid.multiagent.application.cap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 调用参数规范化（{@code args_norm_v1} · §5.3.2 · W-PERM-P3-T2）。
 *
 * <p>本类是<b>全链路唯一的参数摘要算法</b>：审批的有效参数哈希（§5.3.1）、
 * 账本的请求摘要、执行意图的参数指纹（§5.3 步骤 13a）全部走这里，
 * 三者必须同源，否则「审批绑定的参数」与「实际执行的参数」会算出两个值，
 * 防重放就成了摆设。
 *
 * <p>§5.3.2 的七条规则逐条落地：
 * <ol>
 *   <li><b>字段字典序</b> —— 对象键按字典序输出；</li>
 *   <li><b>字符串 UTF-8 NFC</b> —— Unicode 等价但字节不同的字符串必须同摘要；</li>
 *   <li><b>数字十进制规范形式</b> —— {@code 1}、{@code 1.0}、{@code 1.00} 同摘要，
 *       但<b>数字与字符串不同摘要</b>（{@code 1} ≠ {@code "1"}）；</li>
 *   <li><b>布尔 {@code true/false}</b>；</li>
 *   <li><b>数组默认保持调用序</b>；元数据声明 {@code set} 时按字典序排序；</li>
 *   <li><b>缺失字段与显式 {@code null} 区分编码</b> —— {@code {}} 与 {@code {"a":null}} 不同摘要；</li>
 *   <li><b>Schema default 先展开再哈希</b> —— 未显式传参时按 Schema 默认值参与摘要。</li>
 * </ol>
 * 摘要算法：SHA-256，小写 hex。
 *
 * <p>⚠️ <b>为什么不能直接用 {@code Map.toString()}</b>：
 * 那是 {@code HashMap} 的遍历顺序，键序取决于哈希分布；
 * 同一批参数在不同进程可能产出不同文本 →
 * 「同参数」被误判成「不同参数」，正常重试被打成 {@code IDEMPOTENCY_CONFLICT}。
 */
public final class ArgsNormalizer {

    /** 规范化算法版本（写入摘要前缀，便于将来版本化）。 */
    public static final String ALGORITHM_VERSION = "args_norm_v1";

    /** 摘要前缀。 */
    public static final String PREFIX = ALGORITHM_VERSION + ":";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArgsNormalizer() {
    }

    /**
     * 规范化选项。
     *
     * @param effectfulParams 有效参数集（{@code null} = 全部参数）；
     *                        §5.3.1「覆盖所有可能影响结果或副作用的有效参数」
     * @param schemaDefaults   Schema 默认值（先展开再哈希；{@code null} = 无）
     * @param setValuedParams 声明为「集合」的参数（其数组值按字典序排序；{@code null} = 无）
     */
    public record Options(
            @Nullable Set<String> effectfulParams,
            @Nullable Map<String, Object> schemaDefaults,
            @Nullable Set<String> setValuedParams) {

        /** 全部参数、无默认值、无集合语义。 */
        public static @NonNull Options all() {
            return new Options(null, null, null);
        }

        /** 仅指定的有效参数集。 */
        public static @NonNull Options effectful(@NonNull Set<String> effectfulParams) {
            return new Options(Set.copyOf(effectfulParams), null, null);
        }

        public @NonNull Options withDefaults(@Nullable Map<String, Object> defaults) {
            return new Options(effectfulParams, defaults, setValuedParams);
        }

        public @NonNull Options withSetValued(@Nullable Set<String> setValued) {
            return new Options(effectfulParams, schemaDefaults, setValued);
        }
    }

    /**
     * 计算参数摘要。
     *
     * @param arguments 调用参数
     * @param options   规范化选项
     * @return {@code args_norm_v1:} 前缀 + SHA-256 小写 hex
     */
    public static @NonNull String hash(Map<String, Object> arguments, Options options) {
        return PREFIX + sha256Hex(normalizedJson(arguments, options));
    }

    /**
     * 生成规范化 JSON（人类可读，便于审计与排障）。
     *
     * @param arguments 调用参数
     * @param options   规范化选项
     * @return 规范化 JSON 文本
     */
    public static @NonNull String normalizedJson(Map<String, Object> arguments, Options options) {
        Map<String, Object> effective = effectiveArguments(arguments, options);

        ObjectNode root = MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : new TreeMap<>(effective).entrySet()) {
            root.set(entry.getKey(), toNode(entry.getValue(),
                    options.setValuedParams() != null
                            && options.setValuedParams().contains(entry.getKey())));
        }
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 计算<b>有效参数集</b>（含 Schema default 展开）。
     *
     * @param arguments 调用参数
     * @param options   规范化选项
     * @return 参与摘要的参数集
     */
    public static @NonNull Map<String, Object> effectiveArguments(Map<String, Object> arguments,
                                                                   Options options) {
        Map<String, Object> source = arguments == null ? Map.of() : arguments;

        // ── Schema default 先展开 ────────────────────────────────────
        // ⚠️ 默认值**只填补缺失键**，绝不覆盖显式传值：
        //    覆盖会把「调用方明确传了 null」变成「用了默认值」，
        //    使摘要无法区分两者（§5.3.2 要求缺失与 null 区分编码）。
        // ⚠️⚠️ 这里**不能用 putIfAbsent**：它的语义是「键缺失<b>或值为 null</b> 时写入」，
        //    显式传了 null 的键**会被默认值覆盖** ——
        //    那正好把「显式 null」与「省略参数」压成同一个摘要，
        //    §5.3.2 规则 6 与规则 7 同时失效。
        //    必须用 containsKey 精确判断「键是否存在」。
        Map<String, Object> merged = new TreeMap<>(source);
        if (options.schemaDefaults() != null) {
            for (Map.Entry<String, Object> entry : options.schemaDefaults().entrySet()) {
                if (!merged.containsKey(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // ── 有效参数集裁剪 ──────────────────────────────────────────
        if (options.effectfulParams() == null) {
            return merged;
        }
        Map<String, Object> result = new TreeMap<>();
        for (String name : options.effectfulParams()) {
            // ⚠️ 用 containsKey 而非 get：显式 null 也是「有效参数」，必须参与摘要
            if (merged.containsKey(name)) {
                result.put(name, merged.get(name));
            }
        }
        return result;
    }

    /**
     * 从 JSON Schema 提取各属性的默认值。
     *
     * <p>§5.3.2「Schema default 先展开再哈希」：调用方未显式传参时，
     * 按 Schema 声明的默认值参与摘要 —— 否则「省略参数」与「显式传默认值」
     * 会算出两个摘要，审批绑定就绑不住实际执行。
     *
     * @param jsonSchema JSON Schema 文本（可空）
     * @return 属性名 → 默认值
     */
    public static @NonNull Map<String, Object> defaultsFromSchema(@Nullable String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(jsonSchema);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            JsonNode properties = root.get("properties");
            if (properties == null || !properties.isObject()) {
                return Map.of();
            }
            Map<String, Object> defaults = new TreeMap<>();
            properties.properties().forEach(entry -> {
                JsonNode defaultNode = entry.getValue().get("default");
                if (defaultNode != null && !defaultNode.isMissingNode()) {
                    defaults.put(entry.getKey(), fromNode(defaultNode));
                }
            });
            return defaults;
        } catch (RuntimeException e) {
            // ⚠️ Schema 非法不得让整个调用崩掉：默认值展开失败按「无默认值」处理，
            //    后续的结构校验（步骤 9）会独立拒绝非法参数。
            return Map.of();
        }
    }

    private static @Nullable Object fromNode(@NonNull JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(child -> items.add(fromNode(child)));
            return items;
        }
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), fromNode(entry.getValue())));
            return map;
        }
        return node.asText();
    }

    private static @NonNull JsonNode toNode(@Nullable Object value, boolean setValued) {
        if (value == null) {
            // ⚠️ 显式 null 落成 JSON null，与「键缺失」区分（§5.3.2）
            return MAPPER.nullNode();
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = MAPPER.createObjectNode();
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sorted.forEach((k, v) -> node.set(k, toNode(v, false)));
            return node;
        }
        if (value instanceof Iterable<?> iterable) {
            List<JsonNode> items = new ArrayList<>();
            iterable.forEach(item -> items.add(toNode(item, false)));
            if (setValued) {
                // ⚠️ 声明为 set 的参数：数组按字典序排序 → 元素顺序不影响摘要
                items.sort(java.util.Comparator.comparing(JsonNode::toString));
            }
            ArrayNode array = MAPPER.createArrayNode();
            items.forEach(array::add);
            return array;
        }
        if (value instanceof Boolean bool) {
            return MAPPER.getNodeFactory().booleanNode(bool);
        }
        if (value instanceof Number number) {
            // ⚠️⚠️ 必须是**数字节点**，不能写成文本（修复前的真实缺陷）：
            //    写成文本后 {"score":1} 与 {"score":"1"} 算出**同一个摘要** ——
            //    「审批绑定的参数」与「实际执行的参数」类型都不同却同摘要，
            //    防重放与审批绑定同时失效。
            //    §5.3.2 规则 3 只要求「整数与浮点不分」（1 / 1.0 / 1.00 同摘要），
            //    从未允许「数字与字符串不分」——
            //    后者与 {@code ParamPolicyEngine.valueKey} 的 "n:" / "s:" 前缀口径
            //    直接冲突（那边明确「数值与字符串永不等价」）。
            return numberNode(number);
        }
        return MAPPER.getNodeFactory().textNode(canonicalString(String.valueOf(value)));
    }

    /**
     * 数字的规范形式节点（数字节点；非有限值退化为文本节点）。
     *
     * <p>⚠️ <b>非有限值（NaN / Infinity）不是合法 JSON 数字</b>，
     * 无法表示为数字节点 —— 此时保留文本形态。
     * 理论上的同形风险（与字符串 {@code "NaN"} 撞摘要）不可达：
     * 调用参数来自 JSON，而 JSON 本身承载不了 NaN。
     *
     * @param number 数值
     * @return 规范形式的节点
     */
    private static @NonNull JsonNode numberNode(@NonNull Number number) {
        String canonical = canonicalNumber(number);
        try {
            // ⚠️ 只经 canonicalNumber 转 BigDecimal：所有数值形态（Integer / Long /
            //    Double / BigDecimal）都落到同一份十进制规范文本 → 同一摘要。
            return MAPPER.getNodeFactory().numberNode(new BigDecimal(canonical));
        } catch (NumberFormatException e) {
            return MAPPER.getNodeFactory().textNode(canonical);
        }
    }

    /**
     * 数字的十进制规范形式（§5.3.2）。
     *
     * <p>⚠️ <b>整数与浮点不再区分</b>：{@code 1}、{@code 1.0}、{@code 1.00} 同摘要。
     * 否则「同一批参数」会因为调用方用了 {@code Integer} 还是 {@code Double}
     * 而算出两个摘要，审批绑定形同虚设。
     */
    static @NonNull String canonicalNumber(@NonNull Number number) {
        if (number instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (number instanceof java.math.BigInteger integer) {
            return integer.toString();
        }
        if (number instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return d.toString();
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (number instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                return f.toString();
            }
            return BigDecimal.valueOf(f.doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (number instanceof Short || number instanceof Integer || number instanceof Long
                || number instanceof Byte) {
            return number.toString();
        }
        return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    }

    /**
     * 字符串的 NFC 规范形式（§5.3.2）。
     *
     * <p>⚠️ NFC 而非 NFD/NFKC：NFC 只做等价合成，保留字符的原始语义；
     * NFKC 会把全角、兼容字符折叠掉，可能把两个语义不同的参数折叠成同一个摘要。
     */
    static @NonNull String canonicalString(@NonNull String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static @NonNull String sha256Hex(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 强制支持的算法；返回固定值会让所有摘要相同，
            //    「摘要相同即参数相同」的判定将永远成立 —— 那比抛异常更危险。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** @return 便于审计的参数名集合（有效参数集）。 */
    public static @NonNull Set<String> effectiveParamNames(Map<String, Object> arguments,
                                                            Options options) {
        return new LinkedHashSet<>(effectiveArguments(arguments, options).keySet());
    }
}
