package cn.studykid.multiagent.infrastructure.observer.noop;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.TraceObserver;

/**
 * Noop 端到端追踪观测者（空实现，装配兜底）。
 */
@Order(Integer.MAX_VALUE)
public class NoopTraceObserver implements TraceObserver {

    @Override
    public void onTraceStart(@NonNull String traceId, @NonNull String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             @NonNull Map<String, Object> payload) {}

    @Override
    public void onTraceEvent(@NonNull String traceId, @NonNull String event,
                             @NonNull Map<String, Object> data) {}

    @Override
    public void onTraceEnd(@NonNull String traceId, @NonNull TraceOutcome outcome,
                           @NonNull Duration totalLatency) {}

    @Override
    public void onTraceAborted(@NonNull String traceId, @NonNull String error,
                               @NonNull Duration totalLatency) {}
}
