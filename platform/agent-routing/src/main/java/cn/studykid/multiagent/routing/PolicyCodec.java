package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 策略文本 → {@link PolicySpec} 的<b>唯一</b>解析入口（10 号设计 §4.2 的行形态）。
 *
 * <p>⚠️ <b>发布校验、运行期决策与管理台 dry-run 必须走这一个解析器</b>：
 * §7 债 5 特意禁止前端自建引擎镜像，同一个理由 —— 两份解析代码迟早漂移，
 * 而漂移的症状是「dry-run 说没问题，保存后跑出不一样的结果」。
 *
 * <p>⚠️ {@code allowedTargets} / {@code requiredTargets} <b>由调用方从父的编排声明传入</b>，
 * 不在策略文本里：可委派范围是<b>父的拓扑</b>，策略只是拓扑之上的选择函数。
 * 把目标集合抄进策略文本就会出现「策略声称能派给某个子、父其实没声明它」的双份真相，
 * 而它恰好是准入校验最容易漏的一处。
 *
 * <p><b>未知键一律拒绝</b>（与 {@link PredicateCodec} 同口径）：
 * 拼错的键名会被读成「没写」，于是 {@code noMatchAction} 静默取默认值 ——
 * 那比报错危险得多。
 */
public final class PolicyCodec {

    /** 策略对象的合法键。 */
    private static final Set<String> POLICY_KEYS = Set.of(
            "policyId", "version", "mode", "inputSchema", "evaluation",
            "defaultTarget", "noMatchAction", "onConflict", "sampleSet");

    /** 样本对象的合法键。 */
    private static final Set<String> SAMPLE_KEYS = Set.of(
            "label", "input", "expectTarget", "via", "ctx");

    /** dry-run 探针对象的合法键（比样本少 {@code expectTarget}/{@code via}，多 {@code modelCandidate}）。 */
    private static final Set<String> PROBE_KEYS = Set.of(
            "label", "input", "modelCandidate", "ctx");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicyCodec() {
    }

