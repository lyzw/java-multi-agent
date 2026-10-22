package cn.studykid.multiagent.application.cap;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 参数策略引擎（§4.3「参数策略层级与合并」）。
 *
 * <p>§4.3 规定的三层结构：
 * <pre>
 *   强制安全约束（平台硬规则）      —— 不可覆盖
 *   Tool 参数策略（工具自身基础约束） —— 不可被 Skill 放宽
 *   Skill 参数策略                   —— 仅可进一步收紧
 * </pre>
 *
 * <p><b>合并规则</b>：同参数多策略取更严交集（AND）。
 * {@code in_allowlist} 与 {@code lte} 同时作用于同一参数时<b>均须满足</b>。
 *
 * <p><b>null vs [] 语义</b>（§4.3）：
 * <ul>
 *   <li>{@code null}：未配置该层附加策略 → 继续下层 + 强制安全；</li>
 *   <li>{@code []}：显式无该层附加 → 仍执行下层与强制安全。</li>
 * </ul>
 * 二者对 paramPolicies 的<b>可观测后果相同</b>（该层不追加任何约束），
 * 区别只在 provenance；<b>真正要求 null ≠ [] 的是 §4.4 的 allowlist 字段</b>
 * （{@code exposedToolAllowlist = null} 表示「取清单全集」，
 * {@code = []} 表示「显式不允许任何操作」）—— 那条语义落在 MCP 绑定解析上。
 *
 * <p>⚠️ <b>fail-closed 贯穿全流程</b>：
 * <ul>
 *   <li>未知 op → 抛 {@link ParamPolicyConfigException}（§4.3「未知 op → 配置期拒」，
 *       运行期遇到即为配置缺陷，调用方转 PARAM_POLICY_FAIL）；</li>
 *   <li>策略声明了某参数、而调用未提供该参数 → 拒绝（§4.3「缺失且策略要求该参数 → 拒」）；</li>
 *   <li>参数类型与策略要求不符 → 拒绝（§4.3「类型不符 → 拒」）。</li>
 * </ul>
 */
