package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.compliance.ZeroRetentionStatus;

/**
 * C9 接线单元测试（P4-D07）。
 *
 * <p><b>验证目标</b>：{@code ApplicationConfiguration.zeroRetentionGate(String)}
 * 把 yml 的 {@code confirmed-providers} CSV 逐家注册为 {@code pending}，
 * 且<b>行为不变（fail-closed 维持）</b>——pending ≠ confirmed，
 * {@code allows()} 仍返回 false。这防止「接线后误以为已确认放行」的回归。
 *
 * <p>⚠️ 直接调用 @Bean 方法（不走 Spring），仅验证算法本身；
 * yml 实际喂入由 {@code ConfigEndpointIT}（上下文连通性）覆盖。
 */
@DisplayName("P4-D07 C9 零保留注册表接线")
class ZeroRetentionWiringTest {

    private final ApplicationConfiguration config = new ApplicationConfiguration();

    @Nested
    @DisplayName("W-1 声明清单 → 注册为 pending")
    class DeclaredProviders {

        @Test
        @DisplayName("W-1a 三家供应商均登记且状态为 PENDING")
        void threeProvidersRegisteredAsPending() {
            ZeroRetentionGate gate = config.zeroRetentionGate("deepseek,minimax,workbuddy");
            ZeroRetentionRegistry reg = gate.registry();

            assertThat(reg.registeredProviders())
                    .containsExactlyInAnyOrder("deepseek", "minimax", "workbuddy");
            Instant now = Clock.systemUTC().instant();
            for (String id : reg.registeredProviders()) {
                assertThat(reg.statusOf(id).effectiveState(now))
                        .as("⚠️ 声明未确认证据 → 必须 PENDING，不得是 CONFIRMED")
                        .isEqualTo(ZeroRetentionStatus.State.PENDING);
            }
        }

        @Test
        @DisplayName("W-1b pending 仍被闸门拒绝（fail-closed 维持）")
        void pendingStillDenied() {
            ZeroRetentionGate gate = config.zeroRetentionGate("deepseek,minimax,workbuddy");
            Instant now = Clock.systemUTC().instant();

            assertThat(gate.allowsRealStudentData("deepseek")).isFalse();
            assertThat(gate.registry().allAllow(now)).isFalse();
        }
    }

    @Nested
    @DisplayName("W-2 空配置 → 注册表为空，fail-closed 维持")
    class EmptyConfiguration {

        @Test
        @DisplayName("W-2a 空字符串 → 空注册表（不得伪造确认）")
        void blankKeepsRegistryEmpty() {
            ZeroRetentionGate gate = config.zeroRetentionGate("");
            assertThat(gate.registry().registeredProviders()).isEmpty();
            assertThat(gate.registry().allAllow(Clock.systemUTC().instant())).isFalse();
        }

        @Test
        @DisplayName("W-2b null 兜底 → 空注册表")
        void nullTreatedAsEmpty() {
            ZeroRetentionGate gate = config.zeroRetentionGate(null);
            assertThat(gate.registry().registeredProviders()).isEmpty();
        }
    }

    @Nested
    @DisplayName("W-3 鲁棒性：去空白 / 跳过空段")
    class Robustness {

        @Test
        @DisplayName("W-3a 前后空白与空段被清理")
        void trimsAndSkipsEmpty() {
            ZeroRetentionGate gate = config.zeroRetentionGate(" deepseek ,, minimax ");
            assertThat(gate.registry().registeredProviders())
                    .containsExactlyInAnyOrder("deepseek", "minimax");
        }
    }
}
