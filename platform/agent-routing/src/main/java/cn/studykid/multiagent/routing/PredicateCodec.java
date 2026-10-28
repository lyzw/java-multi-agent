package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import cn.studykid.multiagent.core.orchestration.OnError;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 谓词文法的<b>唯一</b>入口：JSON 文本 → {@link Predicate} AST（10 号设计 §5 的 V1）。
 *
 * <p>⚠️ <b>解析期就把上限判掉，超限即拒而不是降级</b>：
 * 节点数、嵌套深度、字面量项数、正则长度都在这里检查（见 {@link PredicateLimits}）。
 * 放过去一条超限规则，代价不是「稍慢」而是<b>最坏成本失去上界</b> ——
 * 而运行期预算（超时/次数）是按「规则条数 × 节点数有上界」来定的。
 *
 * <p>⚠️ <b>未知算子与未知键一律拒绝</b>：
 * 宽松解析会把「算子名写错」变成「这个条件恒不成立」，
 * 于是策略看起来通过校验、实际整条规则白写 ——
 * 这是本项目反复消除的「看起来生效、实际不生效」缺陷类别（§8 六条缺口同源）。
 *
 * <p><b>取值位置的词法</b>（{@link #parseValue}）：
 * 字符串 {@code "in.a.b"} / {@code "ctx.channel"} 是引用，其它字符串是字面量；
 * 需要把<b>恰好长成引用形状的字符串</b>当字面量时，写 {@code {"lit":"in.topic"}} ——
 * 有逃逸口才不会逼配置者改用「换个写法绕过」，那才是真正引入歧义的地方。
 */
public final class PredicateCodec {

    /** 已实现的算子（新增算子必须同时改 {@link Predicate}、本类与求值器，三者一起过评审）。 */
    private static final Set<String> KNOWN_OPERATORS = Set.of(
            "const", "and", "or", "not", "eq", "in", "null",
            "startsWith", "endsWith", "contains", "anyOf", "re");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String IN_PREFIX = "in.";
    private static final String CTX_PREFIX = "ctx.";
    private static final String LITERAL_ESCAPE_KEY = "lit";

    private PredicateCodec() {
    }

    /**
     * 从 JSON 文本解析谓词。
     *
     * @param json 谓词 JSON
     * @param where 出错信息里回显的位置（规则 id 之类）
     * @return AST
     * @throws RoutingConfigException 文法非法或超上限
     */
    public static @NonNull Predicate parse(@NonNull String json, @NonNull String where) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new RoutingConfigException(where, "谓词不是合法 JSON: " + e.getMessage(), e);
        }
        return parse(node, where);
    }

    /**
     * 从 JSON 节点解析谓词。
     *
     * @param node  谓词节点
     * @param where 出错信息里回显的位置
     * @return AST
     * @throws RoutingConfigException 文法非法或超上限
     */
    public static @NonNull Predicate parse(@NonNull JsonNode node, @NonNull String where) {
        Budget budget = new Budget();
        Predicate predicate = parseNode(node, where, budget, 1);
        return predicate;
    }

    /**
     * 解析规则列表并检查条数上限（§4.4「规则条数 ≤64」）。
     *
     * @param nodes {@code evaluation} 数组
     * @param where 位置
     * @return 规则列表（顺序 = 声明序，服务端按 {@code ordinal} 重排）
     */
    public static @NonNull List<RuleSpec> parseRules(@NonNull JsonNode nodes,
                                                     @NonNull String where) {
        if (nodes == null || !nodes.isArray()) {
            throw new RoutingConfigException(where, "evaluation 必须是规则数组");
        }
        if (nodes.size() > PredicateLimits.MAX_RULES) {
            throw new RoutingConfigException(where,
                    "规则条数超过上限 " + PredicateLimits.MAX_RULES + "（运行期最坏成本 = 条数 × 节点数，"
                            + "超限就没有可预算的上界了）: " + nodes.size());
        }
        List<RuleSpec> rules = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            rules.add(parseRule(nodes.get(i), where).withOrdinal(i + 1));
        }
        return List.copyOf(rules);
    }

    /**
     * 解析单条规则，{@code ordinal} 置为占位值 1。
     *
     * <p>⚠️ {@code ordinal} <b>不信任传入值</b>：调用方按数组顺序重排后回填，
     * 因为「谁先判」是语义而不是装饰 —— 让配置者手写序号，就会出现
     * 两份序号（声明的与实际的）不一致的策略文本。
     */
    public static @NonNull RuleSpec parseRule(@NonNull JsonNode rule, @NonNull String where) {
        if (!rule.isObject()) {
            throw new RoutingConfigException(where, "规则必须是对象");
        }
        Set<String> keys = new java.util.LinkedHashSet<>();
        rule.properties().forEach(entry -> keys.add(entry.getKey()));
        Set<String> unknown = new java.util.LinkedHashSet<>(keys);
        unknown.removeAll(Set.of("ordinal", "ruleId", "target", "when", "onError", "note"));
        if (!unknown.isEmpty()) {
            throw new RoutingConfigException(where,
                    "规则含未定义的键: " + unknown + "（写错的键名会被当成「没写」，那比报错更糟）");
        }
        String ruleId = text(rule.get("ruleId"), where, "ruleId", true);
        String target = text(rule.get("target"), where, "target", true);
        JsonNode when = rule.get("when");
        if (when == null || when.isNull()) {
            // ⚠️ 显式写成 {"when":null} 与漏写 must 同一处理：Jackson 把前者读成 NullNode，
            //    不判 isNull 就会把「没给条件」报成「谓词不是对象」，把配置者指向错误的方向。
            throw new RoutingConfigException(where, "规则缺少 when（无条件规则等于「所有请求都派给」）");
        }
        Budget budget = new Budget();
        Predicate predicate = parseNode(when, where + "/" + ruleId, budget, 1);
        return new RuleSpec(1, ruleId, target, predicate,
                OnError.from(text(rule.get("onError"), where, "onError", false)),
                text(rule.get("note"), where, "note", false));
    }

    // ── 谓词节点 ──────────────────────────────────────────────────

    private static @NonNull Predicate parseNode(@NonNull JsonNode node, @NonNull String where,
                                                @NonNull Budget budget, int depth) {
        budget.enterNode(depth);
        if (!node.isObject()) {
            throw new RoutingConfigException(where,
                    "谓词必须是单键对象（如 {\"eq\":[...]}），收到: " + node.getNodeType());
        }
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        if (names.size() != 1) {
            throw new RoutingConfigException(where,
                    "谓词对象必须只有一个键（算子），收到 " + names.size() + " 个: " + names);
        }
        String operator = names.get(0);
        if (!KNOWN_OPERATORS.contains(operator)) {
            throw new RoutingConfigException(where,
                    "未知谓词算子: " + operator + "（已实现的只有 " + KNOWN_OPERATORS + "）");
        }
        JsonNode body = required(node.get(operator), where, operator);
        return switch (operator) {
            case "const" -> constOf(body, where);
            case "and" -> new Predicate.And(combinator(body, where, budget, depth, "and"));
            case "or" -> new Predicate.Or(combinator(body, where, budget, depth, "or"));
            case "not" -> new Predicate.Not(parseNode(required(body, where, "not"),
                    where, budget, depth + 1));
            case "eq" -> eq(body, where, budget);
            case "in" -> in(body, where, budget);
            case "null" -> new Predicate.Null(unary(body, where, "null", budget));
            case "startsWith" -> pair2(body, where, budget, "startsWith",
                    (a, b) -> new Predicate.StartsWith(a, b));
            case "endsWith" -> pair2(body, where, budget, "endsWith",
                    (a, b) -> new Predicate.EndsWith(a, b));
            case "contains" -> pair2(body, where, budget, "contains",
                    (a, b) -> new Predicate.Contains(a, b));
            case "anyOf" -> anyOf(body, where, budget);
            case "re" -> re(body, where, budget);
            default -> throw new RoutingConfigException(where, "算子未实现: " + operator);
        };
    }

    private static @NonNull Predicate constOf(@NonNull JsonNode body, @NonNull String where) {
        if (!body.isBoolean()) {
            throw new RoutingConfigException(where, "const 的值必须是布尔值");
        }
        return new Predicate.Const(body.booleanValue());
    }

    private static @NonNull List<Predicate> combinator(@NonNull JsonNode body,
                                                       @NonNull String where,
                                                       @NonNull Budget budget,
                                                       int depth, @NonNull String operator) {
        if (!body.isArray() || body.isEmpty()) {
            throw new RoutingConfigException(where,
                    operator + " 必须是非空数组（空数组的语义是恒真还是恒假？"
                            + "这正是必须拒掉的原因 —— 两种读法都说得通的策略文本不能进生产）");
        }
        List<Predicate> terms = new ArrayList<>();
        for (JsonNode term : body) {
            terms.add(parseNode(term, where, budget, depth + 1));
        }
        return List.copyOf(terms);
    }

    private static @NonNull Predicate eq(@NonNull JsonNode body, @NonNull String where,
                                         @NonNull Budget budget) {
        JsonNode[] operands = pairNodes(body, where, "eq");
        return new Predicate.Eq(parseValue(operands[0], where, budget),
                parseValue(operands[1], where, budget));
    }

    /**
     * {@code in}：左值是标量引用，右值<b>只能是字面量数组</b>。
     *
     * <p>⚠️ 右值不允许是引用（「取另一个输入字段当集合」）：
     * 那会让 V4/V5 的比较失去可对齐的常量，
     * 而且「集合来自输入」在路由语境里没有可复核的含义。
     */
    private static @NonNull Predicate in(@NonNull JsonNode body, @NonNull String where,
                                         @NonNull Budget budget) {
        JsonNode[] operands = pairNodes(body, where, "in");
        return new Predicate.In(parseValue(operands[0], where, budget),
                setLiteral(operands[1], where));
    }

    private static @NonNull Predicate anyOf(@NonNull JsonNode body, @NonNull String where,
                                            @NonNull Budget budget) {
        JsonNode[] operands = pairNodes(body, where, "anyOf");
        return new Predicate.AnyOf(parseValue(operands[0], where, budget),
                new Predicate.ArrayLiteral(items(operands[1], where)));
    }

    private static @NonNull Predicate re(@NonNull JsonNode body, @NonNull String where,
                                         @NonNull Budget budget) {
        JsonNode[] operands = pairNodes(body, where, "re");
        if (!operands[1].isString()) {
            throw new RoutingConfigException(where,
                    "re 的右值必须是正则<b>字符串</b>（BoundedRegex 没有对象形态）");
        }
        try {
            return new Predicate.Matches(parseValue(operands[0], where, budget),
                    Predicate.Regex.of(operands[1].asString()));
        } catch (IllegalArgumentException e) {
            throw new RoutingConfigException(where, "正则不合法: " + e.getMessage(), e);
        }
    }

    private interface PredicateFactory {
        @NonNull Predicate create(Predicate.Value left, Predicate.Value right);
    }

    private static @NonNull Predicate pair2(@NonNull JsonNode body, @NonNull String where,
                                            @NonNull Budget budget, @NonNull String operator,
                                            @NonNull PredicateFactory factory) {
        JsonNode[] operands = pairNodes(body, where, operator);
        return factory.create(parseValue(operands[0], where, budget),
                parseValue(operands[1], where, budget));
    }

    // ── 取值 ──────────────────────────────────────────────────────

    /**
     * 解析一个取值位置。
     *
     * @param node   节点
     * @param where  位置
     * @param budget 预算计数
     * @return 引用或标量字面量
     */
    public static Predicate.@NonNull Value parseValue(@NonNull JsonNode node,
                                                      @NonNull String where,
                                                      @NonNull Budget budget) {
        if (node.isObject() && node.has(LITERAL_ESCAPE_KEY)) {
            if (node.size() != 1) {
                throw new RoutingConfigException(where,
                        "{\"lit\":...} 只能带一个键（转义字面量没有别的修饰项）");
            }
            return literalOf(node.get(LITERAL_ESCAPE_KEY), where);
        }
        if (node.isString()) {
            String text = node.asString();
            if (text.startsWith(IN_PREFIX)) {
                String path = text.substring(IN_PREFIX.length());
                budget.enterReference(path);
                try {
                    return new Predicate.InputPath(path);
                } catch (IllegalArgumentException e) {
                    throw new RoutingConfigException(where, e.getMessage(), e);
                }
            }
            if (text.startsWith(CTX_PREFIX)) {
                String name = text.substring(CTX_PREFIX.length());
                try {
                    return new Predicate.CtxValue(CtxName.from(name));
                } catch (IllegalArgumentException e) {
                    throw new RoutingConfigException(where, e.getMessage(), e);
                }
            }
        }
        return literalOf(node, where);
    }

    private static Predicate.@NonNull LiteralValue literalOf(@NonNull JsonNode node,
                                                             @NonNull String where) {
        if (node.isNull()) {
            // ⚠️ JSON null 不是字面量：判空要用 {"null":[V]}，
            //    否则「等于 null」与「字段缺失」在文法里无法区分，而求值层对二者同判。
            throw new RoutingConfigException(where,
                    "取值位置不接受 JSON null（判缺失请用 {\"null\":[V]}）");
        }
        if (node.isString() || node.isBoolean() || node.isNumber()) {
            return new Predicate.LiteralValue(scalar(node));
        }
        throw new RoutingConfigException(where,
                "取值只允许引用（in.*/ctx.*）或 JSON 标量，收到: " + node.getNodeType());
    }

    private static Predicate.@NonNull SetLiteral setLiteral(@NonNull JsonNode node,
                                                            @NonNull String where) {
        return new Predicate.SetLiteral(items(node, where));
    }

    private static @NonNull List<Object> items(@NonNull JsonNode node, @NonNull String where) {
        if (!node.isArray()) {
            throw new RoutingConfigException(where, "集合字面量必须是数组");
        }
        if (node.size() > PredicateLimits.MAX_LITERAL_ITEMS) {
            throw new RoutingConfigException(where,
                    "集合字面量超过 " + PredicateLimits.MAX_LITERAL_ITEMS + " 项: " + node.size()
                            + "（DNF 展开的积按项数累乘，超限后 V4-V6 的精确判定就会超预算）");
        }
        List<Object> items = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isArray() || item.isObject() || item.isNull()) {
                throw new RoutingConfigException(where,
                        "集合字面量只允许标量项（嵌套结构的相等语义有歧义）");
            }
            items.add(scalar(item));
        }
        return List.copyOf(items);
    }

    private static @NonNull Object scalar(@NonNull JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        throw new RoutingConfigException("literal", "只允许 JSON 标量，收到: " + node.getNodeType());
    }

    // ── 小工具 ────────────────────────────────────────────────────

    private static @NonNull JsonNode required(@NonNull JsonNode node, @NonNull String where,
                                              @NonNull String operator) {
        if (node == null) {
            throw new RoutingConfigException(where, operator + " 缺少操作数");
        }
        return node;
    }

    /** {@code {"null":[V]}}：一元数组，返回已解析的取值。 */
    private static Predicate.@NonNull Value unary(@NonNull JsonNode body, @NonNull String where,
                                                  @NonNull String operator,
                                                  @NonNull Budget budget) {
        if (!body.isArray() || body.size() != 1) {
            throw new RoutingConfigException(where,
                    operator + " 必须是一元数组 [值]，收到: " + body);
        }
        return parseValue(body.get(0), where, budget);
    }

    private static JsonNode @NonNull [] pairNodes(@NonNull JsonNode body, @NonNull String where,
                                                  @NonNull String operator) {
        if (!body.isArray() || body.size() != 2) {
            throw new RoutingConfigException(where,
                    operator + " 必须是二元数组 [值, 值]，收到: " + body);
        }
        return new JsonNode[]{body.get(0), body.get(1)};
    }

    private static @Nullable String text(@Nullable JsonNode node, @NonNull String where,
                                         @NonNull String field, boolean mandatory) {
        if (node == null || node.isNull()) {
            if (mandatory) {
                throw new RoutingConfigException(where, "规则缺少 " + field);
            }
            return null;
        }
        if (!node.isString()) {
            throw new RoutingConfigException(where, field + " 必须是字符串");
        }
        String value = node.asString().trim();
        if (mandatory && value.isEmpty()) {
            throw new RoutingConfigException(where, field + " 不能为空");
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析预算计数器（节点数 + 嵌套深度）。
     *
     * <p>⚠️ 有意<b>不是</b>「校验后再计数」：一次遍历同时完成构造与限额检查，
     * 于其超限的分支会<b>在构造出完整 AST 之前</b>就抛出 ——
     * 攻击面「先建 10 万个节点再拒绝」在本层就被削掉。
     */
    public static final class Budget {
        private int nodes;
        private int maxDepth;

        void enterNode(int depth) {
            nodes++;
            if (nodes > PredicateLimits.MAX_NODES) {
                throw new RoutingConfigException("predicate",
                        "谓词节点数超过上限 " + PredicateLimits.MAX_NODES + "（规则膨胀到不可维护时，"
                                + "没有任何人会去读它 —— 上限存在的意义就是逼人拆分策略）");
            }
            if (depth > maxDepth) {
                maxDepth = depth;
            }
            if (maxDepth > PredicateLimits.MAX_DEPTH) {
                throw new RoutingConfigException("predicate",
                        "谓词嵌套深度超过上限 " + PredicateLimits.MAX_DEPTH);
            }
        }

        void enterReference(@NonNull String path) {
            // 引用路径的合法性由 InputPath 的构造器把守（词法不合法直接抛）；
            // 「路径有没有在 input_schema 里声明」是 V3 的职责，不在此处。
            if (path.isBlank()) {
                throw new RoutingConfigException("value", "in. 之后缺少字段路径");
            }
        }

        /** @return 已构造的节点数（守卫报告与排错都要它） */
        public int nodes() {
            return nodes;
        }

        /** @return 实际最大嵌套深度 */
        public int maxDepth() {
            return maxDepth;
        }
    }
}
