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
 * <p>⚠️ <b>为什么「引用」与「引用对象的内容指纹」都要承载</b>（第二十四批 · A4）：
 * 固定版本引用（{@code id@version}）固化了「本 Agent 依赖的是哪个固定版本」这一事实，
 * 但<b>不承载那一行自己的内容</b> —— 于是「同一版本行被 DB 侧就地改写
 * （运维直接 UPDATE）」这一支没有任何防线：版本号没变、引用没变、
 * 编排声明没变，只有<b>被引用行的内容</b>变了。
 * 而版本行不可变只是<b>约定</b>，不是机制。
 * 故本对象同时承载被引用行的<b>内容指纹</b>：
 * 它一变，闭包指纹随之变化 → 在途 Run 失效 → 旧审批不被继续复用（MA-T18）。
 *
 * <p>⚠️ <b>与「委派关系≠授权关系」不冲突</b>：
 * 本对象只承载<b>依赖信息</b>（声明了什么、依赖了哪一版内容），
 * <b>不授予任何权限</b> —— 子 Agent 的工具仍然不并入父闭包的工具集，
 * 逐跳授权仍在每一跳独立判定（FR-025）。
 *
 * @param mode                编排模式（LEAF / ROUTER / SEQUENTIAL）
 * @param routingPolicyRef    固定版本的路由策略引用（可空）
 * @param resultContractRef   固定版本的输出契约引用（可空）
 * @param maxDelegationDepth  最大委派深度（含根 Agent 计为 1）——
 *                            <b>非 LEAF 必填</b>；LEAF 为 {@code null}（无意义）
 * @param maxChildInvocations 单个父 Agent 允许发起的最大子调用次数 ——
 *                            <b>非 LEAF 必填</b>；LEAF 为 {@code null}（无意义）
 * @param timeoutMs           本级委派链路的总超时预算（毫秒）——
 *                            <b>非 LEAF 必填</b>；LEAF 为 {@code null}（无意义）
 * @param failurePolicy       子调用失败策略
 * @param routingPolicyFingerprint 被引用的<b>路由策略行</b>的内容指纹（可空；
 *                                {@code null} = 未声明引用，或引用行<b>解析不出</b>）
 * @param resultContractFingerprint 被引用的<b>输出契约行</b>的内容指纹（可空；
 *                                {@code null} = 未声明引用，或引用行<b>解析不出</b>）
 */
public record OrchestrationBinding(
        @NonNull OrchestrationMode mode,
        @Nullable String routingPolicyRef,
        @Nullable String resultContractRef,
        @Nullable Integer maxDelegationDepth,
        @Nullable Integer maxChildInvocations,
        @Nullable Long timeoutMs,
        @NonNull DelegationFailurePolicy failurePolicy,
        @Nullable String routingPolicyFingerprint,
        @Nullable String resultContractFingerprint) {

    public OrchestrationBinding {
        if (mode == null) {
            throw new IllegalArgumentException("编排模式不能为空");
        }
        if (failurePolicy == null) {
            throw new IllegalArgumentException("失败策略不能为空");
        }
        // ⚠️ 与 {@link AgentOrchestration} **同一口径**：非 LEAF 必须显式声明
        //    三个委派限制（评审前置决策 2）；LEAF 为 null（无意义）。
        //    ⚠️ 这里是**闭包固化视图** —— 它进指纹，所以口径必须与源对象一致，
        //    否则会出现「源对象有一份限制、固化视图没有」的两种真相。
        if (mode != OrchestrationMode.LEAF
                && (maxDelegationDepth == null || maxChildInvocations == null || timeoutMs == null)) {
            throw new IllegalArgumentException(
                    "非 LEAF 模式必须显式声明 maxDelegationDepth / maxChildInvocations / timeoutMs"
                            + "（平台不提供默认值）：mode=" + mode);
        }
        if (maxDelegationDepth != null && maxDelegationDepth <= 0) {
            throw new IllegalArgumentException("最大委派深度必须为正数，收到: " + maxDelegationDepth);
        }
        if (maxChildInvocations != null && maxChildInvocations < 0) {
            throw new IllegalArgumentException("最大子调用次数不能为负数，收到: " + maxChildInvocations);
        }
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new IllegalArgumentException("委派超时必须为正数，收到: " + timeoutMs);
        }
    }

    /**
     * 兼容构造器（<b>不含</b>被引用行的内容指纹）。
     *
     * <p>⚠️ **为什么保留**：被引用行的内容指纹是<b>第二十四批才补上</b>的
     * 第二道防线 —— 存量调用点（LEAF 场景、测试替身）不该被迫逐个改参数。
     * ⚠️ 走本构造器的绑定，其两个指纹为 {@code null}
     * —— 语义是「<b>未承载</b>」，不是「被引用行没变」。
     */
    public OrchestrationBinding(
            @NonNull OrchestrationMode mode,
            @Nullable String routingPolicyRef,
            @Nullable String resultContractRef,
            @Nullable Integer maxDelegationDepth,
            @Nullable Integer maxChildInvocations,
            @Nullable Long timeoutMs,
            @NonNull DelegationFailurePolicy failurePolicy) {
        this(mode, routingPolicyRef, resultContractRef, maxDelegationDepth,
                maxChildInvocations, timeoutMs, failurePolicy, null, null);
    }

    /**
     * @param orchestration 编排声明
     * @return 编排声明的固化视图（<b>不含</b>被引用行的内容指纹）
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
     * 携带<b>被引用行内容指纹</b>的固化视图（第二十四批 · A4）。
     *
     * @param orchestration             编排声明
     * @param routingPolicyFingerprint  被引用的路由策略行内容指纹（可空）
     * @param resultContractFingerprint 被引用的输出契约行内容指纹（可空）
     * @return 编排声明的固化视图
     */
    public static @NonNull OrchestrationBinding of(
            @NonNull AgentOrchestration orchestration,
            @Nullable String routingPolicyFingerprint,
            @Nullable String resultContractFingerprint) {
        return new OrchestrationBinding(
                orchestration.mode(),
                orchestration.routingPolicyRef(),
                orchestration.resultContractRef(),
                orchestration.maxDelegationDepth(),
                orchestration.maxChildInvocations(),
                orchestration.timeoutMs(),
                orchestration.failurePolicy(),
                routingPolicyFingerprint,
                resultContractFingerprint);
    }

    /**
     * 缺省语义（LEAF）的固化视图。
     *
     * <p>⚠️ 与 {@link AgentOrchestration#leaf()} <b>同源</b>，
     * 不在此处另写一份常量 —— 两份常量必然会漂移，
     * 而漂移会让「历史行按 LEAF 解析」与「闭包里固化的 LEAF」对不上。
     * ⚠️ 平台默认值已取消（评审前置决策 2），故 LEAF 的三个限制一律为
     * {@code null}（不适用），**不是** 0 或任何默认值。
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

    /**
     * @return 是否声明了任何<b>被引用行</b>（路由策略 / 输出契约）
     *
     * <p>⚠️ 这是「引用是否存在」的判据，<b>不是</b>「指纹是否已承载」的判据 ——
     * 两者混为一谈会让「声明了引用却没解析出指纹」被当成「没声明引用」。
     */
    public boolean hasReferencedRows() {
        return (routingPolicyRef != null && !routingPolicyRef.isBlank())
                || (resultContractRef != null && !resultContractRef.isBlank());
    }
}