    /**
     * 解析策略 JSON 文本。
     *
     * @param json           策略文本
     * @param allowedTargets 父 {@code childAgentRefs} 里的全部目标
     * @param requiredTargets 其中 {@code required=true} 的目标
     * @return 结构合法的策略对象
     * @throws RoutingConfigException 文法、上限或结构不合法
     */
    public static @NonNull PolicySpec parse(@NonNull String json,
                                            @NonNull Set<String> allowedTargets,
                                            @NonNull Set<String> requiredTargets) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new RoutingConfigException("policy", "策略不是合法 JSON: " + e.getMessage(), e);
        }
        return parse(node, allowedTargets, requiredTargets);
    }

    /**
     * 解析策略 JSON 节点。
     *
     * @param node           策略节点
     * @param allowedTargets 父声明的可委派目标
     * @param requiredTargets 其中必选的目标
     * @return 结构合法的策略对象
     * @throws RoutingConfigException 文法、上限或结构不合法
     */
    public static @NonNull PolicySpec parse(@NonNull JsonNode node,
                                            @NonNull Set<String> allowedTargets,
                                            @NonNull Set<String> requiredTargets) {
        try {
            return read(node, allowedTargets, requiredTargets);
        } catch (IllegalArgumentException e) {
            // ⚠️ 值对象（PolicySpec/RuleSpec/各枚举）抛的是 IllegalArgumentException，
            //    在本模块的对外口径里它属于「配置期拒绝」，必须折算成同一个异常类型 ——
            //    否则调用方要 catch 两种异常，而漏掉一种就把配置缺陷当成运行时崩溃上报。
            throw new RoutingConfigException("policy", e.getMessage(), e);
        }
    }

    private static @NonNull PolicySpec read(@NonNull JsonNode node,
                                            @NonNull Set<String> allowedTargets,
                                            @NonNull Set<String> requiredTargets) {
        if (!node.isObject()) {
            throw new RoutingConfigException("policy", "策略必须是 JSON 对象");
        }
        Set<String> unknown = keysOf(node);
        unknown.removeAll(POLICY_KEYS);
        if (!unknown.isEmpty()) {
            throw new RoutingConfigException("policy", "策略含未定义的键: " + unknown
                    + "（写错的键名会被当成「没写」，那比报错更糟）");
        }
        String policyId = requireText(node.get("policyId"), "policy.policyId");
        int version = requireInt(node.get("version"), "policy.version");
        RoutingMode mode = modeOf(requireText(node.get("mode"), "policy.mode"));
        JsonNode inputSchemaNode = node.get("inputSchema");
        if (inputSchemaNode == null) {
            throw new RoutingConfigException("policy.inputSchema",
                    "缺少 input_schema：没有词汇表就没有 {@code in.*} 可引用，规则无从判起");
        }
        InputSchema inputSchema = InputSchema.from(inputSchemaNode);
        List<RuleSpec> rules = PredicateCodec.parseRules(node.get("evaluation"), "policy.evaluation");
        String defaultTarget = optionalText(node.get("defaultTarget"), "policy.defaultTarget");
        NoMatchAction noMatchAction = NoMatchAction.from(
                requireText(node.get("noMatchAction"), "policy.noMatchAction"));
        ConflictPolicy conflictPolicy = ConflictPolicy.from(optionalText(node.get("onConflict"), "policy.onConflict"));
        List<SampleSpec> samples = parseSamples(node.get("sampleSet"));
        return new PolicySpec(policyId, version, mode, inputSchema, rules, defaultTarget,
                noMatchAction, conflictPolicy, samples, allowedTargets, requiredTargets);
    }

    /**
     * 解析策略 JSON 文本，<b>不引入父声明的目标集合</b>（策略自身发布时用）。
     *
     * <p>⚠️ <b>为什么要有这个入口，而不是让调用方传一个空集合</b>：
     * {@code allowedTargets} 是 V2（「策略派给的目标必须 ∈ 父 childAgentRefs」）的判据，
     * 而策略<b>单独发布</b>的那一刻根本没有父 —— 传空集会让任何写了规则的策略都被拒，
     * 传全集则 V2 形同虚设且<b>无人知道自己被放行了</b>。
     *
     * <p>因此这里的作用域是「策略自己提到的目标」：文法、上限、V3~V10 <b>照常全判</b>，
     * 只有 V2/V6 退化为自反（自己提到的目标当然在集合里）。
     * 真正的 V2 在父 Agent 发布时由 {@code AgentPublishClosureValidator}
     * 用父的<b>真实</b> {@code childAgentRefs} 再跑一遍 —— 那才是 V2/V6 有意义的地方。
     *
     * @param json 策略文本
     * @return 结构合法的策略对象
     * @throws RoutingConfigException 文法、上限或结构不合法
     */
    public static @NonNull PolicySpec parseStandalone(@NonNull String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new RoutingConfigException("policy", "策略不是合法 JSON: " + e.getMessage(), e);
        }
        return parse(node, mentionedTargets(node), Set.of());
    }

    /**
     * 策略文本自己提到的目标集合（规则目标 + 兜底目标）。
     *
     * <p>规则用 {@link PredicateCodec#parseRules} 解析后再取目标，而不是直接抓 JSON 里的
     * {@code target} 键 —— 否则「哪一列是目标」这件事就有了第二份定义。
     */
    private static @NonNull Set<String> mentionedTargets(@NonNull JsonNode node) {
        Set<String> targets = new LinkedHashSet<>();
        for (RuleSpec rule : PredicateCodec.parseRules(node.get("evaluation"), "policy.evaluation")) {
            targets.add(rule.target());
        }
        JsonNode fallback = node.get("defaultTarget");
        if (fallback != null && fallback.isString() && !fallback.asString().isBlank()) {
            targets.add(fallback.asString());
        }
        return targets;
    }

    /**
     * 解析 {@code ctx} 覆盖项（样本用）。
     *
     * <p>⚠️ <b>{@code ctx.tenantId} 不接受样本声明</b>：租户只来自服务端，
     * 让样本自带租户等于在测试装置里重开 G-6 的身份敞口。
     *
     * @param node  节点（可为 {@code null}）
     * @param where 位置
     * @return 上下文映射
     */
    public static @NonNull Map<CtxName, Object> parseCtx(@Nullable JsonNode node,
                                                         @NonNull String where) {
        Map<CtxName, Object> ctx = new LinkedHashMap<>();
        if (node == null) {
            return ctx;
        }
        if (!node.isObject()) {
            throw new RoutingConfigException(where, "ctx 必须是对象");
        }
        for (var entry : node.properties()) {
            String name = entry.getKey();
            if (CtxName.TENANT_ID.wireName().equals(name)) {
                throw new RoutingConfigException(where,
                        "样本不得声明 ctx.tenantId：租户只来自服务端（G-6 的身份敞口不在这里重开）");
            }
            CtxName ctxName;
            try {
                ctxName = CtxName.from(name);
            } catch (IllegalArgumentException e) {
                throw new RoutingConfigException(where, e.getMessage(), e);
            }
            JsonNode value = entry.getValue();
            if (value.isArray() || value.isObject() || value.isNull()) {
                throw new RoutingConfigException(where + "." + name, "ctx 的值必须是 JSON 标量");
            }
            ctx.put(ctxName, scalarOf(value, where + "." + name));
        }
        return ctx;
    }

    /** 模式解析（把 {@link IllegalArgumentException} 折算成配置期异常，保持单一异常口径）。 */
    private static @NonNull RoutingMode modeOf(@NonNull String wireName) {
        try {
            return RoutingMode.from(wireName);
        } catch (IllegalArgumentException e) {
            throw new RoutingConfigException("policy.mode", e.getMessage(), e);
        }
    }

    private static @NonNull List<SampleSpec> parseSamples(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new RoutingConfigException("policy.sampleSet", "样本集必须是数组");
        }
        List<SampleSpec> samples = new ArrayList<>();
        for (JsonNode sample : node) {
            samples.add(parseSample(sample));
        }
        return List.copyOf(samples);
    }

    private static @NonNull SampleSpec parseSample(@NonNull JsonNode sample) {
        String where = "policy.sampleSet";
        if (!sample.isObject()) {
            throw new RoutingConfigException(where, "样本必须是对象");
        }
        Set<String> unknown = keysOf(sample);
        unknown.removeAll(SAMPLE_KEYS);
        if (!unknown.isEmpty()) {
            throw new RoutingConfigException(where, "样本含未定义的键: " + unknown);
        }
        String label = requireText(sample.get("label"), where + ".label");
        String expectTarget = requireText(sample.get("expectTarget"), where + ".expectTarget");
        JsonNode input = sample.get("input");
        if (input == null || !input.isObject()) {
            throw new RoutingConfigException(where + ".input",
                    "样本必须带 input 对象（缺 input 的样本只能验兜底，验不了任何规则）");
        }
        Resolution via;
        try {
            via = SampleSpec.viaOf(optionalText(sample.get("via"), where + ".via"));
        } catch (IllegalArgumentException e) {
            throw new RoutingConfigException(where + ".via", e.getMessage(), e);
        }
        return new SampleSpec(label, toMap(input), expectTarget, via,
                parseCtx(sample.get("ctx"), where + ".ctx"));
    }

    /**
     * 解析 dry-run 探针列表（管理台试跑面板的输入，{@link RoutingDryRun} 用）。
     *
     * <p>⚠️ <b>探针文本不是策略的一部分</b>：它只在一次 dry-run 里存在，
     * 不进 {@code definitionJson()}、不进内容指纹。若探针能改写策略，
     * 「校验的那份文本就是运行的那份文本」这条性质当场失效。
     *
     * <p>⚠️ <b>未知键拒绝、{@code ctx.tenantId} 拒绝</b>与样本同口径（复用
     * {@link #parseCtx}）：dry-run 是租户身份能触达的入口之一，
     * 在这里放开「探针自带租户」就等于在管理台里重开 G-6。
     *
     * @param json      探针 JSON 数组原文（可为空数组）
     * @param maxProbes 条数上限（一次 dry-run 会把每条探针都送进引擎，必须有界）
     * @return 探针列表（缺 {@code label} 时按 {@code 探针#序号} 补）
     * @throws RoutingConfigException 不是数组、含未知键、缺 input 或超出上限
     */
    public static @NonNull List<DryRunProbe> parseProbes(@NonNull String json, int maxProbes) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new RoutingConfigException("probes", "探针列表不是合法 JSON: " + e.getMessage(), e);
        }
        if (!node.isArray()) {
            throw new RoutingConfigException("probes", "探针列表必须是数组");
        }
        if (node.size() > maxProbes) {
            throw new RoutingConfigException("probes",
                    "一次 dry-run 最多 " + maxProbes + " 条探针，收到 " + node.size()
                            + "（每条都要过一遍引擎与守卫，无上限的试跑就是一次自伤）");
        }
        List<DryRunProbe> probes = new ArrayList<>();
        int index = 0;
        for (JsonNode probe : node) {
            index++;
            probes.add(parseProbe(probe, index));
        }
        return List.copyOf(probes);
    }

    private static @NonNull DryRunProbe parseProbe(@NonNull JsonNode probe, int index) {
        String where = "probes[" + index + "]";
        if (!probe.isObject()) {
            throw new RoutingConfigException(where, "探针必须是对象");
        }
        Set<String> unknown = keysOf(probe);
        unknown.removeAll(PROBE_KEYS);
        if (!unknown.isEmpty()) {
            throw new RoutingConfigException(where, "探针含未定义的键: " + unknown
                    + "（拼错的键名会被读成「没写」，那比报错更糟）");
        }
        JsonNode input = probe.get("input");
        if (input == null || !input.isObject()) {
            throw new RoutingConfigException(where + ".input",
                    "探针必须带 input 对象（没有输入的试跑只能验「引擎在不看输入时怎么走」）");
        }
        String label = optionalText(probe.get("label"), where + ".label");
        return new DryRunProbe(label == null ? "探针#" + index : label,
                toMap(input),
                optionalText(probe.get("modelCandidate"), where + ".modelCandidate"),
                parseCtx(probe.get("ctx"), where + ".ctx"));
    }

    /** JSON 对象 → JDK Map（{@code RoutingInput} 只认 JDK 结构，不认 JsonNode）。 */
    private static @NonNull Map<String, Object> toMap(@NonNull JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), toPlain(entry.getValue())));
        // ⚠️ 不能用 Map.copyOf：显式写 null 的字段是合法样本（判缺失的 null 算子要靠它），
        //    而 copyOf 会在构造期 NPE —— 合法策略文本崩在解析器里，比报错更糟。
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static @Nullable Object toPlain(@NonNull JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            node.properties().forEach(entry -> nested.put(entry.getKey(), toPlain(entry.getValue())));
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nested));
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(toPlain(item)));
            return java.util.Collections.unmodifiableList(new ArrayList<>(items));
        }
        if (node.isNull()) {
            return null;
        }
        return scalarOf(node, "sample.input");
    }

    private static @NonNull Object scalarOf(@NonNull JsonNode node, @NonNull String where) {
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        throw new RoutingConfigException(where, "只允许 JSON 标量，收到: " + node.getNodeType());
    }

    private static @NonNull Set<String> keysOf(@NonNull JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.properties().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    private static @NonNull String requireText(@Nullable JsonNode node, @NonNull String where) {
        String value = optionalText(node, where);
        if (value == null) {
            throw new RoutingConfigException(where, "缺少必填字段");
        }
        return value;
    }

    private static @Nullable String optionalText(@Nullable JsonNode node, @NonNull String where) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new RoutingConfigException(where, "必须是字符串");
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    private static int requireInt(@Nullable JsonNode node, @NonNull String where) {
        if (node == null || !node.isNumber()) {
            throw new RoutingConfigException(where, "必须是整数");
        }
        Number value = node.numberValue();
        if (!(value instanceof Integer) && !(value instanceof Long)
                && !(value instanceof java.math.BigInteger)) {
            // ⚠️ 版本号截断成 3.7→3 会让两个不同版本共用同一个 policyRef，重放不变式当场失去主键。
            throw new RoutingConfigException(where, "必须是整数，收到: " + value);
        }
        return value.intValue();
    }
}
