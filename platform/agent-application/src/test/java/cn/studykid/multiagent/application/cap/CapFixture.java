package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;
import cn.studykid.multiagent.ports.security.RolePermissionPort;

/**
 * P1 单元测试夹具：<b>内存数据 + 端口工厂</b>。
 *
 * <p>⚠️ <b>端口必须分对象实现</b>：{@code CapSkillDefPort} / {@code CapToolDefPort} /
 * {@code CapMcpServerDefPort} 都有 {@code find(String, int)}，
 * 返回类型却各不相同 —— 同一个类无法同时实现这三个接口（Java 不允许
 * 「同名同参但返回类型不相关」的继承）。故此处用<b>匿名内部类</b>逐个产出。
 *
 * <p>⚠️ <b>只用于单元测试</b>：真实 PG 上的行为由 {@code InvocationValidatorIT} 覆盖。
 */
final class CapFixture {

    final Map<String, CapSkillDef> skills = new LinkedHashMap<>();
    final Map<String, CapToolDef> tools = new LinkedHashMap<>();
    final Map<String, CapMcpServerDef> servers = new LinkedHashMap<>();
    final Map<String, CapAgentDef> agents = new LinkedHashMap<>();
    /** 输出契约定义行（多级 Agent v1.1 · FR-027）。 */
    final Map<String, CapResultContractDef> resultContracts = new LinkedHashMap<>();
    final Map<String, CapRoutingPolicyDef> routingPolicies = new LinkedHashMap<>();
    final Map<String, Set<String>> manifests = new LinkedHashMap<>();
    /** 标记为「清单不可用」的 server（运行故障态，与「清单为空」语义不同）。 */
    final Set<String> unavailableManifests = new LinkedHashSet<>();
    final Map<String, List<Set<String>>> manifestSequences = new LinkedHashMap<>();
    final Map<String, Integer> manifestCallCounts = new LinkedHashMap<>();
    final Map<String, Long> authzVersions = new LinkedHashMap<>();
    final Map<String, CapabilitySnapshot> snapshots = new LinkedHashMap<>();
    final Map<String, Set<String>> rolePermissions = new LinkedHashMap<>();
    /**
     * 租户级「当前发布指针」所指的 Agent 版本（P0-5）。
     *
     * <p>键：{@code tenantId/agentId}；值：指针指向的发布头里声明的精确版本。
     * 未登记 = 该发布头未引用此 Agent → 解析时回退到「最新已发布版本行」。
     */
    final Map<String, Integer> publishedAgentVersions = new LinkedHashMap<>();

    /** 置为 true 时 {@code AuthzVersionPort.require} 抛「版本服务不可用」。 */
    boolean authzUnavailable;

    // ── 夹具构造 ──────────────────────────────────────────────────

    @NonNull CapFixture withSkill(@NonNull String id, int version,
                                   @NonNull CapabilityRiskLevel ceiling,
                                   @NonNull List<String> declaredTools) {
        return withSkill(id, version, ceiling, declaredTools, List.of(), "[]");
    }

    @NonNull CapFixture withSkill(@NonNull String id, int version,
                                   @NonNull CapabilityRiskLevel ceiling,
                                   @NonNull List<String> declaredTools,
                                   @NonNull List<CapSkillDef.McpDeclaration> declaredMcp,
                                   @NonNull String paramPolicies) {
        skills.put(key(id, version), new CapSkillDef(id, version, id, id + " desc",
                declaredTools, declaredMcp, ceiling, paramPolicies, List.of(),
                "fp-skill-" + id + "-" + version, 1, Instant.EPOCH,
                Instant.EPOCH));
        return this;
    }

    @NonNull CapFixture withTool(@NonNull String id, int version,
                                  @NonNull CapabilityRiskLevel risk,
                                  @NonNull String paramPolicies) {
        return withTool(id, version, CapToolKind.NATIVE, risk, null, null, paramPolicies);
    }

    @NonNull CapFixture withTool(@NonNull String id, int version,
                                  @NonNull CapToolKind kind,
                                  @NonNull CapabilityRiskLevel risk,
                                  @Nullable String mcpServerId,
                                  @Nullable String mcpToolName,
                                  @NonNull String paramPolicies) {
        tools.put(key(id, version), new CapToolDef(id, version, id, kind, risk,
                mcpServerId, mcpToolName, "{}", paramPolicies,
                "fp-tool-" + id + "-" + version, 1, Instant.EPOCH,
                Instant.EPOCH));
        return this;
    }

