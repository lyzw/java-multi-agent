package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MicrometerAuthzMismatchMetrics} 行为测试（阶段一 · I2 · P0-1c 监控面板）。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>四个指标（mismatch / shadow sample / unrestricted / taint violation）都能被
 *       Micrometer 抓到时正确计数；</li>
 *   <li>计数接口（{@link #mismatches()} 等）与 Micrometer 计数<b>同源递增</b>，
 *       不存在两套计数互相复制（单源真相）；</li>
 *   <li>按 {@code resource_type} 分组的标签完整。</li>
 * </ol>
 */
class MicrometerAuthzMismatchMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerAuthzMismatchMetrics metrics =
            new MicrometerAuthzMismatchMetrics(registry);

    @Test
    void recordsMismatchAsCounterAndAccessor() {
        metrics.recordMismatch("cap_agent_def", "Gate 租户=A 但查询绑定租户=B");

        assertThat(metrics.mismatches()).isEqualTo(1);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.MISMATCH_TOTAL, "cap_agent_def"))
                .isEqualTo(1.0d);
    }

    @Test
    void countersGroupByResourceType() {
        metrics.recordMismatch("cap_agent_def", "d1");
        metrics.recordMismatch("cap_agent_def", "d2");
        metrics.recordMismatch("cap_authz_version", "d3");

        assertThat(metrics.mismatches()).isEqualTo(3);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.MISMATCH_TOTAL, "cap_agent_def"))
                .isEqualTo(2.0d);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.MISMATCH_TOTAL, "cap_authz_version"))
                .isEqualTo(1.0d);
    }

    @Test
    void recordsShadowSampleAndUnrestricted() {
        metrics.recordShadowSample("run");
        metrics.recordShadowSample("run");
        metrics.recordUnrestricted("tool_operation");

        assertThat(metrics.shadowSamples()).isEqualTo(2);
        assertThat(metrics.unrestrictedUsages()).isEqualTo(1);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.SHADOW_SAMPLES_TOTAL, "run"))
                .isEqualTo(2.0d);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.UNRESTRICTED_TOTAL, "tool_operation"))
                .isEqualTo(1.0d);
    }

    @Test
    void recordsTaintViolation() {
        RowAttribution row = new RowAttribution("tenant-A", true);
        var expected = new cn.studykid.multiagent.core.security.ScopeFilter.TenantWithShared("tenant-A");

        metrics.recordTaintViolation("cap_config_release", row, expected);

        assertThat(metrics.taintViolations()).isEqualTo(1);
        assertThat(counterValue(MicrometerAuthzMismatchMetrics.TAINT_VIOLATIONS_TOTAL,
                "cap_config_release")).isEqualTo(1.0d);
    }

    private double counterValue(String name, String resourceType) {
        for (Meter m : registry.getMeters()) {
            if (m.getId().getName().equals(name)
                    && resourceType.equals(m.getId().getTag(MicrometerAuthzMismatchMetrics.TAG_RESOURCE_TYPE))) {
                io.micrometer.core.instrument.Counter c = (io.micrometer.core.instrument.Counter) m;
                return c.count();
            }
        }
        return 0.0d;
    }
}