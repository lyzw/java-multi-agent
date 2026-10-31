package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-1 回归：{@link CapEnforcementReadinessGuard} 的纯判定逻辑必须
 * 在「开启却未装配执行流」或「强制要求启用却未启用」时判失败。
 */
class CapEnforcementReadinessGuardTest {

    @Test
    @DisplayName("开启但执行流缺失 → 判定失败（不可能配置必须立刻暴露）")
    void enabledWithoutFlowFails() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, true, false, false);
        assertThat(failures).isNotEmpty();
    }

    @Test
    @DisplayName("开启且执行流就绪 → 判定通过")
    void enabledWithFlowPasses() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, true, true, false);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("关闭且非强制 → 判定通过（仅告警，不拒绝启动）")
    void disabledNotRequiredPasses() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, false, true, false);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("关闭且强制 → 判定失败（fail-closed 拒绝启动）")
    void disabledButRequiredFails() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, false, true, true);
        assertThat(failures).isNotEmpty();
    }

    // ── P0-1a：强制层有两条通路，都得处于强制状态 ──────────────────

    @Test
    @DisplayName("⚠️⚠️ CAP 关 + 闭包守卫 warn + 硬失败开关 → 判定失败（强制层没在校验）")
    void closureWarnModeFailsWhenRequired() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, false, true, true, "warn");
        assertThat(failures)
                .as("两条强制通路都没强制 → 平台带着「不校验的强制层」启动，必须拒绝")
                .isNotEmpty();
    }

    @Test
    @DisplayName("⚠️ CAP 关 + 闭包守卫 enforce → 判定通过（既有链路已在强制校验）")
    void closureEnforceModePassesWhenRequired() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, false, true, true, "enforce");
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("✅ 非强制开关下，warn 模式不拒绝启动（仅报告）")
    void closureWarnModePassesWhenNotRequired() {
        List<String> failures = new ArrayList<>();
        CapEnforcementReadinessGuard.assess(failures, false, true, false, "warn");
        assertThat(failures).isEmpty();
    }
}