    /**
     * @param exposedToolAllowlist {@code null} = 未配置；{@code []} = 显式空清单（§4.4）
     */
    @NonNull CapFixture withServer(@NonNull String id, int version,
                                    @Nullable List<String> exposedToolAllowlist) {
        return withServer(id, version, exposedToolAllowlist,
                "https://mcp.internal/" + id, McpTrustTier.INTERNAL);
    }

    /**
     * 登记一个 MCP Server（可指定端点与信任级别 —— P4 端点安全用例需要）。
     *
     * @param id              Server 逻辑 id
     * @param version         版本号
     * @param exposedToolAllowlist 暴露清单；{@code null} = 未配置；{@code []} = 显式空清单（§4.4）
     * @param endpoint        端点 URL
     * @param tier            信任级别
     */
    @NonNull CapFixture withServer(@NonNull String id, int version,
                                    @Nullable List<String> exposedToolAllowlist,
                                    @NonNull String endpoint,
                                    @NonNull McpTrustTier tier) {
        servers.put(key(id, version), new CapMcpServerDef(id, version,
                CapMcpServerDef.Transport.HTTP, endpoint, null,
                CapMcpServerDef.AuthKind.TOKEN, tier,
                exposedToolAllowlist, CapabilityRiskLevel.WRITE_EXTERNAL, false,
                "fp-server-" + id + "-" + version, "cred-ref-" + id, 1,
                Instant.EPOCH, Instant.EPOCH));
        return this;
    }

    @NonNull CapFixture withManifest(@NonNull String serverId, @NonNull String... ops) {
        manifests.put(serverId, new LinkedHashSet<>(List.of(ops)));
        return this;
    }

    /**
     * 登记一份<b>会变化的</b>清单：按调用次序依次返回。
     *
     * <p>用于模拟 §5.8 步骤 3 所防的 TOCTOU 窗口：清单在「指纹重算」与「操作级复核」
     * 之间发生了变化。此时指纹检查通过（指纹是用第一次读到的清单算的），
     * 而步骤 3 会发现操作已不在当前清单内 → {@code MCP_TOOL_DRIFTED}。
     */
    @NonNull CapFixture withManifestSequence(@NonNull String serverId,
                                              @NonNull Set<String>... successive) {
        manifestSequences.put(serverId, new ArrayList<>(List.of(successive)));
        return this;
    }

    /**
     * 登记「租户当前发布指针所指的 Agent 版本」（P0-5）。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @param version  指针指向的发布头里声明的精确版本
     */
    @NonNull CapFixture withPublishedAgentVersion(@NonNull String tenantId,
                                                   @NonNull String agentId, int version) {
        publishedAgentVersions.put(tenantId + "/" + agentId, version);
        return this;
    }

    @NonNull CapFixture withAuthzVersion(@NonNull String tenantId, @NonNull String principalId,
                                         long version) {
        authzVersions.put(tenantId + "/" + principalId, version);
        return this;
    }

    @NonNull CapFixture withRolePermission(@NonNull String role, @NonNull String... actions) {
        rolePermissions.put(role, new LinkedHashSet<>(List.of(actions)));
        return this;
    }

    @NonNull CapFixture withSnapshot(@NonNull CapabilitySnapshot snapshot) {
        snapshots.put(snapshot.tenantId() + "/" + snapshot.runId(), snapshot);
        return this;
    }

    /**
     * @param enabledSkills 启用的 Skill id（V1 唯一启用途径）
     * @param overrides     收紧覆盖（JSON 文本）
     */
    @NonNull CapFixture withAgent(@NonNull String tenantId, @NonNull String agentId, int version,
                                   @NonNull List<String> enabledSkills,
                                   @NonNull String overrides) {
        return withAgent(tenantId, agentId, version, enabledSkills, overrides, "{}");
    }

