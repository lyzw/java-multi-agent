package cn.studykid.multiagent.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.NonNull;

/**
 * 估算偏差校准器（ADR-013 / 《方案》§5.7、§7.1、R2）。
 *
 * <p>⚠️ <b>为什么按 modelId 聚合</b>：启发式估算的系统性偏差来自
 * tokenizer 与语言分布，与单次请求的 {@code policyVersion} 无关；
 * 若把 policyVersion 也作为聚合维度，每次改配置都把样本数清零，
 * 校准永远达不到 n≥30 → 形同没有校准。
 *
 * <p>⚠️ <b>n&lt;30 绝不生效</b>：样本不足时 {@link #bias(String)} 恒返回 1.0，
 * 面板不得展示百分比（沿用 P4「n&lt;30 不显示百分比」规则）。
 * 用少量样本调 bias 会把一次异常响应放大成长期系统性偏差。
 *
 * <p>⚠️ <b>夹在 [0.8, 2.0]</b>：供应商偶发把 usage 记成 0 或重复计数时，
 * bias 不得把估算抬到荒谬量级；非法 / 非有限样本直接丢弃（fail-closed，
 * 不污染 EWMA）。{@code reserveRatio} 与硬顶仍是最后两道兜底。
 *
 * <p>⚠️ 线程安全：每个 modelId 用不可变 state + CAS 更新，无全局锁。
 */
public final class TokenEstimationCalibrator {

    /** 《方案》§3.2-C：n&lt;30 不启用校准、不展示百分比。 */
    public static final int DEFAULT_MIN_SAMPLES = 30;
    /** EWMA 平滑系数。 */
    public static final double DEFAULT_ALPHA = 0.20;
    public static final double DEFAULT_CLAMP_MIN = 0.80;
    public static final double DEFAULT_CLAMP_MAX = 2.00;

    /** 不可变快照：CAS 整体替换，避免 count 与 ewma 读到不一致的中间态。 */
    private record State(long count, double ewma) {
    }

    private final ConcurrentMap<String, AtomicReference<State>> states = new ConcurrentHashMap<>();
    private final int minSamples;
    private final double alpha;
    private final double clampMin;
    private final double clampMax;

    public TokenEstimationCalibrator() {
        this(DEFAULT_MIN_SAMPLES, DEFAULT_ALPHA, DEFAULT_CLAMP_MIN, DEFAULT_CLAMP_MAX);
    }

    public TokenEstimationCalibrator(int minSamples, double alpha,
                                     double clampMin, double clampMax) {
        if (minSamples < 2 || alpha <= 0 || alpha > 1 || clampMin <= 0
                || clampMax <= clampMin) {
            throw new IllegalArgumentException("校准参数非法");
        }
        this.minSamples = minSamples;
        this.alpha = alpha;
        this.clampMin = clampMin;
        this.clampMax = clampMax;
    }

    /**
     * 记录一次「真值 / 全窗口估算」的比值。
     *
     * @param actualTokens  供应商 {@code usage.prompt_tokens}（即 ModelUsage.inputTokens）
     * @param estimatedTokens <b>全窗口</b>估算，⛔ 不得传旧链路的「消息序列」估算
     * @return 本次比值；样本非法返回 {@code Optional.empty()} 的等价语义用 NaN 表示
     */
    public double addSample(@NonNull String modelId, long actualTokens, int estimatedTokens) {
        if (estimatedTokens <= 0 || actualTokens <= 0) {
            return Double.NaN;   // 丢弃：绝不把 0 用量当成「估算偏大 100%」
        }
        return addSample(modelId, (double) actualTokens / estimatedTokens);
    }

    /** 记录已算好的比值；非有限 / 非正样本直接丢弃。 */
    public double addSample(@NonNull String modelId, double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0) {
            return Double.NaN;
        }
        AtomicReference<State> ref = states.computeIfAbsent(modelId,
                k -> new AtomicReference<>(new State(0, 1.0)));
        ref.updateAndGet(prev -> {
            double next = prev.count() == 0
                    ? ratio : prev.ewma() + alpha * (ratio - prev.ewma());
            return new State(prev.count() + 1, next);
        });
        return ratio;
    }

    /** 是否已有足够样本允许校准生效。 */
    public boolean ready(@NonNull String modelId) {
        return sampleCount(modelId) >= minSamples;
    }

    public long sampleCount(@NonNull String modelId) {
        AtomicReference<State> ref = states.get(modelId);
        return ref == null ? 0 : ref.get().count();
    }

    /** 未 ready 恒为 1.0；ready 后返回夹在 [clampMin, clampMax] 的 EWMA。 */
    public double bias(@NonNull String modelId) {
        AtomicReference<State> ref = states.get(modelId);
        if (ref == null) {
            return 1.0;
        }
        State s = ref.get();
        if (s.count() < minSamples) {
            return 1.0;
        }
        return Math.min(clampMax, Math.max(clampMin, s.ewma()));
    }

    /** 单次误差，供 {@code context.estimation_error_ratio} 指标使用。 */
    public static double errorRatio(double ratio) {
        return Math.abs(1.0 - ratio);
    }
}
