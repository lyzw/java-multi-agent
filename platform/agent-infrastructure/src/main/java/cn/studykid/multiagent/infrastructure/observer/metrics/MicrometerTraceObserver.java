package cn.studykid.multiagent.infrastructure.observer.metrics;

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
 */
@Order(20)
public class MicrometerTraceObserver implements TraceObserver {

    private final MeterRegistry registry;

    private static final String TRACE_TOTAL = "ai.trace.total";
    private static final String TRACE_LATENCY = "ai.trace.latency";

    private static final String TAG_TENANT = "tenant";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_ERROR = "error";

    private Timer.Sample sample;

    public MicrometerTraceObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onTraceStart(String traceId, String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             java.util.Map<String, Object> payload) {
        // 开始计时
        sample = Timer.start(registry);
    }

    @Override
    public void onTraceEvent(String traceId, String event,
                             java.util.Map<String, Object> data) {
        // 不采集
    }

    @Override
    public void onTraceEnd(String traceId, TraceOutcome outcome,
                           java.time.Duration totalLatency) {
        // 追踪总次数
        Counter.builder(TRACE_TOTAL)
                .description("Total traces")
                .tags(TAG_TENANT, extractTenant(traceId),
                      TAG_OUTCOME, outcome.name())
                .register(registry)
                .increment();

        // 端到端延迟
        if (sample != null) {
            Timer timer = Timer.builder(TRACE_LATENCY)
                    .description("End-to-end trace latency")
                    .tags(TAG_TENANT, extractTenant(traceId),
                          TAG_OUTCOME, outcome.name())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry);
            sample.stop(timer);
        }
    }

    @Override
    public void onTraceAborted(String traceId, String error,
                               java.time.Duration totalLatency) {
        // 追踪总次数（含异常）
        Counter.builder(TRACE_TOTAL)
                .description("Total traces")
                .tags(TAG_TENANT, extractTenant(traceId),
                      TAG_ERROR, error)
                .register(registry)
                .increment();

        // 延迟
        if (sample != null) {
            Timer timer = Timer.builder(TRACE_LATENCY)
                    .description("End-to-end trace latency")
                    .tags(TAG_TENANT, extractTenant(traceId),
                          TAG_ERROR, error)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry);
            sample.stop(timer);
        }
    }

    private String extractTenant(String traceId) {
        String[] parts = traceId.split(":");
        return parts.length > 0 ? parts[0] : "unknown";
    }
}
