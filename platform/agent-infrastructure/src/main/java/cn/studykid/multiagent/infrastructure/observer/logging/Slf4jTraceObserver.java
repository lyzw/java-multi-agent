package cn.studykid.multiagent.infrastructure.observer.logging;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.TraceObserver;

/**
 * Slf4j 端到端追踪观测者（MDC 注入 Trace ID）。
 *
 * <p>输出到专用 logger {@code ai.trace}，并在 MDC 中注入 traceId，
 * 所有下游日志自动携带 Trace ID。
 *
 * <p><b>Order = 10</b>：最先执行。
 */
@Order(10)
public class Slf4jTraceObserver implements TraceObserver {

    private static final Logger log = LoggerFactory.getLogger("ai.trace");
    private static final String MDC_KEY = "traceId";

    @Override
    public void onTraceStart(String traceId, String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             @NonNull Map<String, Object> payload) {
        MDC.put(MDC_KEY, traceId);
        log.info("[TRACE_START] trace={} tenant={} run={} eventType={} " +
                 "payloadKeys={}",
                 traceId, tenantId, runId, eventType,
                 payload.keySet());
    }

    @Override
    public void onTraceEvent(String traceId, String event,
                             @NonNull Map<String, Object> data) {
        MDC.put(MDC_KEY, traceId);
        log.debug("[TRACE_EVENT] trace={} event={} dataKeys={}",
                  traceId, event, data.keySet());
    }

    @Override
    public void onTraceEnd(String traceId, TraceOutcome outcome,
                           @NonNull Duration totalLatency) {
        MDC.put(MDC_KEY, traceId);
        log.info("[TRACE_END] trace={} outcome={} latencyMs={}",
                 traceId, outcome.name(), totalLatency.toMillis());
        MDC.remove(MDC_KEY);
    }

    @Override
    public void onTraceAborted(String traceId, String error,
                               @NonNull Duration totalLatency) {
        MDC.put(MDC_KEY, traceId);
        log.error("[TRACE_ABORTED] trace={} error={} latencyMs={}",
                  traceId, error, totalLatency.toMillis());
        MDC.remove(MDC_KEY);
    }

    /**
     * 生成 Trace ID（全局唯一）。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 唯一的 Trace ID
     */
    public static String generateTraceId(String tenantId,
                                                   @NonNull String runId) {
        return tenantId + ":" + runId + ":" +
               UUID.randomUUID().toString().substring(0, 8);
    }
}
