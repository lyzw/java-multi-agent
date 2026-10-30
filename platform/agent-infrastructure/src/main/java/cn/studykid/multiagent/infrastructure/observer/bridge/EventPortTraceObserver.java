package cn.studykid.multiagent.infrastructure.observer.bridge;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.TraceObserver;
import cn.studykid.multiagent.ports.persistence.RunEventPort;

/**
 * 端到端追踪观测者 → RunEventPort 桥接。
 *
 * <p>事件类型：TRACE_START / TRACE_EVENT / TRACE_END / TRACE_ABORTED
 *
 * <p><b>Order = 30</b>：在日志和指标之后执行。
 */
@Order(30)
public class EventPortTraceObserver implements TraceObserver {

    private final RunEventPort eventPort;

    public EventPortTraceObserver(RunEventPort eventPort) {
        this.eventPort = eventPort;
    }

    @Override
    public void onTraceStart(String traceId, String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             @NonNull Map<String, Object> payload) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "TRACE_START",
                    Map.of(
                            "traceId", traceId,
                            "eventType", eventType,
                            "payloadKeys", String.join(",", payload.keySet())));
        } catch (RuntimeException e) {
            logWarn("TRACE_START", traceId, e);
        }
    }

    @Override
    public void onTraceEvent(String traceId, String event,
                             @NonNull Map<String, Object> data) {
        if (eventPort == null) return;
        // ⚠️ TraceEvent 不写 RunEventPort（避免事件膨胀）
        // 仅在 TRACE_END 时写入汇总
    }

    @Override
    public void onTraceEnd(String traceId, TraceOutcome outcome,
                           @NonNull Duration totalLatency) {
        if (eventPort == null) return;
        try {
            // 从 traceId 中解析 tenantId 和 runId
            String[] parts = traceId.split(":");
            String tenantId = parts.length > 0 ? parts[0] : "unknown";
            String runId = parts.length > 1 ? parts[1] : "unknown";

            eventPort.append(tenantId, runId, "TRACE_END",
                    Map.of(
                            "traceId", traceId,
                            "outcome", outcome.name(),
                            "latencyMs", totalLatency.toMillis()));
        } catch (RuntimeException e) {
            logWarn("TRACE_END", traceId, e);
        }
    }

    @Override
    public void onTraceAborted(String traceId, String error,
                               @NonNull Duration totalLatency) {
        if (eventPort == null) return;
        try {
            String[] parts = traceId.split(":");
            String tenantId = parts.length > 0 ? parts[0] : "unknown";
            String runId = parts.length > 1 ? parts[1] : "unknown";

            eventPort.append(tenantId, runId, "TRACE_ABORTED",
                    Map.of(
                            "traceId", traceId,
                            "error", error,
                            "latencyMs", totalLatency.toMillis()));
        } catch (RuntimeException e) {
            logWarn("TRACE_ABORTED", traceId, e);
        }
    }

    private void logWarn(String type, String traceId, RuntimeException e) {
        System.err.println("[OBSERVER-BRIDGE-WARN] EventPortTraceObserver." + type
                + " trace=" + traceId + " cause=" + e.getClass().getSimpleName());
    }
}
