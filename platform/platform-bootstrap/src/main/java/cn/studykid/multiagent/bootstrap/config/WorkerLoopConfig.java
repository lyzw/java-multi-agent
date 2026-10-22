package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
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
import cn.studykid.multiagent.application.config.MessagingPolicy;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.infrastructure.messaging.CompositeQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueListener;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueRepository;
import cn.studykid.multiagent.infrastructure.messaging.RabbitExecutionListener;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.RabbitQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessagingMode;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.worker.dispatch.OutboxPublisher;
import cn.studykid.multiagent.worker.dispatch.OutboxRelay;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;
import cn.studykid.multiagent.worker.dispatch.TenantOutboxPublishers;

import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 主循环装配（P4-D03；P4-D17 起支持消息通道可切换）。
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
 *   outbox_event 表 --OutboxRelay--> 消息通道 --消费端--> RunDispatchConsumer
 * </pre>
 *
 * <p><b>⚠️ P4-D17：消息通道不再是「写死 RabbitMQ」</b>。
 * 现在有两条等价通道，靠 {@code agent.messaging.mode} 切换：
 * <ul>
 *   <li>{@code rabbitmq} —— 既有通道（quorum 队列 + publisher confirms + DLX）；</li>
 *   <li>{@code database} —— 以 PostgreSQL 表为队列（无外部 broker 依赖）。</li>
 * </ul>
 * 两条通道<b>共用同一组端口</b>（{@link MessageSender} /
 * {@link QueueDepthProbe} / {@code MessageHandler}），
 * 故切换**不触碰业务语义**；发送端按 {@link TenantRoutingMessageSender} 路由，
 * 消费端各管各的租户。
 *
 * <p><b>⚠️ 开关</b>：{@code agent.worker.enabled}（默认 true）。
 * 本地/测试环境可关掉以免无 broker 时启动失败，
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
     *
     * <p>⚠️ DB 通道下这些声明<b>仍会被创建</b>（Bean 创建不连 broker），
     * 但不会有任何消费者连接它们——Rabbit 队列深度为 0，不影响背压。
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
     *
     * <p>⚠️ <b>DB 通道下必须不启动</b>：否则容器会连 broker，
     * 无 broker 的环境（CI）启动失败——本项目已踩过同形态。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${agent.mq.queues.execute.name}") String queueName,
            @Value("${agent.worker.concurrency}") String concurrency,
            @Value("${agent.worker.prefetch}") String prefetch,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
                    boolean autoStartup,
            MessagingChannels channels) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // ⚠️⚠️ 本类自建工厂 → Boot 的 SimpleRabbitListenerContainerFactoryConfigurer
        //    **不会**自动应用，故 autoStartup 等属性必须自己读。
        //    漏掉这一行 = 「关掉 worker」只是假象：容器仍会连 broker，
        //    无 broker 的环境（CI）直接启动失败（本次实测踩到）。
        //
        // ⚠️ P4-D17：**再与「是否有租户走 RabbitMQ 通道」求与**。
        //    纯 DB 部署（CI、试运营灰度）里 Rabbit 通道无人使用，
        //    容器若启动就会去连一个根本不存在的 broker。
        factory.setAutoStartup(autoStartup && channels.rabbitActive());
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
     * 消息通道分配（P4-D17 · 全局模式 + 租户覆盖）。
     *
     * <p>⚠️ 本 Bean 在**启动期**解析 slug → tenantId 并分配模式，
     * 解析失败即拒绝启动（fail-closed）：
     * 模式写错而静默回落 = 「以为在灰度、实际没灰度」。
     */
    @Bean
    public MessagingChannels messagingChannels(
            DataSource dataSource,
            @Value("${" + MessagingPolicy.KEY_MODE + "}") String mode,
            @Value("${" + MessagingPolicy.KEY_TENANT_MODES + ":}") String tenantModes,
            @Value("${" + WorkerPolicy.KEY_TENANT_SLUGS + "}") String tenantSlugs,
            @Value("${" + PlatformConfig.KEY_PILOT_MAX_TENANTS + "}") String pilotMaxTenants) {
        int maxTenants = PlatformConfig.Resolver.requirePositiveInt(
                pilotMaxTenants, PlatformConfig.KEY_PILOT_MAX_TENANTS);
        List<String> slugs = WorkerPolicy.requireTenantSlugs(tenantSlugs, maxTenants);
        return MessagingChannels.resolve(dataSource, mode, tenantModes, slugs);
    }

    /**
     * PostgreSQL 队列仓储（P4-D17）。
     *
     * <p>⚠️ 无条件创建：它是 DB 通道的发送/探测/消费三方的共同底座。
     * 纯 Rabbit 部署下它只是没人用（不会自己连库、不开线程）。
     */
    @Bean
    public PgQueueRepository pgQueueRepository(DataSource dataSource, ObjectMapper mapper) {
        return new PgQueueRepository(dataSource, mapper);
    }

    /**
     * 消息发送（P4-D17 · 按租户路由）。
     *
     * <p>⚠️ <b>路由而非二选一</b>：全局模式是默认，按租户覆盖可做「小客群灰度」——
     * 试运营阶段让几个租户先走 DB 通道，其余仍走 RabbitMQ。
     */
    @Bean
    public MessageSender messageSender(MessagingChannels channels,
                                      RabbitTemplate template,
                                      PgQueueRepository pgRepository) {
        MessageSender rabbit = new RabbitMessageSender(template);
        MessageSender database = new PgQueueMessageSender(pgRepository);

        Map<String, MessageSender> overrides = new LinkedHashMap<>();
        for (String tenantId : channels.tenantIds()) {
            MessagingMode mode = channels.modeOf(tenantId);
            if (mode != channels.globalMode()) {
                overrides.put(tenantId,
                        mode == MessagingMode.RABBITMQ ? rabbit : database);
            }
        }
        MessageSender defaultSender =
                channels.globalMode() == MessagingMode.RABBITMQ ? rabbit : database;

        log.info("消息通道发送端已装配: 全局={}, 灰度覆盖租户数={}, Rabbit 活动={}, DB 活动={}",
                channels.globalMode().key(), overrides.size(),
                channels.rabbitActive(), channels.databaseActive());
        return new TenantRoutingMessageSender(defaultSender, overrides);
    }

    /**
     * 队列深度探测（P4-D05 / L-6 背压护栏的输入 · P4-D17 组合）。
     *
     * <p>⚠️ 探测失败由 {@link BackpressureController} 按「超限」处理：
     * 探测不到深度时放行 = 背压在最需要它的时候失效。
     *
     * <p>⚠️ <b>只纳入活动通道</b>：纯 DB 部署若把 Rabbit 探测纳入，
     * 会因 broker 不存在而恒抛异常 → 背压恒超限 → 消息永不发布。
     */
    @Bean
    public QueueDepthProbe queueDepthProbe(MessagingChannels channels,
                                          RabbitTemplate template,
                                          PgQueueRepository pgRepository) {
        List<QueueDepthProbe> probes = new ArrayList<>();
        if (channels.rabbitActive()) {
            probes.add(new RabbitQueueDepthProbe(template));
        }
        if (channels.databaseActive()) {
            probes.add(new PgQueueDepthProbe(pgRepository, channels.databaseTenantIds()));
        }
        log.info("消息通道深度探测已装配: 活动通道数={}", probes.size());
        return new CompositeQueueDepthProbe(probes);
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
            MessagingChannels channels,
            @Value("${agent.worker.outbox-batch}") String outboxBatch,
            @Value("${agent.worker.max-publish-attempts}") String maxAttempts) {
        int batch = WorkerPolicy.requireOutboxBatch(outboxBatch);
        int attempts = WorkerPolicy.requireMaxPublishAttempts(maxAttempts);

        List<OutboxPublisher> publishers = new ArrayList<>(channels.tenantIds().size());
        for (String tenantId : channels.tenantIds()) {
            publishers.add(new OutboxPublisher(runCreationPort, messageSender, tenantId,
                    Clock.systemUTC(), batch, attempts));
        }
        log.info("租户发布器已装配: 租户数={}, 通道={}",
                publishers.size(), channels.globalMode().key());
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
     *
     * <p>⚠️ DB 通道下容器<b>不会启动</b>（auto-startup 与「Rabbit 是否活动」求与），
     * 但 Bean 仍存在——它是 {@code @RabbitListener} 端点的宿主。
     */
    @Bean
    public RabbitExecutionListener rabbitExecutionListener(
            RunDispatchConsumer consumer, ObjectMapper mapper) {
        return new RabbitExecutionListener(consumer, mapper);
    }

    /**
     * PostgreSQL 队列消费端（P4-D17）。
     *
     * <p>⚠️ <b>仅在存在 DB 模式租户时启动</b>：纯 Rabbit 部署下没有 DB 队列，
     * 起一个空转的轮询线程只会白白打库。
     */
    @Bean
    public PgQueueListener pgQueueListener(MessagingChannels channels,
                                           PgQueueRepository pgRepository,
                                           RunDispatchConsumer consumer,
                                           @Value("${" + MessagingPolicy.KEY_POLL_INTERVAL_MS + "}")
                                                   String pollIntervalMs,
                                           @Value("${" + MessagingPolicy.KEY_BATCH_SIZE + "}")
                                                   String batchSize,
                                           @Value("${" + MessagingPolicy.KEY_MAX_ATTEMPTS + "}")
                                                   String maxAttempts,
                                           @Value("${" + MessagingPolicy.KEY_VISIBILITY_TIMEOUT_MS + "}")
                                                   String visibilityTimeoutMs,
                                           @Value("${" + MessagingPolicy.KEY_RETRY_BACKOFF_MS + "}")
                                                   String retryBackoffMs,
                                           @Value("${" + MessagingPolicy.KEY_RETRY_BACKOFF_MAX_MS + "}")
                                                   String retryBackoffMaxMs) {
        List<String> databaseTenants = channels.databaseTenantIds();
        PgQueueListener listener = new PgQueueListener(
                databaseTenants, pgRepository, consumer,
                Duration.ofMillis(MessagingPolicy.requireMillis(
                        pollIntervalMs, MessagingPolicy.KEY_POLL_INTERVAL_MS, 10)),
                MessagingPolicy.requireBoundedInt(
                        batchSize, MessagingPolicy.KEY_BATCH_SIZE, 1000),
                MessagingPolicy.requireBoundedInt(
                        maxAttempts, MessagingPolicy.KEY_MAX_ATTEMPTS, 100),
                Duration.ofMillis(MessagingPolicy.requireMillis(
                        visibilityTimeoutMs, MessagingPolicy.KEY_VISIBILITY_TIMEOUT_MS, 1000)),
                Duration.ofMillis(MessagingPolicy.requireMillis(
                        retryBackoffMs, MessagingPolicy.KEY_RETRY_BACKOFF_MS, 10)),
                Duration.ofMillis(MessagingPolicy.requireMillis(
                        retryBackoffMaxMs, MessagingPolicy.KEY_RETRY_BACKOFF_MAX_MS, 10)));
        if (channels.databaseActive()) {
            listener.start();
        } else {
            log.info("PG 队列监听器未启动（当前无 database 模式租户）");
        }
        return listener;
    }

    /**
     * 消息通道切换残留检查（P4-D17）。
     *
     * <p>⚠️ 本检查在**启动期**执行，失败即拒绝启动：
     * 通道切换后旧通道里的残留消息不会被新通道接管，
     * 若放行则这些 Run 永远不执行、且系统表面完全正常。
     */
    @Bean
    public MessagingSwitchGuard messagingSwitchGuard(
            MessagingChannels channels,
            PgQueueRepository pgRepository,
            org.springframework.beans.factory.ObjectProvider<AmqpAdmin> rabbitAdmin,
            @Value("${spring.rabbitmq.host:}") String rabbitHost,
            @Value("${spring.rabbitmq.port:5672}") int rabbitPort,
            @Value("${agent.mq.queues.execute.name}") String queueName) {
        MessagingSwitchGuard.verify(channels, pgRepository,
                rabbitAdmin.getIfAvailable(), rabbitHost, rabbitPort, queueName);
        return new MessagingSwitchGuard();
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
    public WorkerShutdownCoordinator workerShutdownCoordinator(OutboxRelay relay,
                                                               PgQueueListener pgListener) {
        return new WorkerShutdownCoordinator(relay, pgListener);
    }

    /**
     * 优雅停机协调器。
     *
     * <p>作为 Bean 存在即让 Spring 在上下文关闭时回调其 {@code close()}。
     */
    public static class WorkerShutdownCoordinator implements AutoCloseable {

        private final OutboxRelay relay;

        private final PgQueueListener pgListener;

        public WorkerShutdownCoordinator(OutboxRelay relay, PgQueueListener pgListener) {
            this.relay = relay;
            this.pgListener = pgListener;
        }

        @Override
        public void close() {
            log.info("Worker 停机：停止 Outbox 中继（在途执行不强杀，"
                    + "租约自然过期由其他实例接管）");
            relay.stop();
            // ⚠️ DB 通道的消费端也要停：否则停机窗口内仍在领取新任务
            pgListener.stop();
        }
    }
}
