package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * ModelCallObserver 链式组合器。
 *
 * <p>按顺序调用每个观测者，任何一个观测者异常都不影响后续观测者和主流程。
 */
public class ModelCallObserverChain implements ModelCallObserver {

    private final List<ModelCallObserver> observers;

    public ModelCallObserverChain(@NonNull List<ModelCallObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    public void beforeCall(@NonNull String tenantId, @NonNull String runId, int step,
                           @NonNull ModelRequest request) {
        for (ModelCallObserver o : observers) {
            try {
                o.beforeCall(tenantId, runId, step, request);
            } catch (RuntimeException e) {
                // ⚠️ 观测者异常不得影响主流程
                logWarn("beforeCall", o.getClass().getSimpleName(), tenantId, runId, e);
            }
        }
    }

    @Override
    public void afterCall(@NonNull String tenantId, @NonNull String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        for (ModelCallObserver o : observers) {
            try {
                o.afterCall(tenantId, runId, step, request, response, latency);
            } catch (RuntimeException e) {
                logWarn("afterCall", o.getClass().getSimpleName(), tenantId, runId, e);
            }
        }
    }

    @Override
    public void onException(@NonNull String tenantId, @NonNull String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        for (ModelCallObserver o : observers) {
            try {
                o.onException(tenantId, runId, step, request, exception, latency);
            } catch (RuntimeException e) {
                logWarn("onException", o.getClass().getSimpleName(), tenantId, runId, e);
            }
        }
    }

    private void logWarn(String method, String observerName,
                         String tenantId, String runId, RuntimeException e) {
        System.err.println("[OBSERVER-CHAIN-WARN] " + observerName + "." + method
                + " tenant=" + tenantId + " run=" + runId
                + " cause=" + e.getClass().getSimpleName());
    }
}
