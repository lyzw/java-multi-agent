package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CapEnforcementSwitch} 灰度白名单语义（阶段一 · P0-1c）。
 *
 * <p>钉死三条命题：
 * <ol>
 *   <li><b>全局关 + 白名单</b>：仅白名单内租户走 CAP 链路（先行者），其余租户回退既有链路；</li>
 *   <li><b>全局开</b>：所有租户都走 CAP 链路，白名单失效（白名单不是豁免/旁路）；</li>
 *   <li><b>空白名单</b>：与旧行为逐字节一致（仅全局位生效）。</li>
 * </ol>
 */
class CapEnforcementSwitchTest {

    private static final String GRAY = "tenant-gray-01";

    @Nested
    @DisplayName("全局关 + 灰度白名单")
    class GrayOnly {

        @Test
        @DisplayName("白名单内租户启用；其余租户不启用")
        void allowlistedTenantEnabledOthersNot() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(false, Set.of(GRAY));
            assertThat(sw.isEnabled(GRAY)).isTrue();
            assertThat(sw.isEnabled("tenant-other")).isFalse();
            assertThat(sw.isEnabled(null)).isFalse();
            assertThat(sw.isEnabled("")).isFalse();
            assertThat(sw.isEnabled(" ")).isFalse();
        }

        @Test
        @DisplayName("全局仍为关（isEnabled() = false）")
        void globalStillOff() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(false, Set.of(GRAY));
            assertThat(sw.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("返回白名单只读视图")
        void exposesAllowlist() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(false, Set.of(GRAY, "tenant-gray-02"));
            assertThat(sw.tenantAllowlist()).containsExactlyInAnyOrder(GRAY, "tenant-gray-02");
        }

        @Test
        @DisplayName("null 白名单按空集合处理（不抛）")
        void nullAllowlistIsEmpty() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(false, null);
            assertThat(sw.tenantAllowlist()).isEmpty();
            assertThat(sw.isEnabled(GRAY)).isFalse();
        }
    }

    @Nested
    @DisplayName("全局开")
    class GlobalOn {

        @Test
        @DisplayName("所有租户都启用，白名单不构成豁免")
        void allTenantsEnabled() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(true, Set.of(GRAY));
            assertThat(sw.isEnabled()).isTrue();
            assertThat(sw.isEnabled(GRAY)).isTrue();
            assertThat(sw.isEnabled("anything")).isTrue();
            assertThat(sw.isEnabled(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("空白名单（与旧行为一致）")
    class NoGray {

        @Test
        @DisplayName("仅全局位生效")
        void globalOnly() {
            CapEnforcementSwitch off = new CapEnforcementSwitch(false);
            assertThat(off.isEnabled("any")).isFalse();

            CapEnforcementSwitch on = new CapEnforcementSwitch(true);
            assertThat(on.isEnabled("any")).isTrue();
            assertThat(on.tenantAllowlist()).isEmpty();
        }
    }

    @Nested
    @DisplayName("运行时切换")
    class RuntimeToggle {

        @Test
        @DisplayName("开启全局后，白名单外租户也启用")
        void enableBroadens() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(false, Set.of(GRAY));
            assertThat(sw.isEnabled("tenant-other")).isFalse();
            sw.enable();
            assertThat(sw.isEnabled("tenant-other")).isTrue();
        }

        @Test
        @DisplayName("关闭全局后，白名单内租户仍启用（灰度保留）")
        void disableKeepsGrayTenants() {
            CapEnforcementSwitch sw = new CapEnforcementSwitch(true, Set.of(GRAY));
            sw.disable();
            assertThat(sw.isEnabled("anything")).isFalse();
            assertThat(sw.isEnabled(GRAY)).isTrue();
        }
    }
}