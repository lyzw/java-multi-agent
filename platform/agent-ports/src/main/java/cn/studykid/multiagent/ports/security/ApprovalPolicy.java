package cn.studykid.multiagent.ports.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 工具审批策略（P4-D01 / FR-013 / DEC-11）。
 *
 * <p><b>为何在本端口层</b>：本接口此前定义在 {@code agent-worker} 的
 * {@code ToolInvocationGate} 内，导致 {@code RiskBasedApprovalPolicy}
 * （application 层）必须反向依赖 worker 模块——
 * <b>违反模块依赖方向</b>（{@code application → infrastructure → worker}）。
 * 策略契约属于端口层，故前移至此。
 *
 * <p><b>⚠️ 判定方向必须是 fail-closed</b>：
 * 「拿不准是否需要审批」时一律判为**需要**——
 * 漏审批的代价（有副作用动作被静默执行）远大于多审批的代价。
 * 未登记的工具风险等级同样按最严处理。
 */
@FunctionalInterface
public interface ApprovalPolicy {

    /** @return 审批决策；实现<b>不得</b>返回 null（未登记即最严）。 */
    @NonNull ApprovalDecision evaluate(@NonNull ToolHandler handler);

    /**
     * 审批决策。
     *
     * @param requiresApproval 是否需要审批
     * @param reason           判定依据；写入审计以支持事后复核「为何要求审批」
     */
    record ApprovalDecision(boolean requiresApproval, @Nullable String reason) {

        public static ApprovalDecision required(String reason) {
            return new ApprovalDecision(true, reason);
        }

        public static ApprovalDecision notRequired() {
            return new ApprovalDecision(false, null);
        }
    }
}