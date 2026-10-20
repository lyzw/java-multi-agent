package cn.studykid.multiagent.application.approval;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 按风险等级的审批策略（P4-D01 / FR-013 / DEC-11 / P4-D08 R-3）。
 *
 * <p><b>判定规则</b>（D-2 / P4-D08）：
 * <ul>
 *   <li>{@code NOT_REQUIRED} —— 不需审批；</li>
 *   <li>{@code OPTIONAL} —— <b>默认不阻塞</b>，但满足条件时仍可触发；</li>
 *   <li>{@code REQUIRED} —— 需审批。</li>
 * </ul>
 *
 * <p><b>⚠️ 未登记等级一律按最严处理</b>（fail-closed）：
 * 新增 {@link ToolRiskLevel} 枚举值后若忘记更新本策略，
 * 默认「需审批」比默认「放行」安全得多——
 * 与 {@code ConfigFailClosedTest} 的既有语义一致。
 *
 * <p>⚠️ <b>依据 P4-D08 R-3 修复</b>：本类原先硬编码「非 READ 一律需审批」，
 * 与 {@code application.yml} 声明的 {@code agent.approval.required-by-risk}
 * （{@code READ: NOT_REQUIRED, WRITE_PROFILE: OPTIONAL, WRITE_EXTERNAL: REQUIRED}）
 * <b>相矛盾</b>——配置的 {@code OPTIONAL} 语义从未被任何代码读取，
 * 致使 {@code record_weakness} 工具即便修好注册也仍会被审批闸门拦死。
 * 现改为<b>由配置驱动</b>，让代码与声明一致。
 *
 * <p>⚠️ <b>这不是门禁降级</b>：{@code WRITE_PROFILE: OPTIONAL} 是 <b>yml 中已有的声明</b>，
 * 本次变更方向是「代码对齐配置」，不是「配置对齐代码」。
 * {@code WRITE_EXTERNAL: REQUIRED} 仍是最严；未登记工具按 {@code WRITE_EXTERNAL} 兜底（仍需审批）。
 *
 * <p><b>依据</b>：P0-D00 §3.2 —— {@code record_weakness} 是首期
 * <b>唯一</b>的有副作用工具，正是红线 4/5/7 的实证载体。
 */
public final class RiskBasedApprovalPolicy implements ApprovalPolicy {

    /**
     * 风险等级 → 审批要求。
     *
     * <p>⚠️ 空映射 = fail-closed 兜底：未登记等级按最严处理（需审批）。
     */
    private final Map<ToolRiskLevel, ApprovalRequirement> byRisk;

    /**
     * 按配置构造。
     *
     * @param byRisk 风险等级 → 审批要求（来自 {@code agent.approval.required-by-risk}）
     */
    public RiskBasedApprovalPolicy(@NonNull Map<ToolRiskLevel, ApprovalRequirement> byRisk) {
        this.byRisk = Map.copyOf(byRisk);
    }

    /**
     * fail-closed 兜底构造：空映射 → 所有等级均需审批。
     *
     * <p>⚠️ 这是<b>比配置更严</b>的方向（安全侧），不构成门禁放松。
     */
    public RiskBasedApprovalPolicy() {
        this(Map.of());
    }

    @Override
    public @NonNull ApprovalDecision evaluate(@NonNull ToolHandler handler) {
        ToolRiskLevel level = handler.riskLevel();
        // ⚠️ null 视为最严：拿不到风险等级时不得放行有副作用的动作
        if (level == null) {
            return ApprovalDecision.required("风险等级未知，按最严处理");
        }
        ApprovalRequirement configured = byRisk.get(level);
        if (configured == null) {
            // ⚠️ 未登记等级一律按最严（fail-closed）—— 与 ConfigFailClosedTest 语义一致
            return ApprovalDecision.required(level.name());
        }
        // ⚠️⚠️ **只能收紧不能放松**（P0-D03 红线 15 / R-020）：
        //    实际要求 = 该等级的默认要求 maxWith 配置值。
        //    默认要求是「代码层的下限」，配置只能把它调严，不能调松。
        //    ⚠️ 此处合并**必须在本类内部**做，不能只依赖装配层——
        //    策略若被直接构造（测试/其他装配路径），绕过合并即等于门禁可被放松。
        ApprovalRequirement effective = level.defaultApproval().maxWith(configured);
        return switch (effective) {
            case NOT_REQUIRED -> ApprovalDecision.notRequired();
            // ⚠️ OPTIONAL：默认不阻塞，但审批记录/审计链仍可由外部流程触发
            case OPTIONAL -> ApprovalDecision.notRequired();
            case REQUIRED -> ApprovalDecision.required(level.name());
        };
    }

    /**
     * @return 必然需审批的风险等级集合（文档/审计用）。
     *
     * <p>⚠️ 随配置变化：仅 {@code REQUIRED} 等级必然需审批；
     * {@code OPTIONAL} 默认不阻塞，故不在此集合内。
     */
    public @NonNull Set<ToolRiskLevel> approvalRequiredLevels() {
        EnumSet<ToolRiskLevel> result = EnumSet.noneOf(ToolRiskLevel.class);
        for (ToolRiskLevel level : ToolRiskLevel.values()) {
            // ⚠️ 与 evaluate 保持**同一判据**（否则二者会给出矛盾结论）：
            //    未配置 → 必审（fail-closed）；已配置 → 默认要求 maxWith 配置值。
            ApprovalRequirement configured = byRisk.get(level);
            ApprovalRequirement effective = configured == null
                    ? ApprovalRequirement.REQUIRED
                    : level.defaultApproval().maxWith(configured);
            if (effective == ApprovalRequirement.REQUIRED) {
                result.add(level);
            }
        }
        return Set.copyOf(result);
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