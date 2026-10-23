package cn.studykid.multiagent.application.cap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生命周期状态机单测（§6.6 / 06 §3 · W-PERM-P5-T4）。
 *
 * <p>⚠️ <b>锁住的是「哪些迁移不合法」</b>，而不是「哪些合法」：
 * 合法迁移清单容易记漏，<b>非法</b>迁移一旦被放行，后果是静默的
 * （草稿直接变已发布、已发布退回草稿、停用对象复活）。
 */
class ConfigLifecycleStateMachineTest {

    private final ConfigLifecycleStateMachine machine = new ConfigLifecycleStateMachine();

    @Test
    @DisplayName("✅ 主链：DRAFT → VALIDATED → PUBLISHED → DEPRECATED 全部合法")
    void mainChainIsLegal() {
        assertThat(machine.canTransition(ConfigLifecycleState.DRAFT, ConfigLifecycleState.VALIDATED))
                .isTrue();
        assertThat(machine.canTransition(ConfigLifecycleState.VALIDATED,
                ConfigLifecycleState.PUBLISHED)).isTrue();
        assertThat(machine.canTransition(ConfigLifecycleState.PUBLISHED,
                ConfigLifecycleState.DEPRECATED)).isTrue();
    }

    @Test
    @DisplayName("✅ 校验失败保持可修订：VALIDATED → DRAFT 是唯一允许的回退边")
    void validationFailureReturnsToDraft() {
        assertThat(machine.canTransition(ConfigLifecycleState.VALIDATED, ConfigLifecycleState.DRAFT))
                .as("⛔ 校验失败后无法退回草稿：对象将卡在「已校验」而无法修订")
                .isTrue();
        assertThat(machine.transition(ConfigLifecycleState.VALIDATED, ConfigLifecycleState.DRAFT))
                .isEqualTo(ConfigLifecycleState.DRAFT);
    }

    @Test
    @DisplayName("⛔ 跳过校验直接发布被拒（DRAFT → PUBLISHED）")
    void draftCannotJumpToPublished() {
        assertThat(machine.canTransition(ConfigLifecycleState.DRAFT, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 允许跳过校验直接发布：§6.6 明令「校验失败不静默进 PUBLISHED」")
                .isFalse();
        assertThatThrownBy(() -> machine.transition(ConfigLifecycleState.DRAFT,
                ConfigLifecycleState.PUBLISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的生命周期迁移");
    }

    @Test
    @DisplayName("⛔ 已发布不可退回草稿（会让已生效闭包突然失效）")
    void publishedCannotReturnToDraft() {
        assertThat(machine.canTransition(ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DRAFT))
                .as("⛔ 允许已发布退回草稿：已生效的闭包会突然失效，在途 Run 无从预期")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ DEPRECATED 是终态，不可迁出（停用必须单向）")
    void deprecatedIsTerminal() {
        for (ConfigLifecycleState target : ConfigLifecycleState.values()) {
            assertThat(machine.canTransition(ConfigLifecycleState.DEPRECATED, target))
                    .as("⛔ DEPRECATED 可迁出到 %s：停用形同虚设", target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("✅ 只有 PUBLISHED 可被新发布引用（DRAFT/VALIDATED/DEPRECATED 均不可）")
    void onlyPublishedIsReferenceable() {
        assertThat(ConfigLifecycleState.PUBLISHED.isReferenceable()).isTrue();
        assertThat(ConfigLifecycleState.DRAFT.isReferenceable())
                .as("⛔ 草稿可被引用：未发布对象会被 Agent 启用（§6.6）")
                .isFalse();
        assertThat(ConfigLifecycleState.VALIDATED.isReferenceable()).isFalse();
        assertThat(ConfigLifecycleState.DEPRECATED.isReferenceable())
                .as("⛔ 已停用对象可被新发布引用：06 §3.4「新 Run 不可引用」被架空")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ 断言非 PUBLISHED 状态时给出可定位的拒绝")
    void assertReferenceableRejectsNonPublished() {
        assertThatThrownBy(() -> machine.assertReferenceable(
                ConfigLifecycleState.DRAFT, "tool-x"))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("tool-x");
    }
}
