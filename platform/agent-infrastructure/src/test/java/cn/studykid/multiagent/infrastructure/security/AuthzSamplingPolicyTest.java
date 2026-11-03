package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采样策略单测（12 文 §7.1 R7）。
 *
 * <p>⚠️ 这里钉死的是<b>可复现性</b>与<b>高敏必采</b>两条：
 * 缺前者，mismatch 复现不出来；缺后者，高敏接口的「100% 染色」验收项是空话。
 */
class AuthzSamplingPolicyTest {

    private static final long WINDOW = 60_000L;

    @Test
    @DisplayName("rate=0 且非高敏 → 永不采样（不是「很少」，是确定不采）")
    void zeroRate_neverSamples() {
        AuthzSamplingPolicy p = AuthzSamplingPolicy.of(0.0d, WINDOW, Set.of());
        for (int i = 0; i < 200; i++) {
            assertThat(p.shouldSample("agent", "tenant-" + i)).isFalse();
        }
    }

    @Test
    @DisplayName("高敏资源在 rate=0 时仍 100% 采样（验收项：高敏接口 100% 染色）")
    void highSensitivity_alwaysSampled_evenAtZeroRate() {
        AuthzSamplingPolicy p = AuthzSamplingPolicy.of(0.0d, WINDOW, Set.of("run", "tool_operation"));
        assertThat(p.shouldSample("run", "tenant-x")).isTrue();
        assertThat(p.shouldSample("tool_operation", null)).isTrue();
        assertThat(p.shouldSample("agent", "tenant-x")).isFalse();
    }

    @Test
    @DisplayName("rate=1 → 全采")
    void fullRate_samplesEverything() {
        AuthzSamplingPolicy p = AuthzSamplingPolicy.of(1.0d, WINDOW, Set.of());
        assertThat(p.shouldSample("agent", "tenant-x")).isTrue();
        assertThat(p.shouldSample("agent", null)).isTrue();
    }

    @Test
    @DisplayName("⚠️ 同一窗口内同一 (主体,资源) 结论恒定 —— 否则 mismatch 复现不出来")
    void sameWindow_sameDecision() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthzSamplingPolicy p = new AuthzSamplingPolicy(0.3d, WINDOW, Set.of(), clock::get);

        boolean first = p.shouldSample("agent", "tenant-42");
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(500L);           // 仍在同一窗口内
            assertThat(p.shouldSample("agent", "tenant-42")).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("窗口滚动后重新分桶 —— 长期看每个 (主体,资源) 都会被采到，而不是靠运气")
    void windowRotation_coversEventually() {
        AtomicLong clock = new AtomicLong(0L);
        AuthzSamplingPolicy p = new AuthzSamplingPolicy(0.2d, WINDOW, Set.of(), clock::get);

        boolean seenSample = false;
        for (int bucket = 0; bucket < 400 && !seenSample; bucket++) {
            clock.set(bucket * WINDOW);
            seenSample = p.shouldSample("agent", "tenant-slow");
        }
        assertThat(seenSample)
                .as("20%% 采样在 400 个窗口内一次都没采到同一个键，说明分桶退化了")
                .isTrue();
    }

    @Test
    @DisplayName("非法参数拒绝构造（fail-closed，不静默退化）")
    void invalidArguments_rejected() {
        assertThatThrownBy(() -> AuthzSamplingPolicy.of(-0.1d, WINDOW, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthzSamplingPolicy.of(0.5d, 0L, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthzSamplingPolicy.of(Double.NaN, WINDOW, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("高敏清单解析：空串为空集，逗号分隔去空白")
    void parseAlwaysSample() {
        assertThat(AuthzSamplingPolicy.parseAlwaysSample(null)).isEmpty();
        assertThat(AuthzSamplingPolicy.parseAlwaysSample("  ")).isEmpty();
        assertThat(AuthzSamplingPolicy.parseAlwaysSample("run, tool_operation ,, weakness_record"))
                .containsExactly("run", "tool_operation", "weakness_record");
    }
}