    /**
     * 登记一个 Agent 定义行（带编排声明）。
     *
     * @param tenantId     租户标识
     * @param agentId      Agent 标识
     * @param version      版本号
     * @param enabledSkills 启用的 Skill id
     * @param overrides    收紧覆盖（JSON 文本）
     * @param orchestration 编排声明（JSON 文本；{@code "{}"} = LEAF）
     */
    @NonNull CapFixture withAgent(@NonNull String tenantId, @NonNull String agentId, int version,
                                   @NonNull List<String> enabledSkills,
                                   @NonNull String overrides,
                                   @NonNull String orchestration) {
        agents.put(tenantId + "/" + agentId + "@" + version,
                agentDef(tenantId, agentId, version, enabledSkills, overrides, orchestration,
                        Instant.EPOCH));
        return this;
    }

    /**
     * 构造 Agent 定义行，<b>内容指纹按真实算法计算</b>。
     *
     * <p>⚠️ <b>为什么不能用假的固定指纹</b>（如 {@code "fp-agent-" + id}）：
     * 真实链路里 {@code config_fingerprint} 是「这一行内容没变」的证明，
     * 且它<b>覆盖编排声明</b>（见 {@link CapObjectFingerprint#of}）。
     * 夹具若给一个与内容无关的固定值，则「同一版本行的编排声明被改写」
     * 这类漂移在测试里<b>根本不可见</b> ——
     * 用例会「测了一个不存在的正确性」。
     */
    @NonNull
    private static CapAgentDef agentDef(@NonNull String tenantId,
                                        @NonNull String agentId,
                                        int version,
                                        @NonNull List<String> enabledSkills,
                                        @NonNull String overrides,
                                        @NonNull String orchestration,
                                        @Nullable Instant publishedAt) {
        // ⚠️ 内容指纹不含自身（否则自引用无法计算），
        //    故先用占位值构造一行，再按内容算出真实指纹，最后重建该行。
        CapAgentDef provisional = new CapAgentDef(
                tenantId, agentId, version, agentId, agentId + " desc",
                List.of("TEACHER"), enabledSkills, List.of(), List.of(),
                overrides, orchestration, "pending", 1,
                Instant.EPOCH, publishedAt);
        return new CapAgentDef(
                tenantId, agentId, version, agentId, agentId + " desc",
                List.of("TEACHER"), enabledSkills, List.of(), List.of(),
                overrides, orchestration, CapObjectFingerprint.of(provisional), 1,
                Instant.EPOCH, publishedAt);
    }

    /**
     * 登记一个<b>未发布</b>的 Agent 定义行（草稿）。
     *
     * <p>用于验证「子 Agent 引用未发布版本 → 拒绝」（MA-T02）。
     *
     * @param tenantId     租户标识
     * @param agentId      Agent 标识
     * @param version      版本号
     * @param enabledSkills 启用的 Skill id
     * @param overrides    收紧覆盖（JSON 文本）
     * @param orchestration 编排声明（JSON 文本）
     */
    @NonNull CapFixture withUnpublishedAgent(@NonNull String tenantId, @NonNull String agentId,
                                              int version, @NonNull List<String> enabledSkills,
                                              @NonNull String overrides,
                                              @NonNull String orchestration) {
        agents.put(tenantId + "/" + agentId + "@" + version,
                agentDef(tenantId, agentId, version, enabledSkills, overrides, orchestration,
                        null));
        return this;
    }

    /**
     * 登记一个输出契约定义行。
     *
     * @param contractId 契约标识
     * @param version    契约版本号
     * @param schemaJson 契约定义（JSON Schema 文本）
     * @param published  是否已发布（未发布不可被 Agent 引用）
     * @return 本夹具
     */
    @NonNull CapFixture withContract(@NonNull String tenantId, @NonNull String contractId,
                                     int version, @NonNull String schemaJson, boolean published) {
        return withContract(tenantId, contractId, version, schemaJson, published,
                "fp-contract-" + contractId + "-" + version);
    }

    /**
     * 登记一个输出契约定义行（<b>显式指定内容指纹</b>）。
     *
     * <p>⚠️ <b>为什么需要显式指定</b>：第二十四批要验证的是
     * 「<b>同一版本行被就地改写</b> → 闭包指纹必变」——
     * 而「被改写」在数据上就是<b>内容指纹变了</b>（版本号与引用都没变）。
     * 若指纹由夹具按 id@version 派生，就<b>永远改不出来</b>，
     * 那条用例会「测了一个不存在的正确性」。
     */
    @NonNull CapFixture withContract(@NonNull String tenantId, @NonNull String contractId,
                                     int version, @NonNull String schemaJson, boolean published,
                                     @NonNull String fingerprint) {
        resultContracts.put(tenantId + "/" + contractId + "@" + version,
                new CapResultContractDef(
                tenantId, contractId, version, schemaJson,
                fingerprint, 1,
                Instant.EPOCH,
                published ? Instant.EPOCH : null));
        return this;
    }

