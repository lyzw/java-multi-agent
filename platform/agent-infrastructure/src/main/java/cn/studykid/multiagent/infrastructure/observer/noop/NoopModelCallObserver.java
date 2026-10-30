package cn.studykid.multiagent.infrastructure.observer.noop;

import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * Noop 模型调用观测者（空实现，装配兜底）。
 *
 * <p>当 Spring 容器中没有其他 ModelCallObserver 实现时，
 * 本 Bean 作为兜底，避免观测者链为空。
 *
 * <p><b>Order = Integer.MAX_VALUE</b>：最后执行，不影响其他观测者。
 */
@Order(Integer.MAX_VALUE)
public class NoopModelCallObserver implements ModelCallObserver {

    @Override
    public void beforeCall(@NonNull String tenantId, @NonNull String runId, int step,
                           @NonNull ModelRequest request) {
        // 空实现
    }

    @Override
    public void afterCall(@NonNull String tenantId, @NonNull String runId, int step,
                          @NonNull ModelRequest request, @NonNull ModelResponse response,
                          @NonNull Duration latency) {
        // 空实现
    }

    @Override
    public void onException(@NonNull String tenantId, @NonNull String runId, int step,
                            @NonNull ModelRequest request,
                            @NonNull ModelGatewayException exception,
                            @NonNull Duration latency) {
        // 空实现
    }
}
