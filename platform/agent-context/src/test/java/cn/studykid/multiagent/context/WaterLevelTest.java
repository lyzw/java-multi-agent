package cn.studykid.multiagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四级水位判定守卫（M5-a / 《方案》§2.3）。
 *
 * <p>⚠️ 阈值**显式注入**，断言用字面量 0.60 / 0.90，不引用被测实现常量。
 */
class WaterLevelTest {

    private static final WaterLevel.Thresholds T = WaterLevel.Thresholds.defaults();
    private static final ContextPolicy P = ContextPolicy.defaults(); // triggerRatio = 0.70

    @Test
    @DisplayName("✅ W0：低于 snip 线不压缩")
    void w0BelowSnip() {
        assertThat(WaterLevel.decide(590, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W0);
    }

    @Test
    @DisplayName("✅ W1：达到 snip(0.60) 未到 trigger(0.70)")
    void w1AtSnip() {
        assertThat(WaterLevel.decide(600, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W1);
        assertThat(WaterLevel.decide(699, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W1);
    }

    @Test
    @DisplayName("✅ W2：达到 triggerRatio(0.70) 未到 summary(0.90)")
    void w2AtTrigger() {
        assertThat(WaterLevel.decide(700, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W2);
        assertThat(WaterLevel.decide(899, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W2);
    }

    @Test
    @DisplayName("✅ W3：达到 summary(0.90)")
    void w3AtSummary() {
        assertThat(WaterLevel.decide(900, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W3);
    }

    @Test
    @DisplayName("✅ HARD：达到/超过窗口 fail-closed（顺序先命中高水位）")
    void hardAtOverflow() {
        assertThat(WaterLevel.decide(1_000, 1_000, P, T)).isEqualTo(WaterLevel.Tier.HARD);
        assertThat(WaterLevel.decide(1_200, 1_000, P, T)).isEqualTo(WaterLevel.Tier.HARD);
    }

    @Test
    @DisplayName("✅ 判定顺序固定：高水位不被低水位分支吞掉")
    void highTierWins() {
        // 0.95 usage 同时 >= snip / trigger / summary，必须返回最高命中的 W3。
        assertThat(WaterLevel.decide(950, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W3);
    }

    @Test
    @DisplayName("✅ triggerRatio 来自策略快照（阈值注入生效，非硬编码）")
    void triggerRatioComesFromPolicy() {
        ContextPolicy aggressive = P.withStrategy(ContextPolicy.Strategy.TIERED);
        // 用 0.50 的 trigger 造一个「比默认更早进 W2」的策略。
        ContextPolicy lowTrigger = new ContextPolicy(true, P.maxInputTokens(), 0.50, 0.30,
                P.reserveRatio(), P.recentTurns(), P.pinFirstUserTurn(), P.ragRatio(),
                P.memoryRatio(), P.summaryRatio(), aggressive.strategy(), P.summarizerModel(),
                P.summaryMaxTokens(), P.summaryTimeoutMs(), P.maxCompactionsPerRun(),
                P.carryOver(), P.checkpointTtlSeconds(), P.policyVersion());
        // 0.65 usage：默认 trigger 0.70 时是 W1，低 trigger 0.50 时应是 W2。
        assertThat(WaterLevel.decide(650, 1_000, P, T)).isEqualTo(WaterLevel.Tier.W1);
        assertThat(WaterLevel.decide(650, 1_000, lowTrigger, T)).isEqualTo(WaterLevel.Tier.W2);
    }

    @Test
    @DisplayName("✅ 阈值校验：snip >= summary / 越界一律拒绝（fail-closed）")
    void thresholdsAreValidated() {
        assertThatThrownBy(() -> new WaterLevel.Thresholds(0.90, 0.60, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaterLevel.Thresholds(1.2, 0.90, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaterLevel.Thresholds(0.60, 0.90, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ defaults 阈值与 yml 逐字对齐（0.60 / 0.90 / 0.10）")
    void defaultsMatchYml() {
        assertThat(T.snipRatio()).isEqualTo(0.60);
        assertThat(T.summaryRatio()).isEqualTo(0.90);
        assertThat(T.minNewTokensRatio()).isEqualTo(0.10);
    }
}
