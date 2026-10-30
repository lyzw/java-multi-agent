package cn.studykid.multiagent.infrastructure.observer.logging;

import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * Slf4j 模型调用观测者（结构化日志）。
 *
 * <p>输出到专用 logger {@code ai.model.calls}，便于独立采集。
 * 所有输出字段均为脱敏后数据，不含 API Key 等凭据。
 *
 * <p><b>Order = 10</b>：最先执行，确保日志完整。
 */
@Order(10)
public class Slf4jModelCallObserver implements ModelCallObserver {

    private static final Logger log = LoggerFactory.getLogger("ai.model.calls");

    @Override
    public void beforeCall(String tenantId, String runId, int step,
                           @NonNull ModelRequest request) {
        log.info("[MODEL_CALL_BEFORE] tenant={} run={} step={} modelId={} " +
                 "temperature={} maxTokens={} timeout={} tools={} " +
                 "systemPromptLen={} userContentLen={} messages={}",
                 tenantId, runId, step,
                 request.modelId(),
                 request.temperature(),
                 request.maxTokens(),
                 request.timeout(),
                 request.toolSchemas().size(),
                 request.systemPrompt().length(),
                 request.userContent().length(),
                 request.effectiveMessages().size());
    }

    @Override
    public void afterCall(String tenantId, String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        log.info("[MODEL_CALL_DONE] tenant={} run={} step={} modelId={} " +
                 "latencyMs={} contentLen={} toolCalls={} finishReason={} " +
                 "usage={}:{} (total={})",
                 tenantId, runId, step,
                 request.modelId(),
                 latency.toMillis(),
                 response.content().length(),
                 response.toolCalls().size(),
                 response.finishReason().name(),
                 response.usage().inputTokens(),
                 response.usage().outputTokens(),
                 response.usage().totalTokens());
    }

    @Override
    public void onException(String tenantId, String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        log.warn("[MODEL_CALL_ERROR] tenant={} run={} step={} modelId={} " +
                 "latencyMs={} kind={} retryable={}",
                 tenantId, runId, step,
                 request.modelId(),
                 latency.toMillis(),
                 exception.kind().name(),
                 exception.kind().retryable());
    }
}
