package cn.studykid.multiagent.bootstrap.config;

import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserverChain;
import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.observer.TraceObserver;
import cn.studykid.multiagent.ports.observer.TraceObserverChain;
import cn.studykid.multiagent.ports.persistence.RunEventPort;
import cn.studykid.multiagent.infrastructure.observer.bridge.EventPortExecutionObserver;
import cn.studykid.multiagent.infrastructure.observer.bridge.EventPortModelCallObserver;
import cn.studykid.multiagent.infrastructure.observer.bridge.EventPortTraceObserver;
import cn.studykid.multiagent.infrastructure.observer.logging.Slf4jExecutionObserver;
import cn.studykid.multiagent.infrastructure.observer.logging.Slf4jModelCallObserver;
import cn.studykid.multiagent.infrastructure.observer.logging.Slf4jTraceObserver;
import cn.studykid.multiagent.infrastructure.observer.metrics.MicrometerExecutionObserver;
import cn.studykid.multiagent.infrastructure.observer.metrics.MicrometerModelCallObserver;
import cn.studykid.multiagent.infrastructure.observer.metrics.MicrometerTraceObserver;
import cn.studykid.multiagent.infrastructure.observer.noop.NoopExecutionObserver;
import cn.studykid.multiagent.infrastructure.observer.noop.NoopModelCallObserver;
import cn.studykid.multiagent.infrastructure.observer.noop.NoopTraceObserver;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 观测者装配配置（Observer 层）。
 *
 * <p><b>装配策略</b>：
 * <ul>
 *   <li>每个观测者接口有 3 种实现：Slf4j（日志）/ Micrometer（指标）/ EventPort（事件桥接）；</li>
 *   <li>每种接口有 Noop 兜底（@Primary），确保观测者链永不为空；</li>
 *   <li>观测者按 {@code @Order} 排序：10（日志）→ 20（指标）→ 30（事件桥接）；</li>
 *   <li>观测者异常不得影响主流程——链内 try/catch 捕获后只记 WARN。</li>
 * </ul>
 *
 * <p><b>启用/禁用</b>：通过 {@code @ConditionalOnProperty} 控制各观测者的启用。
 * 默认全部启用。禁用示例：{@code ai.observer.model-call.slf4j.enabled=false}
 */
@Configuration
public class ObserverConfiguration {

    // ═══════════════════════════════════════════════════════════════════
    //  ModelCallObserver 实现
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Slf4j 模型调用观测者（结构化日志）。
     * 输出到 logger: ai.model.calls
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(10)
    public Slf4jModelCallObserver slf4jModelCallObserver() {
        return new Slf4jModelCallObserver();
    }

    /**
     * Micrometer 模型调用观测者（指标采集）。
     * 需要 MeterRegistry Bean 才生效。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(20)
    public MicrometerModelCallObserver micrometerModelCallObserver(
            MeterRegistry registry) {
        return new MicrometerModelCallObserver(registry);
    }

    /**
     * EventPort 模型调用观测者（事件桥接）。
     * eventPort 为 null 时静默跳过。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(30)
    public EventPortModelCallObserver eventPortModelCallObserver(
            @Autowired(required = false) RunEventPort eventPort) {
        return new EventPortModelCallObserver(eventPort);
    }

    /**
     * 模型调用观测者链（按 @Order 排序）。
     */
    @Bean
    public ModelCallObserverChain modelCallObserverChain(
            @NonNull List<ModelCallObserver> observers) {
        observers.sort(Comparator.comparingInt(o -> {
            Order order = o.getClass().getAnnotation(Order.class);
            return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
        }));
        return new ModelCallObserverChain(observers);
    }

    /**
     * 可观测 ModelGateway 包装器（@Primary 覆盖默认装配）。
     * 将 rawModelGateway 包装为 ObservableModelGateway，注入观测者链。
     */
    @Bean
    @Primary
    public ObservableModelGateway observableModelGateway(
            ModelGateway rawModelGateway,
            ModelCallObserverChain observerChain) {
        return new ObservableModelGateway(rawModelGateway, observerChain);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ExecutionObserver 实现
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Slf4j 执行循环观测者（结构化日志）。
     * 输出到 logger: ai.execution.loop
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(10)
    public Slf4jExecutionObserver slf4jExecutionObserver() {
        return new Slf4jExecutionObserver();
    }

    /**
     * Micrometer 执行循环观测者（指标采集）。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(20)
    public MicrometerExecutionObserver micrometerExecutionObserver(
            MeterRegistry registry) {
        return new MicrometerExecutionObserver(registry);
    }

    /**
     * EventPort 执行循环观测者（事件桥接）。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(30)
    public EventPortExecutionObserver eventPortExecutionObserver(
            @Autowired(required = false) RunEventPort eventPort) {
        return new EventPortExecutionObserver(eventPort);
    }

    /**
     * 执行循环观测者链（按 @Order 排序）。
     */
    @Bean
    public ExecutionObserverChain executionObserverChain(
            @NonNull List<ExecutionObserver> observers) {
        observers.sort(Comparator.comparingInt(o -> {
            Order order = o.getClass().getAnnotation(Order.class);
            return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
        }));
        return new ExecutionObserverChain(observers);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TraceObserver 实现
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Slf4j 端到端追踪观测者（MDC 注入 Trace ID）。
     * 输出到 logger: ai.trace
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(10)
    public Slf4jTraceObserver slf4jTraceObserver() {
        return new Slf4jTraceObserver();
    }

    /**
     * Micrometer 端到端追踪观测者（指标采集）。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(20)
    public MicrometerTraceObserver micrometerTraceObserver(
            MeterRegistry registry) {
        return new MicrometerTraceObserver(registry);
    }

    /**
     * EventPort 端到端追踪观测者（事件桥接）。
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(30)
    public EventPortTraceObserver eventPortTraceObserver(
            @Autowired(required = false) RunEventPort eventPort) {
        return new EventPortTraceObserver(eventPort);
    }

    /**
     * 端到端追踪观测者链（按 @Order 排序）。
     */
    @Bean
    public TraceObserverChain traceObserverChain(
            @NonNull List<TraceObserver> observers) {
        observers.sort(Comparator.comparingInt(o -> {
            Order order = o.getClass().getAnnotation(Order.class);
            return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
        }));
        return new TraceObserverChain(observers);
    }
}
