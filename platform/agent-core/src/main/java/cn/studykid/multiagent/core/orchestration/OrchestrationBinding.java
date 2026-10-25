package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 编排声明的<b>固化视图</b>（多级 Agent v1.1 · MA-T15 / MA-T18）。
 *
 * <p>⚠️ <b>为什么编排声明必须进闭包</b>：
 * 闭包指纹是「本 Run 依赖的东西没变」的<b>唯一证明</b>（§6.4）。
 * 而编排声明里的每一项都是<b>行为性</b>的：
 * <ul>
 *   <li>{@code routingPolicyRef} —— 决定「委派给谁」（路由策略）；</li>
 *   <li>{@code resultContractRef} —— 决定「子 Agent 的输出按什么 Schema 校验」；</li>
 *   <li>{@code timeoutMs} / {@code maxDelegationDepth} / {@code maxChildInvocations}
 *       —— 决定「整棵调用树能跑多久、能跑多深、能调多少次」（MA-T09 的共享上限）；</li>
 *   <li>{@code failurePolicy} —— 决定「子调用失败后父 Agent 怎么办」。</li>
 * </ul>
 * 这些若不入指纹，就会出现最危险的一种局面：
 * <b>指纹说「没变」，行为却变了</b> ——
 * 在途 Run 不失效、旧审批被继续复用（MA-T18），
 * 而运维在审计里看到的是「配置没动过」。
 *
 * <p>⚠️ <b>与「委派关系≠授权关系」不冲突</b>：
 * 本对象只承载<b>依赖信息</b>（声明了什么），
 * <b>不授予任何权限</b> —— 子 Agent 的工具仍然不并入父闭包的工具集，
 * 逐跳授权仍在每一跳独立判定（FR-025）。
 *
 * <p>⚠️ <b>为什么固化「引用」而不是「引用对象的内容」</b>：
 * 输出契约、路由策略都是<b>固定版本引用</b>（{@code id@version}），
 * 其内容指纹由各自的内容指纹机制负责；本对象固化的是
 * 「本 Agent 依赖的是哪个固定版本」这一事实。
 * 引用指向的版本行内容被就地改写时，那一行自己的内容指纹会变，
 * 由发布闭包校验（{@code AgentPublishClosureValidator}）与审批绑定负责发现。
 *
 * @param mode                编排模式（LEAF / ROUTER / SEQUENTIAL）
 * @param routingPolicyRef    固定版本的路由策略引用（可空）
 * @param resultContractRef   固定版本的输出契约引用（可空）
 * @param maxDelegationDepth  最大委派深度（含根 Agent 计为 1）
 * @param maxChildInvocations 单个父 Agent 允许发起的最大子调用次数
 * @param timeoutMs           本级委派链路的总超时预算（毫秒）
 * @param failurePolicy       子调用失败策略
 */
public record OrchestrationBinding(
        @NonNull OrchestrationMode mode,
        @Nullable String routingPolicyRef,
        @Nullable String resultContractRef,
        int maxDelegationDepth,
        int maxChildInvocations,
        long timeoutMs,
        @NonNull DelegationFailurePolicy failurePolicy) {

    public OrchestrationBinding {
        if (mode == null) {
            throw new IllegalArgumentException("编排模式不能为空");
        }
        if (failurePolicy == null) {
            throw new IllegalArgumentException("失败策略不能为空");
        }
        if (maxDelegationDepth <= 0) {
            throw new IllegalArgumentException("最大委派深度必须为正数，收到: " + maxDelegationDepth);
        }
        if (maxChildInvocations < 0) {
            throw new IllegalArgumentException("最大子调用次数不能为负数，收到: " + maxChildInvocations);
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("委派超时必须为正数，收到: " + timeoutMs);
        }
    }

    /**
     * @param orchestration 编排声明
     * @return 编排声明的固化视图
     */
    public static @NonNull OrchestrationBinding of(@NonNull AgentOrchestration orchestration) {
        return new OrchestrationBinding(
                orchestration.mode(),
                orchestration.routingPolicyRef(),
                orchestration.resultContractRef(),
                orchestration.maxDelegationDepth(),
                orchestration.maxChildInvocations(),
                orchestration.timeoutMs(),
                orchestration.failurePolicy());
    }

    /**
     * 缺省语义（LEAF）的固化视图。
     *
     * <p>⚠️ 缺省值与 {@link AgentOrchestration#leaf()} <b>同源</b>，
     * 不在此处另写一份常量 —— 两份常量必然会漂移，
     * 而漂移会让「历史行按 LEAF 解析」与「闭包里固化的 LEAF」对不上。
     *
     * @return LEAF 编排的固化视图
     */
    public static @NonNull OrchestrationBinding leaf() {
        AgentOrchestration leaf = AgentOrchestration.leaf();
        return new OrchestrationBinding(
                leaf.mode(), leaf.routingPolicyRef(), leaf.resultContractRef(),
                leaf.maxDelegationDepth(), leaf.maxChildInvocations(),
                leaf.timeoutMs(), leaf.failurePolicy());
    }
}
