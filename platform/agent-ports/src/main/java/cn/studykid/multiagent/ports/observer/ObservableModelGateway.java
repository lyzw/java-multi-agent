package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 可观测 ModelGateway 包装器（装饰器模式）。
 *
 * <p><b>用途</b>：在不修改现有 ModelGateway 实现的前提下，
 * 通过装饰器注入观测者链。装配时使用 {@code @Primary} 覆盖默认装配，
 * 将所有观测者串联到调用链中。
 *
 * <p><b>关键纪律</b>：
 * <ul>
 *   <li>观测者异常不得影响主流程——异常被捕获后只记 WARN，不冒泡；</li>
 *   <li>tenantId / runId / step 由调用方传入（ModelGateway 本身无状态，
 *       无法自行获取这些上下文）；</li>
 *   <li>本包装器仅负责调用观测者，不持有任何业务状态。</li>
 * </ul>
 *
 * <p><b>使用方式</b>：
 * <pre>
 * // 在 RunExecutionLoop 中
 * ModelResponse response = observableGateway.complete(
 *         tenantId, task.runId(), currentStep, request);
 * </pre>
 *
 * @see ModelCallObserver
 */
public class ObservableModelGateway implements ModelGateway {

    private final ModelGateway delegate;
    private final ModelCallObserver observerChain;

    public ObservableModelGateway(@NonNull ModelGateway delegate,
                                   @NonNull ModelCallObserver observerChain) {
        this.delegate = delegate;
        this.observerChain = observerChain;
    }

    @Override
    public @NonNull ModelResponse complete(@NonNull ModelRequest request) {
        // ⚠️ 无上下文的调用：不触发观测者，直接透传
        // （RunExecutionLoop 和 WorkerChildAgentRunner 应使用带上下文的 overload）
        return delegate.complete(request);
    }

    /**
     * 带执行上下文的完整调用（触发观测者链）。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @param step     当前轮次（从 1 开始）
     * @param request  模型请求
     * @return 模型响应
     */
    public @NonNull ModelResponse complete(@NonNull String tenantId,
                                            @NonNull String runId,
                                            int step,
                                            @NonNull ModelRequest request) {
        Instant start = Instant.now();

        // beforeCall
        try {
            observerChain.beforeCall(tenantId, runId, step, request);
        } catch (RuntimeException e) {
            // ⚠️ 观测者异常不得影响主流程
            logWarn("ModelCallObserver.beforeCall", tenantId, runId, e);
        }

        try {
            ModelResponse response = delegate.complete(request);
            Duration latency = Duration.between(start, Instant.now());

            // afterCall
            try {
                observerChain.afterCall(tenantId, runId, step, request, response, latency);
            } catch (RuntimeException e) {
                logWarn("ModelCallObserver.afterCall", tenantId, runId, e);
            }

            return response;

        } catch (ModelGatewayException e) {
            Duration latency = Duration.between(start, Instant.now());

            // onException
            try {
                observerChain.onException(tenantId, runId, step, request, e, latency);
            } catch (RuntimeException e2) {
                logWarn("ModelCallObserver.onException", tenantId, runId, e2);
            }

            throw e;
        }
    }

    private void logWarn(String method, String tenantId, String runId, RuntimeException e) {
        // ⚠️ 使用 System.err 而非 SLF4J——避免引入日志依赖
        // 生产中由装配层注入 Logger
        System.err.println("[OBSERVER-WARN] " + method + " tenant=" + tenantId
                + " run=" + runId + " cause=" + e.getClass().getSimpleName()
                + ": " + safeMessage(e.getMessage()));
    }

    private String safeMessage(String msg) {
        if (msg == null) return "";
        // 脱敏：截断过长消息，避免日志中泄露凭据
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}
