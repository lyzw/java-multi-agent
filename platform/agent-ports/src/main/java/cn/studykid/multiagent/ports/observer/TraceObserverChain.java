package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * TraceObserver 链式组合器。
 */
public class TraceObserverChain implements TraceObserver {

    private final List<TraceObserver> observers;

    public TraceObserverChain(@NonNull List<TraceObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    public void onTraceStart(@NonNull String traceId, @NonNull String tenantId,
                             @NonNull String runId, @NonNull String eventType,
                             @NonNull Map<String, Object> payload) {
        for (TraceObserver o : observers) {
            try { o.onTraceStart(traceId, tenantId, runId, eventType, payload); }
            catch (RuntimeException e) { logWarn("onTraceStart", o.getClass(), traceId, e); }
        }
    }

    @Override
    public void onTraceEvent(@NonNull String traceId, @NonNull String event,
                             @NonNull Map<String, Object> data) {
        for (TraceObserver o : observers) {
            try { o.onTraceEvent(traceId, event, data); }
            catch (RuntimeException e) { logWarn("onTraceEvent", o.getClass(), traceId, e); }
        }
    }

    @Override
    public void onTraceEnd(@NonNull String traceId, @NonNull TraceOutcome outcome,
                           @NonNull Duration totalLatency) {
        for (TraceObserver o : observers) {
            try { o.onTraceEnd(traceId, outcome, totalLatency); }
            catch (RuntimeException e) { logWarn("onTraceEnd", o.getClass(), traceId, e); }
        }
    }

    @Override
    public void onTraceAborted(@NonNull String traceId, @NonNull String error,
                               @NonNull Duration totalLatency) {
        for (TraceObserver o : observers) {
            try { o.onTraceAborted(traceId, error, totalLatency); }
            catch (RuntimeException e) { logWarn("onTraceAborted", o.getClass(), traceId, e); }
        }
    }

    private void logWarn(String method, Class<?> observerClass,
                         String traceId, RuntimeException e) {
        System.err.println("[OBSERVER-CHAIN-WARN] " + observerClass.getSimpleName() + "." + method
                + " trace=" + traceId + " cause=" + e.getClass().getSimpleName());
    }
}
