package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
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
 * <p>⚠️ <b>本载荷可描述租户级与平台级两类对象</b>（第三十四批起）：
 * <ul>
 *   <li><b>租户级</b>（Agent / 输出契约 / 路由策略）—— 走应用角色通道；</li>
 *   <li><b>平台级</b>（Tool / Skill / MCP Server）—— 走<b>平台运维通道</b>
 *       （BYPASSRLS 凭据 + {@code PLATFORM_ADMIN} 闸门）。</li>
 * </ul>
 * ⚠️ <b>作用域与对象集合必须一致</b>：{@code TENANT} 只带租户级对象，
 * {@code PLATFORM} 只带平台级对象 —— 混装会被配置期门禁拒绝
 * （{@code ConfigValidationGate}），且发布头的作用域会与内容自相矛盾。
 *
 * @param operation          变更类型
 * @param scope              作用域（{@code TENANT} 或 {@code PLATFORM}）
 * @param expectedPublishSeq CAS 基线（客户端读到的当前发布序号；{@code null} = 未指定）
 * @param agents             本次发布的 Agent 定义草稿（仅 {@code PUBLISH} 用）
 * @param targetReleaseId    回滚目标发布（仅 {@code ROLLBACK} 用）
 * @param lifecycle          生命周期迁移参数（仅 {@code LIFECYCLE_TRANSITION} 用）
 * @param resultContracts    本次发布的输出契约草稿（仅 {@code PUBLISH} 用 · 多级 Agent v1.1）
 * @param routingPolicies    本次发布的路由策略草稿（仅 {@code PUBLISH} 用 · 多级 Agent v1.1 第十四批）
 * @param tools              本次发布的 Tool 定义草稿（仅 {@code PUBLISH} 用 · 第三十四批）
 * @param skills             本次发布的 Skill 定义草稿（仅 {@code PUBLISH} 用 · 第三十四批）
 * @param mcpServers         本次发布的 MCP Server 定义草稿（仅 {@code PUBLISH} 用 · 第三十四批）
 */
