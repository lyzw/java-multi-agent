package cn.studykid.multiagent.infrastructure.observer.bridge;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunEventPort;

/**
 * 执行循环观测者 → RunEventPort 桥接。
 *
 * <p>事件类型：STEP_START / STEP_MODEL_DONE / STEP_TOOL_DONE / CONTEXT_TRIMMED / LOOP_COMPLETE
 *
 * <p><b>Order = 30</b>：在日志和指标之后执行。
 */
@Order(30)
public class EventPortExecutionObserver implements ExecutionObserver {

    private final RunEventPort eventPort;

    public EventPortExecutionObserver(RunEventPort eventPort) {
        this.eventPort = eventPort;
    }

    @Override
    public void onLoopStart(String tenantId, String runId, int maxSteps) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "LOOP_START",
                    Map.of("maxSteps", (long) maxSteps));
        } catch (RuntimeException e) {
            logWarn("LOOP_START", tenantId, runId, e);
        }
    }

    @Override
    public void onStepStart(String tenantId, String runId, int step) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "STEP_START",
                    Map.of("step", (long) step));
        } catch (RuntimeException e) {
            logWarn("STEP_START", tenantId, runId, e);
        }
    }

    @Override
    public void onModelCallComplete(String tenantId, String runId, int step,
                                    @NonNull ModelResponse response, @NonNull Duration latency) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "STEP_MODEL_DONE",
                    Map.of(
                            "step", (long) step,
                            "latencyMs", latency.toMillis(),
                            "contentLen", (long) response.content().length(),
                            "toolCalls", (long) response.toolCalls().size(),
                            "finishReason", response.finishReason().name()));
        } catch (RuntimeException e) {
            logWarn("STEP_MODEL_DONE", tenantId, runId, e);
        }
    }

    @Override
    public void onToolCallComplete(String tenantId, String runId, int step,
                                   @NonNull ToolCallSummary toolResult,
                                   @NonNull Duration latency) {
        if (eventPort == null) return;
        try {
            int total = toolResult.succeededTools().size()
                      + toolResult.failedTools().size()
                      + toolResult.rejectedTools().size();
            eventPort.append(tenantId, runId, "STEP_TOOL_DONE",
                    Map.of(
                            "step", (long) step,
                            "latencyMs", latency.toMillis(),
                            "total", (long) total,
                            "succeeded", (long) toolResult.succeededTools().size(),
                            "failed", (long) toolResult.failedTools().size(),
                            "rejected", (long) toolResult.rejectedTools().size(),
                            "unknown", toolResult.unknownResultOccurred() ? 1L : 0L));
        } catch (RuntimeException e) {
            logWarn("STEP_TOOL_DONE", tenantId, runId, e);
        }
    }

    @Override
    public void onContextTrimmed(String tenantId, String runId,
                                 int droppedRounds, int droppedTokens,
                                 @NonNull List<String> droppedTools) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "CONTEXT_TRIMMED",
                    Map.of(
                            "droppedRounds", (long) droppedRounds,
                            "droppedTokens", (long) droppedTokens,
                            "droppedTools", String.join(",", droppedTools)));
        } catch (RuntimeException e) {
            logWarn("CONTEXT_TRIMMED", tenantId, runId, e);
        }
    }

    @Override
    public void onLoopComplete(String tenantId, String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "LOOP_COMPLETE",
                    Map.of(
                            "kind", outcome.kind().name(),
                            "detail", outcome.detail() != null ? outcome.detail() : "",
                            "totalSteps", (long) totalSteps,
                            "retryable", outcome.retryable()));
        } catch (RuntimeException e) {
            logWarn("LOOP_COMPLETE", tenantId, runId, e);
        }
    }

    private void logWarn(String type, String tenantId, String runId, RuntimeException e) {
        System.err.println("[OBSERVER-BRIDGE-WARN] EventPortExecutionObserver." + type
                + " tenant=" + tenantId + " run=" + runId
                + " cause=" + e.getClass().getSimpleName());
    }
}
