package cn.studykid.multiagent.application.approval;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 按风险等级的审批策略（P4-D01 / FR-013 / DEC-11）。
 *
 * <p><b>判定规则</b>（D-2）：
 * <ul>
 *   <li>{@code READ} —— 只读，无副作用 → <b>不需审批</b>；</li>
 *   <li>{@code WRITE_PROFILE} / {@code WRITE_EXTERNAL} —— 有副作用 → <b>需审批</b>。</li>
 * </ul>
 *
 * <p><b>⚠️ 未登记等级一律按最严处理</b>（fail-closed）：
 * 新增 {@link ToolRiskLevel} 枚举值后若忘记更新本策略，
 * 默认「需审批」比默认「放行」安全得多——
 * 与 {@code ConfigFailClosedTest} 的既有语义一致。
 *
 * <p><b>依据</b>：P0-D00 §3.2 —— {@code record_weakness} 是首期
 * <b>唯一</b>的有副作用工具，正是红线 4/5/7 的实证载体。
 */
public final class RiskBasedApprovalPolicy implements ApprovalPolicy {

    /** 明确「不需审批」的风险等级（白名单）。 */
    private static final Set<ToolRiskLevel> NO_APPROVAL_LEVELS = Set.of(ToolRiskLevel.READ);

    @Override
    public @NonNull ApprovalDecision evaluate(@NonNull ToolHandler handler) {
        ToolRiskLevel level = handler.riskLevel();
        // ⚠️ null 视为最严：拿不到风险等级时不得放行有副作用的动作
        if (level == null) {
            return ApprovalDecision.required("风险等级未知，按最严处理");
        }
        if (NO_APPROVAL_LEVELS.contains(level)) {
            return ApprovalDecision.notRequired();
        }
        return ApprovalDecision.required(level.name());
    }

    /** @return 需审批的风险等级集合（文档/审计用）。 */
    public static @NonNull Set<ToolRiskLevel> approvalRequiredLevels() {
        return Set.of(ToolRiskLevel.WRITE_PROFILE, ToolRiskLevel.WRITE_EXTERNAL);
    }

    /**
     * 审批结论来源（P4-D01 / E-2 可插拔契约）。
     *
     * <p><b>⚠️ 这是给「外部人类审批台」预留的接口</b>：
     * 真实审批台（HTTP / 工作流引擎）实现本接口即可接入，
     * 无需改动 {@code ToolInvocationGate}。
     *
     * <p><b>⚠️ 失败必须 {@link Verdict#FAIL_CLOSED}</b>：
     * 审批权威不可用时放行 = 无审批的副作用动作 = Sev-0。
     */
    public interface ApprovalSource {

        /** @return 针对该租户与该工具的审批结论。 */
        @NonNull Verdict verdictFor(@NonNull String tenantId, @NonNull ToolHandler handler);

        /** 审批结论。 */
        enum Verdict {
            /** 明确批准。 */
            APPROVED,
            /** 明确拒绝。 */
            REJECTED,
            /** ⚠️ 权威不可用 / 超时 / 未接入 —— <b>fail-closed</b>。 */
            FAIL_CLOSED
        }
    }

    /**
     * 配置化的本地审批来源（P4-D01 / D-3）。
     *
     * <p><b>用途</b>：在外部审批台就绪前，让链路<b>端到端可跑通</b>——
     * 需要审批的工具能按配置获得「批准 / 拒绝」，从而验证
     * D5 的审批消费与 D7 的审计链路在生产装配下真实生效。
     *
     * <p><b>⚠️ 它不是审批台</b>：只读配置、无 UI、无通知、无二次确认。
     * 配置缺失 → {@link Verdict#FAIL_CLOSED}（fail-closed，绝不默认批准）。
     */
    public static final class PolicyEngineApprovalSource implements ApprovalSource {

        /** 租户 → (工具名 → 结论)。 */
        private final Map<String, Map<String, Verdict>> policy;

        public PolicyEngineApprovalSource(
                @NonNull Map<String, Map<String, Verdict>> policy) {
            this.policy = Map.copyOf(policy);
        }

        /** 构造空策略（全部 fail-closed）。 */
        public PolicyEngineApprovalSource() {
            this(Map.of());
        }

        @Override
        public @NonNull Verdict verdictFor(@NonNull String tenantId,
                                           @NonNull ToolHandler handler) {
            Map<String, Verdict> byTool = policy.get(tenantId);
            if (byTool == null) {
                // ⚠️ 未登记该租户 → fail-closed（不能因为「没配置」就默认批准）
                return Verdict.FAIL_CLOSED;
            }
            Verdict verdict = byTool.get(handler.toolName());
            // ⚠️ 未登记该工具 → fail-closed
            return verdict == null ? Verdict.FAIL_CLOSED : verdict;
        }

        /** @return 已配置的租户数（可观测）。 */
        public int configuredTenants() {
            return policy.size();
        }
    }
}