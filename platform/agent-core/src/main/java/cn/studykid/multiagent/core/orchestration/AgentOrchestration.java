package cn.studykid.multiagent.core.orchestration;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 的编排声明（06 变更补充「AgentDefinition 增补」）。
 *
 * <p>⚠️ <b>本对象只描述「委派拓扑」，不授予任何权限</b>：
 * 子 Agent 引用意味着「本 Agent <b>允许</b>把工作交给它」，
 * 而不是「调用方因此获得子 Agent 的能力」或「子 Agent 继承父 Agent 的权限」。
 * 逐跳授权在每一跳独立判定（FR-025 / 07 变更补充「委派时序」第 4 步）。
 *
 * <p>⚠️ <b>缺省即 LEAF</b>：{@link #leaf()} 是所有无 {@code orchestration} 字段的
 * 历史 Agent 的解析结果（MA-T01）。不得因为「读不到编排声明」就默认放行委派。
 *
 * @param mode                编排模式
 * @param childAgentRefs      固定版本的子 Agent 引用（LEAF 必须为空）
 * @param routingPolicyRef    固定版本的路由策略引用（ROUTER 必填）
 * @param maxDelegationDepth  最大委派深度（含根 Agent 计为 1）
 * @param maxChildInvocations 单个父 Agent 允许发起的最大子调用次数
 * @param timeoutMs           本级委派链路的总超时预算（毫秒）
 * @param failurePolicy       子调用失败策略
 * @param resultContractRef   固定版本的<b>链接契约</b>引用（可空）：约束的是
 *                            <b>本 Agent 的子 Agent 的输出</b>（本 Agent 作为
 *                            调用方时的期望形状），不是本 Agent 自己的输出 ——
 *                            每一跳的契约都由**调用方**声明
 */
public record AgentOrchestration(
        @NonNull OrchestrationMode mode,
        @NonNull List<ChildAgentRef> childAgentRefs,
        @Nullable String routingPolicyRef,
        int maxDelegationDepth,
        int maxChildInvocations,
        long timeoutMs,
        @NonNull DelegationFailurePolicy failurePolicy,
        @Nullable String resultContractRef
) {

    /**
     * 平台默认最大委派深度（含根 Agent 计为 1）。
     *
     * <p>⚠️ NFR-012 要求「默认值必须由配置显式给出或使用经过批准的平台默认值」——
     * 此处即为<b>经过批准的平台默认值</b>，调用方可在配置中显式覆盖。
     */
    public static final int DEFAULT_MAX_DELEGATION_DEPTH = 2;

    /** 平台默认最大子调用次数。 */
    public static final int DEFAULT_MAX_CHILD_INVOCATIONS = 8;

    /** 平台默认委派链路超时（毫秒）。 */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    public AgentOrchestration {
        if (mode == null) {
            throw new IllegalArgumentException("编排模式不能为空");
        }
        if (failurePolicy == null) {
            throw new IllegalArgumentException("失败策略不能为空");
        }
        childAgentRefs = childAgentRefs == null ? List.of() : List.copyOf(childAgentRefs);
        if (mode == OrchestrationMode.LEAF && !childAgentRefs.isEmpty()) {
            // ⚠️ LEAF 与子引用互斥：让 LEAF 携带子引用会让「是否允许委派」出现两种解读，
            //    授权判定就失去了唯一真相。配置期直接拒绝，而不是运行时静默忽略。
            throw new IllegalArgumentException("LEAF 模式不得声明子 Agent 引用");
        }
        if (mode == OrchestrationMode.ROUTER
                && (routingPolicyRef == null || routingPolicyRef.isBlank())) {
            // ⚠️ ROUTER 必须锚定固定版本的路由策略：否则路由行为无法随发布闭包固化，
            //    路由输出也就无从复核（MA-T05 / MA-T15）。
            throw new IllegalArgumentException("ROUTER 模式必须声明固定版本的路由策略引用");
        }
        if (maxDelegationDepth <= 0) {
            throw new IllegalArgumentException("最大委派深度必须为正数，收到: " + maxDelegationDepth);
        }
        if (maxChildInvocations < 0) {
            throw new IllegalArgumentException(
                    "最大子调用次数不能为负数，收到: " + maxChildInvocations);
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("委派超时必须为正数，收到: " + timeoutMs);
        }
        for (ChildAgentRef ref : childAgentRefs) {
            if (ref == null) {
                throw new IllegalArgumentException("子 Agent 引用不能为 null");
            }
        }
        // ⚠️ 同一子 release 不得重复声明：否则子调用次数预算被重复计入，
        //    且「一次声明多处出现」会让发布闭包的边集合出现重复。
        for (int i = 0; i < childAgentRefs.size(); i++) {
            for (int j = i + 1; j < childAgentRefs.size(); j++) {
                if (childAgentRefs.get(i).key().equals(childAgentRefs.get(j).key())) {
                    throw new IllegalArgumentException(
                            "子 Agent 引用重复: " + childAgentRefs.get(i).key());
                }
            }
        }
    }

    /**
     * @return LEAF 编排（缺省语义）
     */
    public static @NonNull AgentOrchestration leaf() {
        return new AgentOrchestration(
                OrchestrationMode.LEAF,
                List.of(),
                null,
                DEFAULT_MAX_DELEGATION_DEPTH,
                DEFAULT_MAX_CHILD_INVOCATIONS,
                DEFAULT_TIMEOUT_MS,
                DelegationFailurePolicy.FAIL_FAST,
                null);
    }

    /**
     * @return 是否为叶子 Agent（不参与任何委派）
     */
    public boolean isLeaf() {
        return mode == OrchestrationMode.LEAF;
    }

    /**
     * @param agentId 子 Agent 标识
     * @return 声明的子 Agent 引用；未声明时为 {@link Optional#empty()}
     */
    public @NonNull Optional<ChildAgentRef> child(@NonNull String agentId) {
        return childAgentRefs.stream().filter(ref -> ref.agentId().equals(agentId)).findFirst();
    }

    /**
     * @return 是否声明了任何子 Agent
     */
    public boolean hasChildren() {
        return !childAgentRefs.isEmpty();
    }
}
