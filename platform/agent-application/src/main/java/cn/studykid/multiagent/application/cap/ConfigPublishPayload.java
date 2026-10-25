package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;

/**
 * 配置变更方案（P6R4 · 管理端点 · 申请单里保存的「原文」）。
 *
 * <p>⚠️ <b>为什么保存原文而不是保存「解析后的领域对象」</b>：
 * 审批人批的是**这份**方案，执行时执行的也必须是**这份**方案。
 * 若在申请时就把方案翻译成领域对象，执行时再翻译一次，
 * 两次翻译之间的任何差异都会让「批的」和「做的」不是同一件事。
 * 故申请单里存 JSON 原文（{@code cap_config_publish_request.payload}），
 * 执行时**只解析一次**并直接使用。
 *
 * <p>⚠️ <b>本载荷只描述租户级对象</b>（Agent 定义）：
 * 平台级对象（Tool / Skill / MCP Server）无 {@code tenant_id}，
 * 其发布走平台运维通道，**不经租户身份暴露**。
 *
 * @param operation          变更类型
 * @param scope              作用域（**本通道只接受 {@code TENANT}**）
 * @param expectedPublishSeq CAS 基线（客户端读到的当前发布序号；{@code null} = 未指定）
 * @param agents             本次发布的 Agent 定义草稿（仅 {@code PUBLISH} 用）
 * @param targetReleaseId    回滚目标发布（仅 {@code ROLLBACK} 用）
 * @param lifecycle          生命周期迁移参数（仅 {@code LIFECYCLE_TRANSITION} 用）
 * @param resultContracts    本次发布的输出契约草稿（仅 {@code PUBLISH} 用 · 多级 Agent v1.1）
 * @param routingPolicies    本次发布的路由策略草稿（仅 {@code PUBLISH} 用 · 多级 Agent v1.1 第十四批）
 */
