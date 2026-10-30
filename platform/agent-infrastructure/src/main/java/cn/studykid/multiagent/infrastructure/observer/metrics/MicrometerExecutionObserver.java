package cn.studykid.multiagent.infrastructure.observer.metrics;

import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;
import cn.studykid.multiagent.ports.model.ModelResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 执行循环观测者（指标采集）。
 *
 * <p>采集指标：
 * <ul>
 *   <li>{@code ai.execution.steps} — 执行步数（DistributionSummary）</li>
 *   <li>{@code ai.execution.latency.total} — 端到端延迟（Timer）</li>
 *   <li>{@code ai.execution.loops.total} — 循环完成次数（Counter）</li>
 *   <li>{@code ai.execution.trimmed} — 裁剪发生次数（Counter）</li>
 * </ul>
 *
 * <p><b>Order = 20</b>：在日志之后执行。
 */
@Order(20)
public class MicrometerExecutionObserver implements ExecutionObserver {

    private final MeterRegistry registry;

    private static final String LOOPS_TOTAL = "ai.execution.loops.total";
    private static final String STEPS = "ai.execution.steps";
    private static final String LATENCY_TOTAL = "ai.execution.latency.total";
    private static final String TRIMMED = "ai.execution.trimmed";

    private static final String TAG_TENANT = "tenant";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_RETRYABLE = "retryable";

    private Timer.Sample loopTimer;

    public MicrometerExecutionObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onLoopStart(String tenantId, String runId, int maxSteps) {
        loopTimer = Timer.start(registry);
    }

    @Override
    public void onStepStart(String tenantId, String runId, int step) {
        // 不采集
    }

    @Override
    public void onModelCallComplete(String tenantId, String runId, int step,
                                    @NonNull ModelResponse response, java.time.Duration latency) {
        // 不采集（由 MicrometerModelCallObserver 覆盖）
    }

    @Override
    public void onToolCallComplete(String tenantId, String runId, int step,
                                   @NonNull ToolCallSummary toolResult,
                                   java.time.Duration latency) {
        // 不采集
    }

    @Override
    public void onContextTrimmed(String tenantId, String runId,
                                 int droppedRounds, int droppedTokens,
                                 java.util.List<String> droppedTools) {
        Counter.builder(TRIMMED)
                .description("Context trimmed events")
                .tag(TAG_TENANT, tenantId)
                .register(registry)
                .increment();
    }

    @Override
    public void onLoopComplete(String tenantId, String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {
        Counter.builder(LOOPS_TOTAL)
                .description("Execution loop completions")
                .tags(TAG_TENANT, tenantId,
                      TAG_OUTCOME, outcome.kind().name(),
                      TAG_RETRYABLE, String.valueOf(outcome.retryable()))
                .register(registry)
                .increment();

        DistributionSummary.builder(STEPS)
                .description("Steps per execution loop")
                .tag(TAG_TENANT, tenantId)
                .register(registry)
                .record(totalSteps);

        if (loopTimer != null) {
            Timer timer = Timer.builder(LATENCY_TOTAL)
                    .description("End-to-end execution latency")
                    .tags(TAG_TENANT, tenantId,
                          TAG_OUTCOME, outcome.kind().name())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry);
            loopTimer.stop(timer);
        }
    }
}
