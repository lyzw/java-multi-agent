package cn.studykid.multiagent.application.cap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.application.orchestration.OrchestrationConfigException;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;

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
 */
public class AgentPublishClosureValidator {

    private final CapAgentDefPort agentDefs;
    private final CapResultContractPort contracts;

    /**
     * ⚠️ <b>契约仓储必须显式注入</b>：契约校验缺席 = 声明了契约的 Agent
     * 可以在「契约根本不存在」的情况下被发布出去，运行期才 fail-closed ——
     * 那等于把配置错误推迟到线上才暴露。
     */
    public AgentPublishClosureValidator(@NonNull CapAgentDefPort agentDefs,
                                         @NonNull CapResultContractPort contracts) {
        this.agentDefs = agentDefs;
        this.contracts = contracts;
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
        if (orchestration.isLeaf() || !orchestration.hasChildren()) {
            return;
        }
        validateChildren(tenantId, draft, orchestration,
                new ArrayDeque<>(List.of(key(draft.agentId(), draft.version()))), 0);
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
