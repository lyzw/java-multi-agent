package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.infrastructure.messaging.RabbitExecutionListener;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.worker.dispatch.OutboxPublisher;
import cn.studykid.multiagent.worker.dispatch.OutboxRelay;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;

import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 主循环装配（P4-D03）。
 *
 * <p><b>⚠️ 本类关闭的是「断链的第二层」</b>：D01 修复了
 * 「{@code RunDispatchConsumer} 领到租约后不执行」，但核实发现
 * <b>没有任何东西调用这个 consumer</b>——
 * {@code @RabbitListener} 与 {@code RabbitTemplate} 在全仓为零，
 * {@code OutboxPublisher.publishOnce()} 只被单测调用。
 * 结果是 Run 创建后消息永远躺在 {@code outbox_event} 表里，
 * 而系统表面完全正常（API 返回 201、无报错、无告警）。
 *
 * <p>本类把三段接起来：
 * <pre>
 *   outbox_event 表 --OutboxRelay--> RabbitMQ --RabbitExecutionListener--> RunDispatchConsumer
 * </pre>
 *
 * <p><b>开关</b>：{@code agent.worker.enabled}（默认 true）。
 * 本地/测试环境可关掉以免无 RabbitMQ 时启动失败，
 * 但<b>生产必须为 true</b>——关掉即回到「消息永不消费」的状态。
 */
@Configuration
@ConditionalOnProperty(name = "agent.worker.enabled", havingValue = "true",
        matchIfMissing = true)
