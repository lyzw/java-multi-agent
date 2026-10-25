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

        int maxDepth = intOrDefault(root.get("maxDelegationDepth"),
                AgentOrchestration.DEFAULT_MAX_DELEGATION_DEPTH, "maxDelegationDepth");
        int maxInvocations = intOrDefault(root.get("maxChildInvocations"),
                AgentOrchestration.DEFAULT_MAX_CHILD_INVOCATIONS, "maxChildInvocations");
        long timeoutMs = longOrDefault(root.get("timeoutMs"),
                AgentOrchestration.DEFAULT_TIMEOUT_MS, "timeoutMs");
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
            }
        }
        if (orchestration.routingPolicyRef() != null) {
            root.put("routingPolicyRef", orchestration.routingPolicyRef());
        }
        root.put("maxDelegationDepth", orchestration.maxDelegationDepth());
        root.put("maxChildInvocations", orchestration.maxChildInvocations());
        root.put("timeoutMs", orchestration.timeoutMs());
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
            refs.add(new ChildAgentRef(id.trim(), version, required));
        }
        return List.copyOf(refs);
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

    private static int intOrDefault(@Nullable Object raw, int defaultValue, @NonNull String field) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            throw new OrchestrationConfigException(field + " 必须是整数");
        }
        return number.intValue();
    }

    private static long longOrDefault(@Nullable Object raw, long defaultValue, @NonNull String field) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            throw new OrchestrationConfigException(field + " 必须是整数");
        }
        return number.longValue();
    }
}
