package cn.studykid.multiagent.infrastructure.observer.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 模型调用观测者（指标采集）。
 *
 * <p>采集指标：
 * <ul>
 *   <li>{@code ai.model.calls.total} — 调用总次数（Counter）</li>
 *   <li>{@code ai.model.calls.latency} — 调用延迟（Timer，P50/P95/P99）</li>
 *   <li>{@code ai.model.tokens.input} — 输入 token 数（DistributionSummary）</li>
 *   <li>{@code ai.model.tokens.output} — 输出 token 数（DistributionSummary）</li>
 * </ul>
 *
 * <p>标签：tenant, modelId, provider, finishReason, errorKind（异常时）
 *
 * <p><b>Order = 20</b>：在日志之后执行。
 */
@Order(20)
public class MicrometerModelCallObserver implements ModelCallObserver {

    private final MeterRegistry registry;

    // 指标名称常量
    private static final String CALLS_TOTAL = "ai.model.calls.total";
    private static final String CALLS_LATENCY = "ai.model.calls.latency";
    private static final String TOKENS_INPUT = "ai.model.tokens.input";
    private static final String TOKENS_OUTPUT = "ai.model.tokens.output";

    // 标签名常量
    private static final String TAG_TENANT = "tenant";
    private static final String TAG_MODEL = "model";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_FINISH = "finish";
    private static final String TAG_ERROR = "error";

    public MicrometerModelCallObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeCall(String tenantId, String runId, int step,
                           @NonNull ModelRequest request) {
        // beforeCall 不采集指标（仅 afterCall / onException 采集）
    }

    @Override
    public void afterCall(String tenantId, String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        String provider = extractProvider(request.modelId());
        String model = extractModel(request.modelId());

        // 调用总次数
        Counter.builder(CALLS_TOTAL)
                .description("Total model calls")
                .tags(TAG_TENANT, tenantId,
                      TAG_MODEL, model,
                      TAG_PROVIDER, provider,
                      TAG_FINISH, response.finishReason().name())
                .register(registry)
                .increment();

        // 调用延迟
        Timer.builder(CALLS_LATENCY)
                .description("Model call latency")
                .tags(TAG_TENANT, tenantId,
                      TAG_MODEL, model,
                      TAG_PROVIDER, provider)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency.toMillis(), TimeUnit.MILLISECONDS);

        // Token 数
        DistributionSummary.builder(TOKENS_INPUT)
                .description("Input tokens per call")
                .tags(TAG_TENANT, tenantId, TAG_MODEL, model)
                .register(registry)
                .record(response.usage().inputTokens());

        DistributionSummary.builder(TOKENS_OUTPUT)
                .description("Output tokens per call")
                .tags(TAG_TENANT, tenantId, TAG_MODEL, model)
                .register(registry)
                .record(response.usage().outputTokens());
    }

    @Override
    public void onException(String tenantId, String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        String provider = extractProvider(request.modelId());
        String model = extractModel(request.modelId());

        // 调用总次数（含错误）
        Counter.builder(CALLS_TOTAL)
                .description("Total model calls")
                .tags(TAG_TENANT, tenantId,
                      TAG_MODEL, model,
                      TAG_PROVIDER, provider,
                      TAG_ERROR, exception.kind().name())
                .register(registry)
                .increment();

        // 延迟（含失败）
        Timer.builder(CALLS_LATENCY)
                .description("Model call latency")
                .tags(TAG_TENANT, tenantId,
                      TAG_MODEL, model,
                      TAG_PROVIDER, provider,
                      TAG_ERROR, exception.kind().name())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 从 modelId 中提取 provider（如 "minimax:MiniMax-M2.5" → "minimax"）。
     */
    private @NonNull String extractProvider(@NonNull String modelId) {
        int idx = modelId.indexOf(':');
        return idx >= 0 ? modelId.substring(0, idx) : "unknown";
    }

    /**
     * 从 modelId 中提取 model 名称（如 "minimax:MiniMax-M2.5" → "MiniMax-M2.5"）。
     */
    private @NonNull String extractModel(@NonNull String modelId) {
        int idx = modelId.indexOf(':');
        return idx >= 0 ? modelId.substring(idx + 1) : modelId;
    }
}