public record ConfigPublishPayload(
        ConfigPublishRequest.@NonNull Operation operation,
        ConfigRelease.@NonNull Scope scope,
        @Nullable Long expectedPublishSeq,
        @NonNull List<AgentSpec> agents,
        @Nullable Long targetReleaseId,
        @Nullable LifecycleSpec lifecycle,
        @NonNull List<ResultContractSpec> resultContracts,
        @NonNull List<RoutingPolicySpec> routingPolicies) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 兼容构造器：<b>无输出契约、无路由策略</b>的变更方案（多级 Agent 之前的形态）。
     */
    public ConfigPublishPayload(ConfigPublishRequest.@NonNull Operation operation,
                                ConfigRelease.@NonNull Scope scope,
                                @Nullable Long expectedPublishSeq,
                                @NonNull List<AgentSpec> agents,
                                @Nullable Long targetReleaseId,
                                @Nullable LifecycleSpec lifecycle) {
        this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle, List.of(),
                List.of());
    }

    /**
     * 兼容构造器：<b>有输出契约、无路由策略</b>的变更方案（第十四批之前的形态）。
     */
    public ConfigPublishPayload(ConfigPublishRequest.@NonNull Operation operation,
                                ConfigRelease.@NonNull Scope scope,
                                @Nullable Long expectedPublishSeq,
                                @NonNull List<AgentSpec> agents,
                                @Nullable Long targetReleaseId,
                                @Nullable LifecycleSpec lifecycle,
                                @NonNull List<ResultContractSpec> resultContracts) {
        this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle,
                resultContracts, List.of());
    }

    public ConfigPublishPayload {
        if (operation == null) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        agents = agents == null ? List.of() : List.copyOf(agents);
        resultContracts = resultContracts == null ? List.of() : List.copyOf(resultContracts);
        routingPolicies = routingPolicies == null ? List.of() : List.copyOf(routingPolicies);
    }

    /**
     * 路由策略草稿（<b>只含客户端可提交的语义字段</b>）。
     *
     * <p>⚠️ <b>刻意不含 version / configFingerprint / createdAt / publishedAt</b>：
     * 与 Agent 草稿、输出契约草稿同口径 —— 版本号由服务端按「现有最大版本 + 1」分配，
     * 内容指纹由服务端计算（{@link CapObjectFingerprint}），
     * 时间戳由服务端填写。客户端若能指定这些，就等于能指定「内容没变」这个证明。
     *
     * <p>⚠️ <b>策略枚举由客户端提交</b>：这是<b>内容</b>而非「证明」——
     * 客户端当然可以决定自己的路由规则长什么样（那是它要发布的东西）；
     * 但服务端仍会校验目标集合的合法性（非空、不重复）并计算指纹。
     *
     * @param policyId 策略标识（可读字符串）
     * @param strategy 选择策略名（{@code EXPLICIT_TARGETS} / {@code ORDERED_FALLBACK}）
     * @param targets  允许的目标 Agent 标识（有序）
     */
    public record RoutingPolicySpec(@NonNull String policyId,
                                     @NonNull String strategy,
                                     @NonNull List<String> targets) {

        public RoutingPolicySpec {
            if (policyId == null || policyId.isBlank()) {
                throw new IllegalArgumentException("policyId 不能为空");
            }
            if (strategy == null || strategy.isBlank()) {
                throw new IllegalArgumentException("strategy 不能为空");
            }
            targets = targets == null ? List.of() : List.copyOf(targets);
            if (targets.isEmpty()) {
                // ⚠️ 空目标集合 ≠ 「允许任意」：策略必须**显式**声明允许哪些目标。
                throw new IllegalArgumentException("targets 不能为空（策略必须显式声明允许的目标）");
            }
            for (String target : targets) {
                if (target == null || target.isBlank()) {
                    throw new IllegalArgumentException("targets 元素不能为空");
                }
            }
        }
    }

    /**
     * 输出契约草稿（<b>只含客户端可提交的语义字段</b>）。
     *
     * <p>⚠️ <b>刻意不含 version / configFingerprint / createdAt / publishedAt</b>：
     * 与 Agent 草稿同口径 —— 版本号由服务端按「现有最大版本 + 1」分配，
     * 内容指纹由服务端计算（{@link CapObjectFingerprint}），
     * 时间戳由服务端填写。
     * 客户端若能指定这些，就等于能指定「内容没变」这个证明。
     *
     * @param contractId 契约标识
     * @param schemaJson 契约定义（JSON Schema 文本）
     */
    public record ResultContractSpec(@NonNull String contractId,
                                      @NonNull String schemaJson) {

        public ResultContractSpec {
            if (contractId == null || contractId.isBlank()) {
                throw new IllegalArgumentException("contractId 不能为空");
            }
            if (schemaJson == null || schemaJson.isBlank()) {
                // ⚠️ 空契约 ≠ 任意结构：契约必须**显式**声明「允许任意」，
                //    否则「填坏了」与「没填」在存储层就分不开。
                throw new IllegalArgumentException("schemaJson 不能为空");
            }
        }
    }

    /**
     * Agent 定义草稿（<b>只含客户端可提交的语义字段</b>）。
     *
     * <p>⚠️ <b>刻意不含 version / configFingerprint / createdAt / publishedAt</b>：
     * 版本号由服务端按「现有最大版本 + 1」分配，
     * 内容指纹由服务端计算（{@link CapObjectFingerprint}），
     * 时间戳由服务端填写。
     * 客户端若能指定这三者，就等于能指定「内容没变」这个证明。
     *
     * @param agentId        Agent 标识
     * @param name           名称
     * @param description    描述
     * @param roles          角色集
     * @param enabledSkills  启用的 Skill 集
     * @param skillBundles   Skill 包集
     * @param capabilityTags 能力标签集
     * @param overrides      收紧覆盖（JSON 文本）
     */
    public record AgentSpec(@NonNull String agentId,
                            @Nullable String name,
                            @Nullable String description,
                            @NonNull List<String> roles,
                            @NonNull List<String> enabledSkills,
                            @NonNull List<String> skillBundles,
                            @NonNull List<String> capabilityTags,
                            @NonNull String overrides,
                            @NonNull String orchestration) {

        /**
         * 兼容构造器：<b>无编排声明</b>的 Agent 草稿（多级 Agent 之前的提交形态）。
         *
         * <p>⚠️ 缺省 {@code "{}"} = LEAF（MA-T01）：不是「不限制」，而是「不允许委派」。
         */
        public AgentSpec(@NonNull String agentId,
                         @Nullable String name,
                         @Nullable String description,
                         @NonNull List<String> roles,
                         @NonNull List<String> enabledSkills,
                         @NonNull List<String> skillBundles,
                         @NonNull List<String> capabilityTags,
                         @NonNull String overrides) {
            this(agentId, name, description, roles, enabledSkills, skillBundles,
                    capabilityTags, overrides, "{}");
        }

        public AgentSpec {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId 不能为空");
            }
            if (overrides == null || overrides.isBlank()) {
                throw new IllegalArgumentException("overrides 不能为空");
            }
            if (orchestration == null || orchestration.isBlank()) {
                throw new IllegalArgumentException(
                        "orchestration 不能为空（缺省用 {} 表示 LEAF）");
            }
            roles = roles == null ? List.of() : List.copyOf(roles);
            enabledSkills = enabledSkills == null ? List.of() : List.copyOf(enabledSkills);
            skillBundles = skillBundles == null ? List.of() : List.copyOf(skillBundles);
            capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        }
    }

    /**
     * 生命周期迁移参数（§6.6）。
     *
     * @param type        对象类型标识（{@code tool} / {@code skill} / {@code mcp_server} / {@code agent}）
     * @param objectId    对象逻辑 id
     * @param version     版本号
     * @param expectedFrom 期望的原状态（CAS 前置条件）
     * @param to          目标状态
     */
    public record LifecycleSpec(@NonNull String type,
                                @NonNull String objectId,
                                int version,
                                @NonNull String expectedFrom,
                                @NonNull String to) {

        public LifecycleSpec {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type 不能为空");
            }
            if (objectId == null || objectId.isBlank()) {
                throw new IllegalArgumentException("objectId 不能为空");
            }
            if (version <= 0) {
                throw new IllegalArgumentException("version 必须为正数: " + version);
            }
            if (expectedFrom == null || expectedFrom.isBlank()) {
                throw new IllegalArgumentException("expectedFrom 不能为空");
            }
            if (to == null || to.isBlank()) {
                throw new IllegalArgumentException("to 不能为空");
            }
        }
    }

    /**
     * 序列化为 JSON 文本。
     *
     * @return JSON 文本
     */
    public @NonNull String toJson() {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * 从 JSON 文本反序列化。
     *
     * @param json JSON 文本
     * @return 变更方案
     * @throws IllegalArgumentException JSON 非法或结构不符
     */
    public static @NonNull ConfigPublishPayload fromJson(@NonNull String json) {
        try {
            return MAPPER.readValue(json, ConfigPublishPayload.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("变更方案不是合法 JSON 或结构不符: " + e.getMessage(), e);
        }
    }

    /**
     * 生成一个「回滚」方案。
     *
     * @param targetReleaseId    回滚目标发布 id
     * @param expectedPublishSeq CAS 基线
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload rollback(long targetReleaseId,
                                                          @Nullable Long expectedPublishSeq) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.ROLLBACK,
                ConfigRelease.Scope.TENANT, expectedPublishSeq, List.of(), targetReleaseId, null,
                List.of(), List.of());
    }

    /**
     * 生成一个「生命周期迁移」方案。
     *
     * @param spec 迁移参数
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload lifecycle(@NonNull LifecycleSpec spec) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.LIFECYCLE_TRANSITION,
                ConfigRelease.Scope.TENANT, null, List.of(), null, spec, List.of(), List.of());
    }

    /**
     * 生成一个「发布」方案。
     *
     * @param agents             Agent 定义草稿
     * @param expectedPublishSeq CAS 基线
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload publish(@NonNull List<AgentSpec> agents,
                                                         @Nullable Long expectedPublishSeq) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.PUBLISH,
                ConfigRelease.Scope.TENANT, expectedPublishSeq, agents, null, null, List.of(),
                List.of());
    }

    /**
     * 生成一个「发布」方案（同时登记输出契约）。
     *
     * <p>⚠️ <b>契约与 Agent 在同一次发布里</b>：这是「同发布闭包」的完整性要求 ——
     * 否则「先发契约、再发引用它的 Agent」会横跨两个发布批次，
     * 契约的审批与 Agent 的审批就不是同一件事了。
     *
     * @param agents             Agent 定义草稿
     * @param resultContracts    输出契约草稿
     * @param expectedPublishSeq CAS 基线
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload publish(
            @NonNull List<AgentSpec> agents,
            @NonNull List<ResultContractSpec> resultContracts,
            @Nullable Long expectedPublishSeq) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.PUBLISH,
                ConfigRelease.Scope.TENANT, expectedPublishSeq, agents, null, null,
                resultContracts, List.of());
    }

    /**
     * 生成一个「发布」方案（同时登记输出契约与路由策略）。
     *
     * <p>⚠️ <b>策略与 Agent 也在同一次发布里</b>：与契约同理 ——
     * ROUTER Agent 的 {@code routingPolicyRef} 指向的策略若不在本批次，
     * 「批的」与「生效的」就不是同一件事。
     *
     * @param agents             Agent 定义草稿
     * @param resultContracts    输出契约草稿
     * @param routingPolicies    路由策略草稿
     * @param expectedPublishSeq CAS 基线
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload publish(
            @NonNull List<AgentSpec> agents,
            @NonNull List<ResultContractSpec> resultContracts,
            @NonNull List<RoutingPolicySpec> routingPolicies,
            @Nullable Long expectedPublishSeq) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.PUBLISH,
                ConfigRelease.Scope.TENANT, expectedPublishSeq, agents, null, null,
                resultContracts, routingPolicies);
    }

    /**
     * 把方案转成便于审计的摘要（<b>不含用户自由文本</b>）。
     *
     * @return 摘要 JSON 文本
     */
    public @NonNull String summary() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("operation", operation.name());
        node.put("agentCount", agents.size());
        node.put("resultContractCount", resultContracts.size());
        node.put("routingPolicyCount", routingPolicies.size());
        node.put("hasExpectedPublishSeq", expectedPublishSeq != null);
        node.put("hasTargetReleaseId", targetReleaseId != null);
        node.put("hasLifecycle", lifecycle != null);
        return MAPPER.writeValueAsString(node);
    }
}