    /**
     * 登记一个输出契约定义行（<b>显式指定生命周期状态</b> · 第二十五批 A5）。
     *
     * <p>⚠️ <b>为什么需要显式指定</b>：「已停用」是发布期引用校验的一道防线，
     * 而「停用」在数据上就是 {@code lifecycle_state = 'DEPRECATED'}。
     * 若夹具造不出这一态，那条防线就<b>永远测不到</b> ——
     * 用例会「测了一个不存在的正确性」。
     *
     * @param tenantId       租户标识
     * @param contractId     契约标识
     * @param version        契约版本号
     * @param schemaJson     契约定义（JSON Schema 文本）
     * @param published      是否已发布
     * @param lifecycleState 生命周期状态（{@code null} = 未记录）
     * @return 本夹具
     */
    @NonNull CapFixture withContract(@NonNull String tenantId, @NonNull String contractId,
                                     int version, @NonNull String schemaJson, boolean published,
                                     @Nullable ConfigLifecycleState lifecycleState) {
        resultContracts.put(tenantId + "/" + contractId + "@" + version,
                new CapResultContractDef(
                tenantId, contractId, version, schemaJson,
                "fp-contract-" + contractId + "-" + version, 1,
                Instant.EPOCH,
                published ? Instant.EPOCH : null,
                lifecycleState));
        return this;
    }

    /**
     * 登记一个路由策略定义行。
     *
     * @param tenantId  租户标识
     * @param policyId  策略标识
     * @param version   策略版本号
     * @param strategy  路由策略
     * @param targets   有序目标列表
     * @param published 是否已发布（未发布不可被 Agent 引用）
     * @return 本夹具
     */
    @NonNull CapFixture withRoutingPolicy(@NonNull String tenantId, @NonNull String policyId,
                                          int version, @NonNull RoutingStrategy strategy,
                                          @NonNull List<String> targets, boolean published) {
        return withRoutingPolicy(tenantId, policyId, version, strategy, targets, published, null);
    }

    /**
     * 登记一个路由策略定义行（<b>显式指定生命周期状态</b> · 第二十五批 A5）。
     *
     * @param tenantId       租户标识
     * @param policyId       策略标识
     * @param version        策略版本号
     * @param strategy       路由策略
     * @param targets        有序目标列表
     * @param published      是否已发布
     * @param lifecycleState 生命周期状态（{@code null} = 未记录）
     * @return 本夹具
     */
    @NonNull CapFixture withRoutingPolicy(@NonNull String tenantId, @NonNull String policyId,
                                          int version, @NonNull RoutingStrategy strategy,
                                          @NonNull List<String> targets, boolean published,
                                          @Nullable ConfigLifecycleState lifecycleState) {
        routingPolicies.put(tenantId + "/" + policyId + "@" + version,
                new CapRoutingPolicyDef(
                tenantId, policyId, version, strategy, targets,
                "fp-policy-" + policyId + "-" + version, 1,
                Instant.EPOCH,
                published ? Instant.EPOCH : null,
                lifecycleState));
        return this;
    }

    /**
     * 被引用行（输出契约 / 路由策略）的内容指纹解析器（第二十四批 · A4）。
     *
     * <p>⚠️ 闭包构建与缓存新鲜度判定<b>共用这一份</b>：
     * 两处各写一遍，就有了「两处可能对同一行给出不同解读」的裂缝。
     */
    @NonNull ReferencedContentResolver referencedContent() {
        return new ReferencedContentResolver(routingPolicies(), resultContracts());
    }

