package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 原子发布命令（§6.3 第 1/3 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>一次发布 = 一个事务里的三件事</b>：
 * <ol>
 *   <li>插入本次发布的<b>不可变新版本行</b>（{@code cap_*_def}）；</li>
 *   <li>插入一条 {@code cap_config_release} 发布头（序号由 DB 序列分配）；</li>
 *   <li>CAS 移动当前指针。</li>
 * </ol>
 * 三者必须同事务提交；任一失败整事务回滚（PUB-1）。
 *
 * <p>⚠️ <b>作用域与对象集合必须一致</b>：
 * PLATFORM 作用域只含 platform 对象（Tool / Skill / MCP Server），
 * TENANT 作用域只含本租户对象（Agent）。
 * 混装会让「平台发布」把某个租户的 Agent 结构写进全局发布头 ——
 * 那是跨租户结构泄漏（P0 落地说明已登记该风险）。
 *
 * @param scope                作用域
 * @param tenantId             租户标识；PLATFORM 作用域为 {@code null}
 * @param expectedPublishSeq   CAS 基线；{@code null} 表示「期望尚未发布过」
 * @param requesterId          申请人
 * @param approverId           审批人（双人复核：不得等于申请人）
 * @param aggregateFingerprint 聚合指纹
 * @param tools                本次发布的 Tool 版本行
 * @param skills               本次发布的 Skill 版本行
 * @param mcpServers           本次发布的 MCP Server 版本行
 * @param agents               本次发布的 Agent 版本行（仅 TENANT 作用域）
 * @param effectiveAt          生效时间
 * @param resultContracts      本次发布的输出契约版本行（仅 TENANT 作用域 · 多级 Agent v1.1）
 * @param routingPolicies      本次发布的路由策略版本行（仅 TENANT 作用域 · 多级 Agent v1.1 第十四批）
 */
public record ConfigPublishCommand(
        ConfigRelease.@NonNull Scope scope,
        @Nullable String tenantId,
        @Nullable Long expectedPublishSeq,
        @Nullable String requesterId,
        @Nullable String approverId,
        @Nullable String aggregateFingerprint,
        @NonNull List<CapToolDef> tools,
        @NonNull List<CapSkillDef> skills,
        @NonNull List<CapMcpServerDef> mcpServers,
        @NonNull List<CapAgentDef> agents,
        @Nullable Instant effectiveAt,
        @NonNull List<CapResultContractDef> resultContracts,
        @NonNull List<CapRoutingPolicyDef> routingPolicies) {

    /**
     * 兼容构造器：<b>无输出契约、无路由策略</b>的发布命令（多级 Agent 之前的形态）。
     *
     * <p>⚠️ 缺省 = <b>本次发布不含契约与策略</b>，不是「契约/策略随意」——
     * 命令里没有就没有可发布的，Agent 的 {@code resultContractRef} /
     * {@code routingPolicyRef} 只能指向<b>此前已发布</b>的对应对象。
     */
    public ConfigPublishCommand(ConfigRelease.@NonNull Scope scope,
                                 @Nullable String tenantId,
                                 @Nullable Long expectedPublishSeq,
                                 @Nullable String requesterId,
                                 @Nullable String approverId,
                                 @Nullable String aggregateFingerprint,
                                 @NonNull List<CapToolDef> tools,
                                 @NonNull List<CapSkillDef> skills,
                                 @NonNull List<CapMcpServerDef> mcpServers,
                                 @NonNull List<CapAgentDef> agents,
                                 @Nullable Instant effectiveAt) {
        this(scope, tenantId, expectedPublishSeq, requesterId, approverId, aggregateFingerprint,
                tools, skills, mcpServers, agents, effectiveAt, List.of(), List.of());
    }

    /**
     * 兼容构造器：<b>有输出契约、无路由策略</b>的发布命令（第十四批之前的形态）。
     */
    public ConfigPublishCommand(ConfigRelease.@NonNull Scope scope,
                                 @Nullable String tenantId,
                                 @Nullable Long expectedPublishSeq,
                                 @Nullable String requesterId,
                                 @Nullable String approverId,
                                 @Nullable String aggregateFingerprint,
                                 @NonNull List<CapToolDef> tools,
                                 @NonNull List<CapSkillDef> skills,
                                 @NonNull List<CapMcpServerDef> mcpServers,
                                 @NonNull List<CapAgentDef> agents,
                                 @Nullable Instant effectiveAt,
                                 @NonNull List<CapResultContractDef> resultContracts) {
        this(scope, tenantId, expectedPublishSeq, requesterId, approverId, aggregateFingerprint,
                tools, skills, mcpServers, agents, effectiveAt, resultContracts, List.of());
    }

    public ConfigPublishCommand {
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (scope == ConfigRelease.Scope.TENANT && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT 作用域必须携带 tenantId");
        }
        if (scope == ConfigRelease.Scope.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM 作用域不得携带 tenantId");
        }
        if (expectedPublishSeq != null && expectedPublishSeq < 0) {
            throw new IllegalArgumentException("expectedPublishSeq 不得为负数");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        agents = agents == null ? List.of() : List.copyOf(agents);
        resultContracts = resultContracts == null ? List.of() : List.copyOf(resultContracts);
        routingPolicies = routingPolicies == null ? List.of() : List.copyOf(routingPolicies);
    }

    /**
     * @return 本次发布涉及的全部对象版本引用（写入发布头 {@code object_versions}）
     */
    @NonNull
    public List<ConfigObjectVersion> objectVersions() {
        List<ConfigObjectVersion> refs = new ArrayList<>();
        for (CapToolDef tool : tools) {
            refs.add(ConfigObjectVersion.tool(tool.id(), tool.version()));
        }
        for (CapSkillDef skill : skills) {
            refs.add(ConfigObjectVersion.skill(skill.id(), skill.version()));
        }
        for (CapMcpServerDef server : mcpServers) {
            refs.add(ConfigObjectVersion.mcpServer(server.id(), server.version()));
        }
        for (CapAgentDef agent : agents) {
            refs.add(ConfigObjectVersion.agent(agent.agentId(), agent.version()));
        }
        // ⚠️ 契约必须进发布头：发布头是「这次发布了哪些精确版本」的权威清单。
        //    契约不在清单里 → 回滚/追溯时无法确定「当时生效的是哪份 Schema」。
        for (CapResultContractDef contract : resultContracts) {
            refs.add(ConfigObjectVersion.resultContract(contract.contractId(), contract.version()));
        }
        // ⚠️ 路由策略必须进发布头：发布头是「这次发布了哪些精确版本」的权威清单。
        //    策略不在清单里 → 回滚/追溯时无法确定「当时生效的是哪份路由规则」。
        for (CapRoutingPolicyDef policy : routingPolicies) {
            refs.add(ConfigObjectVersion.routingPolicy(policy.policyId(), policy.version()));
        }
        return List.copyOf(refs);
    }

    /**
     * @return 本次发布涉及的对象总数
     */
    public int objectCount() {
        return tools.size() + skills.size() + mcpServers.size() + agents.size()
                + resultContracts.size() + routingPolicies.size();
    }

    /**
     * @return 是否为平台级作用域
     */
    public boolean isPlatformScope() {
        return scope == ConfigRelease.Scope.PLATFORM;
    }
}
