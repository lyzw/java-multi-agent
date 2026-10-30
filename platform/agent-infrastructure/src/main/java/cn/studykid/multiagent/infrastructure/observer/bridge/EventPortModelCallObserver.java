package cn.studykid.multiagent.infrastructure.observer.bridge;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunEventPort;

/**
 * 模型调用观测者 → RunEventPort 桥接。
 *
 * <p>将模型调用观测事件写入现有的 {@code RunEventPort}，复用既有事件管道。
 * 事件类型：MODEL_CALL_BEFORE / MODEL_CALL_DONE / MODEL_CALL_ERROR。
 *
 * <p><b>Order = 30</b>：在日志和指标之后执行（事件写入可能失败，不应阻塞前两者）。
 *
 * <p><b>关键纪律</b>：
 * <ul>
 *   <li>写入失败只记 WARN，不冒泡（与 RunExecutionLoop 中 appendEvent 同纪律）；</li>
 *   <li>事件数据只含白名单字段，不含 content 原文（合规红线）；</li>
 *   <li>eventPort 为 null 时静默跳过（向后兼容）。</li>
 * </ul>
 */
@Order(30)
public class EventPortModelCallObserver implements ModelCallObserver {

    private final RunEventPort eventPort;

    public EventPortModelCallObserver(RunEventPort eventPort) {
        this.eventPort = eventPort;
    }

    @Override
    public void beforeCall(String tenantId, String runId, int step,
                           @NonNull ModelRequest request) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "MODEL_CALL_BEFORE",
                    Map.of(
                            "step", step,
                            "modelId", request.modelId(),
                            "temperature", request.temperature(),
                            "maxTokens", request.maxTokens(),
                            "timeoutMs", request.timeout().toMillis(),
                            "tools", (long) request.toolSchemas().size(),
                            "systemPromptLen", (long) request.systemPrompt().length(),
                            "messages", (long) request.effectiveMessages().size()));
        } catch (RuntimeException e) {
            // ⚠️ 写入失败只记 WARN，不冒泡
            logWarn("MODEL_CALL_BEFORE", tenantId, runId, e);
        }
    }

    @Override
    public void afterCall(String tenantId, String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "MODEL_CALL_DONE",
                    Map.of(
                            "step", step,
                            "modelId", request.modelId(),
                            "latencyMs", latency.toMillis(),
                            "contentLen", (long) response.content().length(),
                            "toolCalls", (long) response.toolCalls().size(),
                            "finishReason", response.finishReason().name(),
                            "inputTokens", response.usage().inputTokens(),
                            "outputTokens", response.usage().outputTokens()));
        } catch (RuntimeException e) {
            logWarn("MODEL_CALL_DONE", tenantId, runId, e);
        }
    }

    @Override
    public void onException(String tenantId, String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        if (eventPort == null) return;
        try {
            eventPort.append(tenantId, runId, "MODEL_CALL_ERROR",
                    Map.of(
                            "step", step,
                            "modelId", request.modelId(),
                            "latencyMs", latency.toMillis(),
                            "errorKind", exception.kind().name(),
                            "retryable", exception.kind().retryable()));
        } catch (RuntimeException e) {
            logWarn("MODEL_CALL_ERROR", tenantId, runId, e);
        }
    }

    private void logWarn(String type, String tenantId, String runId, RuntimeException e) {
        System.err.println("[OBSERVER-BRIDGE-WARN] EventPortModelCallObserver." + type
                + " tenant=" + tenantId + " run=" + runId
                + " cause=" + e.getClass().getSimpleName());
    }
}
