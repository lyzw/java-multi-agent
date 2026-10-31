package cn.studykid.multiagent.bootstrap.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.tool.ToolHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时人工审批就绪自检单测（P1-C）。
 *
 * <p><b>锁的是「缺口必须可见」</b>：修复前「需要审批的工具在运行时恒被拒」
 * 是安全默认，但它<b>没有任何信号</b>，运维无从区分「审批未通过」与
 * 「压根没有活体审批通道」。此处确保该状态至少会出现在启动日志，
 * 并且可以被升级为启动失败。
 */
@DisplayName("ApprovalReadinessGuard · 运行时人工审批就绪自检")
class ApprovalReadinessGuardTest {

    private static final RiskBasedApprovalPolicy STRICT =
            new RiskBasedApprovalPolicy(Map.of(
                    ToolRiskLevel.READ, ApprovalRequirement.NOT_REQUIRED,
                    ToolRiskLevel.WRITE_PROFILE, ApprovalRequirement.OPTIONAL,
                    ToolRiskLevel.WRITE_EXTERNAL, ApprovalRequirement.REQUIRED));

    private static ToolHandler tool(String name, ToolRiskLevel level) {
        return new ToolHandler() {
            @Override public String toolName() { return name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolRiskLevel riskLevel() { return level; }
            @Override public void validate(Map<String, Object> arguments) { }
            @Override public ToolResult execute(ToolExecutionContext ctx,
                                                Map<String, Object> a) {
                return ToolResult.of("ok");
            }
        };
    }

    @Test
    @DisplayName("⚠️ 只有需审批的工具被列入「运行时恒被拒」清单")
    void listsOnlyToolsRequiringApproval() {
        List<ToolHandler> tools = List.of(
                tool("get_student_profile", ToolRiskLevel.READ),
                tool("record_weakness", ToolRiskLevel.WRITE_PROFILE),
                tool("notify_parent", ToolRiskLevel.WRITE_EXTERNAL));

        assertThat(ApprovalReadinessGuard.toolsRequiringApproval(tools, STRICT))
                .as("⚠️ WRITE_EXTERNAL 需审批；READ 与 OPTIONAL 的 WRITE_PROFILE 默认不阻塞")
                .containsExactly("notify_parent");
    }

    @Test
    @DisplayName("✅ 无需审批的工具齐备时不报警、也不熔断")
    void noApprovalRequiredToolsPasses() {
        List<ToolHandler> tools = List.of(tool("read_only", ToolRiskLevel.READ));
        ApprovalReadinessGuard guard =
                new ApprovalReadinessGuard(tools, STRICT, false, true);

        assertThatCode(() -> guard.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠️ 未接活体通道时不默认熔断，仅告警（保持既有可用性与安全默认）")
    void warnsWithoutFailingByDefault() {
        List<ToolHandler> tools = List.of(tool("notify_parent", ToolRiskLevel.WRITE_EXTERNAL));
        ApprovalReadinessGuard guard =
                new ApprovalReadinessGuard(tools, STRICT, false, false);

        assertThatCode(() -> guard.run(new DefaultApplicationArguments()))
                .as("⚠️ 默认不得让「审批未接线」直接打断启动——那会把大量环境一次性打挂")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠️⚠️ 开启 require-live-source 且未接通道 → fail-closed 拒绝启动")
    void failsClosedWhenRequired() {
        List<ToolHandler> tools = List.of(tool("notify_parent", ToolRiskLevel.WRITE_EXTERNAL));
        ApprovalReadinessGuard guard =
                new ApprovalReadinessGuard(tools, STRICT, false, true);

        assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify_parent");
    }

    @Test
    @DisplayName("✅ 已接活体通道时不熔断（即便开启 require-live-source）")
    void liveSourceSatisfiesRequirement() {
        List<ToolHandler> tools = List.of(tool("notify_parent", ToolRiskLevel.WRITE_EXTERNAL));
        ApprovalReadinessGuard guard =
                new ApprovalReadinessGuard(tools, STRICT, true, true);

        assertThatCode(() -> guard.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
