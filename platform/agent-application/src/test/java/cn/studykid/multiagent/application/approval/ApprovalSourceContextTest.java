package cn.studykid.multiagent.application.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * M2-1 回归测试：审批来源能拿到<b>运行期上下文</b>（runId / operationId / 参数摘要），
 * 从而做出<b>精确</b>判定，而不是「恒批」或「恒拒」。
 *
 * <p><b>缺陷背景（E2）</b>：{@code verdictFor(tenantId, handler)} 只拿租户与工具句柄，
 * 拿不到 runId / 参数 / operationId —— 任何实现只能恒拒或恒批（后者是漏洞）。
 */
class ApprovalSourceContextTest {

    private static final String TOOL = "record_weakness";

    private static final class TestTool implements ToolHandler {
        @Override public String toolName() { return TOOL; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.WRITE_EXTERNAL; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext context,
                                            Map<String, Object> arguments) {
            return ToolResult.of("ok");
        }
    }

    @Test
    @DisplayName("✅ 来源可按 operationId 精确放行单次调用")
    void sourceCanAllowSpecificOperation() {
        String wanted = "op-123";
        RiskBasedApprovalPolicy.ApprovalSource source = new RiskBasedApprovalPolicy.ApprovalSource() {
            @Override
            public RiskBasedApprovalPolicy.ApprovalSource.Verdict verdictFor(
                    String tenantId, ToolHandler handler) {
                // 旧签名拿不到上下文 → 只能恒拒
                return RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED;
            }

            @Override
            public RiskBasedApprovalPolicy.ApprovalSource.Verdict verdictFor(
                    String tenantId, ToolHandler handler,
                    RiskBasedApprovalPolicy.ApprovalSource.ApprovalContext context) {
                return wanted.equals(context.operationId())
                        ? RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED
                        : RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED;
            }
        };

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("studentId", "s-1");

        var allowed = new RiskBasedApprovalPolicy.ApprovalSource.ApprovalContext(
                "tenant-1", "run-1", wanted, "args_norm_v1:abc", "WRITE_EXTERNAL");
        var other = new RiskBasedApprovalPolicy.ApprovalSource.ApprovalContext(
                "tenant-1", "run-1", "op-999", "args_norm_v1:abc", "WRITE_EXTERNAL");

        assertThat(source.verdictFor("tenant-1", new TestTool(), allowed))
                .as("来源必须能只放行指定的那一次操作")
                .isEqualTo(RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED);
        assertThat(source.verdictFor("tenant-1", new TestTool(), other))
                .as("⚠️ 其他操作必须仍被拒 —— 上下文放行不得扩大成「一律批准」")
                .isEqualTo(RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED);
    }

    @Test
    @DisplayName("⚠️ 上下文必须携带完整绑定要素（缺一不可）")
    void contextCarriesBindingFacts() {
        var context = new RiskBasedApprovalPolicy.ApprovalSource.ApprovalContext(
                "tenant-1", "run-1", "op-1", "args_norm_v1:abc", "WRITE_EXTERNAL");

        assertThat(context.tenantId()).isEqualTo("tenant-1");
        assertThat(context.runId()).isEqualTo("run-1");
        assertThat(context.operationId()).isEqualTo("op-1");
        assertThat(context.argumentDigest())
                .as("⚠️ 只传摘要，绝不传参数原文（合规红线）")
                .startsWith("args_norm_v1:").doesNotContain("studentId");
        assertThat(context.riskLevel()).isEqualTo("WRITE_EXTERNAL");
    }

    @Test
    @DisplayName("⚠️ 未覆写上下文方法的老来源仍走旧签名（向后兼容，行为不变）")
    void legacySourceStillWorks() {
        RiskBasedApprovalPolicy.ApprovalSource legacy =
                new RiskBasedApprovalPolicy.ApprovalSource() {
                    @Override
                    public RiskBasedApprovalPolicy.ApprovalSource.Verdict verdictFor(
                            String tenantId, ToolHandler handler) {
                        return RiskBasedApprovalPolicy.ApprovalSource.Verdict.REJECTED;
                    }
                };

        var context = new RiskBasedApprovalPolicy.ApprovalSource.ApprovalContext(
                "tenant-1", "run-1", "op-1", "args_norm_v1:abc", "WRITE_EXTERNAL");

        assertThat(legacy.verdictFor("tenant-1", new TestTool(), context))
                .as("默认实现必须委托旧方法 —— 未覆写的来源行为不得改变")
                .isEqualTo(RiskBasedApprovalPolicy.ApprovalSource.Verdict.REJECTED);
    }
}
