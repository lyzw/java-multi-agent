package cn.studykid.multiagent.infrastructure.observer.noop;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * Noop 执行循环观测者（空实现，装配兜底）。
 */
@Order(Integer.MAX_VALUE)
public class NoopExecutionObserver implements ExecutionObserver {

    @Override
    public void onLoopStart(@NonNull String tenantId, @NonNull String runId, int maxSteps) {}

    @Override
    public void onStepStart(@NonNull String tenantId, @NonNull String runId, int step) {}

    @Override
    public void onModelCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                    @NonNull ModelResponse response, @NonNull Duration latency) {}

    @Override
    public void onToolCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                   @NonNull ToolCallSummary toolResult,
                                   @NonNull Duration latency) {}

    @Override
    public void onContextTrimmed(@NonNull String tenantId, @NonNull String runId,
                                 int droppedRounds, int droppedTokens,
                                 @NonNull List<String> droppedTools) {}

    @Override
    public void onLoopComplete(@NonNull String tenantId, @NonNull String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {}
}
