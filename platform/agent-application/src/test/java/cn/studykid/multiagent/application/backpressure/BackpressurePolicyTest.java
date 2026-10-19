package cn.studykid.multiagent.application.backpressure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.BackpressurePolicy;
import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * 背压护栏配置与状态机测试（P4-D05 / 关闭 D03 的 L-6）。
 *
 * <p>⚠️ 本类只测「策略与状态机」，不依赖真实 broker：
 * 深度来自 {@link QueueDepthProbe} 端口（lambda 注入固定值）。
 * 真实 broker 的往返由 P4-D06 验证——两者不可互相替代。
 */
@DisplayName("P4-D05 背压护栏")
class BackpressurePolicyTest {

    // ── P-1：配置 fail-closed ──────────────────────────────────

    @Nested
    @DisplayName("P-1 水位配置 fail-closed")
    class ConfigFailClosed {

        @Test
        @DisplayName("⚠️⚠️ P-1a 缺失即拒绝（禁止退回没人审过的水位线）")
        void missingRejected() {
            assertThatThrownBy(() -> BackpressurePolicy.requireHighWatermark(null))
                    .as("⚠️⚠️ 退回默认水位 = 悄悄用了一个没人审过的值")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BackpressurePolicy.requireLowWatermark(null, 1000))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BackpressurePolicy.requireCooldownMultiplier(null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️ P-1b 非正数 / 非整数 → 拒绝")
        void invalidValuesRejected() {
            assertThatThrownBy(() -> BackpressurePolicy.requireHighWatermark("0"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BackpressurePolicy.requireHighWatermark("-5"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BackpressurePolicy.requireHighWatermark("abc"))
                    .as("⚠️ 非整数必须拒绝，而非静默取默认")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️⚠️ P-1c 低水位 ≥ 高水位 → 拒绝（滞回区间为空会抖动）")
        void lowMustBeBelowHigh() {
            assertThatThrownBy(() -> BackpressurePolicy.requireLowWatermark("1000", 1000))
                    .as("⚠️⚠️ 两者相等 → 在阈值处来回切换，比恒定停发更难排障")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BackpressurePolicy.requireLowWatermark("2000", 1000))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(BackpressurePolicy.requireLowWatermark("200", 1000)).isEqualTo(200);
        }

        @Test
        @DisplayName("⚠️ P-1d 降级倍数 >20 → 拒绝（队列清空后长期不恢复）")
        void cooldownMultiplierBounded() {
            assertThatThrownBy(() -> BackpressurePolicy.requireCooldownMultiplier("21"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(BackpressurePolicy.requireCooldownMultiplier("4")).isEqualTo(4);
        }
    }

    // ── P-2：控制器构造期防护 ──────────────────────────────────

    @Nested
    @DisplayName("P-2 控制器构造期防护")
    class ControllerGuards {

        @Test
        @DisplayName("⚠️⚠️ P-2a 低水位 ≥ 高水位 → 构造即拒绝")
        void invalidWatermarksRejected() {
            assertThatThrownBy(() -> controller(1000, 1000))
                    .as("⚠️⚠️ 滞回区间为空 = 阈值处抖动，必须在构造期拒绝")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⚠️ P-2b 队列名为空 → 拒绝（否则探测目标不明）")
        void blankQueueRejected() {
            assertThatThrownBy(() ->
                    new BackpressureController(q -> 0, "  ", 1000, 200, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── P-3：状态机可观测性 ────────────────────────────────────

    @Nested
    @DisplayName("P-3 状态机与计数")
    class StateMachine {

        @Test
        @DisplayName("⚠️ P-3a 停发轮次与探测失败分别计数（排障需要区分二者）")
        void countersAreDistinct() {
            BackpressureController probeFailing = new BackpressureController(q -> {
                throw new IllegalStateException("broker 不可达");
            }, "agent.run.execute", 1000, 200, 4);
            probeFailing.allowPublish();
            probeFailing.allowPublish();

            assertThat(probeFailing.probeFailures())
                    .as("⚠️ 探测失败次数用于区分「队列真的满了」与「探测坏了」")
                    .isEqualTo(2);
            assertThat(probeFailing.throttledRounds()).isEqualTo(2);
            assertThat(probeFailing.lastDepth())
                    .as("⚠️ 从未探测成功时为 -1，不得伪装成 0")
                    .isEqualTo(-1);

            BackpressureController overloaded = new BackpressureController(
                    q -> 5000, "agent.run.execute", 1000, 200, 4);
            overloaded.allowPublish();
            assertThat(overloaded.probeFailures()).isZero();
            assertThat(overloaded.lastDepth()).isEqualTo(5000);
            assertThat(overloaded.throttledRounds()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ P-3b 降级间隔至少为基准间隔（不得缩短）")
        void cooldownNeverShortens() {
            BackpressureController c = controller(1000, 200);
            assertThat(c.effectiveInterval(Duration.ofMillis(200)))
                    .isEqualTo(Duration.ofMillis(200));
        }
    }

    // ── P-4：租户清单（L-4）────────────────────────────────────

    @Nested
    @DisplayName("P-4 租户 slug 清单（L-4）")
    class TenantSlugs {

        @Test
        @DisplayName("⚠️⚠️ P-4a 缺失 / 空清单 → 拒绝（空清单 = 消息永不发布）")
        void missingOrEmptyRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs(null, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs("", 1))
                    .as("⚠️⚠️ 空清单的失败形态是「消息永不发布而系统全绿」")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs(" , ", 1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️⚠️ P-4b 超过试点上限 → 拒绝（灰度控制不得被配置绕过）")
        void exceedsPilotLimitRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs("t-a,t-b", 1))
                    .as("⚠️⚠️ deployment.pilot.max-tenants 是灰度承诺的一部分")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(WorkerPolicy.requireTenantSlugs("t-a,t-b", 2))
                    .containsExactly("t-a", "t-b");
        }

        @Test
        @DisplayName("⚠️ P-4c 重复项 → 拒绝（同一租户被发布两次 = 消息重复）")
        void duplicatesRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs("t-a,t-a", 5))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️ P-4d 空白项被忽略，顺序保持（便于按租户排障）")
        void blanksIgnoredAndOrderKept() {
            List<String> slugs = WorkerPolicy.requireTenantSlugs(" t-b , t-a ", 5);
            assertThat(slugs).containsExactly("t-b", "t-a");
        }

        @Test
        @DisplayName("⚠️ P-4e 上限本身非法 → 拒绝（否则清单校验失去意义）")
        void invalidLimitRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireTenantSlugs("t-a", 0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static BackpressureController controller(int high, int low) {
        return new BackpressureController(q -> 0, "agent.run.execute", high, low, 4);
    }
}