    @NonNull CapRoutingPolicyPort routingPolicies() {
        return new CapRoutingPolicyPort() {
            @Override
            public boolean insert(@NonNull CapRoutingPolicyDef def) {
                String key = def.tenantId() + "/" + def.policyId() + "@" + def.version();
                return routingPolicies.putIfAbsent(key, def) == null;
            }

            @Override
            public @NonNull Optional<CapRoutingPolicyDef> find(@NonNull String tenantId,
                                                                @NonNull String policyId,
                                                                int version) {
                return Optional.ofNullable(routingPolicies.get(
                        tenantId + "/" + policyId + "@" + version));
            }

            @Override
            public @NonNull Optional<CapRoutingPolicyDef> findLatestPublished(
                    @NonNull String tenantId, @NonNull String policyId) {
                return routingPolicies.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> policyId.equals(c.policyId()))
                        .filter(CapRoutingPolicyDef::isPublished)
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }

            @Override
            public @NonNull Optional<CapRoutingPolicyDef> findLatest(
                    @NonNull String tenantId, @NonNull String policyId) {
                return routingPolicies.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> policyId.equals(c.policyId()))
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }
        };
    }

    // ── 端口工厂 ──────────────────────────────────────────────────

    @NonNull CapAgentDefPort agentDefs() {
        return new CapAgentDefPort() {
            @Override
            public boolean insert(@NonNull CapAgentDef def) {
                String key = def.tenantId() + "/" + def.agentId() + "@" + def.version();
                if (agents.containsKey(key)) {
                    return false;
                }
                agents.put(key, def);
                return true;
            }

            @Override
            public @NonNull Optional<CapAgentDef> find(
                    @NonNull String tenantId, @NonNull String agentId, int version) {
                return Optional.ofNullable(agents.get(tenantId + "/" + agentId + "@" + version));
            }

            @Override
            public @NonNull Optional<CapAgentDef> findLatest(
                    @NonNull String tenantId, @NonNull String agentId) {
                return latestAgent(tenantId, agentId);
            }

            @Override
            public @NonNull Optional<CapAgentDef>
                    findLatestPublished(@NonNull String tenantId, @NonNull String agentId) {
                return latestAgent(tenantId, agentId);
            }

            @Override
            public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
                Map<String, CapAgentDef> latest = new LinkedHashMap<>();
                for (CapAgentDef a : agents.values()) {
                    if (!tenantId.equals(a.tenantId())) {
                        continue;
                    }
                    CapAgentDef prev = latest.get(a.agentId());
                    if (prev == null || a.version() > prev.version()) {
                        latest.put(a.agentId(), a);
                    }
                }
                return latest.values().stream()
                        .sorted((a, b) -> a.agentId().compareTo(b.agentId()))
                        .toList();
            }

            private Optional<CapAgentDef> latestAgent(
                    String tenantId, String agentId) {
                return agents.values().stream()
                        .filter(a -> tenantId.equals(a.tenantId()) && agentId.equals(a.agentId()))
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }
        };
    }

    @NonNull CapResultContractPort resultContracts() {
        return new CapResultContractPort() {
            @Override
            public boolean insert(@NonNull CapResultContractDef def) {
                String key = def.tenantId() + "/" + def.contractId() + "@" + def.version();
                return resultContracts.putIfAbsent(key, def) == null;
            }

            @Override
            public @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                                 @NonNull String contractId,
                                                                 int version) {
                return Optional.ofNullable(resultContracts.get(
                        tenantId + "/" + contractId + "@" + version));
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatestPublished(
                    @NonNull String tenantId, @NonNull String contractId) {
                return resultContracts.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> contractId.equals(c.contractId()))
                        .filter(CapResultContractDef::isPublished)
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatest(
                    @NonNull String tenantId, @NonNull String contractId) {
                // ⚠️ 含草稿：草稿也占用版本号，只看已发布版本会分配出撞号的版本
                return resultContracts.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> contractId.equals(c.contractId()))
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }
        };
    }

    @NonNull CapSkillDefPort skillDefs() {
        return new CapSkillDefPort() {
            @Override
            public boolean insert(@NonNull CapSkillDef def) {
                return skills.putIfAbsent(key(def.id(), def.version()), def) == null;
            }

            @Override
            public @NonNull Optional<CapSkillDef> find(@NonNull String skillId, int version) {
                return Optional.ofNullable(skills.get(key(skillId, version)));
            }

            @Override
            public @NonNull Optional<CapSkillDef> findLatestPublished(@NonNull String skillId) {
                return latestPublished(skills, skillId);
            }

            @Override
            public @NonNull List<CapSkillDef> listAll() {
                return latestPerId(skills);
            }
        };
    }

    @NonNull CapToolDefPort toolDefs() {
        return new CapToolDefPort() {
            @Override
            public boolean insert(@NonNull CapToolDef def) {
                return tools.putIfAbsent(key(def.id(), def.version()), def) == null;
            }

            @Override
            public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
                return Optional.ofNullable(tools.get(key(toolId, version)));
            }

            @Override
            public @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId) {
                return latestPublished(tools, toolId);
            }

            @Override
            public @NonNull Optional<CapToolDef> findByMcpBinding(@NonNull String mcpServerId,
                                                                   @NonNull String mcpToolName) {
                return tools.values().stream()
                        .filter(t -> mcpServerId.equals(t.mcpServerId())
                                && mcpToolName.equals(t.mcpToolName()))
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }

            @Override
            public @NonNull List<CapToolDef> listAll() {
                return latestPerId(tools);
            }
        };
    }

    @NonNull CapMcpServerDefPort serverDefs() {
        return new CapMcpServerDefPort() {
            @Override
            public boolean insert(@NonNull CapMcpServerDef def) {
                return servers.putIfAbsent(key(def.id(), def.version()), def) == null;
            }

            @Override
            public @NonNull Optional<CapMcpServerDef> find(@NonNull String serverId, int version) {
                return Optional.ofNullable(servers.get(key(serverId, version)));
            }

            @Override
            public @NonNull Optional<CapMcpServerDef> findLatestPublished(@NonNull String serverId) {
                return latestPublished(servers, serverId);
            }

            @Override
            public java.util.List<CapMcpServerDef> listAll() {
                return java.util.List.copyOf(servers.values());
            }
        };
    }

    @NonNull PublishedVersionPort publishedVersions() {
        return new PublishedVersionPort() {
            @Override
            public @NonNull Optional<Integer> currentVersion(@NonNull CapObjectType type,
                                                             @NonNull String objectId) {
                Map<String, ?> source = switch (type) {
                    case TOOL -> tools;
                    case SKILL -> skills;
                    case MCP_SERVER -> servers;
                };
                List<Integer> versions = new ArrayList<>();
                String prefix = objectId + "@";
                for (String mapKey : source.keySet()) {
                    if (mapKey.startsWith(prefix)) {
                        versions.add(Integer.parseInt(mapKey.substring(prefix.length())));
                    }
                }
                return versions.isEmpty()
                        ? Optional.empty()
                        : Optional.of(versions.stream().max(Integer::compareTo).orElseThrow());
            }

            /**
             * 租户级（Agent）当前发布指针所指的版本（P0-5）。
             *
             * <p>⚠️ 夹具用 {@code publishedAgentVersions} 模拟
             * 「TENANT 指针 → release → object_versions」这条解析链的结果：
             * 未显式登记时返回空 → 调用方按「该发布头未引用此 Agent」回退到
             * 「最新已发布版本行」（与真实实现同构）。
             */
            @Override
            public @NonNull Optional<Integer> currentTenantVersion(@NonNull String tenantId,
                                                                    @NonNull String type,
                                                                    @NonNull String objectId) {
                if (!ConfigObjectVersion.TYPE_AGENT.equals(type)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(
                        publishedAgentVersions.get(tenantId + "/" + objectId));
            }
        };
    }

    @NonNull McpManifestPort manifest() {
        return new McpManifestPort() {
            @Override
            public McpManifestPort.@NonNull ManifestSnapshot manifest(@NonNull String serverId) {
                if (unavailableManifests.contains(serverId)) {
                    // ⚠️ 「清单不可用」≠「清单为空」：前者是运行故障，后者是配置事实
                    return McpManifestPort.ManifestSnapshot.unavailable("测试夹具：清单不可用");
                }
                List<Set<String>> sequence = manifestSequences.get(serverId);
                if (sequence == null) {
                    return McpManifestPort.ManifestSnapshot.available(
                            manifests.getOrDefault(serverId, Set.of()));
                }
                int call = manifestCallCounts.merge(serverId, 1, Integer::sum) - 1;
                return McpManifestPort.ManifestSnapshot.available(
                        sequence.get(Math.min(call, sequence.size() - 1)));
            }
        };
    }

    @NonNull AuthzVersionPort authzVersions() {
        return new AuthzVersionPort() {
            @Override
            public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                         @NonNull String principalId) {
                Long version = authzVersions.get(tenantId + "/" + principalId);
                return version == null
                        ? Optional.empty()
                        : Optional.of(new AuthzVersion(tenantId, principalId, version,
                                Instant.EPOCH));
            }

            @Override
            public @NonNull AuthzVersion require(@NonNull String tenantId,
                                                  @NonNull String principalId) {
                if (authzUnavailable) {
                    throw new AuthzUnavailableException(tenantId, principalId,
                            "版本服务不可用", null);
                }
                return find(tenantId, principalId).orElseThrow(() ->
                        new AuthzUnavailableException(tenantId, principalId,
                                "无授权版本记录", null));
            }

            @Override
            public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                                    @NonNull String principalId) {
                return find(tenantId, principalId).orElseGet(() -> {
                    AuthzVersion created = new AuthzVersion(tenantId, principalId,
                            AuthzVersion.INITIAL_VERSION, Instant.EPOCH);
                    authzVersions.put(tenantId + "/" + principalId, created.authzVersion());
                    return created;
                });
            }

            @Override
            public long bump(@NonNull String tenantId, @NonNull String principalId) {
                long next = authzVersions.getOrDefault(tenantId + "/" + principalId,
                        AuthzVersion.INITIAL_VERSION) + 1;
                authzVersions.put(tenantId + "/" + principalId, next);
                return next;
            }

            @Override
            public AuthzWatermark watermark(@NonNull String tenantId) {
                long subjects = 0;
                long revoked = 0;
                long max = 0;
                String prefix = tenantId + "/";
                for (Map.Entry<String, Long> entry : authzVersions.entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) {
                        continue;
                    }
                    subjects++;
                    if (entry.getValue() > AuthzVersion.INITIAL_VERSION) {
                        revoked++;
                    }
                    max = Math.max(max, entry.getValue());
                }
                return new AuthzWatermark(tenantId, subjects, revoked, max, null,
                        Instant.EPOCH);
            }
        };
    }

    @NonNull CapabilitySnapshotPort snapshots() {
        return new CapabilitySnapshotPort() {
            @Override
            public boolean save(@NonNull CapabilitySnapshot snapshot) {
                String key = snapshot.tenantId() + "/" + snapshot.runId();
                if (snapshots.containsKey(key)) {
                    return false;
                }
                snapshots.put(key, snapshot);
                return true;
            }

            @Override
            public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                              @NonNull String runId) {
                return Optional.ofNullable(snapshots.get(tenantId + "/" + runId));
            }
        };
    }

    @NonNull RolePermissionPort rolePermissions() {
        return (roles, action) -> roles.stream()
                .anyMatch(role -> rolePermissions.getOrDefault(role, Set.of()).contains(action));
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    private static <T> Optional<T> latestPublished(Map<String, T> source, String id) {
        return source.values().stream()
                .filter(v -> id.equals(idOf(v)))
                .max((a, b) -> Integer.compare(versionOf2(a), versionOf2(b)));
    }

    /**
     * 每个逻辑 id 的最高版本（供 {@code CapToolDefPort#listAll()} /
     * {@code CapSkillDefPort#listAll()} 的夹具实现，与 DB 侧
     * {@code DISTINCT ON (id) ... ORDER BY id, version DESC} 同口径）。
     */
    private static <T> List<T> latestPerId(Map<String, T> source) {
        Map<String, T> best = new LinkedHashMap<>();
        for (T def : source.values()) {
            best.merge(idOf(def), def, (a, b) -> versionOf2(a) >= versionOf2(b) ? a : b);
        }
        return List.copyOf(best.values());
    }

    private static String idOf(Object def) {
        if (def instanceof CapSkillDef s) {
            return s.id();
        }
        if (def instanceof CapToolDef t) {
            return t.id();
        }
        if (def instanceof CapMcpServerDef m) {
            return m.id();
        }
        throw new IllegalArgumentException("未知类型");
    }

    private static int versionOf2(Object def) {
        if (def instanceof CapSkillDef s) {
            return s.version();
        }
        if (def instanceof CapToolDef t) {
            return t.version();
        }
        if (def instanceof CapMcpServerDef m) {
            return m.version();
        }
        throw new IllegalArgumentException("未知类型");
    }

    private static String key(String id, int version) {
        return id + "@" + version;
    }
}
