package cn.studykid.multiagent.infrastructure.observer.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MicrometerExecutionObserver#toolAvailabilityRatio} 行为测试
 * （阶段一 · I2 · P0-1c 监控面板）。
 *
 * <p>钉死工具可用率=<code>succeeded / (succeeded+failed)</code>：
 * <ul>
 *   <li>无调用 → 0.0；</li>
 *   <li>全成功 → 1.0；</li>
 *   <li>半成功 → 0.5；</li>
 *   <li>{@code rejected} / {@code unknown} 不计入分母（它们是准入拦截/结果未知，
 *       不是「工具自己是否可用」，计入会扭曲工具可用率语义）。</li>
 * </ul>
 */
class MicrometerExecutionObserverAvailabilityTest {

    @Test
    void noCallsYieldsZeroRatio() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new MicrometerExecutionObserver(registry);
        assertThat(observer.toolAvailabilityRatio()).isEqualTo(0.0d);
        assertThat(gaugeValue(registry)).isEqualTo(0.0d);
    }

    @Test
    void allSucceededYieldsOne() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new MicrometerExecutionObserver(registry);
        observer.onToolCallComplete("t", "r", 1, summary(2, 0), Duration.ZERO);

        assertThat(observer.toolAvailabilityRatio()).isEqualTo(1.0d);
    }

    @Test
    void halfSucceededYieldsHalf() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new MicrometerExecutionObserver(registry);
        observer.onToolCallComplete("t", "r", 1, summary(1, 1), Duration.ZERO);

        assertThat(observer.toolAvailabilityRatio()).isEqualTo(0.5d);
    }

    @Test
    void rejectedAndUnknownExcludedFromDenominator() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new MicrometerExecutionObserver(registry);
            // 2 成功 + 1 失败 + 3 拒绝 + 1 unknown
            observer.onToolCallComplete("t", "r", 1,
                    new ToolCallSummary(
                            java.util.List.of("a", "b"), // succeeded
                            java.util.List.of("c"),       // failed
                            java.util.List.of("d", "e", "f"), // rejected
                            true,
                            "op-1"),
                    Duration.ZERO);

            // rejected/unknown 不计入分母 → ratio = 2 / (2+1) = 0.666...
            assertThat(observer.toolAvailabilityRatio()).isCloseTo(2.0d / 3.0d,
                    org.assertj.core.data.Offset.offset(1e-9));
    }

    private static ToolCallSummary summary(int succeeded, int failed) {
        return new ToolCallSummary(
                succeededTools(succeeded),
                failedTools(failed),
                java.util.List.of(), false, null);
    }

    private static java.util.List<String> succeededTools(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "ok-" + i).toList();
    }

    private static java.util.List<String> failedTools(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "fail-" + i).toList();
    }

    private static double gaugeValue(SimpleMeterRegistry registry) {
        for (Meter m : registry.getMeters()) {
            if (m.getId().getName().equals(MicrometerExecutionObserver.TOOL_AVAILABILITY_RATIO)) {
                return ((Gauge) m).value();
            }
        }
        throw new AssertionError("cap.tool_availability_ratio gauge not registered");
    }
}