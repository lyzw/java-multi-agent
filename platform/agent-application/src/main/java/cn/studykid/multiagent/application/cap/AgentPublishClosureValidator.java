package cn.studykid.multiagent.application.cap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.application.orchestration.OrchestrationConfigException;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * 多级 Agent 的<b>发布期</b>闭包校验（多级 Agent v1.1 · FR-023 · MA-T02～T04）。
 *
 * <p>⚠️ <b>为什么必须在发布期拦</b>：运行期才发现「子 Agent 引用未发布 / 成环」，
 * 意味着配置<b>已经生效</b> —— 新 Run 全部被拒、在途 Run 按旧指纹继续跑，
 * 运维看到的现象是「这个 Agent 突然全员不可用」，
 * 而真因（引用写错）在提交那一刻就已知。
 * 发布期拒发布把问题挡在生效之前。
 *
 * <p>⚠️ <b>跨租户引用不泄漏存在性</b>（MA-T04）：子 Agent 查询按本租户进行，
 * 查不到时统一报「引用不存在」，不区分「不存在」与「在别的租户」。
 *
 * <p>⚠️ <b>引用必须固定版本</b>：子 Agent 引用指向确定的版本行；
 * 未发布的版本一律拒（MA-T02）。
 *
 * <p>⚠️ <b>输出契约引用同样在发布期校验</b>：声明了 {@code resultContractRef}
 * 却指向未发布 / 不存在的契约 → 拒绝发布。否则运行期每次委派都会
 * 因「取不到契约」fail-closed（MA-T16），配置看起来生效了、实际委派全废。
 *
 * <p>⚠️ <b>路由策略引用同样在发布期校验</b>（第十四批 · MA-T05 / MA-T15）：
 * 声明了 {@code routingPolicyRef} 却指向未发布 / 不存在的策略 → 拒绝发布；
 * 策略允许的目标不在父声明的 {@code childAgentRefs} 内 → 拒绝发布。
 * 理由与契约完全同构：此前 {@code routingPolicyRef} 是<b>悬空引用</b> ——
 * 全仓没有任何地方真正持有这个被引用的对象，
 * 于是「路由策略写了」与「路由策略生效了」是两件毫不相干的事。
 */
public class AgentPublishClosureValidator {

    private final CapAgentDefPort agentDefs;
    private final CapResultContractPort contracts;
    private final CapRoutingPolicyPort routingPolicies;