public final class ParamPolicyEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 允许的操作类型（V1 仅两种，其余一律拒绝 · §4.2/§4.3）。 */
    public enum Op {
        /** 值必须在白名单内。 */
        IN_ALLOWLIST("in_allowlist"),
        /** 数值必须小于等于阈值。 */
        LTE("lte");

        private final String code;

        Op(@NonNull String code) {
            this.code = code;
        }

        /**
         * @return 持久化形式的操作码
         */
        public @NonNull String code() {
            return code;
        }

        private static @Nullable Op parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            for (Op candidate : values()) {
                if (candidate.code.equals(raw.trim())) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * 单条参数策略。
     *
     * @param param 参数名
     * @param op    操作类型
     * @param value 取值（{@code in_allowlist} 为数组；{@code lte} 为数值）
     */
    public record ParamPolicy(@NonNull String param, @NonNull Op op, @NonNull JsonNode value) {
    }

    /**
     * 策略求值结果。
     *
     * @param passed        是否通过
     * @param failureReason 未通过的原因描述（通过时为 {@code null}）
     */
    public record Evaluation(boolean passed, @Nullable String failureReason) {

        /** 通过的结果。 */
        public static final Evaluation PASS = new Evaluation(true, null);

        /**
         * @param reason 失败原因
         * @return 失败结果
         */
        public static @NonNull Evaluation fail(@NonNull String reason) {
            return new Evaluation(false, reason);
        }
    }

    /**
     * 解析一层参数策略。
     *
     * <p>⚠️ 支持的形态：{@code null}（未配置）、{@code []}（显式为空）、
     * {@code {}}（DB 列默认值，防御性兼容）、{@code [{param, op, value}]}。
     * 前三者一律解析为<b>空策略集</b>（该层不追加约束），语义符合 §4.3。
     *
     * @param rawJson 原始 JSON 文本
     * @return 解析出的策略列表
     * @throws ParamPolicyConfigException JSON 非法、结构非法或 op 未知
     */
    public static @NonNull List<ParamPolicy> parse(@Nullable String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (RuntimeException e) {
            throw new ParamPolicyConfigException("参数策略不是合法 JSON: " + rawJson, e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            // null：未配置该层附加策略 → 该层不追加约束
            return List.of();
        }
        if (root.isObject() && root.isEmpty()) {
            // {}：DB 列默认值，等价于无附加策略
            return List.of();
        }
        if (root.isArray()) {
            if (root.isEmpty()) {
                // []：显式无该层附加 → 该层不追加约束
                return List.of();
            }
            List<ParamPolicy> policies = new ArrayList<>();
            for (JsonNode element : root) {
                policies.add(parsePolicy(element));
            }
            return List.copyOf(policies);
        }
        throw new ParamPolicyConfigException("参数策略结构非法（期望数组）: " + rawJson);
    }

    private static @NonNull ParamPolicy parsePolicy(@NonNull JsonNode element) {
        if (!element.isObject()) {
            throw new ParamPolicyConfigException("参数策略项必须是对象: " + element);
        }
        ObjectNode node = (ObjectNode) element;
        JsonNode paramNode = node.get("param");
        JsonNode opNode = node.get("op");
        if (paramNode == null || !paramNode.isTextual() || paramNode.asText().isBlank()) {
            throw new ParamPolicyConfigException("参数策略缺少 param: " + element);
        }
        if (opNode == null || !opNode.isTextual()) {
            throw new ParamPolicyConfigException("参数策略缺少 op: " + element);
        }
        Op op = Op.parse(opNode.asText());
        if (op == null) {
            // ⚠️ 未知 op 必须拒绝（§4.3），不得当作「无约束」放行
            throw new ParamPolicyConfigException("未知参数策略 op: " + opNode.asText());
        }
        JsonNode value = node.get("value");
        if (value == null || value.isMissingNode()) {
            throw new ParamPolicyConfigException("参数策略缺少 value: " + element);
        }
        // ⚠️ **值的形状在解析期就校验**（配置期拒，§4.3）：
        //    若拖到求值期才发现，某些「本次调用恰好不涉及该参数」的路径会绕过校验，
        //    让非法配置长期潜伏 —— 直到某次调用恰好命中它才暴露。
        if (op == Op.IN_ALLOWLIST && !value.isArray()) {
            throw new ParamPolicyConfigException("in_allowlist 的 value 必须是数组: " + element);
        }
        if (op == Op.LTE && !value.isNumber()) {
            throw new ParamPolicyConfigException("lte 的 value 必须是数值: " + element);
        }
        return new ParamPolicy(paramNode.asText(), op, value);
    }

    /**
     * 求值：合并 Tool 层与 Skill 层策略，并逐参数校验实际入参。
     *
     * @param toolPolicies  Tool 层策略（{@code null} 表示该层未配置）
     * @param skillPolicies Skill 层策略（{@code null} 表示该层未配置）
     * @param arguments     实际入参
     * @return 求值结果
     * @throws ParamPolicyConfigException 策略本身非法（配置缺陷）
     */
    public static @NonNull Evaluation evaluate(@Nullable List<ParamPolicy> toolPolicies,
                                                @Nullable List<ParamPolicy> skillPolicies,
                                                @NonNull Map<String, Object> arguments) {
        Map<String, Constraint> merged = new LinkedHashMap<>();
        merge(merged, toolPolicies);
        merge(merged, skillPolicies);

        for (Map.Entry<String, Constraint> entry : merged.entrySet()) {
            String param = entry.getKey();
            Constraint constraint = entry.getValue();

            if (!arguments.containsKey(param)) {
                // §4.3：缺失且策略要求该参数 → 拒
                return Evaluation.fail("参数缺失且策略要求该参数: " + param);
            }
            Object raw = arguments.get(param);
            if (raw == null) {
                // ⚠️ 显式 null 与缺失要区分（§5.3.2）：策略要求该参数时 null 同样不通过
                return Evaluation.fail("参数为 null 且策略要求该参数: " + param);
            }
            JsonNode actual = MAPPER.valueToTree(raw);

            if (constraint.allowlist() != null) {
                String key = valueKey(actual);
                if (!constraint.allowlist().contains(key)) {
                    return Evaluation.fail("参数 " + param + " 不在允许集内");
                }
            }
            if (constraint.lte() != null) {
                BigDecimal number = asNumber(actual);
                if (number == null) {
                    // §4.3：类型不符 → 拒
                    return Evaluation.fail("参数 " + param + " 非数值，无法与 lte 阈值比较");
                }
                if (number.compareTo(constraint.lte()) > 0) {
                    return Evaluation.fail("参数 " + param + " 超过 lte 阈值");
                }
            }
        }
        return Evaluation.PASS;
    }

    /**
     * 把一层策略并入合并结果（AND 语义）。
     *
     * <p>⚠️ Skill 层与 Tool 层用<b>同一套合并逻辑</b>，这正是「Skill 只能收紧」的机制：
     * {@code in_allowlist} 取交集、{@code lte} 取更小阈值，
     * 任何情况下 Skill 层都不可能放宽 Tool 层。
     */
    private static void merge(@NonNull Map<String, Constraint> target,
                              @Nullable List<ParamPolicy> policies) {
        if (policies == null) {
            return;
        }
        for (ParamPolicy policy : policies) {
            Constraint existing = target.get(policy.param());
            Constraint merged = existing == null
                    ? Constraint.from(policy)
                    : existing.combine(policy);
            target.put(policy.param(), merged);
        }
    }

    /**
     * 单参数的合并约束。
     *
     * @param allowlist 允许集（{@code null} 表示该参数无 in_allowlist 约束）
     * @param lte       数值上限（{@code null} 表示该参数无 lte 约束）
     */
    private record Constraint(@Nullable Set<String> allowlist, @Nullable BigDecimal lte) {

        static @NonNull Constraint from(@NonNull ParamPolicy policy) {
            return switch (policy.op()) {
                case IN_ALLOWLIST -> new Constraint(allowlistOf(policy), null);
                case LTE -> new Constraint(null, thresholdOf(policy));
            };
        }
        @NonNull Constraint combine(@NonNull ParamPolicy policy) {
            return switch (policy.op()) {
                case IN_ALLOWLIST -> new Constraint(intersect(allowlist, allowlistOf(policy)), lte);
                case LTE -> new Constraint(allowlist, stricter(lte, thresholdOf(policy)));
            };
        }

        /**
         * 允许集交集：任一方为空约束时取另一方；两侧都有则取交集。
         */
        private static @Nullable Set<String> intersect(@Nullable Set<String> a,
                                                      @Nullable Set<String> b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            Set<String> result = new LinkedHashSet<>(a);
            result.retainAll(b);
            return result;
        }

        private static @Nullable BigDecimal stricter(@Nullable BigDecimal a, @Nullable BigDecimal b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.compareTo(b) <= 0 ? a : b;
        }

        private static @NonNull Set<String> allowlistOf(@NonNull ParamPolicy policy) {
            JsonNode value = policy.value();
            if (!value.isArray()) {
                throw new ParamPolicyConfigException(
                        "in_allowlist 的 value 必须是数组: param=" + policy.param());
            }
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode element : value) {
                keys.add(valueKey(element));
            }
            return keys;
        }

        private static @NonNull BigDecimal thresholdOf(@NonNull ParamPolicy policy) {
            BigDecimal number = asNumber(policy.value());
            if (number == null) {
                throw new ParamPolicyConfigException(
                        "lte 的 value 必须是数值: param=" + policy.param());
            }
            return number;
        }
    }

    /**
     * 值的语义键：用于 allowlist 成员判定。
     *
     * <p>⚠️ <b>数值按数值比较</b>（{@code 1} 与 {@code 1.0} 等价），
     * 但<b>数值与字符串永不等价</b>（类型不符 → 拒，§4.3）。
     */
    private static @NonNull String valueKey(@NonNull JsonNode node) {
        if (node.isNumber()) {
            return "n:" + asNumber(node).stripTrailingZeros().toPlainString();
        }
        if (node.isTextual()) {
            return "s:" + node.asText();
        }
        if (node.isBoolean()) {
            return "b:" + node.asBoolean();
        }
        if (node.isNull()) {
            return "null";
        }
        return "j:" + node.toString();
    }

    /**
     * @param node JSON 节点
     * @return 数值形式；非数值返回 {@code null}
     */
    @Nullable
    private static BigDecimal asNumber(@NonNull JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return null;
    }

    /**
     * 便于测试构造策略数组。
     *
     * @param policies 策略列表
     * @return JSON 数组文本
     */
    public static @NonNull String toJson(@NonNull List<ParamPolicy> policies) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ParamPolicy policy : policies) {
            ObjectNode node = array.addObject();
            node.put("param", policy.param());
            node.put("op", policy.op().code());
            node.set("value", policy.value());
        }
        return MAPPER.writeValueAsString(array);
    }
}
