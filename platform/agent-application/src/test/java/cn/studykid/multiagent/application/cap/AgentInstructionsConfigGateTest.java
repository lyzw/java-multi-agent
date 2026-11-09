package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 提示词声明的<b>配置期契约门禁</b>守卫（2026-11-10 · 06 §5.3）。
 *
 * <p>⚠️ <b>补的是哪一道关</b>：此前只有「前端提交前拒」与「运行期 fail-closed」，
 * 直接调发布 API 仍能把含 {@code systemPromptRef} 的声明发布成功。
 * 配置生效后该 Agent 的所有新 Run 一律被拒，而真因藏在提交那一刻 ——
 * 这是典型的「配置期能判却不判」。
 *
 * <p>⚠️ <b>反向验证</b>：撤掉
 * {@code ConfigValidationGate#validateAgentInstructions}（或从 {@code validate()}
 * 里摘掉这一行），前三个用例必须变红，而「合法声明 / 未声明」两个用例仍绿 ——
 * 证明守卫拦的是<b>缺陷</b>而不是拦一切。
 */
class AgentInstructionsConfigGateTest {

    private final ConfigValidationGate gate = new ConfigValidationGate();

    private static CapAgentDef agent(String instructions) {
        return new CapAgentDef("tenant-a", "agent-a", 1, "辅导 Agent", "desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", "{}", instructions, "Model-A", "{}",
                "pending", 1, Instant.EPOCH, null);
    }

    private static ConfigPublishCommand tenantCommand(String instructions) {
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, "tenant-a", null,
                "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), List.of(agent(instructions)), null);
    }

    private void assertRejected(String instructions) {
        assertThatThrownBy(() -> gate.validate(tenantCommand(instructions)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_INSTRUCTIONS_INVALID));
    }

    @Test
    @DisplayName("⛔ 提示词声明含 systemPromptRef → 拒绝发布（V1 未实现引用式提示词库）")
    void systemPromptRefRejectsPublish() {
        assertRejected("{\"systemPromptRef\":\"prompt-x\",\"systemPromptVersion\":\"3\"}");
    }

    @Test
    @DisplayName("⛔ 提示词声明不是合法 JSON → 拒绝发布（不得把读不懂的声明发布出去）")
    void malformedJsonRejectsPublish() {
        assertRejected("{inlineInstructions: 缺引号}");
    }

    @Test
    @DisplayName("⛔ 提示词声明是数组 / inlineInstructions 非字符串 → 拒绝发布")
    void nonObjectInstructionsRejectPublish() {
        assertRejected("[\"inlineInstructions\"]");
        assertRejected("{\"inlineInstructions\":123}");
    }

    @Test
    @DisplayName("✅ 合法 inlineInstructions 放行（守卫不得拦正常配置）")
    void validInlineInstructionsPass() {
        assertThatCode(() -> gate.validate(
                tenantCommand("{\"inlineInstructions\":\"你是数学辅导助手\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("✅ 未声明提示词 {} 放行（缺省 = 未声明，不是解析失败）")
    void emptyInstructionsPass() {
        assertThatCode(() -> gate.validate(tenantCommand("{}")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("✅ PLATFORM 作用域不含 Agent → 门禁不误伤平台发布")
    void platformScopeIsUnaffected() {
        ConfigPublishCommand cmd = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), List.of(), null);
        assertThatCode(() -> gate.validate(cmd)).doesNotThrowAnyException();
    }
}
