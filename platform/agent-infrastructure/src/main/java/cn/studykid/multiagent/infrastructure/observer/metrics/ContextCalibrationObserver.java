package cn.studykid.multiagent.infrastructure.observer.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.context.RequestContextProjection;
import cn.studykid.multiagent.context.TokenEstimationCalibrator;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 上下文压缩估算校准桥（ADR-013 / M4 / 方案 §6.4-⑦、§7.1）。
 *
 * <p><b>职责</b>：在模型调用生命周期里<b>只读</b>地采集「全窗口估算 vs
 * {@code usage.prompt_tokens} 真值」的偏差，喂给 {@link TokenEstimationCalibrator}
 * 做 EWMA 校准；影子期即启用，为 S2 的压缩决策积累可信的估算系数。
 *
 * <p>⚠️⚠️ <b>G1「影子对照零差异」的类型级保证</b>：本观测者<b>只读</b>
 * {@code ModelRequest}，从不构造、替换、修改它（它是不可变 record）。
 * {@code ModelCallObserverChain} 在 {@code delegate.complete(request)} 前后调用，
 * 传入 delegate 的始终是<b>同一个对象引用</b>——即使本 Bean 注册进链，
 * 模型请求字节也逐位不变。这是「接了观测 ≠ 改了请求」的结构性证据。
 *
 * <p>⚠️ <b>比较对象必须是全窗口估算</b>：{@link #estimate(ModelRequest)} 用
 * {@link RequestContextProjection} 投影六块后交给 {@link TokenCounter}，
 * 不用旧 {@code ContextBudgetTrimmer.trim()} 的「消息序列」口径——
 * 否则误差被系统性低估，bias 会把真实超窗悄悄放大。
 *
 * <p>⚠️ <b>异常隔离</b>：所有可失败逻辑包在 try/catch 里，观测者异常只记 WARN，
 * 绝不冒泡影响模型调用（与 {@code ModelCallObserver} 契约一致，双保险）。
 * {@code ThreadLocal} 在 finally 清理，防止线程池复用导致的跨请求估算串味。
 *
 * <p>⚠️ <b>Order = 40</b>：观测链末尾（日志 10 / 指标 20 / 事件桥接 30 之后），
 * 本桥失败不影响既有观测者；且它自身不发送任何事件、不产生 outbox。
 */
@Order(40)
public class ContextCalibrationObserver implements ModelCallObserver {

    /** 指标名（方案 §7.1）。 */
    static final String TOKENS_BEFORE = "context.tokens.before";
    static final String TOKENS_ACTUAL = "context.tokens.actual";
    static final String ESTIMATION_ERROR_RATIO = "context.estimation_error_ratio";
    static final String BIAS = "context.bias";

    private static final String TAG_MODEL = "model";
    private static final String TAG_TENANT = "tenant";

    private final MeterRegistry registry;
    private final TokenCounter counter;
    private final TokenEstimationCalibrator calibrator;

    /** before→after 的估算值桥接：同一调用线程同步执行，用 ThreadLocal 隔离请求。 */
    private final ThreadLocal<Integer> pendingEstimate = new ThreadLocal<>();

    /** modelId → 已注册 gauge 去重；避免每次调用重复注册同名 meter。 */
    private final Map<String, AtomicBoolean> gaugeRegistered = new ConcurrentHashMap<>();

    public ContextCalibrationObserver(@NonNull MeterRegistry registry,
                                      @NonNull TokenCounter counter,
                                      @NonNull TokenEstimationCalibrator calibrator) {
        this.registry = registry;
        this.counter = counter;
        this.calibrator = calibrator;
    }

    @Override
    public void beforeCall(@NonNull String tenantId, @NonNull String runId, int step,
                           @NonNull ModelRequest request) {
        try {
            pendingEstimate.set(estimate(request));
            summary(TOKENS_BEFORE, request.modelId(), tenantId).record(pendingEstimate.get());
        } catch (RuntimeException e) {
            clearQuietly();
            warn("beforeCall", e);
        }
    }

    @Override
    public void afterCall(@NonNull String tenantId, @NonNull String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        try {
            Integer estimate = pendingEstimate.get();
            if (estimate == null) {
                // 无上下文路径（ObservableModelGateway.complete(request) 无重载）不会触发 beforeCall；
                // 此时跳过校准而非补算——避免把「无 before 的调用」当成估算缺失写入 0 样本。
                return;
            }
            long actual = response.usage().inputTokens();
            summary(TOKENS_ACTUAL, request.modelId(), tenantId).record(actual);

            double ratio = calibrator.addSample(request.modelId(), actual, estimate);
            if (!Double.isNaN(ratio)) {
                // errorRatio 用真值/估算的偏差；NaN 样本已被校准器丢弃，不入指标。
                summary(ESTIMATION_ERROR_RATIO, request.modelId(), tenantId)
                        .record(TokenEstimationCalibrator.errorRatio(ratio));
            }
            registerBiasGaugeIfNeeded(request.modelId());
        } catch (RuntimeException e) {
            warn("afterCall", e);
        } finally {
            clearQuietly();
        }
    }

    @Override
    public void onException(@NonNull String tenantId, @NonNull String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        // 失败调用无 usage 真值，不产生校准样本；仅清理 ThreadLocal 防止串味。
        clearQuietly();
    }

    /** 全窗口估算（六块投影后计数）。包可见以便单测直接断言口径。 */
    int estimate(@NonNull ModelRequest request) {
        return counter.count(RequestContextProjection.project(request));
    }

    private void registerBiasGaugeIfNeeded(@NonNull String modelId) {
        AtomicBoolean flag = gaugeRegistered.computeIfAbsent(modelId, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            try {
                Gauge.builder(BIAS, () -> calibrator.bias(modelId))
                        .description("Heuristic token estimation bias (EWMA, 1.0 until n>=30)")
                        .tags(TAG_MODEL, modelId)
                        .register(registry);
            } catch (RuntimeException e) {
                // 重复注册等冲突：gauge 已存在则忽略，不影响校准样本写入。
                warn("registerBiasGauge", e);
            }
        }
    }

    private @NonNull DistributionSummary summary(@NonNull String name, @NonNull String modelId,
                                                  @NonNull String tenantId) {
        return DistributionSummary.builder(name)
                .description("Context estimation shadow metric")
                .tags(TAG_MODEL, modelId, TAG_TENANT, tenantId)
                .register(registry);
    }

    private void clearQuietly() {
        try {
            pendingEstimate.remove();
        } catch (RuntimeException ignored) {
            // 观测者清理失败不影响主流程
        }
    }

    private static void warn(@NonNull String method, @NonNull RuntimeException e) {
        System.err.println("[CONTEXT-CALIBRATION-WARN] " + method + " cause="
                + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
    }

    private static @NonNull String safe(String msg) {
        return msg == null ? "" : (msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
    }
}
