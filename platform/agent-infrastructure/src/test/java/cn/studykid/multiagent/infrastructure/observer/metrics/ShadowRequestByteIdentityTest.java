package cn.studykid.multiagent.infrastructure.observer.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.TokenEstimationCalibrator;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * G1「影子对照请求字节零差异」<b>行为证据</b>（ADR-013 / M4 / Worker 两链路）。
 *
 * <p>⚠️ <b>覆盖边界（报告必须诚实标注）</b>：本类驱动的是
 * {@link ObservableModelGateway}（{@code ModelCallObserver} 包装器），
 * 它覆盖 Worker 的 {@code RunExecutionLoop} 与 {@code WorkerChildAgentRunner}
 * 两条链路。<b>SSE / Console 走 {@code ChatStreamGateway}，不经过本链</b>——
 * 那两条只能靠「零接线守卫 + {@code ModelRequest} 不可变 record」做结构证明，
 * 不能声称「所有链路都过了校准桥」。
 *
 * <p>钉死两条结构性事实：
 * <ol>
 *   <li>{@code ObservableModelGateway} 把<b>调用方传入的同一个引用</b>交给 delegate，
 *       校准观测链只读不写 —— 接了观测 ≠ 改了请求；</li>
 *   <li>失败路径同样不改写请求，且 {@code onException} 清理 ThreadLocal，
 *       不让上一次的估算值串味到下一次请求。</li>
 * </ol>
 */
class ShadowRequestByteIdentityTest {

    private static final String MODEL = "minimax:MiniMax-M2.5";

    @Test
    @DisplayName("✅ 校准桥接入真实观测链后，delegate 收到的是同一个请求对象引用（G1 核心证据）")
    void calibrationBridgePassesExactSameRequestInstanceToDelegate() {
        AtomicReference<ModelRequest> seenByDelegate = new AtomicReference<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator();
        var calibration = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ModelCallObserverChain chain = new ModelCallObserverChain(List.of(calibration));
        ModelGateway delegate = req -> {
            seenByDelegate.set(req);
            return response(120);
        };
        ObservableModelGateway gateway = new ObservableModelGateway(delegate, chain);

        ModelRequest request = sampleRequest();
        String before = request.toString();

        ModelResponse out = gateway.complete("t1", "r1", 1, request);

        assertThat(seenByDelegate.get())
                .as("⛔ delegate 收到的不是调用方传入的同一个请求对象："
                        + "G1「请求字节零差异」被破坏，S1 不再等于「无压缩功能」")
                .isSameAs(request);
        assertThat(request.toString())
                .as("⛔ 经过观测链后请求字段被改写")
                .isEqualTo(before);
        assertThat(out).isNotNull();
        assertThat(calibrator.sampleCount(MODEL))
                .as("⛔ 校准桥没真正运行：恒等断言会退化成空转（没接=当然不变）")
                .isEqualTo(1);
        assertThat(calibrator.bias(MODEL))
                .as("⛔ n<30 时 bias 偏离 1.0")
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("✅ 全观测链（多观测者）下每个观测者看到的都是同一个请求引用，且 delegate 不变")
    void everyObserverSeesSameRequestReference() {
        AtomicReference<ModelRequest> firstSeen = new AtomicReference<>();
        AtomicReference<ModelRequest> lastSeen = new AtomicReference<>();
        ModelCallObserverChain chain = new ModelCallObserverChain(List.of(
                new CapturingObserver(firstSeen),
                new ContextCalibrationObserver(new SimpleMeterRegistry(),
                        new HeuristicTokenCounter(), new TokenEstimationCalibrator()),
                new CapturingObserver(lastSeen)));
        AtomicReference<ModelRequest> delegateRef = new AtomicReference<>();
        ObservableModelGateway gateway = new ObservableModelGateway(
                req -> {
                    delegateRef.set(req);
                    return response(80);
                }, chain);

        ModelRequest request = sampleRequest();
        gateway.complete("t", "r", 2, request);

        assertThat(firstSeen.get())
                .as("⛔ 链首观测者看到的请求不是同一个引用：观测链悄悄替换了请求")
                .isSameAs(request);
        assertThat(lastSeen.get())
                .as("⛔ 经校准桥之后的观测者看到的请求被替换：G1 请求字节零差异被破坏")
                .isSameAs(request);
        assertThat(delegateRef.get())
                .as("⛔ delegate 收到的请求被替换")
                .isSameAs(request);
    }

    @Test
    @DisplayName("✅ 失败路径不改写请求；onException 清理状态，不串味到下一次成功调用")
    void exceptionPathLeavesRequestUntouchedAndClearsState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var calibrator = new TokenEstimationCalibrator();
        var calibration = new ContextCalibrationObserver(
                registry, new HeuristicTokenCounter(), calibrator);
        ObservableModelGateway failing = new ObservableModelGateway(
                req -> {
                    throw new ModelGatewayException(ModelErrorKind.TIMEOUT, "stub timeout");
                },
                new ModelCallObserverChain(List.of(calibration)));

        ModelRequest request = sampleRequest();
        String before = request.toString();
        assertThatThrownBy(() -> failing.complete("t", "r1", 1, request))
                .as("失败必须冒泡给主流程（fail-closed），校准桥不得吞掉模型调用异常")
                .isInstanceOf(ModelGatewayException.class);

        assertThat(request.toString()).as("⛔ 失败路径改写了请求").isEqualTo(before);
        assertThat(calibrator.sampleCount(MODEL))
                .as("⛔ 失败调用无 usage 真值，不应产生校准样本")
                .isZero();

        // 下一次成功调用：ThreadLocal 已被 onException 清理，不会用到上一次的 pending 值。
        AtomicReference<ModelRequest> next = new AtomicReference<>();
        ObservableModelGateway ok = new ObservableModelGateway(req -> {
            next.set(req);
            return response(100);
        }, new ModelCallObserverChain(List.of(calibration)));
        ModelRequest request2 = sampleRequest();
        ok.complete("t", "r2", 2, request2);

        assertThat(calibrator.sampleCount(MODEL))
                .as("⛔ 成功调用应恰好新增 1 个样本（前一次失败未串味）")
                .isEqualTo(1);
        assertThat(next.get()).isSameAs(request2);
    }

