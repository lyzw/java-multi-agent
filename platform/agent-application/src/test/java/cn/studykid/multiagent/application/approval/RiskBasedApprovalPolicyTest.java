package cn.studykid.multiagent.application.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy.ApprovalSource.Verdict;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 审批策略与权威来源测试（P4-D01 / FR-013 / E-2 / P4-D08 R-3）。
 *
 * <p><b>核心命题</b>：
 * <ol>
 *   <li>按<b>风险等级</b>判定是否需要审批，未登记等级 fail-closed；</li>
 *   <li>审批权威来源可插拔，且<b>拿不到「明确批准」即拒绝</b>；</li>
 *   <li><b>P4-D08 R-3</b>：策略<b>由配置驱动</b>（{@code agent.approval.required-by-risk}），
 *       且「只能收紧不能放松」——配置不能把默认更严的要求调松。</li>
 * </ol>
 */
class RiskBasedApprovalPolicyTest {

    /**
     * 与 {@code application.yml} 声明一致的策略。
     *
     * <p>⚠️ 这是<b>生产装配</b>的等价形态（P4-D08 R-3）。
     */
    private static final Map<ToolRiskLevel, ApprovalRequirement> YAML_LIKE = Map.of(
            ToolRiskLevel.READ, ApprovalRequirement.NOT_REQUIRED,
            ToolRiskLevel.WRITE_PROFILE, ApprovalRequirement.OPTIONAL,
            ToolRiskLevel.WRITE_EXTERNAL, ApprovalRequirement.REQUIRED);

    private final RiskBasedApprovalPolicy policy =
            new RiskBasedApprovalPolicy(YAML_LIKE);

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
        @DisplayName("⚠️⚠️ WRITE_EXTERNAL → 需要审批（对外副作用，最严）")
        void writeExternalRequiresApproval() {
            var decision = policy.evaluate(tool(ToolRiskLevel.WRITE_EXTERNAL));
            assertThat(decision.requiresApproval())
                    .as("⚠️⚠️ 对外副作用的工具必须需要审批")
                    .isTrue();
            assertThat(decision.reason())
                    .as("⚠️ 判定依据须写入审计（支持事后复核）")
                    .isNotBlank();
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

    // ── P4-D08 R-3：配置驱动 + 只能收紧不能放松 ────────────────────

    @Nested
    @DisplayName("P4-D08 R-3 策略由配置驱动")
    class ConfigDriven {

        @Test
        @DisplayName("✅ WRITE_PROFILE(OPTIONAL) → 默认不阻塞（对齐 yml 声明）")
        void writeProfileOptionalNotBlocked() {
            assertThat(policy.evaluate(tool(ToolRiskLevel.WRITE_PROFILE)).requiresApproval())
                    .as("⚠️ yml 声明 WRITE_PROFILE: OPTIONAL，"
                            + "语义是「默认不阻塞」——代码必须与声明一致")
                    .isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ 未登记等级 → 需要审批（fail-closed 不被配置放松）")
        void unregisteredLevelRequiresApproval() {
            var partial = new RiskBasedApprovalPolicy(
                    Map.of(ToolRiskLevel.READ, ApprovalRequirement.NOT_REQUIRED));
            assertThat(partial.evaluate(tool(ToolRiskLevel.WRITE_PROFILE)).requiresApproval())
                    .as("⚠️⚠️ 未登记等级必须按最严处理")
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️⚠️ 无参构造（空映射）→ 全部需要审批（比配置更严，安全侧）")
        void emptyMappingIsFailClosed() {
            var strict = new RiskBasedApprovalPolicy();
            for (var level : ToolRiskLevel.values()) {
                assertThat(strict.evaluate(tool(level)).requiresApproval())
                        .as("⚠️⚠️ 空映射时等级 %s 必须需审批", level)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("⚠️⚠️ 配置把更严的要求放松 → 实际仍取更严者（只能收紧不能放松）")
        void configCannotRelax() {
            // READ 的默认要求是 NOT_REQUIRED（最松），改配 REQUIRED 应生效（收紧）
            var tightened = new RiskBasedApprovalPolicy(
                    Map.of(ToolRiskLevel.READ, ApprovalRequirement.REQUIRED));
            assertThat(tightened.evaluate(tool(ToolRiskLevel.READ)).requiresApproval())
                    .as("✅ 配置可以收紧")
                    .isTrue();
            // WRITE_EXTERNAL 的默认要求是 REQUIRED（最严），改配 NOT_REQUIRED 应被忽略
            var relaxed = new RiskBasedApprovalPolicy(
                    Map.of(ToolRiskLevel.WRITE_EXTERNAL, ApprovalRequirement.NOT_REQUIRED));
            assertThat(relaxed.evaluate(tool(ToolRiskLevel.WRITE_EXTERNAL)).requiresApproval())
                    .as("⚠️⚠️ 配置不得把最严等级放松——那是门禁降级")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ approvalRequiredLevels 反映当前配置")
        void requiredLevelsReflectConfig() {
            assertThat(policy.approvalRequiredLevels())
                    .as("✅ 仅 REQUIRED 等级必然需审批；OPTIONAL 默认不阻塞")
                    .containsExactly(ToolRiskLevel.WRITE_EXTERNAL);
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