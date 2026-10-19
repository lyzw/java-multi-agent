package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

import cn.studykid.multiagent.application.backpressure.BackpressureController;
import cn.studykid.multiagent.application.config.BackpressurePolicy;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.infrastructure.messaging.RabbitExecutionListener;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.RabbitQueueDepthProbe;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.worker.dispatch.OutboxPublisher;
import cn.studykid.multiagent.worker.dispatch.OutboxRelay;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;
import cn.studykid.multiagent.worker.dispatch.TenantOutboxPublishers;
import cn.studykid.multiagent.worker.dispatch.TenantSlugResolver;

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
     * 队列深度探测（P4-D05 / L-6 背压护栏的输入）。
     *
     * <p>⚠️ 探测失败由 {@link BackpressureController} 按「超限」处理：
     * 探测不到深度时放行 = 背压在最需要它的时候失效。
     */
    @Bean
    public QueueDepthProbe queueDepthProbe(RabbitTemplate template) {
        return new RabbitQueueDepthProbe(template);
    }

    /**
     * 背压控制器（P4-D05 / L-6）。
     *
     * <p>⚠️ 三项水位配置<b>缺失即拒绝启动</b>（fail-closed）：
     * 水位线的「过高」与「过低」两个方向都危险，不存在保守兜底值。
     */
    @Bean
    public BackpressureController backpressureController(
            QueueDepthProbe probe,
            @Value("${agent.mq.queues.execute.name}") String queueName,
            @Value("${" + BackpressurePolicy.KEY_HIGH_WATERMARK + "}")
                    String highWatermark,
            @Value("${" + BackpressurePolicy.KEY_LOW_WATERMARK + "}")
                    String lowWatermark,
            @Value("${" + BackpressurePolicy.KEY_COOLDOWN_MULTIPLIER + "}")
                    String cooldownMultiplier) {
        int high = BackpressurePolicy.requireHighWatermark(highWatermark);
        return new BackpressureController(probe, queueName, high,
                BackpressurePolicy.requireLowWatermark(lowWatermark, high),
                BackpressurePolicy.requireCooldownMultiplier(cooldownMultiplier));
    }

    /**
     * 租户发布器清单（P4-D05 / 关闭 L-4）。
     *
     * <p><b>⚠️ 每个租户一个独立的 {@link OutboxPublisher}</b>，
     * 各自绑定自己的 {@code tenantId}，而不是「一个发布器扫全表」——
     * 后者需要跨租户读权限，配置错误即<b>跨租户发布</b>。
     * 显式清单的最坏结果是<b>漏发或拒启</b>，
     * 失败形态从「越权」降级为「不可用」。
     *
     * <p>⚠️ <b>清单长度受试点上限约束</b>：
     * {@code deployment.pilot.max-tenants} 是灰度承诺的一部分，
     * 允许配置绕过等于灰度控制失效。
     */
    @Bean
    public TenantOutboxPublishers tenantOutboxPublishers(
            RunCreationPort runCreationPort,
            MessageSender messageSender,
            DataSource dataSource,
            @Value("${" + WorkerPolicy.KEY_TENANT_SLUGS + "}") String tenantSlugs,
            @Value("${" + PlatformConfig.KEY_PILOT_MAX_TENANTS + "}") String pilotMaxTenants,
            @Value("${agent.worker.outbox-batch}") String outboxBatch,
            @Value("${agent.worker.max-publish-attempts}") String maxAttempts) {
        int maxTenants = PlatformConfig.Resolver.requirePositiveInt(
                pilotMaxTenants, PlatformConfig.KEY_PILOT_MAX_TENANTS);
        List<String> slugs = WorkerPolicy.requireTenantSlugs(tenantSlugs, maxTenants);
        int batch = WorkerPolicy.requireOutboxBatch(outboxBatch);
        int attempts = WorkerPolicy.requireMaxPublishAttempts(maxAttempts);

        List<OutboxPublisher> publishers = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            // ⚠️ slug → UUID 在启动期解析，解析失败即拒绝启动（fail-closed）
            String tenantId = TenantSlugResolver.resolve(dataSource, slug);
            publishers.add(new OutboxPublisher(runCreationPort, messageSender, tenantId,
                    Clock.systemUTC(), batch, attempts));
        }
        log.info("租户发布器已装配: slugs={}, 租户数={}", slugs, publishers.size());
        return new TenantOutboxPublishers(publishers);
    }

    /**
     * Outbox 中继（P4-D03 / D-1，P4-D05 接入多租户与背压）。
     */
    @Bean
    public OutboxRelay outboxRelay(TenantOutboxPublishers publishers,
                                   BackpressureController backpressure,
                                   @Value("${agent.worker.relay-interval-ms}")
                                           String relayIntervalMs) {
        return new OutboxRelay(publishers.publishers(), backpressure,
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