    /**
     * 兼容构造器：<b>无路由策略校验能力</b>。
     *
     * <p>⚠️ 此时声明了 {@code routingPolicyRef} 的 Agent 会在发布时被拒（fail-closed）：
     * 「策略校验没接上」不等于「策略引用合法」。
     */
    public AgentPublishClosureValidator(@NonNull CapAgentDefPort agentDefs,
                                         @NonNull CapResultContractPort contracts) {
        this(agentDefs, contracts, null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1 第十四批）：额外注入路由策略仓储。
     *
     * <p>⚠️ 路由策略仓储必须显式注入：策略校验缺席 = 声明了策略的 Agent
     * 可以在「策略根本不存在 / 未发布 / 与声明不一致」的情况下被发布出去，
     * 运行期才 fail-closed —— 那等于把配置错误推迟到线上才暴露。
     */
    public AgentPublishClosureValidator(@NonNull CapAgentDefPort agentDefs,
                                         @NonNull CapResultContractPort contracts,
                                         @Nullable CapRoutingPolicyPort routingPolicies) {
        this.agentDefs = agentDefs;
        this.contracts = contracts;
        this.routingPolicies = routingPolicies;
    }

    /**
     * 校验一个待发布的 Agent 定义（<b>发布期</b>）。
     *
     * @param tenantId 租户标识
     * @param draft    待发布的 Agent 定义行（<b>尚未落库</b>）
     * @throws ConfigPublishRejectedException 校验不通过
     */
    public void validate(@NonNull String tenantId, @NonNull CapAgentDef draft) {
        AgentOrchestration orchestration = parse(draft.orchestration(), draft);
        // ⚠️ 契约校验与「有没有子 Agent」无关：LEAF 也可以声明输出契约
        //    （作为该 Agent 自身输出的约束），故先校验契约。
        validateResultContract(tenantId, draft, orchestration);
        // ⚠️ 路由策略校验同样与「有没有子 Agent」无关：
        //    但**任何**模式声明 routingPolicyRef 都要校验 ——
        //    非 ROUTER 模式声明它本身就是配置缺陷（路由策略只对 ROUTER 有意义）。
        validateRoutingPolicy(tenantId, draft, orchestration);
        if (orchestration.isLeaf() || !orchestration.hasChildren()) {
            return;
        }
        validateChildren(tenantId, draft, orchestration,
                new ArrayDeque<>(List.of(key(draft.agentId(), draft.version()))), 0);
    }

    /**
     * 校验路由策略引用（多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
     *
     * <p>⚠️ <b>三道关</b>，缺一不可：
     * <ol>
     *   <li><b>只有 ROUTER 模式可以声明策略</b>：LEAF / SEQUENTIAL 声明
     *       {@code routingPolicyRef} 是配置缺陷 —— 策略对它们毫无作用，
     *       却让人以为「路由规则已经生效」；</li>
     *   <li><b>引用必须指向已发布的固定版本</b>：策略是配置对象，
     *       引用未发布的策略 = 引用了一个「还不生效的事实」；</li>
     *   <li><b>策略的目标集合必须是父声明的子集</b>：这是本批新增的硬约束 ——
     *       「委派拓扑」与「路由允许集」若不是两层收窄关系，就有了两个真相，
     *       「谁说了算」说不清；且运行期「候选不在策略集合内」的拒绝码
     *       也就失去了解释力。</li>
     * </ol>
     */
    private void validateRoutingPolicy(@NonNull String tenantId,
                                       @NonNull CapAgentDef draft,
                                       @NonNull AgentOrchestration orchestration) {
        String policyRef = orchestration.routingPolicyRef();
        if (policyRef == null) {
            // ⚠️ ROUTER 必带策略引用 —— 该约束已由编排解析层强制
            //    （解析期缺引用即 ORCHESTRATION_INVALID），此处不重复判定。
            return;
        }
        if (orchestration.mode() != OrchestrationMode.ROUTER) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的模式是 " + orchestration.mode()
                            + "，不得声明 routingPolicyRef（路由策略只对 ROUTER 有意义）；"
                            + "声明了却不会生效，会让人误以为路由规则已固化");
        }
        if (routingPolicies == null) {
            // ⚠️ 校验能力未装配 → fail-closed：不能因为「没接上校验器」就放行。
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "路由策略校验能力未装配（策略仓储缺失），拒绝发布声明了 routingPolicyRef 的"
                            + " Agent: agent=" + draft.agentId() + " v" + draft.version());
        }
        int separator = policyRef.lastIndexOf("@");
        if (separator <= 0 || separator == policyRef.length() - 1) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 routingPolicyRef 不是固定版本引用（id@version）: "
                            + policyRef);
        }
        String policyId = policyRef.substring(0, separator);
        int version;
        try {
            version = Integer.parseInt(policyRef.substring(separator + 1).trim());
        } catch (NumberFormatException e) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 routingPolicyRef 版本不是数字: " + policyRef);
        }
        if (version <= 0) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 routingPolicyRef 版本必须为正数: " + policyRef);
        }
        CapRoutingPolicyDef policy = routingPolicies.find(tenantId, policyId, version)
                .orElseThrow(() -> new ConfigPublishRejectedException(
                        ReasonCode.RESOURCE_UNKNOWN, "orchestration",
                        "路由策略引用不存在: policy=" + policyId + ", version=" + version));
        if (!policy.isPublished()) {
            throw new ConfigPublishRejectedException(ReasonCode.ROUTING_POLICY_NOT_PUBLISHED,
                    "orchestration",
                    "路由策略引用未发布: policy=" + policyId + ", version=" + version);
        }
        if (policy.isDeprecated()) {
            // ⚠️ **停用只挡「新引用」，不追溯存量**（06 §3.2）：
            //    理由与输出契约同构 —— 「停用」若挡不住新发布引用，
            //    那它只是给人看的告示牌，而不是一道防线。
            throw new ConfigPublishRejectedException(ReasonCode.ROUTING_POLICY_DEPRECATED,
                    "orchestration",
                    "路由策略引用已停用（DEPRECATED）: policy=" + policyId
                            + ", version=" + version
                            + "。已停用的策略不得被新发布引用（06 §3.2）；"
                            + "存量引用按版本继续过渡");
        }
        for (String target : policy.targets()) {
            if (orchestration.child(target).isEmpty()) {
                // ⚠️ 策略允许的目标不在父声明的委派拓扑内 → 拒绝发布。
                //    放行的话运行期只能报「目标未声明」，而真因是「策略与声明不一致」，
                //    排查方向会被带偏；更糟的是策略里可以藏着一个「父根本没打算交给谁」的目标。
                throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                        "orchestration",
                        "路由策略 " + policy.ref() + " 允许的目标 " + target
                                + " 不在 Agent " + draft.agentId() + " v" + draft.version()
                                + " 的 childAgentRefs 内：委派拓扑与路由允许集必须两层收窄，"
                                + "否则「允许交给谁」有两个真相");
            }
        }
    }

    /**
     * 校验输出契约引用。
     *
     * <p>⚠️ <b>引用必须指向已发布的固定版本</b>：契约是配置对象，
     * 引用未发布的契约 = 引用了一个「还不生效的事实」。
     */
    private void validateResultContract(@NonNull String tenantId,
                                        @NonNull CapAgentDef draft,
                                        @NonNull AgentOrchestration orchestration) {
        String contractRef = orchestration.resultContractRef();
        if (contractRef == null) {
            return;
        }
        int separator = contractRef.lastIndexOf("@");
        if (separator <= 0 || separator == contractRef.length() - 1) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 resultContractRef 不是固定版本引用（id@version）: "
                            + contractRef);
        }
        String contractId = contractRef.substring(0, separator);
        int version;
        try {
            version = Integer.parseInt(contractRef.substring(separator + 1).trim());
        } catch (NumberFormatException e) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 resultContractRef 版本不是数字: " + contractRef);
        }
        if (version <= 0) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + draft.agentId() + " v" + draft.version()
                            + " 的 resultContractRef 版本必须为正数: " + contractRef);
        }
        CapResultContractDef contract = contracts.find(tenantId, contractId, version)
                .orElseThrow(() -> new ConfigPublishRejectedException(
                        ReasonCode.RESOURCE_UNKNOWN, "orchestration",
                        "输出契约引用不存在: contract=" + contractId
                                + ", version=" + version));
        if (!contract.isPublished()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESULT_CONTRACT_NOT_PUBLISHED,
                    "orchestration",
                    "输出契约引用未发布: contract=" + contractId
                            + ", version=" + version);
        }
        if (contract.isDeprecated()) {
            // ⚠️ **停用只挡「新引用」，不追溯存量**（06 §3.2）：
            //    已被停用的契约仍可被「停用之前就发布」的 Agent 按固定版本引用，
            //    那是存量 Run 的过渡口径（发布期已校验过，引用在发布那一刻就已成立）。
            //    但**新的发布**不得再引用它 —— 否则「停用」只是给人看的，
            //    任何人都能立刻发布一个新 Agent 把已停用的契约重新挂上去。
            throw new ConfigPublishRejectedException(ReasonCode.RESULT_CONTRACT_DEPRECATED,
                    "orchestration",
                    "输出契约引用已停用（DEPRECATED）: contract=" + contractId
                            + ", version=" + version
                            + "。已停用的契约不得被新发布引用（06 §3.2）；"
                            + "存量引用按版本继续过渡");
        }
    }

    private void validateChildren(@NonNull String tenantId,
                                  @NonNull CapAgentDef parent,
                                  @NonNull AgentOrchestration orchestration,
                                  @NonNull Deque<String> path,
                                  int depth) {
        if (depth + 1 > PermissionResolver.MAX_STATIC_CLOSURE_DEPTH) {
            throw new ConfigPublishRejectedException(ReasonCode.DELEGATION_DEPTH_EXCEEDED,
                    "orchestration",
                    "Agent " + parent.agentId() + " v" + parent.version()
                            + " 的子 Agent 闭包超过最大静态深度 "
                            + PermissionResolver.MAX_STATIC_CLOSURE_DEPTH);
        }
        for (ChildAgentRef ref : orchestration.childAgentRefs()) {
            String childKey = ref.key();
            if (path.contains(childKey)) {
                throw new ConfigPublishRejectedException(ReasonCode.AGENT_REF_CYCLE,
                        "childAgentRefs",
                        "Agent " + parent.agentId() + " v" + parent.version()
                                + " 的子 Agent 引用形成环: " + childKey);
            }
            CapAgentDef childDef = agentDefs.find(tenantId, ref.agentId(), ref.releaseVersion())
                    .orElseThrow(() -> new ConfigPublishRejectedException(
                            ReasonCode.RESOURCE_UNKNOWN, "childAgentRefs",
                            "子 Agent 引用不存在: agent=" + ref.agentId()
                                    + ", version=" + ref.releaseVersion()));
            if (!childDef.isPublished()) {
                throw new ConfigPublishRejectedException(ReasonCode.AGENT_REF_NOT_PUBLISHED,
                        "childAgentRefs",
                        "子 Agent 引用未发布: agent=" + ref.agentId()
                                + ", version=" + ref.releaseVersion()
                                + "（委派目标必须固定到已发布 release）");
            }
            AgentOrchestration childOrchestration = parse(childDef.orchestration(), childDef);
            if (childOrchestration.isLeaf() || !childOrchestration.hasChildren()) {
                continue;
            }
            path.push(childKey);
            validateChildren(tenantId, childDef, childOrchestration, path, depth + 1);
            path.pop();
        }
    }

    private static @NonNull AgentOrchestration parse(@NonNull String raw,
                                                      @NonNull CapAgentDef def) {
        try {
            return AgentOrchestrationParser.parse(raw);
        } catch (OrchestrationConfigException e) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + def.agentId() + " v" + def.version() + " 的编排声明非法: "
                            + e.getMessage());
        }
    }

    private static @NonNull String key(@NonNull String agentId, int version) {
        return agentId + "@" + version;
    }
}
