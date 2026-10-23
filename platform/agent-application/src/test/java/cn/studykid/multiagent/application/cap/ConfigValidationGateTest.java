package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置期校验门禁单测（P5-T5 · §6.4 / 06 §3）。
 *
 * <p>⚠️ <b>退出门禁「配置期 {@code riskLevel} 未知/非法拒绝发布」在此验证</b>。
 * P1 已在运行期把未知风险拒为 {@code RISK_LEVEL_UNKNOWN}，
 * 但运行期拒绝意味着<b>配置已经生效</b> —— 在途 Run 按旧指纹继续跑，
 * 新 Run 全部被拒，运维看到的现象是「这个 Tool 突然全员不可用」。
 * 配置期拒发布把问题挡在生效之前。
 */
class ConfigValidationGateTest {

    private final ConfigValidationGate gate = new ConfigValidationGate();

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapToolDef tool(String id, CapabilityRiskLevel risk) {
        return new CapToolDef(id, 1, id, CapToolKind.NATIVE, risk, null, null,
                "{}", "{}", "fp-" + id, 1, Instant.EPOCH, null);
    }

    private static CapSkillDef skill(String id, CapabilityRiskLevel ceiling, List<String> tools) {
        return new CapSkillDef(id, 1, id, null, tools, List.of(), ceiling, "{}", List.of(),
                "fp-" + id, 1, Instant.EPOCH, null);
    }

    private static CapMcpServerDef server(String id, boolean dynamicDiscovery) {
        return new CapMcpServerDef(id, 1, CapMcpServerDef.Transport.HTTP,
                "https://mcp.example.com/rpc", null, CapMcpServerDef.AuthKind.TOKEN,
                McpTrustTier.PARTNER, List.of("op-a"), CapabilityRiskLevel.WRITE_EXTERNAL,
                dynamicDiscovery, "fp-" + id, "cred-ref", 1, Instant.EPOCH, null);
    }

    private static ConfigPublishCommand command(CapToolDef... tools) {
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "requester-1", "approver-1", null,
                List.of(tools), List.of(), List.of(), List.of(), null);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 未知/非法风险等级拒绝发布（退出门禁）")
    void unknownRiskLevelRejectsPublish() {
        ConfigPublishCommand cmd = command(tool("t-unknown", CapabilityRiskLevel.UNKNOWN));

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RISK_LEVEL_UNKNOWN))
                .hasMessageContaining("t-unknown");
    }

    @Test
    @DisplayName("⛔ 未知风险上限（Skill 的 riskCeiling）同样拒绝发布")
    void unknownRiskCeilingRejectsPublish() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(tool("t-ok", CapabilityRiskLevel.READ)),
                List.of(skill("s-unknown", CapabilityRiskLevel.UNKNOWN, List.of("t-ok"))),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RISK_LEVEL_UNKNOWN));
    }

    @Test
    @DisplayName("✅ 合法风险等级通过门禁")
    void knownRiskLevelPasses() {
        assertThatCode(() -> gate.validate(
                command(tool("t-ok", CapabilityRiskLevel.WRITE_EXTERNAL))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⛔ PLATFORM 作用域不得携带租户 Agent（跨租户结构泄漏）")
    void platformScopeRejectsAgentDefs() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(),
                List.of(new cn.studykid.multiagent.core.cap.CapAgentDef(
                        "00000000-0000-0000-0000-000000000001", "agent-1", 1,
                        "a", null, List.of(), List.of(), List.of(), List.of(), "{}", "fp",
                        1, Instant.EPOCH, null)),
                null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("租户级 Agent");
    }

    @Test
    @DisplayName("⛔ TENANT 作用域不得携带平台对象（避免两个来源）")
    void tenantScopeRejectsPlatformObjects() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.TENANT,
                "00000000-0000-0000-0000-000000000001", null,
                "requester-1", "approver-1", null,
                List.of(tool("t-ok", CapabilityRiskLevel.READ)), List.of(), List.of(), List.of(),
                null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("平台级对象");
    }

    @Test
    @DisplayName("⛔ 申请人与审批人同一主体被拒（双人复核）")
    void sameRequesterAndApproverRejected() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "same-user", "same-user", null,
                List.of(tool("t-ok", CapabilityRiskLevel.READ)), List.of(), List.of(), List.of(),
                null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("双人复核");
    }

    @Test
    @DisplayName("⛔ Skill 声明的 Tool 不在发布集合中 → 发布校验失败（§6.4）")
    void missingReferencedToolRejected() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(tool("t-a", CapabilityRiskLevel.READ)),
                List.of(skill("s-a", CapabilityRiskLevel.READ, List.of("t-missing"))),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.TOOL_NOT_DECLARED))
                .hasMessageContaining("t-missing");
    }

    @Test
    @DisplayName("⛔ Skill 声明的 MCP Server 不在发布集合中 → 拒绝")
    void missingReferencedServerRejected() {
        CapSkillDef skill = new CapSkillDef("s-a", 1, "s", null, List.of(), List.of(
                new CapSkillDef.McpDeclaration("srv-missing", null)),
                CapabilityRiskLevel.READ, "{}", List.of(), "fp", 1, Instant.EPOCH, null);
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(), List.of(skill), List.of(), List.of(), null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.MCP_NOT_BOUND));
    }

    @Test
    @DisplayName("⛔ dynamicDiscovery=true 的 MCP Server 拒绝发布（P4 门禁复用）")
    void dynamicDiscoveryRejected() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(server("srv-a", true)), List.of(), null);

        assertThatThrownBy(() -> gate.validate(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("dynamicDiscovery");
    }

    @Test
    @DisplayName("⛔ 同一 id 出现两个版本被拒（id→版本解析必须确定）")
    void duplicateObjectRejected() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(tool("t-a", CapabilityRiskLevel.READ),
                        tool("t-a", CapabilityRiskLevel.WRITE_EXTERNAL)),
                List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> gate.validateNoDuplicateObjects(cmd))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("重复");
    }
}
