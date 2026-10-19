package cn.studykid.multiagent.application.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy.ApprovalSource.Verdict;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 审批策略与权威来源测试（P4-D01 / FR-013 / E-2）。
 *
 * <p><b>核心命题</b>：
 * <ol>
 *   <li>按<b>风险等级</b>判定是否需要审批，未登记等级 fail-closed；</li>
 *   <li>审批权威来源可插拔，且<b>拿不到「明确批准」即拒绝</b>。</li>
 * </ol>
 */
class RiskBasedApprovalPolicyTest {

    private final RiskBasedApprovalPolicy policy = new RiskBasedApprovalPolicy();

    private static ToolHandler tool(ToolRiskLevel level) {
        return new ToolHandler() {
            @Override public String toolName() { return "t-" + level; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolRiskLevel riskLevel() { return level; }
            @Override public void validate(Map<String, Object> a) { }
            @Override public ToolResult execute(ToolExecutionContext c,
                    Map<String, Object> a) {
                return ToolResult.of("ok");
            }
        };
    }

    // ── A-1…A-3：按风险等级判定 ─────────────────────────────────────

    @Nested
    @DisplayName("A-1…A-3 按风险等级判定（D-2）")
    class RiskLevelBasedDecision {

        @Test
        @DisplayName("⚠️⚠️ WRITE_EXTERNAL / WRITE_PROFILE → 需要审批（有副作用）")
        void writeLevelsRequireApproval() {
            for (var level : RiskBasedApprovalPolicy.approvalRequiredLevels()) {
                var decision = policy.evaluate(tool(level));
                assertThat(decision.requiresApproval())
                        .as("⚠️⚠️ 有副作用的工具 %s 必须需要审批", level)
                        .isTrue();
                assertThat(decision.reason())
                        .as("⚠️ 判定依据须写入审计（支持事后复核）")
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("✅ READ → 不需审批（不误拦只读工具）")
        void readDoesNotRequireApproval() {
            assertThat(policy.evaluate(tool(ToolRiskLevel.READ)).requiresApproval())
                    .as("⚠️ 只读工具被拦会让功能不可用")
                    .isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ 风险等级未知（null）→ 需要审批（fail-closed）")
        void unknownLevelRequiresApproval() {
            var decision = policy.evaluate(tool(null));
            assertThat(decision.requiresApproval())
                    .as("⚠️⚠️ 拿不到风险等级时必须按最严处理——"
                            + "默认放行 = 无审批的副作用动作")
                    .isTrue();
        }
    }

    // ── A-4/A-5：审批权威来源 ──────────────────────────────────────

    @Nested
    @DisplayName("A-4/A-5 审批权威来源（D-3 · E-2）")
    class ApprovalSourceBehaviour {

        private final ToolHandler handler = tool(ToolRiskLevel.WRITE_EXTERNAL);

        @Test
        @DisplayName("⚠️ 已登记 APPROVED → 放行")
        void configuredApproved() {
            var source = new RiskBasedApprovalPolicy.PolicyEngineApprovalSource(
                    Map.of("tenant-1", Map.of(handler.toolName(), Verdict.APPROVED)));
            assertThat(source.verdictFor("tenant-1", handler))
                    .isEqualTo(Verdict.APPROVED);
        }

        @Test
        @DisplayName("⚠️ 已登记 REJECTED → 拒绝")
        void configuredRejected() {
            var source = new RiskBasedApprovalPolicy.PolicyEngineApprovalSource(
                    Map.of("tenant-1", Map.of(handler.toolName(), Verdict.REJECTED)));
            assertThat(source.verdictFor("tenant-1", handler))
                    .isEqualTo(Verdict.REJECTED);
        }

        @Test
        @DisplayName("⚠️⚠️ 租户未登记 → FAIL_CLOSED（不能因「没配置」就默认批准）")
        void unknownTenantFailsClosed() {
            var source = new RiskBasedApprovalPolicy.PolicyEngineApprovalSource(
                    Map.of("tenant-1", Map.of(handler.toolName(), Verdict.APPROVED)));
            assertThat(source.verdictFor("tenant-other", handler))
                    .as("⚠️⚠️ 未登记租户必须 fail-closed")
                    .isEqualTo(Verdict.FAIL_CLOSED);
        }

        @Test
        @DisplayName("⚠️⚠️ 工具未登记 → FAIL_CLOSED")
        void unknownToolFailsClosed() {
            var source = new RiskBasedApprovalPolicy.PolicyEngineApprovalSource(
                    Map.of("tenant-1", Map.of("other-tool", Verdict.APPROVED)));
            assertThat(source.verdictFor("tenant-1", handler))
                    .as("⚠️⚠️ 未登记工具必须 fail-closed")
                    .isEqualTo(Verdict.FAIL_CLOSED);
        }

        @Test
        @DisplayName("⚠️⚠️ 空策略（默认构造）→ 恒 FAIL_CLOSED")
        void emptyPolicyFailsClosed() {
            var source = new RiskBasedApprovalPolicy.PolicyEngineApprovalSource();
            assertThat(source.verdictFor("any-tenant", handler))
                    .as("⚠️⚠️ 默认策略必须 fail-closed——绝不能默认批准")
                    .isEqualTo(Verdict.FAIL_CLOSED);
            assertThat(source.configuredTenants()).isZero();
        }
    }

    // ── 契约兼容性 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 策略实现 ports 层 ApprovalPolicy 契约（模块依赖方向正确）")
    void implementsPortsContract() {
        // ⚠️ 本断言的意义：RiskBasedApprovalPolicy 在 application 层，
        //    ApprovalPolicy 在 ports 层——application → ports 是允许的方向。
        //    若当初把 ApprovalPolicy 留在 worker 层，application 就会
        //    反向依赖 worker（违反模块依赖方向）。
        ApprovalPolicy asPort = policy;
        assertThat(asPort.evaluate(tool(ToolRiskLevel.WRITE_EXTERNAL)).requiresApproval())
                .isTrue();
    }
}