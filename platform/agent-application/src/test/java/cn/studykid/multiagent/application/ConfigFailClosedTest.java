package cn.studykid.multiagent.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.ConfigResolutionException;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.RunConfigSnapshotFactory;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置基线与 fail-closed 行为测试（TODO-9）。
 *
 * <p>对应 P0-D06 §8 DoD：「配置缺失/非法时拒绝而非放行（fail-closed）有测试」。
 * 本测试是 R-020（配置被当作绕过安全门禁的后门）的自动化防护。
 */
class ConfigFailClosedTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    private RunConfigSnapshotFactory factory;
    private Map<String, ToolRiskLevel> riskMap;
    private Map<ToolRiskLevel, ApprovalRequirement> approvalByRisk;

    @BeforeEach
    void setUp() {
        factory = new RunConfigSnapshotFactory(Clock.fixed(NOW, ZoneOffset.UTC));
        riskMap = new HashMap<>(Map.of(
                "get_student_profile", ToolRiskLevel.READ,
                "record_weakness", ToolRiskLevel.WRITE_PROFILE));
        approvalByRisk = new HashMap<>(Map.of(
                ToolRiskLevel.READ, ApprovalRequirement.NOT_REQUIRED,
                ToolRiskLevel.WRITE_PROFILE, ApprovalRequirement.OPTIONAL,
                ToolRiskLevel.WRITE_EXTERNAL, ApprovalRequirement.REQUIRED));
    }

    private RunConfigSnapshot create(String tool) {
        return factory.create("run-1", new BigDecimal("2.00"), 12, 20, riskMap, approvalByRisk, tool);
    }

    @Nested
    @DisplayName("fail-closed：配置缺失一律拒绝，不取默认值放行")
    class MissingConfig {

        @Test
        @DisplayName("成本上限缺失 → 拒绝创建 Run")
        void missingCostRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", null, 12, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class)
                    .hasMessageContaining("fail-closed")
                    .hasMessageContaining(PlatformConfig.KEY_MAX_COST_PER_RUN);
        }

        @Test
        @DisplayName("调用上限缺失 → 拒绝")
        void missingLlmCallsRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", new BigDecimal("2.00"), null, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class)
                    .hasMessageContaining(PlatformConfig.KEY_MAX_LLM_CALLS);
        }

        @Test
        @DisplayName("步数上限缺失 → 拒绝")
        void missingStepsRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", new BigDecimal("2.00"), 12, null, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class)
                    .hasMessageContaining(PlatformConfig.KEY_MAX_STEPS);
        }

        @Test
        @DisplayName("⚠️ 不允许静默使用 DEC-9 默认值兜底")
        void noSilentDefaultFallback() {
            // 明确验证：缺失配置抛异常，而非返回 DEFAULT_MAX_COST_PER_RUN
            assertThatThrownBy(() -> factory.create(
                    "run-1", null, 12, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class);
            assertThat(PlatformConfig.DEFAULT_MAX_COST_PER_RUN).isEqualByComparingTo("2.00");
        }
    }

    @Nested
    @DisplayName("fail-closed：非法配置一律拒绝")
    class InvalidConfig {

        @Test
        @DisplayName("成本上限为 0 → 拒绝")
        void zeroCostRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", new BigDecimal("0"), 12, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class)
                    .hasMessageContaining("必须为正数");
        }

        @Test
        @DisplayName("成本上限为负 → 拒绝")
        void negativeCostRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", new BigDecimal("-1.00"), 12, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class);
        }

        @Test
        @DisplayName("步数为 0 → 拒绝")
        void zeroStepsRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", new BigDecimal("2.00"), 12, 0, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class);
        }

        @Test
        @DisplayName("非数值成本 → 拒绝")
        void nonNumericCostRejected() {
            assertThatThrownBy(() -> factory.create(
                    "run-1", "abc", 12, 20, riskMap, approvalByRisk, "record_weakness"))
                    .isInstanceOf(ConfigResolutionException.class)
                    .hasMessageContaining("非数值");
        }
    }

    @Nested
    @DisplayName("审批要求解析：只能收紧不能放松")
    class ApprovalResolution {

        @Test
        @DisplayName("record_weakness(WRITE_PROFILE) → OPTIONAL")
        void writeProfileIsOptional() {
            assertThat(create("record_weakness").approvalRequired())
                    .isEqualTo(ApprovalRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("get_student_profile(READ) → NOT_REQUIRED")
        void readIsNotRequired() {
            assertThat(create("get_student_profile").approvalRequired())
                    .isEqualTo(ApprovalRequirement.NOT_REQUIRED);
        }

        @Test
        @DisplayName("⚠️ 未登记的工具按 WRITE_EXTERNAL 兜底 → REQUIRED（fail-closed）")
        void unregisteredToolFallsBackToStrictest() {
            assertThat(create("some_unknown_tool").approvalRequired())
                    .as("新工具忘记配置时必须按最高风险审批，不能漏审批")
                    .isEqualTo(ApprovalRequirement.REQUIRED);
        }

        @Test
        @DisplayName("⚠️ 配置试图放松时，取更严格者")
        void cannotRelaxBelowDefault() {
            // 把 WRITE_PROFILE 显式配置为 NOT_REQUIRED（试图放松）
            approvalByRisk.put(ToolRiskLevel.WRITE_PROFILE, ApprovalRequirement.NOT_REQUIRED);
            assertThat(create("record_weakness").approvalRequired())
                    .as("配置不得把审批要求放松到低于该风险级别默认值")
                    .isEqualTo(ApprovalRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("配置收紧时生效（READ 被配置为 REQUIRED）")
        void canTighten() {
            approvalByRisk.put(ToolRiskLevel.READ, ApprovalRequirement.REQUIRED);
            assertThat(create("get_student_profile").approvalRequired())
                    .isEqualTo(ApprovalRequirement.REQUIRED);
        }

        @Test
        @DisplayName("放松判定：REQUIRED → OPTIONAL 属门禁降级")
        void relaxationDetection() {
            assertThat(ApprovalRequirement.REQUIRED.isRelaxationTo(ApprovalRequirement.OPTIONAL)).isTrue();
            assertThat(ApprovalRequirement.OPTIONAL.isRelaxationTo(ApprovalRequirement.REQUIRED)).isFalse();
            assertThat(ApprovalRequirement.REQUIRED.isRelaxationTo(ApprovalRequirement.NOT_REQUIRED)).isTrue();
        }
    }

    @Nested
    @DisplayName("配置快照（TODO-10 / R-021）：改配置只影响新 Run")
    class SnapshotIsolation {

        @Test
        @DisplayName("快照捕获创建时的配置值")
        void snapshotCapturesValues() {
            RunConfigSnapshot s = create("record_weakness");
            assertThat(s.maxCostPerRun()).isEqualByComparingTo("2.00");
            assertThat(s.maxLlmCalls()).isEqualTo(12);
            assertThat(s.maxSteps()).isEqualTo(20);
            assertThat(s.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("⚠️ 配置变更后，已创建 Run 的快照不受影响")
        void snapshotIsImmutableAcrossConfigChanges() {
            RunConfigSnapshot before = create("record_weakness");

            // 模拟运行时改配置
            approvalByRisk.put(ToolRiskLevel.WRITE_PROFILE, ApprovalRequirement.REQUIRED);
            RunConfigSnapshot after = create("run-2");

            assertThat(before.approvalRequired())
                    .as("旧 Run 快照不得被配置变更影响")
                    .isEqualTo(ApprovalRequirement.OPTIONAL);
            assertThat(after.approvalRequired())
                    .as("新 Run 应采用新配置")
                    .isEqualTo(ApprovalRequirement.REQUIRED);
        }

        @Test
        @DisplayName("快照为不可变值对象，审计导出不含学生数据")
        void snapshotAuditMapIsSafe() {
            RunConfigSnapshot s = create("record_weakness");
            assertThat(s.toAuditMap())
                    .containsEntry("runId", "run-1")
                    .containsEntry("maxLlmCalls", 12)
                    .containsEntry("maxSteps", 20)
                    .containsEntry("approvalRequired", "OPTIONAL");
            assertThat(s.toAuditMap().keySet())
                    .as("审计导出不得含学生可识别信息（P0-D00 §4 白名单）")
                    .doesNotContain("studentId", "name", "school");
        }

        @Test
        @DisplayName("步数与调用上限判定")
        void limitsEnforced() {
            RunConfigSnapshot s = create("record_weakness");
            assertThat(s.allowsStep(19)).isTrue();
            assertThat(s.allowsStep(20)).isFalse();
            assertThat(s.allowsLlmCall(11)).isTrue();
            assertThat(s.allowsLlmCall(12)).isFalse();
        }

        @Test
        @DisplayName("快照构造时拒绝非法值（纵深防御）")
        void snapshotConstructorValidates() {
            assertThatThrownBy(() -> new RunConfigSnapshot(
                    "run-x", new BigDecimal("0"), 12, 20, ApprovalRequirement.OPTIONAL, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RunConfigSnapshot(
                    "run-x", new BigDecimal("2.00"), 0, 20, ApprovalRequirement.OPTIONAL, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