public class WorkerLoopConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoopConfig.class);

    /**
     * 队列与交换机（ADR-001 §4）。
     *
     * <p>⚠️ <b>{@code x-queue-type} 与 {@code x-delivery-limit} 必须显式声明</b>：
     * RabbitMQ 4.x 未配置 vhost 默认队列类型时默认仍是 classic，
     * 而 classic 在 4.x 没有复制能力（mirrored queue 已在 4.0 移除）。
     * quorum queue 的默认 delivery-limit 是 <b>20</b>——
     * 对 {@code record_weakness} 这类有副作用工具，
     * 重投 20 次即重复写入最多 20 次（<b>Sev-0</b>），故必须降到 3。
     */
    @Bean
    public TopicExchange runDispatchExchange() {
        return new TopicExchange("agent.run.dispatch", true, false);
    }

    @Bean
    public TopicExchange runDeadLetterExchange() {
        return new TopicExchange("agent.run.dlx", true, false);
    }

    @Bean
    public Queue runExecuteQueue(
            @Value("${agent.mq.queues.execute.name}") String name,
            @Value("${agent.mq.queues.execute.delivery-limit}") int deliveryLimit) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(deliveryLimit)
                .deadLetterExchange("agent.run.dlx")
                .deadLetterRoutingKey("agent.run.dead")
                .build();
    }

    @Bean
    public Queue runDeadQueue(
            @Value("${agent.mq.queues.dead.name}") String name) {
        // ⚠️ 死信队列自身**不挂 DLX**，避免死信循环
        return QueueBuilder.durable(name).quorum().build();
    }

    /**
     * 消息转换器（Jackson 3）。
     *
     * <p>⚠️ 使用项目统一的 {@link ObjectMapper}，不新建——
     * 序列化规则（脱敏、时间格式）必须全局一致。
     */
    @Bean
    public JacksonJsonMessageConverter amqpMessageConverter(ObjectMapper mapper) {
        // ⚠️ Jackson 3：转换器要 JsonMapper（ObjectMapper 的父类），
        //    直接传 ObjectMapper 编译不过。
        return new JacksonJsonMessageConverter(
                (tools.jackson.databind.json.JsonMapper) mapper);
    }

    /**
     * 消费容器工厂（P4-D03 / D-2）。
     *
     * <p>⚠️ <b>MANUAL ack 是硬要求</b>：auto-ack 下 nack 无效，
     * {@code x-delivery-limit} → 死信的控制会完全失效。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${agent.mq.queues.execute.name}") String queueName,
            @Value("${agent.worker.concurrency}") String concurrency,
            @Value("${agent.worker.prefetch}") String prefetch,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
                    boolean autoStartup) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // ⚠️⚠️ 本类自建工厂 → Boot 的 SimpleRabbitListenerContainerFactoryConfigurer
        //    **不会**自动应用，故 autoStartup 等属性必须自己读。
        //    漏掉这一行 = 「关掉 worker」只是假象：容器仍会连 broker，
        //    无 broker 的环境（CI）直接启动失败（本次实测踩到）。
        factory.setAutoStartup(autoStartup);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // ⚠️ I-4：并发上限来自配置，缺失/非法已在 WorkerPolicy 拒绝启动
        factory.setConcurrentConsumers(WorkerPolicy.requireConcurrency(concurrency));
        factory.setPrefetchCount(WorkerPolicy.requirePrefetch(prefetch));
        // ⚠️ 重投时未 ack 的消息会被 broker 重投给其他消费者；
        //   单条消息不应阻塞整个批次
        factory.setDefaultRequeueRejected(false);
        factory.setMissingQueuesFatal(true);
        return factory;
    }

    @Bean
    public RabbitTemplate runRabbitTemplate(ConnectionFactory connectionFactory,
                                           JacksonJsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // ⚠️ publisher confirms：quorum queue 只有在消息被多数派复制后
        //   才返回 ack。不开这个，RabbitMessageSender 的「等待确认」会立即返回。
        template.setMandatory(true);
        // ⚠️ Jackson 3 / Spring AMQP 4：确认类型设在 CachingConnectionFactory 上，
        //    RabbitTemplate 无此 setter。
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * 消息发送（P4-D03 / D-2 publisher confirms）。
     */
    @Bean
    public MessageSender messageSender(RabbitTemplate template) {
        return new RabbitMessageSender(template);
    }

    /**
     * Outbox 发布器（P2-D02 逻辑 + P4-D03 装配）。
     *
     * <p>⚠️ <b>单租户设计</b>（P2-D02 §3.2）：一个实例只处理一个租户。
     * 全租户轮询需要能读所有租户的权限，一旦连接配置错误就会
     * <b>跨租户发布消息</b>——隔离在最弱的地方被打破。
     */
    @Bean
    public OutboxPublisher outboxPublisher(RunCreationPort runCreationPort,
                                          MessageSender messageSender,
                                          DataSource dataSource,
                                          @Value("${agent.worker.tenant-slug}")
                                                  String tenantSlug,
                                          @Value("${agent.worker.outbox-batch}")
                                                  String outboxBatch,
                                          @Value("${agent.worker.max-publish-attempts}")
                                                  String maxAttempts) {
        // ⚠️ 配置只放人类可读的 slug，启动时解析成 UUID——
        //    配置里出现裸 UUID 时没人能看出它指哪个租户（见 TenantSlugResolver）。
        return new OutboxPublisher(runCreationPort, messageSender,
                cn.studykid.multiagent.worker.dispatch.TenantSlugResolver
                        .resolve(dataSource, tenantSlug),
                Clock.systemUTC(),
                WorkerPolicy.requireOutboxBatch(outboxBatch),
                WorkerPolicy.requireMaxPublishAttempts(maxAttempts));
    }

    /**
     * Outbox 中继（P4-D03 / D-1）。
     */
    @Bean
    public OutboxRelay outboxRelay(OutboxPublisher publisher,
                                   @Value("${agent.worker.relay-interval-ms}")
                                           String relayIntervalMs) {
        return new OutboxRelay(publisher,
                Duration.ofMillis(WorkerPolicy.requireRelayIntervalMs(relayIntervalMs)));
    }

    /**
     * MQ 消费容器（P4-D03）。
     */
    @Bean
    public RabbitExecutionListener rabbitExecutionListener(
            RunDispatchConsumer consumer, ObjectMapper mapper) {
        return new RabbitExecutionListener(consumer, mapper);
    }

    /**
     * 优雅停机编排（P4-D03 / D-4）。
     *
     * <p>⚠️ <b>顺序很重要</b>：先停中继（不再领取新任务），
     * 再等在途完成。顺序反了会在停机窗口内不断产生新工作。
     *
     * <p>⚠️ <b>不强制中断在途执行</b>：Run 的租约会自然过期被其他实例接管，
     * 强制中断只会浪费一个租约周期（fencing 保证安全，但代价白付）。
     */
    @Bean
    public WorkerShutdownCoordinator workerShutdownCoordinator(OutboxRelay relay) {
        return new WorkerShutdownCoordinator(relay);
    }

    /**
     * 优雅停机协调器。
     *
     * <p>作为 Bean 存在即让 Spring 在上下文关闭时回调其 {@code close()}。
     */
    public static class WorkerShutdownCoordinator implements AutoCloseable {

        private final OutboxRelay relay;

        public WorkerShutdownCoordinator(OutboxRelay relay) {
            this.relay = relay;
        }

        @Override
        public void close() {
            log.info("Worker 停机：停止 Outbox 中继（在途执行不强杀，"
                    + "租约自然过期由其他实例接管）");
            relay.stop();
        }
    }
}