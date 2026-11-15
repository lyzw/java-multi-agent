package cn.studykid.multiagent.infrastructure.observer.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.TraceObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 端到端追踪观测者（指标采集）。
 *
 * <p>采集指标：
 * <ul>
 *   <li>{@code ai.trace.total} — 追踪总次数（Counter）</li>
 *   <li>{@code ai.trace.latency} — 端到端延迟（Timer，P50/P95/P99）</li>
 * </ul>
 *
 * <p>标签：tenant, outcome, error（异常时）
 *
 * <p><b>Order = 20</b>：在日志之后执行。
 *
 * <p>⚠️ <b>P0 修复（2026-11-15）· 并发正确性</b>：此前用<b>实例字段</b>
 * {@code private Timer.Sample sample} 保存计时起点，但本类是<b>单例 Bean</b>，
 * 多个 Run 并发消费时 A 的 sample 会被 B 的 {@code onTraceStart} 覆盖 ——
 * A 结束时 stop 的是 B 的起点，延迟分位数整体失真（且与 worker 并发度正相关）。
 * 现在改为使用 {@code onTraceEnd} 已经传入的 {@code totalLatency} 直接
 * {@code timer.record(...)}，<b>不再持有任何可变状态</b>。
 * 这个修复是「把 TraceObserverChain 接进生产链路」的前置条件：不修就接线，
 * 等于把一份确定错误的延迟数据灌进看板。
 */
@Order(20)
public class MicrometerTraceObserver implements TraceObserver {

    private final MeterRegistry registry;

    private static final String TRACE_TOTAL = "ai.trace.total";
    private static final String TRACE_LATENCY = "ai.trace.latency";

    private static final String TAG_TENANT = "tenant";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_ERROR = "error";

    public MicrometerTraceObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onTraceStart(String traceId, String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             java.util.Map<String, Object> payload) {
        // ⚠️ 刻意无状态：计时由调用方负责，结束事件自带 totalLatency。
        //    这里一旦再引入实例字段，就会重新制造并发覆盖（见类注释）。
    }

    @Override
    public void onTraceEvent(String traceId, String event,
                             java.util.Map<String, Object> data) {
        // 不采集
    }

    @Override
    public void onTraceEnd(String traceId, TraceOutcome outcome,
                           java.time.Duration totalLatency) {
        String tenant = extractTenant(traceId);
        Counter.builder(TRACE_TOTAL)
                .description("Total traces")
                .tags(TAG_TENANT, tenant,
                      TAG_OUTCOME, outcome.name())
                .register(registry)
                .increment();

        recordLatency(tenant, TAG_OUTCOME, outcome.name(), totalLatency);
    }

    @Override
    public void onTraceAborted(String traceId, String error,
                               java.time.Duration totalLatency) {
        String tenant = extractTenant(traceId);
        Counter.builder(TRACE_TOTAL)
                .description("Total traces")
                .tags(TAG_TENANT, tenant,
                      TAG_ERROR, error)
                .register(registry)
                .increment();

        recordLatency(tenant, TAG_ERROR, error, totalLatency);
    }

    /** 无状态记录：直接用结束事件给出的端到端耗时，不依赖任何共享的 Timer.Sample。 */
    private void recordLatency(String tenant, String tagKey, String tagValue,
                               @NonNull Duration totalLatency) {
        Timer timer = Timer.builder(TRACE_LATENCY)
                .description("End-to-end trace latency")
                .tags(TAG_TENANT, tenant, tagKey, tagValue)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        timer.record(Math.max(0L, totalLatency.toNanos()), TimeUnit.NANOSECONDS);
    }

    /**
     * 从 traceId 取租户标签。
     *
     * <p>契约由 {@code Slf4jTraceObserver.generateTraceId} 钉死：
     * {@code tenantId + ":" + runId + ":" + 8 位随机}。因此第一段即租户。
     * ⚠️ 只用于打标签（指标维度），<b>绝不作为权限或隔离依据</b>；
     * 格式不符时返回 {@code unknown}，不猜、不抛异常（观测者不得影响主流程）。
     */
    private String extractTenant(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "unknown";
        }
        int sep = traceId.indexOf(':');
        return sep > 0 ? traceId.substring(0, sep) : "unknown";
    }
}
