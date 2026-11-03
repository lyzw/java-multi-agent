package cn.studykid.multiagent.infrastructure.security;

import java.util.List;

import cn.studykid.multiagent.core.security.Action;

import org.jspecify.annotations.NonNull;

/**
 * 影子期（P1）的<b>内置策略规则</b> —— 只覆盖「已经接了影子钩子」的那批资源类型。
 *
 * <p>⚠️ <b>为什么必须有内置规则，而不是等配置中心下发</b>：
 * {@link RuleBasedPolicyEngine} 对「无匹配规则」返回 {@code Deny(NO_RULE)}（fail-closed，正确）。
 * 但影子期一旦某资源类型没有规则，每一次查询都会落到「Gate 拒绝」这条 mismatch 上 ——
 * <b>恒定的假 mismatch 会把真实违例淹掉</b>，比不接更糟。
 * 所以接线时必须同时保证「钩子用到的每个资源类型都有对应规则」，
 * 这条对应关系由 {@code ShadowPolicyCoverageTest} 扫描源码钉死（新加资源类型不登记即红）。
 *
 * <p><b>规则来源</b>：逐条对齐 D1 基线
 * {@code docs/权限管控体系/authz/policy-mapping.yaml}（{@code agent_dev}，50 条策略，
 * 全部 {@code TENANT_ONLY}）。因此这里同域资源一律 {@code TENANT_ONLY} + {@code TENANT}。
 *
 * <p>⚠️ <b>两个例外，必须记住原因</b>：
 * <ol>
 *   <li>{@code platform.agent_trace_metric} —— 逻辑资源名（非物理表名）。其基线是 {@code NONE}
 *       （该表带 {@code tenant_id} 但<b>没有 RLS 策略</b>，属 D1 记的 9 张缺口之一）。
 *       缺口表的隔离<b>本来就只靠应用层 WHERE</b>，故期望值仍是 {@code TENANT_ONLY} ——
 *       影子期正好用它来证明「应用层谓词确实在」。</li>
 *   <li>哨兵（共享）行：平台域无哨兵模型 ⇒ {@code TENANT_WITH_SHARED} 不出现。
 *       RAG 域的哨兵规则在 {@code policy-mapping-rag.yaml}，随 RAG 侧接线（D5 后续）再引入。</li>
 * </ol>
 */
public final class AuthzShadowPolicyDefaults {

    /**
     * 已接影子钩子的资源类型（与各仓库传入 {@code AuthzShadowHooks} 的字面量一致）。
     *
     * <p>顺序即 {@link PolicyRule} 的 id 顺序，便于日志比对。
     */
    public static final List<String> RESOURCE_TYPES = List.of(
            "agent",
            "agent_version",
            "run",
            "run_step",
            "weakness_record",
            "tool_operation",
            "budget_reservation",
            "budget_account",
            "agent_invocation",
            // 逻辑资源名（物理表 agent_trace_metric，基线 scope=NONE 的缺口表）
            "platform.agent_trace_metric");

    private AuthzShadowPolicyDefaults() {
    }

    /**
     * 内置规则集：{@code RETRIEVE + TENANT + TENANT_ONLY}。
     *
     * <p>⚠️ 动作只有 {@code RETRIEVE}：影子钩子当前只观测「读路径」
     * （{@code AuthzShadowHooks} 内部固定 {@code Action.RETRIEVE}）。
     * 写路径的隔离由 RLS 的 {@code WITH CHECK} 与各仓 {@code WHERE tenant_id} 保证，
     * 属 P2 双写期的接入范围 —— <b>不要</b>在此假装覆盖。
     */
    public static @NonNull List<PolicyRule> rules() {
        return RESOURCE_TYPES.stream()
                .map(t -> new PolicyRule("shadow-" + t, t, Action.RETRIEVE,
                        ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY))
                .toList();
    }

    /** 内置规则直接构成的策略存储（构造期校验 R3 不变式）。 */
    public static @NonNull PolicyStore store() {
        return new InMemoryPolicyStore(rules());
    }
}
