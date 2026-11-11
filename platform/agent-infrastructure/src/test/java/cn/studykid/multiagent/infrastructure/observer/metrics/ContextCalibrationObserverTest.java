package cn.studykid.multiagent.infrastructure.observer.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.TokenEstimationCalibrator;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link ContextCalibrationObserver} 行为测试（ADR-013 / M4 / G1 证据链）。
 *
 * <p>钉死四件事：
 * <ol>
 *   <li><b>只读</b>：before/after 前后 {@code ModelRequest} 逐字段不变（G1 请求零差异）；</li>
 *   <li><b>全窗口估算</b>：before 记的 token 含 systemPrompt + 工具 Schema，不是消息序列；</li>
 *   <li><b>校准语义</b>：n&lt;30 时 bias 恒 1.0，样本照记；非法样本（0 用量）被丢弃；</li>
 *   <li><b>异常隔离</b>：计数器/注册表抛异常时，观测方法不冒泡。</li>
 * </ol>
 */
class ContextCalibrationObserverTest {

    private static final String MODEL = "minimax:MiniMax-M2.5";

    @Test
    void requestIsUntouchedBeforeAndAfter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator();
        var observer = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ModelRequest request = sampleRequest();

        String before = request.toString();
        observer.beforeCall("t1", "r1", 1, request);
        observer.afterCall("t1", "r1", 1, request,
                response(120), Duration.ofMillis(50));

        assertThat(request.toString()).as("⛔ 观测者修改了 ModelRequest：G1 请求字节零差异被破坏").isEqualTo(before);
        assertThat(calibrator.sampleCount(MODEL)).isEqualTo(1);
    }

    @Test
    void beforeEstimatesFullWindowNotMessagesOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), new TokenEstimationCalibrator());
        ModelRequest request = sampleRequest();

        // 纯消息序列估算（旧口径）：把 systemPrompt / 工具 Schema 排除。
        int messagesOnly = new HeuristicTokenCounter().count(
                new cn.studykid.multiagent.ports.context.ContextBlocks(
                        "", "", "", List.of(),
                        List.of("USER:这道题为什么错了？"), request.userContent()));

        int fullWindow = observer.estimate(request);
        assertThat(fullWindow)
                .as("⛔ 校准桥估算退回「只看消息序列」：会把真实超窗悄悄放大")
                .isGreaterThan(messagesOnly);

        // 指标由生产入口 beforeCall 记录（estimate() 是包可见的纯估算助手，不产指标）。
        observer.beforeCall("t", "r", 1, request);
        assertThat(metricValue(registry, ContextCalibrationObserver.TOKENS_BEFORE))
                .as("⛔ before 未记录全窗口估算指标")
                .isEqualTo(fullWindow);
    }

    @Test
    void biasStaysOneUntilMinSamplesReached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator(30, 0.2, 0.8, 2.0);
        var observer = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ModelRequest request = sampleRequest();

        for (int i = 0; i < 29; i++) {
            observer.beforeCall("t", "r" + i, i + 1, request);
            observer.afterCall("t", "r" + i, i + 1, request, response(100), Duration.ZERO);
        }
        assertThat(calibrator.sampleCount(MODEL)).isEqualTo(29);
        assertThat(calibrator.bias(MODEL))
                .as("⛔ n<30 时 bias 偏离 1.0：少量样本被放大成系统性偏差")
                .isEqualTo(1.0d);

        observer.beforeCall("t", "r30", 30, request);
        observer.afterCall("t", "r30", 30, request, response(100), Duration.ZERO);
        assertThat(calibrator.sampleCount(MODEL)).isEqualTo(30);
        assertThat(meterExists(registry, ContextCalibrationObserver.BIAS))
                .as("⛔ 达到样本数后未注册 context.bias gauge")
                .isTrue();
    }

    @Test
    void zeroUsageSampleIsDroppedNotRecordedAsInfiniteBias() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator();
        var observer = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ModelRequest request = sampleRequest();

        observer.beforeCall("t", "r", 1, request);
        observer.afterCall("t", "r", 1, request, response(0), Duration.ZERO);

        assertThat(calibrator.sampleCount(MODEL))
                .as("⛔ 0 用量被当成样本：会把 bias 拉到 0 并污染后续所有估算")
                .isZero();
        assertThat(metricValue(registry, ContextCalibrationObserver.ESTIMATION_ERROR_RATIO))
                .as("⛔ 非法样本仍进了误差指标")
                .isZero();
    }

    @Test
    void observerExceptionsNeverPropagateToMainFlow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 计数器抛异常 → beforeCall 内部必须吞掉，绝不冒泡。
        var observer = new ContextCalibrationObserver(registry,
                req -> { throw new IllegalStateException("counter boom"); },
                new TokenEstimationCalibrator());
        ModelRequest request = sampleRequest();

        assertThatCode(() -> {
            observer.beforeCall("t", "r", 1, request);
            observer.afterCall("t", "r", 1, request, response(100), Duration.ZERO);
            observer.onException("t", "r", 1, request,
                    new ModelGatewayExceptionStub(), Duration.ZERO);
        }).as("⛔ 观测者异常冒泡到主流程：校准桥反而让模型调用失败").doesNotThrowAnyException();
    }

    @Test
    void threadLocalIsClearedAfterCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator();
        var observer = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ModelRequest request = sampleRequest();

        observer.beforeCall("t", "r1", 1, request);
        observer.afterCall("t", "r1", 1, request, response(100), Duration.ZERO);
        // 第二次只调 after（无 before）：不应产生样本，证明 ThreadLocal 已被清理。
        observer.afterCall("t", "r2", 2, request, response(100), Duration.ZERO);

        assertThat(calibrator.sampleCount(MODEL))
                .as("⛔ ThreadLocal 未清理：上一次的估算值串味到下一次请求")
                .isEqualTo(1);
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static ModelRequest sampleRequest() {
        return new ModelRequest(MODEL,
                "你是错题归因助手，必须遵守安全与版权约束，不得编造答案。",
                "这道题为什么错了？", 0.3, 2048, Duration.ofSeconds(30),
                Map.of("grade_homework", Map.of("type", "object", "required", List.of("qid"))));
    }

    private static ModelResponse response(long inputTokens) {
        return new ModelResponse("答案", Map.of(),
                new ModelResponse.ModelUsage(inputTokens, 30), ModelResponse.FinishReason.STOP);
    }

    private static double metricValue(SimpleMeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(name))
                .filter(m -> m instanceof io.micrometer.core.instrument.DistributionSummary)
                .mapToDouble(m -> ((io.micrometer.core.instrument.DistributionSummary) m).totalAmount())
                .sum();
    }

    private static boolean meterExists(SimpleMeterRegistry registry, String name) {
        return registry.getMeters().stream().anyMatch(m -> m.getId().getName().equals(name));
    }

    /** 轻量异常桩，避免依赖具体网关实现。 */
    private static final class ModelGatewayExceptionStub
            extends cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException {
        ModelGatewayExceptionStub() {
            super(ModelErrorKind.PROVIDER_ERROR, "stub failure");
        }
    }
}