public record ConfigPublishPayload(
        ConfigPublishRequest.@NonNull Operation operation,
        ConfigRelease.@NonNull Scope scope,
        @Nullable Long expectedPublishSeq,
        @NonNull List<AgentSpec> agents,
        @Nullable Long targetReleaseId,
        @Nullable LifecycleSpec lifecycle,
        @NonNull List<ResultContractSpec> resultContracts,
        @NonNull List<RoutingPolicySpec> routingPolicies,
        @NonNull List<ToolSpec> tools,
        @NonNull List<SkillSpec> skills,
        @NonNull List<McpServerSpec> mcpServers) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 兼容构造器：<b>无输出契约、无路由策略、无平台对象</b>的变更方案
     * （多级 Agent 之前的形态）。
     */
    public ConfigPublishPayload(ConfigPublishRequest.@NonNull Operation operation,
                                ConfigRelease.@NonNull Scope scope,
                                @Nullable Long expectedPublishSeq,
                                @NonNull List<AgentSpec> agents,
                                @Nullable Long targetReleaseId,
                                @Nullable LifecycleSpec lifecycle) {
        this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle, List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 兼容构造器：<b>有输出契约、无路由策略、无平台对象</b>的变更方案
     * （第十四批之前的形态）。
     */
    public ConfigPublishPayload(ConfigPublishRequest.@NonNull Operation operation,
                                ConfigRelease.@NonNull Scope scope,
                                @Nullable Long expectedPublishSeq,
                                @NonNull List<AgentSpec> agents,
                                @Nullable Long targetReleaseId,
                                @Nullable LifecycleSpec lifecycle,
                                @NonNull List<ResultContractSpec> resultContracts) {
        this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle,
                resultContracts, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 兼容构造器：<b>有输出契约与路由策略、无平台对象</b>的变更方案
     * （第十四批之后的形态 · 第三十四批之前的最新形态）。
     */
    public ConfigPublishPayload(ConfigPublishRequest.@NonNull Operation operation,
                                ConfigRelease.@NonNull Scope scope,
                                @Nullable Long expectedPublishSeq,
                                @NonNull List<AgentSpec> agents,
                                @Nullable Long targetReleaseId,
                                @Nullable LifecycleSpec lifecycle,
                                @NonNull List<ResultContractSpec> resultContracts,
                                @NonNull List<RoutingPolicySpec> routingPolicies) {
        this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle,
                resultContracts, routingPolicies, List.of(), List.of(), List.of());
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
        tools = tools == null ? List.of() : List.copyOf(tools);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
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
     * Tool 定义草稿（<b>只含客户端可提交的语义字段</b> · 第三十四批）。
     *
     * <p>⚠️ <b>刻意不含 version / configFingerprint / createdAt / publishedAt</b>：
     * 与 Agent / 契约 / 策略草稿同口径 —— 版本号由服务端按「现有最大版本 + 1」分配，
     * 内容指纹由服务端计算，时间戳由服务端填写。
     *
     * <p>⚠️ <b>{@code kind} 与 MCP 绑定必须自洽</b>：
     * {@code kind=mcp} 必须同时给出 {@code mcpServerId} 与 {@code mcpToolName}，
     * {@code kind=native} 必须两者都不给 —— DB 层有同款 CHECK 兜底
     * （{@code cap_tool_def_mcp_binding_ck}）。
     *
     * <p>⚠️ <b>{@code kind=http} 与 HTTP 配置必须自洽</b>（第三十七批）：
     * {@code kind=http} 必须同时给出 {@code httpMethod} / {@code httpUrl} / {@code httpTimeoutMs}；
     * 其余 kind 必须三者都不给。DB 层有同款 CHECK 兜底
     * （{@code cap_tool_def_http_binding_ck}）。
     *
     * @param httpMethod    远程 HTTP 请求方法（仅 kind=http 时非空）
     * @param httpUrl       远程 HTTP 请求目标 URL（仅 kind=http 时非空）
     * @param httpTimeoutMs 远程 HTTP 请求超时（毫秒；仅 kind=http 时非空）
     */
    public record ToolSpec(@NonNull String toolId,
                            @Nullable String name,
                            @NonNull String kind,
                            @NonNull String riskLevel,
                            @Nullable String mcpServerId,
                            @Nullable String mcpToolName,
                            @Nullable String httpMethod,
                            @Nullable String httpUrl,
                            @Nullable Integer httpTimeoutMs,
                            @NonNull String inputSchema,
                            @NonNull String paramPolicies) {

        public ToolSpec {
            if (toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("toolId 不能为空");
            }
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("kind 不能为空");
            }
            if (riskLevel == null || riskLevel.isBlank()) {
                throw new IllegalArgumentException("riskLevel 不能为空");
            }
            if (inputSchema == null || inputSchema.isBlank()) {
                // ⚠️ 空 Schema ≠ 「允许任意参数」：参数域由 paramPolicies 收紧，
                //    两者都必须**显式**声明，缺省无法区分「没配」与「配成空」。
                throw new IllegalArgumentException("inputSchema 不能为空");
            }
            if (paramPolicies == null || paramPolicies.isBlank()) {
                throw new IllegalArgumentException("paramPolicies 不能为空");
            }
            boolean mcp = "mcp".equalsIgnoreCase(kind);
            boolean http = "http".equalsIgnoreCase(kind);
            boolean hasBinding = mcpServerId != null && !mcpServerId.isBlank();
            boolean hasToolName = mcpToolName != null && !mcpToolName.isBlank();
            if (mcp && !(hasBinding && hasToolName)) {
                throw new IllegalArgumentException(
                        "kind=mcp 的 Tool 必须同时声明 mcpServerId 与 mcpToolName");
            }
            if (!mcp && (hasBinding || hasToolName)) {
                // ⚠️ kind=http 也走这一支 —— HTTP 工具携带 MCP 绑定 = 「目标不唯一」。
                throw new IllegalArgumentException(
                        "kind=" + kind + " 的 Tool 不得携带 MCP 绑定"
                                + "（MCP 目标由固定清单约束，HTTP 目标由配置给出，两者同时存在则目标不唯一）");
            }
            // ⚠️ HTTP 三件套：要么齐全，要么全无。
            boolean hasHttpMethod = httpMethod != null && !httpMethod.isBlank();
            boolean hasHttpUrl = httpUrl != null && !httpUrl.isBlank();
            boolean hasHttpTimeout = httpTimeoutMs != null;
            if (http && !(hasHttpMethod && hasHttpUrl && hasHttpTimeout)) {
                throw new IllegalArgumentException(
                        "kind=http 的 Tool 必须同时声明 httpMethod、httpUrl 与 httpTimeoutMs");
            }
            if (!http && (hasHttpMethod || hasHttpUrl || hasHttpTimeout)) {
                throw new IllegalArgumentException(
                        "kind=" + kind + " 的 Tool 不得携带 HTTP 配置");
            }
            if (http) {
                // ⚠️ 形状合法不等于取值合法 —— 取值校验交给 CapHttpToolConfig
                //    （方法白名单 / 超时上下限），此处只挡住明显非法的形态。
                if (!CapHttpToolConfig.ALLOWED_METHODS.contains(
                        httpMethod.trim().toUpperCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "不允许的 HTTP 方法: " + httpMethod
                                    + "（合法值: " + CapHttpToolConfig.ALLOWED_METHODS.stream()
                                            .sorted().toList() + "）");
                }
                if (httpTimeoutMs <= 0 || httpTimeoutMs > CapHttpToolConfig.MAX_TIMEOUT_MS) {
                    throw new IllegalArgumentException(
                            "HTTP 超时必须在 1 ~ " + CapHttpToolConfig.MAX_TIMEOUT_MS
                                    + " 毫秒之间: " + httpTimeoutMs);
                }
            }
        }

        /**
         * 兼容构造器（<b>非 HTTP 工具</b>）—— 既有构造点不必逐个补参数。
         *
         * @param toolId Tool 标识
         * @param name 显示名
         * @param kind Tool 种类
         * @param riskLevel 风险等级
         * @param mcpServerId MCP Server 标识（仅 kind=mcp）
         * @param mcpToolName MCP 远端操作名（仅 kind=mcp）
         * @param inputSchema 入参 JSON Schema 文本
         * @param paramPolicies 参数策略 JSON 文本
         */
        public ToolSpec(@NonNull String toolId,
                        @Nullable String name,
                        @NonNull String kind,
                        @NonNull String riskLevel,
                        @Nullable String mcpServerId,
                        @Nullable String mcpToolName,
                        @NonNull String inputSchema,
                        @NonNull String paramPolicies) {
            this(toolId, name, kind, riskLevel, mcpServerId, mcpToolName,
                    null, null, null, inputSchema, paramPolicies);
        }
    }

    /**
     * Skill 定义草稿（<b>只含客户端可提交的语义字段</b> · 第三十四批）。
     *
     * <p>⚠️ <b>{@code toolAllowlist} 的 {@code null} 与 {@code []} 语义不同</b>
     * （§4.4）：{@code null} = 未配置（取 ServerAllowedOps 全集），
     * {@code []} = 显式不允许任何操作。
     * ⚠️ <b>不可归一化</b>：把 {@code null} 归一成 {@code []} 会让
     * 「没配」变成「什么都不许」—— 前者可用、后者直接不可用，
     * 这是**可用性事故**而不是保守收紧。
     */
    public record SkillSpec(@NonNull String skillId,
                             @Nullable String name,
                             @Nullable String description,
                             @NonNull List<String> declaredTools,
                             @NonNull List<McpDeclarationSpec> declaredMcp,
                             @NonNull String riskCeiling,
                             @NonNull String paramPolicies,
                             @NonNull List<String> requiredCaps) {

        public SkillSpec {
            if (skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("skillId 不能为空");
            }
            if (riskCeiling == null || riskCeiling.isBlank()) {
                throw new IllegalArgumentException("riskCeiling 不能为空");
            }
            if (paramPolicies == null || paramPolicies.isBlank()) {
                throw new IllegalArgumentException("paramPolicies 不能为空");
            }
            declaredTools = declaredTools == null ? List.of() : List.copyOf(declaredTools);
            declaredMcp = declaredMcp == null ? List.of() : List.copyOf(declaredMcp);
            requiredCaps = requiredCaps == null ? List.of() : List.copyOf(requiredCaps);
        }
    }

    /**
     * Skill 对 MCP server 的声明草稿（第三十四批）。
     *
     * <p>⚠️ <b>{@code toolAllowlist} 可空，且 {@code null} ≠ {@code []}</b>：
     * {@code null} = 未配置（取 ServerAllowedOps 全集）；
     * {@code []} = 显式不允许任何操作。两者语义不同，**不可**用同一默认值混淆。
     */
    public record McpDeclarationSpec(@NonNull String serverId,
                                      @Nullable List<String> toolAllowlist) {

        public McpDeclarationSpec {
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("serverId 不能为空");
            }
            // ⚠️ **刻意不把 null 归一成 []**：归一化会让「未配置」变成「显式禁止一切」。
            toolAllowlist = toolAllowlist == null ? null : List.copyOf(toolAllowlist);
        }
    }

    /**
     * MCP Server 定义草稿（<b>只含客户端可提交的语义字段</b> · 第三十四批）。
     *
     * <p>⚠️ <b>{@code dynamicDiscovery} 刻意不在草稿里</b>：V1 固定 {@code false}，
     * DB 层有 CHECK 兜底（写入 {@code true} 即拒）—— 让客户端提交一个必然被拒的值没有意义。
     *
     * <p>⚠️ <b>{@code credentialRef} 是凭据库定位符，不是凭据材料</b>：
     * 材料永不入库、永不入指纹、永不入日志（§4.2 / §6.4）。
     * ⚠️ <b>它也不参与闭包指纹</b> —— 否则凭据轮换会让所有在途 Run 集体失效。
     */
    public record McpServerSpec(@NonNull String serverId,
                                 @NonNull String transport,
                                 @Nullable String endpoint,
                                 @Nullable String command,
                                 @NonNull String authKind,
                                 @NonNull String trustTier,
                                 @Nullable List<String> exposedToolAllowlist,
                                 @NonNull String connectionRisk,
                                 @Nullable String credentialRef) {

        public McpServerSpec {
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("serverId 不能为空");
            }
            if (transport == null || transport.isBlank()) {
                throw new IllegalArgumentException("transport 不能为空");
            }
            if (authKind == null || authKind.isBlank()) {
                throw new IllegalArgumentException("authKind 不能为空");
            }
            if (trustTier == null || trustTier.isBlank()) {
                throw new IllegalArgumentException("trustTier 不能为空");
            }
            if (connectionRisk == null || connectionRisk.isBlank()) {
                throw new IllegalArgumentException("connectionRisk 不能为空");
            }
            // ⚠️ **刻意不把 null 归一成 []**：exposedToolAllowlist 的 null（仅连接级授权）
            //    与 []（显式空清单 = 任何操作都不允许）语义不同（§4.4）。
            exposedToolAllowlist = exposedToolAllowlist == null
                    ? null : List.copyOf(exposedToolAllowlist);
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
     * 生成一个「平台级发布」方案（第三十四批）。
     *
     * <p>⚠️ <b>作用域固定为 {@code PLATFORM}</b>，且**不得携带任何租户级对象** ——
     * 平台级发布走平台运维通道（BYPASSRLS + {@code PLATFORM_ADMIN} 闸门），
     * 混装租户级对象会被配置期门禁拒绝。
     *
     * @param tools              Tool 定义草稿
     * @param skills             Skill 定义草稿
     * @param mcpServers         MCP Server 定义草稿
     * @param expectedPublishSeq CAS 基线
     * @return 变更方案
     */
    public static @NonNull ConfigPublishPayload publishPlatform(
            @NonNull List<ToolSpec> tools,
            @NonNull List<SkillSpec> skills,
            @NonNull List<McpServerSpec> mcpServers,
            @Nullable Long expectedPublishSeq) {
        return new ConfigPublishPayload(ConfigPublishRequest.Operation.PUBLISH,
                ConfigRelease.Scope.PLATFORM, expectedPublishSeq, List.of(), null, null,
                List.of(), List.of(), tools, skills, mcpServers);
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
        node.put("toolCount", tools.size());
        node.put("skillCount", skills.size());
        node.put("mcpServerCount", mcpServers.size());
        node.put("hasExpectedPublishSeq", expectedPublishSeq != null);
        node.put("hasTargetReleaseId", targetReleaseId != null);
        node.put("hasLifecycle", lifecycle != null);
        return MAPPER.writeValueAsString(node);
    }
}
