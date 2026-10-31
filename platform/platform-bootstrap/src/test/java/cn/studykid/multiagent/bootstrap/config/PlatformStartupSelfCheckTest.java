package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-2 回归：{@link PlatformStartupSelfCheck} 的纯判定逻辑必须正确识别
 * 超级用户 / BYPASSRLS / 运维角色串用主数据源等不安全配置。
 */
class PlatformStartupSelfCheckTest {

    @Test
    @DisplayName("超级用户 → 判定失败")
    void superUserFails() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessNonPrivilegedRole(failures, "ai_agent", true, false);
        assertThat(failures).isNotEmpty();
    }

    @Test
    @DisplayName("BYPASSRLS 角色 → 判定失败（dev 的 ai_agent 即此情形）")
    void bypassRlsFails() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessNonPrivilegedRole(failures, "ai_agent", false, true);
        assertThat(failures).isNotEmpty();
    }

    @Test
    @DisplayName("非特权应用角色（app_agent）→ 判定通过")
    void normalRolePasses() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessNonPrivilegedRole(failures, "app_agent", false, false);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("主数据源连接恰为运维角色 → 判定失败")
    void opsSeparationFails() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessOpsRoleSeparation(failures, "platform_ops", "platform_ops");
        assertThat(failures).isNotEmpty();
    }

    @Test
    @DisplayName("主数据源非运维角色 → 判定通过")
    void opsSeparationPasses() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessOpsRoleSeparation(failures, "app_agent", "platform_ops");
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("未配置运维用户名 → 不参与互检")
    void opsSeparationSkippedWhenEmpty() {
        List<String> failures = new ArrayList<>();
        PlatformStartupSelfCheck.assessOpsRoleSeparation(failures, "platform_ops", "");
        assertThat(failures).isEmpty();
    }
}
