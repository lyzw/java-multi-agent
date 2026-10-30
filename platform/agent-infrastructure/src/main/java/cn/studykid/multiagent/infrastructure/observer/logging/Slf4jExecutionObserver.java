package cn.studykid.multiagent.infrastructure.observer.logging;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * Slf4j 执行循环观测者（结构化日志）。
 *
 * <p>输出到专用 logger {@code ai.execution.loop}，便于独立采集。
 * 记录执行循环的生命周期事件。
 *
 * <p><b>Order = 10</b>：最先执行。
 */
@Order(10)
public class Slf4jExecutionObserver implements ExecutionObserver {

    private static final Logger log = LoggerFactory.getLogger("ai.execution.loop");

    @Override
    public void onLoopStart(String tenantId, String runId, int maxSteps) {
        log.info("[LOOP_START] tenant={} run={} maxSteps={}", tenantId, runId, maxSteps);
    }

    @Override
    public void onStepStart(String tenantId, String runId, int step) {
        log.debug("[STEP_START] tenant={} run={} step={}", tenantId, runId, step);
    }

    @Override
    public void onModelCallComplete(String tenantId, String runId, int step,
                                    @NonNull ModelResponse response, @NonNull Duration latency) {
        log.info("[STEP_MODEL_DONE] tenant={} run={} step={} latencyMs={} " +
                 "contentLen={} toolCalls={} finish={}",
                 tenantId, runId, step, latency.toMillis(),
                 response.content().length(),
                 response.toolCalls().size(),
                 response.finishReason().name());
    }

    @Override
    public void onToolCallComplete(String tenantId, String runId, int step,
                                   @NonNull ToolCallSummary toolResult,
                                   @NonNull Duration latency) {
        int total = toolResult.succeededTools().size()
                  + toolResult.failedTools().size()
                  + toolResult.rejectedTools().size();
        log.info("[STEP_TOOL_DONE] tenant={} run={} step={} latencyMs={} " +
                 "total={} succeeded={} failed={} rejected={} unknown={}",
                 tenantId, runId, step, latency.toMillis(),
                 total,
                 toolResult.succeededTools().size(),
                 toolResult.failedTools().size(),
                 toolResult.rejectedTools().size(),
                 toolResult.unknownResultOccurred() ? 1 : 0);
    }

    @Override
    public void onContextTrimmed(String tenantId, String runId,
                                 int droppedRounds, int droppedTokens,
                                 @NonNull List<String> droppedTools) {
        log.info("[CONTEXT_TRIMMED] tenant={} run={} droppedRounds={} " +
                 "droppedTokens≈{} droppedTools={}",
                 tenantId, runId, droppedRounds, droppedTokens, droppedTools);
    }

    @Override
    public void onLoopComplete(String tenantId, String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {
        log.info("[LOOP_COMPLETE] tenant={} run={} kind={} detail={} totalSteps={} " +
                 "retryable={}",
                 tenantId, runId,
                 outcome.kind().name(),
                 outcome.detail(),
                 totalSteps,
                 outcome.retryable());
    }
}
