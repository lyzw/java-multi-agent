package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome.Kind;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * ExecutionObserver 链式组合器。
 */
public class ExecutionObserverChain implements ExecutionObserver {

    private final List<ExecutionObserver> observers;

    public ExecutionObserverChain(@NonNull List<ExecutionObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    public void onLoopStart(@NonNull String tenantId, @NonNull String runId, int maxSteps) {
        for (ExecutionObserver o : observers) {
            try { o.onLoopStart(tenantId, runId, maxSteps); }
            catch (RuntimeException e) { logWarn("onLoopStart", o.getClass(), tenantId, runId, e); }
        }
    }

    @Override
    public void onStepStart(@NonNull String tenantId, @NonNull String runId, int step) {
        for (ExecutionObserver o : observers) {
            try { o.onStepStart(tenantId, runId, step); }
            catch (RuntimeException e) { logWarn("onStepStart", o.getClass(), tenantId, runId, e); }
        }
    }

    @Override
    public void onModelCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                    @NonNull ModelResponse response, @NonNull Duration latency) {
        for (ExecutionObserver o : observers) {
            try { o.onModelCallComplete(tenantId, runId, step, response, latency); }
            catch (RuntimeException e) { logWarn("onModelCallComplete", o.getClass(), tenantId, runId, e); }
        }
    }

    @Override
    public void onToolCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                   @NonNull ToolCallSummary toolResult,
                                   @NonNull Duration latency) {
        for (ExecutionObserver o : observers) {
            try { o.onToolCallComplete(tenantId, runId, step, toolResult, latency); }
            catch (RuntimeException e) { logWarn("onToolCallComplete", o.getClass(), tenantId, runId, e); }
        }
    }

    @Override
    public void onContextTrimmed(@NonNull String tenantId, @NonNull String runId,
                                 int droppedRounds, int droppedTokens,
                                 @NonNull List<String> droppedTools) {
        for (ExecutionObserver o : observers) {
            try { o.onContextTrimmed(tenantId, runId, droppedRounds, droppedTokens, droppedTools); }
            catch (RuntimeException e) { logWarn("onContextTrimmed", o.getClass(), tenantId, runId, e); }
        }
    }

    @Override
    public void onLoopComplete(@NonNull String tenantId, @NonNull String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {
        for (ExecutionObserver o : observers) {
            try { o.onLoopComplete(tenantId, runId, outcome, totalSteps); }
            catch (RuntimeException e) { logWarn("onLoopComplete", o.getClass(), tenantId, runId, e); }
        }
    }

    /**
     * 从 worker 模块的 ExecutionOutcome 适配为中性的 LoopOutcome。
     */
    public static @NonNull LoopOutcome adapt(@NonNull Object workerOutcome) {
        // 通过反射读取 kind 和 detail，避免直接依赖 worker 模块
        try {
            java.lang.reflect.Method kindMethod = workerOutcome.getClass().getMethod("kind");
            Object kind = kindMethod.invoke(workerOutcome);
            String kindName = kind.toString(); // Kind enum name

            java.lang.reflect.Method detailMethod = workerOutcome.getClass().getMethod("detail");
            Object detail = detailMethod.invoke(workerOutcome);
            String detailStr = detail != null ? detail.toString() : null;

            Kind loopKind = Kind.valueOf(kindName);
            return new LoopOutcome(loopKind, detailStr);
        } catch (Exception e) {
            // 适配失败时返回最保守的结果
            return new LoopOutcome(Kind.FAILED, "adapt_error");
        }
    }

    private void logWarn(String method, Class<?> observerClass,
                         String tenantId, String runId, RuntimeException e) {
        System.err.println("[OBSERVER-CHAIN-WARN] " + observerClass.getSimpleName() + "." + method
                + " tenant=" + tenantId + " run=" + runId
                + " cause=" + e.getClass().getSimpleName());
    }
}