    @Test
    @DisplayName("✅ 无上下文 overload 不触发观测链（覆盖边界：只有带上下文的调用才被计量）")
    void noContextOverloadSkipsObservers() {
        var calibrator = new TokenEstimationCalibrator();
        var calibration = new ContextCalibrationObserver(new SimpleMeterRegistry(),
                new HeuristicTokenCounter(), calibrator);
        ObservableModelGateway gateway = new ObservableModelGateway(
                req -> response(100), new ModelCallObserverChain(List.of(calibration)));

        gateway.complete(sampleRequest());

        assertThat(calibrator.sampleCount(MODEL))
                .as("无上下文调用不进观测链：SSE/Console 不经 ObservableModelGateway，"
                        + "其行为覆盖不能靠本桥证明，须由零接线守卫承担")
                .isZero();
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

    /** 只读观测者：仅记录引用，绝不构造/替换请求。 */
    private static final class CapturingObserver implements ModelCallObserver {
        private final AtomicReference<ModelRequest> ref;

        private CapturingObserver(AtomicReference<ModelRequest> ref) {
            this.ref = ref;
        }

        @Override
        public void beforeCall(String tenantId, String runId, int step, ModelRequest request) {
            ref.set(request);
        }

        @Override
        public void afterCall(String tenantId, String runId, int step, ModelRequest request,
                              ModelResponse response, Duration latency) {
            ref.set(request);
        }

        @Override
        public void onException(String tenantId, String runId, int step, ModelRequest request,
                                ModelGatewayException exception, Duration latency) {
            ref.set(request);
        }
    }
}
