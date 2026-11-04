package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link CapEnforcementMetrics} 状态映射测试（阶段一 · I2 · P0-1c 监控面板）。
 *
 * <p>钉死 {@code cap.enforcement_status} 三种取值：
 * <ul>
 *   <li>{@code 0} —— 全局关且无灰度白名单；</li>
 *   <li>{@code 1} —— 全局关但有灰度白名单；</li>
 *   <li>{@code 2} —— 全局开（白名单失效）。</li>
 * </ul>
 * 并验证 Gauge 被注册到 {@code MeterRegistry}。
 */
class CapEnforcementMetricsTest {

    @Test
    void offWithoutGrayIsZero() {
        var sw = new CapEnforcementSwitch(false);
        assertThat(CapEnforcementMetrics.statusOf(sw)).isEqualTo(0d);
    }

    @Test
    void offWithGrayIsOne() {
        var sw = new CapEnforcementSwitch(false, Set.of("tenant-gray-01"));
        assertThat(CapEnforcementMetrics.statusOf(sw)).isEqualTo(1d);
    }

    @Test
    void globalOnIsTwoEvenWithGray() {
        var sw = new CapEnforcementSwitch(true, Set.of("tenant-gray-01"));
        assertThat(CapEnforcementMetrics.statusOf(sw)).isEqualTo(2d);
    }

    @Test
    void globalOnIsTwoWithoutGray() {
        var sw = new CapEnforcementSwitch(true);
        assertThat(CapEnforcementMetrics.statusOf(sw)).isEqualTo(2d);
    }

    @Test
    void gaugeRegisteredAndReflectsLiveSwitchState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var sw = new CapEnforcementSwitch(false);
        new CapEnforcementMetrics(sw, registry);

        assertThat(gaugeValue(registry)).isEqualTo(0d);

        // 运行期翻转 → Gauge 实时反映（拉模型）
        sw.enable();
        assertThat(gaugeValue(registry)).isEqualTo(2d);
    }

    private static double gaugeValue(SimpleMeterRegistry registry) {
        for (Meter m : registry.getMeters()) {
            if (m.getId().getName().equals(CapEnforcementMetrics.ENFORCEMENT_STATUS)) {
                return ((Gauge) m).value();
            }
        }
        throw new AssertionError("cap.enforcement_status gauge not registered");
    }
}