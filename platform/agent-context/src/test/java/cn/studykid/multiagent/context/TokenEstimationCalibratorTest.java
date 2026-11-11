package cn.studykid.multiagent.context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 估算偏差校准：n&lt;30 不生效、bias 夹紧、非法样本丢弃、并发安全。 */
class TokenEstimationCalibratorTest {

    @Test
    @DisplayName("✅ n<30 时 ready=false 且 bias 恒为 1.0（绝不拿少量样本调估算）")
    void calibrationDoesNotActivateBeforeMinSamples() {
        TokenEstimationCalibrator c = new TokenEstimationCalibrator();
        for (int i = 0; i < 29; i++) {
            c.addSample("m1", 2.0);
        }
        assertThat(c.sampleCount("m1")).isEqualTo(29);
        assertThat(c.ready("m1")).isFalse();
        assertThat(c.bias("m1")).isEqualTo(1.0);

        c.addSample("m1", 2.0);
        assertThat(c.ready("m1")).isTrue();
        assertThat(c.bias("m1")).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
    }

    @Test
    @DisplayName("✅ 稳定样本下 EWMA 收敛到接近真值比")
    void ewmaConverges() {
        TokenEstimationCalibrator c = new TokenEstimationCalibrator(30, 0.5, 0.8, 2.0);
        for (int i = 0; i < 40; i++) {
            c.addSample("m1", 1.25);
        }
        assertThat(c.bias("m1")).isBetween(1.24, 1.26);
    }

    @Test
    @DisplayName("⛔ bias 必须夹在 [0.8, 2.0]，异常 usage 不得把估算抬到荒谬量级")
    void biasIsClamped() {
        TokenEstimationCalibrator high = new TokenEstimationCalibrator();
        for (int i = 0; i < 35; i++) {
            high.addSample("m1", 100.0);
        }
        assertThat(high.bias("m1")).isEqualTo(2.0);

        TokenEstimationCalibrator low = new TokenEstimationCalibrator();
        for (int i = 0; i < 35; i++) {
            low.addSample("m2", 0.001);
        }
        assertThat(low.bias("m2")).isEqualTo(0.8);
    }

    @Test
    @DisplayName("⛔ 0 用量 / 非有限 / 估算非正一律丢弃，不污染 EWMA")
    void invalidSamplesAreDropped() {
        TokenEstimationCalibrator c = new TokenEstimationCalibrator();
        assertThat(c.addSample("m1", 0, 1_000)).isNaN();
        assertThat(c.addSample("m1", 1_000, 0)).isNaN();
        assertThat(c.addSample("m1", Double.NaN)).isNaN();
        assertThat(c.addSample("m1", Double.POSITIVE_INFINITY)).isNaN();
        assertThat(c.addSample("m1", -1.0)).isNaN();
        assertThat(c.sampleCount("m1")).isZero();

        assertThat(c.addSample("m1", 900, 1_000)).isEqualTo(0.9);
        assertThat(c.sampleCount("m1")).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 按 modelId 独立聚合，切换模型不串用偏差")
    void samplesAreIsolatedPerModel() {
        TokenEstimationCalibrator c = new TokenEstimationCalibrator(2, 0.5, 0.8, 2.0);
        c.addSample("m1", 1.5);
        c.addSample("m1", 1.5);
        c.addSample("m2", 0.9);
        c.addSample("m2", 0.9);

        assertThat(c.bias("m1")).isGreaterThan(1.4);
        assertThat(c.bias("m2")).isLessThan(1.0);
        assertThat(c.bias("unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("✅ 并发 addSample 不丢样本（CAS 更新不可变 state）")
    void concurrentSamplesAreNotLost() throws Exception {
        TokenEstimationCalibrator c = new TokenEstimationCalibrator();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            c.addSample("m1", 1.1);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(c.sampleCount("m1")).isEqualTo((long) threads * perThread);
    }

    @Test
    @DisplayName("✅ 单次误差按 |1 - actual/estimate| 计算")
    void errorRatioIsAbsoluteDeviation() {
        assertThat(TokenEstimationCalibrator.errorRatio(0.9)).isBetween(0.099, 0.101);
        assertThat(TokenEstimationCalibrator.errorRatio(1.3)).isBetween(0.299, 0.301);
        assertThat(TokenEstimationCalibrator.errorRatio(1.0)).isZero();
    }
}
