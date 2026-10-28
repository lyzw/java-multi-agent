package cn.studykid.multiagent.application.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;
import cn.studykid.multiagent.core.orchestration.RoutingContract;

/**
 * 编排声明的解析与规范化（多级 Agent v1.1 · P1-MA-01）。
 *
 * <p>⚠️ <b>缺省等价于 LEAF</b>（MA-T01）：{@code null} / 空文本 / {@code {}}
 * 一律解析为 {@link AgentOrchestration#leaf()}。旧 Agent 定义行<b>不因缺字段而改写</b>，
 * 兼容读在解析层完成。
 *
 * <p>⚠️ <b>配置期一律 fail-closed</b>：非法编排声明抛
 * {@link OrchestrationConfigException}，绝不「降级成 LEAF 照常发布」——
 * 那会让作者以为多级已生效，实际所有委派都被静默丢弃。
 *
 * <p>⚠️ <b>版本必须固定</b>：子 Agent 引用与路由/结果契约引用都要求
 * {@code id@version} 形式，裸 id 或 {@code latest} 一律拒绝（MA-T02）。
 */
public final class AgentOrchestrationParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 引用分隔符：{@code id@version}。 */
    private static final String REF_SEPARATOR = "@";

    /** 禁止出现的漂移别名。 */
    private static final String DRIFTING_ALIAS = "latest";

    /** 子 Agent 引用对象的已知键（其余键 = 写错了名字，见 {@link #rejectUnknownKeys}）。 */
    private static final List<String> CHILD_KEYS =
            List.of("id", "version", "required", "routingContract");

    /** 路由契约对象的已知键（10 §3.1）。 */
    private static final List<String> CONTRACT_KEYS = List.of(
            "intent", "useWhen", "avoidWhen", "capabilities",
            "inputContract", "priority", "source");

    private AgentOrchestrationParser() {
    }

    /**
     * 解析编排声明。
     *
     * @param json 编排声明 JSON 文本（可为空 → LEAF）
     * @return 规范化后的编排声明
     * @throws OrchestrationConfigException 声明非法（配置期拒绝）
     */
    public static @NonNull AgentOrchestration parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return AgentOrchestration.leaf();
        }
        Map<String, Object> root;
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            root = parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            throw new OrchestrationConfigException("编排声明不是合法 JSON: " + e.getMessage(), e);
        }
        if (root.isEmpty()) {
            return AgentOrchestration.leaf();
        }

        Object rawMode = root.get("mode");
        if (rawMode == null) {
            throw new OrchestrationConfigException("编排声明缺少 mode 字段");
        }
        OrchestrationMode mode = parseMode(rawMode);

        List<ChildAgentRef> children = parseChildren(root.get("childAgentRefs"));
        if (mode == OrchestrationMode.LEAF && !children.isEmpty()) {
            throw new OrchestrationConfigException("LEAF 模式不得声明子 Agent 引用");
        }

        String routingPolicyRef = parsePinnedRef(root.get("routingPolicyRef"), "routingPolicyRef");
        if (mode == OrchestrationMode.ROUTER && routingPolicyRef == null) {
            throw new OrchestrationConfigException("ROUTER 模式必须声明固定版本的路由策略引用");
        }
        String resultContractRef = parsePinnedRef(root.get("resultContractRef"), "resultContractRef");

        // ⚠️ **平台默认值已取消**（评审前置决策 2 · 2026-10-25）：
        //    这三个字段**不再有兜底** —— 缺失就是缺失，由
        //    {@link AgentOrchestration} 的构造器判定「非 LEAF 必须显式声明」，
        //    不满足即抛异常 → 本层包成 {@link OrchestrationConfigException}
        //    → 发布期**拒绝发布**（fail-closed）。
        //    ⚠️ 判据放在领域模型而不是这里：任何构造路径都得过这一关。
        Integer maxDepth = intOrNull(root.get("maxDelegationDepth"), "maxDelegationDepth");
        Integer maxInvocations = intOrNull(root.get("maxChildInvocations"), "maxChildInvocations");
        Long timeoutMs = longOrNull(root.get("timeoutMs"), "timeoutMs");
        DelegationFailurePolicy failurePolicy = parseFailurePolicy(root.get("failurePolicy"));

        try {
            return new AgentOrchestration(mode, children, routingPolicyRef,
                    maxDepth, maxInvocations, timeoutMs, failurePolicy, resultContractRef);
        } catch (IllegalArgumentException e) {
            throw new OrchestrationConfigException("编排声明非法: " + e.getMessage(), e);
        }
    }

    /**
     * 把编排声明序列化为<b>规范化</b> JSON（用于落库与指纹计算）。
     *
     * @param orchestration 编排声明
     * @return 规范化 JSON 文本
     */
    public static @NonNull String toJson(@NonNull AgentOrchestration orchestration) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("mode", orchestration.mode().name());
        if (!orchestration.childAgentRefs().isEmpty()) {
            var children = root.putArray("childAgentRefs");
            for (ChildAgentRef ref : orchestration.childAgentRefs()) {
                ObjectNode node = children.addObject();
                node.put("id", ref.agentId());
                node.put("version", ref.releaseVersion());
                node.put("required", ref.required());
                // ⚠️ 契约必须<b>回写</b>：规范化 JSON 是落库与指纹的载体，
                //    也是管理台读回来再保存的那一份。不回写就是
                //    「解析时丢掉、保存时写没」—— 编辑一次就把契约抹了。
                if (ref.routingContract() != null) {
                    node.set("routingContract", contractNode(ref.routingContract()));
                }
            }
        }
        if (orchestration.routingPolicyRef() != null) {
            root.put("routingPolicyRef", orchestration.routingPolicyRef());
        }
        // ⚠️ **未声明就不写**（LEAF 的三个限制为 null）：
        //    写进去一个 0 或某个默认值，会让「LEAF 没有限制」与
        //    「LEAF 限制为 0」在规范化 JSON 里长得一样。
        if (orchestration.maxDelegationDepth() != null) {
            root.put("maxDelegationDepth", orchestration.maxDelegationDepth().intValue());
        }
        if (orchestration.maxChildInvocations() != null) {
            root.put("maxChildInvocations", orchestration.maxChildInvocations().intValue());
        }
        if (orchestration.timeoutMs() != null) {
            root.put("timeoutMs", orchestration.timeoutMs().longValue());
        }
        root.put("failurePolicy", orchestration.failurePolicy().name());
        if (orchestration.resultContractRef() != null) {
            root.put("resultContractRef", orchestration.resultContractRef());
        }
        return MAPPER.writeValueAsString(root);
    }

    // ── 解析细节 ──────────────────────────────────────────────────

    private static @NonNull OrchestrationMode parseMode(@NonNull Object raw) {
        if (!(raw instanceof String s)) {
            throw new OrchestrationConfigException("mode 必须是字符串");
        }
        try {
            return OrchestrationMode.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrchestrationConfigException("未知的编排模式: " + s, e);
        }
    }

    private static @NonNull DelegationFailurePolicy parseFailurePolicy(@Nullable Object raw) {
        if (raw == null) {
            return DelegationFailurePolicy.FAIL_FAST;
        }
        if (!(raw instanceof String s)) {
            throw new OrchestrationConfigException("failurePolicy 必须是字符串");
        }
        try {
            return DelegationFailurePolicy.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrchestrationConfigException("未知的失败策略: " + s, e);
        }
    }

    private static @NonNull List<ChildAgentRef> parseChildren(@Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new OrchestrationConfigException("childAgentRefs 必须是数组");
        }
        List<ChildAgentRef> refs = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new OrchestrationConfigException("childAgentRefs 元素必须是对象");
            }
            rejectUnknownKeys(map, CHILD_KEYS, "childAgentRefs 元素");
            Object idRaw = map.get("id");
            if (!(idRaw instanceof String id) || id.isBlank()) {
                throw new OrchestrationConfigException("子 Agent 引用缺少 id");
            }
            int version = parseFixedVersion(map.get("version"), id);
            boolean required = true;
            Object requiredRaw = map.get("required");
            if (requiredRaw instanceof Boolean b) {
                required = b;
            } else if (requiredRaw != null) {
                throw new OrchestrationConfigException("子 Agent 引用的 required 必须是布尔值");
            }
            refs.add(new ChildAgentRef(id.trim(), version, required,
                    parseRoutingContract(map.get("routingContract"), id)));
        }
        return List.copyOf(refs);
    }

    /**
     * 把契约写回规范化 JSON（键序与 {@link #CONTRACT_KEYS} 一致）。
     *
     * <p>⚠️ <b>每个字段都要显式写</b>（含空的 {@code inputContract} 与默认的
     * {@code source}）：省略会让「没写」与「写了空」在文本上不可区分，
     * 而这两者对「Router 能看见什么」的影响不同。
     */
    private static @NonNull ObjectNode contractNode(@NonNull RoutingContract contract) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("intent", contract.intent());
        node.set("useWhen", MAPPER.valueToTree(contract.useWhen()));
        node.set("avoidWhen", MAPPER.valueToTree(contract.avoidWhen()));
        node.set("capabilities", MAPPER.valueToTree(contract.capabilities()));
        node.set("inputContract", MAPPER.valueToTree(contract.inputContract()));
        node.put("priority", contract.priority());
        node.put("source", contract.source().name());
        return node;
    }

    /**
     * 解析父侧为该子声明的<b>路由契约</b>（10 §3.1 · S1）。
     *
     * <p>⚠️ <b>缺省为 {@code null}，但绝不「解析失败就当没写」</b>：
     * 写了却写错（少 intent、能力标签不合词表、Schema 用了不认识的关键字）
     * 一律 {@link OrchestrationConfigException} 拒绝发布。
     * 若把它降级成「没写契约」，Router 就回到「只看见一个 id」的老状态，
     * 而配置者以为路由已经按契约生效 —— 这正是本项目反复出现的缺陷形态。
     *
     * @return 契约；该子<b>没有</b>声明时返回 {@code null}
     */
    private static @Nullable RoutingContract parseRoutingContract(@Nullable Object raw,
                                                                   @NonNull String agentId) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的 routingContract 必须是对象");
        }
        rejectUnknownKeys(map, CONTRACT_KEYS, "routingContract");
        try {
            String intent = requireText(map.get("intent"), "intent", agentId);
            return new RoutingContract(intent,
                    stringList(map.get("useWhen"), "useWhen", agentId),
                    stringList(map.get("avoidWhen"), "avoidWhen", agentId),
                    stringList(map.get("capabilities"), "capabilities", agentId),
                    immutableObject(map.get("inputContract"), agentId),
                    priority(map.get("priority")),
                    parseContractSource(map.get("source"), agentId));
        } catch (IllegalArgumentException e) {
            // 领域模型的校验（长度/条数/词表/关键字）在这里收敛成配置期拒绝
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的路由契约非法: " + e.getMessage(), e);
        }
    }

    /** {@code source} 缺省按 {@code INHERITED}（父没声明覆盖 = 用子的底稿）。 */
    private static RoutingContract.@NonNull Source parseContractSource(@Nullable Object raw,
                                                                       @NonNull String agentId) {
        if (raw == null) {
            return RoutingContract.Source.INHERITED;
        }
        if (!(raw instanceof String s)) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的 routingContract.source 必须是字符串");
        }
        try {
            return RoutingContract.Source.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 有未知的路由契约来源: " + s, e);
        }
    }

    /** {@code priority} 缺省 0（最高优先），只接受非负整数。 */
    private static int priority(@Nullable Object raw) {
        if (raw == null) {
            return RoutingContract.MIN_PRIORITY;
        }
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("priority 必须是整数，收到: " + raw);
        }
        return number.intValue();
    }

    private static @NonNull String requireText(@Nullable Object raw, @NonNull String field,
                                                @NonNull String agentId) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的路由契约缺少 " + field);
        }
        return s.trim();
    }

    /** 字符串数组字段：缺省空数组；元素必须是非空白字符串。 */
    private static @NonNull List<String> stringList(@Nullable Object raw, @NonNull String field,
                                                     @NonNull String agentId) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的 " + field + " 必须是数组");
        }
        List<String> items = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new OrchestrationConfigException("子 Agent " + agentId + " 的 "
                        + field + " 每项必须是非空白字符串");
            }
            items.add(s.trim());
        }
        return List.copyOf(items);
    }

    /**
     * 把 {@code inputContract} 收成<b>深度不可变</b>的 JDK 结构。
     *
     * <p>⚠️ 核心层不能引 Jackson，故 Schema 以 {@code Map}/{@code List} 形态进领域模型；
     * 不做深拷贝的话，一份「看起来不可变」的契约里仍藏着可被外部改写的嵌套 Map ——
     * 那会让已算出的闭包指纹与契约实际内容不一致。
     */
    private static @NonNull Map<String, Object> immutableObject(@Nullable Object raw,
                                                                @NonNull String agentId) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new OrchestrationConfigException(
                    "子 Agent " + agentId + " 的 inputContract 必须是对象");
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String k)) {
                throw new OrchestrationConfigException("inputContract 的键必须是字符串");
            }
            copy.put(k, immutableValue(value));
        });
        return Map.copyOf(copy);
    }

    /** 递归收深（数组 → 不可变 List，对象 → 不可变 Map，标量原样）。 */
    private static @Nullable Object immutableValue(@Nullable Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            nested.forEach((k, v) -> {
                if (!(k instanceof String key)) {
                    throw new IllegalArgumentException("Schema 的键必须是字符串");
                }
                copy.put(key, immutableValue(v));
            });
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(immutableValue(item)));
            return List.copyOf(items);
        }
        return value;
    }

    /**
     * 拒绝未声明的键。
     *
     * <p>⚠️ <b>为什么这里必须严格、而顶层可以宽松</b>：
     * 宽松会把「键名写错」降级成「这个字段没写」。对拓扑字段而言那还能被
     * 后续的必填校验兜住；对<b>路由契约</b>而言，写错的后果是 Router
     * 安静地少了一批信息 —— 没有任何一步会报错。
     */
    private static void rejectUnknownKeys(@NonNull Map<?, ?> map, @NonNull List<String> allowed,
                                           @NonNull String where) {
        List<String> unknown = new ArrayList<>();
        map.keySet().forEach(key -> {
            if (!(key instanceof String name)) {
                return;
            }
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new OrchestrationConfigException(where + " 含未定义的键: " + unknown
                    + "（允许的键: " + allowed + "）");
        }
    }

    /**
     * 解析子 Agent 的<b>固定版本号</b>。
     *
     * <p>⚠️ 接受「数字字符串」与「整数」两种写法（配置来源可能是手写 JSON，
     * 也可能是从 DB 版本号序列化而来），但<b>任何非数字</b>（含 {@code latest}）
     * 一律拒绝 —— 那正是漂移的来源（MA-T02）。
     */
    private static int parseFixedVersion(@Nullable Object raw, @NonNull String agentId) {
        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value <= 0 || number.doubleValue() != value) {
                throw new OrchestrationConfigException(
                        "子 Agent " + agentId + " 的版本号必须为正整数，收到: " + raw);
            }
            return value;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new OrchestrationConfigException(
                        "子 Agent " + agentId + " 的版本号不能为空（禁止 latest 漂移别名）");
            }
            if (DRIFTING_ALIAS.equalsIgnoreCase(trimmed)) {
                throw new OrchestrationConfigException(
                        "子 Agent " + agentId + " 的版本必须固定，禁止使用 " + DRIFTING_ALIAS);
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value <= 0) {
                    throw new OrchestrationConfigException(
                            "子 Agent " + agentId + " 的版本号必须为正整数，收到: " + trimmed);
                }
                return value;
            } catch (NumberFormatException e) {
                throw new OrchestrationConfigException(
                        "子 Agent " + agentId + " 的版本号必须是固定版本（数字），收到: " + trimmed, e);
            }
        }
        throw new OrchestrationConfigException(
                "子 Agent " + agentId + " 缺少固定版本号（禁止 latest 漂移别名）");
    }

    /**
     * 解析「固定版本引用」（路由策略 / 结果契约）。
     *
     * <p>⚠️ 引用必须是 {@code id@version} 形式：裸 id 意味着「用当前版本」，
     * 那正是待发布闭包要禁止的漂移。
     */
    private static @Nullable String parsePinnedRef(@Nullable Object raw, @NonNull String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String s)) {
            throw new OrchestrationConfigException(field + " 必须是字符串引用");
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            throw new OrchestrationConfigException(field + " 不能为空字符串");
        }
        int separator = trimmed.lastIndexOf(REF_SEPARATOR);
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw new OrchestrationConfigException(
                    field + " 必须是固定版本引用（id@version），收到: " + trimmed);
        }
        String version = trimmed.substring(separator + 1);
        if (DRIFTING_ALIAS.equalsIgnoreCase(version)) {
            throw new OrchestrationConfigException(
                    field + " 禁止使用 " + DRIFTING_ALIAS + " 等漂移别名");
        }
        return trimmed;
    }

    /**
     * 解析一个<b>可缺省</b>的整数限制。
     *
     * <p>⚠️ 缺失返回 {@code null}（**不是默认值**）——
     * 「没有声明」与「声明成某个默认值」是两件必须能区分的事，
     * 否则上限就会被悄悄继承（评审前置决策 2）。
     */
    private static @Nullable Integer intOrNull(@Nullable Object raw, @NonNull String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new OrchestrationConfigException(field + " 必须是整数");
        }
        return number.intValue();
    }

    /** 解析一个<b>可缺省</b>的长整数限制（缺失 → {@code null}）。 */
    private static @Nullable Long longOrNull(@Nullable Object raw, @NonNull String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new OrchestrationConfigException(field + " 必须是整数");
        }
        return number.longValue();
    }
}
