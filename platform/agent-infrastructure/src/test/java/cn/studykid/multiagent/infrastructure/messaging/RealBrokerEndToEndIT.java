package cn.studykid.multiagent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 真实 broker 端到端验证（P4-D06 / 关闭 D03 的 L-1）。
 *
 * <p><b>⚠️ 本片要证明的只有一件事</b>：消息在<b>真实 RabbitMQ</b> 上
 * 「发布确认 → 入队 → 被消费」全链路打通，且深度探测反映真实队列。
 * 这正是 D03 在诚实边界里留下、并写明「需在可用 broker 上完成」的 L-1。
 *
 * <p><b>为何不复用整条 Spring 上下文</b>：生产链路要接 PG（TenantSlugResolver）
 * 与一整套 Bean，会把「MQ 端到端」与「PG/租户装配」耦合——
 * 一处 PG 配置问题就会让本片无法证明 MQ 本身可用。
 * 故本片<b>只装配 MQ 组件</b>（连接的正是生产所用的 `RabbitMessageSender`
 * 与 `RabbitQueueDepthProbe` 实现），直连本机开发 broker。
 *
 * <p><b>⚠️ 连接参数来自环境变量，缺省值匹配本机 dev broker</b>
 * （见 `scripts/dev-rabbitmq.sh` 与 `config.env`）。
 * 没有 broker 的环境跑此 IT 会失败——这是 IT 的正确行为，
 * 因为它本就要在真实基建上验证（与 14 个需要 `IT_PG_URL` 的 IT 同类）。
 *
 * <p><b>⚠️ 不在测试结束时删除 `agent.run.execute` 队列</b>：那是生产拓扑。
 * 本片只发一条消息并把它收走（receive 即出队），不留残留。
 */
@DisplayName("P4-D06 真实 broker 端到端验证（关闭 L-1）")
class RealBrokerEndToEndIT {

    private static final String EXCHANGE = "agent.run.dispatch";
    private static final String QUEUE = "agent.run.execute";
    private static final String ROUTING_KEY = "agent.run.execute";
    private static final String DLX = "agent.run.dlx";

    private CachingConnectionFactory cf;
    private RabbitTemplate template;
    private AmqpAdmin admin;
    private RabbitMessageSender sender;
    private QueueDepthProbe probe;
    private MessageConverter converter;

    /** 按环境变量（缺省值匹配 dev broker）装配连接与组件。 */
    private void connect() {
        String host = env("RABBITMQ_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("RABBITMQ_PORT", "5672"));
        String user = env("RABBITMQ_USER", "agent");
        String pass = env("RABBITMQ_PASSWORD", "");
        String vhost = env("RABBITMQ_VHOST", "agent");

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(pass);
        cf.setVirtualHost(vhost);
        // ⚠️ publisher confirms 是 RabbitMessageSender 契约的硬要求（见其类注释）：
        //    不发确认就标记 published 会在 broker 宕机时永久丢消息。
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);

        template = new RabbitTemplate(cf);
        // ⚠️ Jackson 3（tools.jackson）：与 WorkerLoopConfig 同款转换器。
        converter = new JacksonJsonMessageConverter(
                new tools.jackson.databind.json.JsonMapper());
        template.setMessageConverter(converter);
        template.setMandatory(true);

        admin = new RabbitAdmin(cf);
        // ⚠️ 幂等声明生产拓扑（与 WorkerLoopConfig 同参数；已存在则无操作）。
        //    若真实队列类型与本声明不一致，此处会抛 PRECONDITION_FAILED——
        //    那正好暴露「真实拓扑与代码声明不符」的隐患。
        admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
        admin.declareExchange(new TopicExchange(DLX, true, false));
        Queue q = QueueBuilder.durable(QUEUE)
                .quorum()
                .deliveryLimit(3)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey("agent.run.dead")
                .build();
        admin.declareQueue(q);
        admin.declareBinding(new Binding(QUEUE, Binding.DestinationType.QUEUE,
                EXCHANGE, ROUTING_KEY, Map.of()));

        sender = new RabbitMessageSender(template);
        probe = new RabbitQueueDepthProbe(template);

        // ⚠️ **开测前先清场**：队列跨轮次累积（每轮全量构建都会发消息），
        //    残留的历史消息会被本片的「接收」先取到，
        //    断言「回环载荷的 runId 必须与发出的一致」就会失败 ——
        //    而该失败与本片逻辑毫无关系，纯属上一轮的残留。
        //    只在收尾时清理是不够的：残留已经污染了本次的接收。
        drainQueue();
    }

    /**
     * 排空执行队列。
     *
     * <p>⚠️ 上限 1000 条是防跑飞的安全阀：队列若被别的生产者持续灌入，
     * 收空会变成无底洞，把测试挂死。
     */
    private void drainQueue() {
        try {
            for (int i = 0; i < 1000; i++) {
                if (template.receive(QUEUE, 200) == null) {
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // 清场失败不应影响测试结果（后续断言会暴露真实问题）
        }
    }

    private void close() {
        if (template != null) {
            // ⚠️ 尽力收走本片可能遗留的测试消息，避免污染真实队列
            //    （dev broker 无应用运行，仅本片消息会残留）。
            drainQueue();
        }
        if (cf != null) {
            cf.destroy();
        }
    }

    private static @NonNull String env(@NonNull String key, @NonNull String def) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) {
            return v;
        }
        // ⚠️ 也读 config.env 常见的 RABBITMQ_* 之外，支持 SPRING 风格缺省
        return def;
    }

    // ── E-1：队列拓扑与生产一致（quorum + 持久）────────────────────

    @Nested
    @DisplayName("E-1 真实队列拓扑（关闭 L-1 的前提）")
    class Topology {

        @Test
        @DisplayName("⚠️⚠️ 真实 agent.run.execute 队列存在且可访问（拓扑由应用 Queue Bean + C-5 保证）")
        void queueExistsAndReachable() {
            connect();
            try {
                // ⚠️ 被动声明成功即可证明队列存在且可访问。
                //    AMQP 0-9-1 的 queue.declare-ok 不回传 arguments，
                //    故 x-queue-type / x-delivery-limit 等拓扑属性由
                //    应用 Queue Bean（quorum + delivery-limit=3）+ 连线测试 C-5 保证；
                //    本片聚焦「真实发送/确认/消费」闭环，不重复验证拓扑。
                Integer depth = template.execute(
                        channel -> channel.queueDeclarePassive(QUEUE).getMessageCount());
                assertThat(depth)
                        .as("⚠️ 被动声明必须成功（队列不存在会抛 404）")
                        .isNotNull();
            } finally {
                close();
            }
        }
    }

    // ── E-2：发布确认 + 投递 + 消费回环 ───────────────────────────

    @Nested
    @DisplayName("E-2 发布确认→投递→消费回环")
    class RoundTrip {

        @Test
        @DisplayName("⚠️⚠️ 经真实 broker 发一条 RUN_DISPATCH，能被消费且载荷完整")
        void messageSentAndReceivedWithIntegrity() {
            connect();
            try {
                String runId = UUID.randomUUID().toString();
                String tenantId = UUID.randomUUID().toString();
                OutboxMessage msg = new OutboxMessage(
                        runId, tenantId, 0, 1L, Instant.now());

                int before = probe.depthOf(QUEUE);

                // ⚠️ 用生产实现发送（publisher confirms 是硬要求）
                sender.send("RUN_DISPATCH", msg);

                int after = probe.depthOf(QUEUE);
                assertThat(after)
                        .as("⚠️⚠️ 深度探测必须反映真实队列：发一条后深度 +1"
                                + "（证明 RabbitQueueDepthProbe 不是假阳性 stub）")
                        .isEqualTo(before + 1);

                // ⚠️ 真实消费：阻塞收取，验证「真的到达 broker 并被取出」
                org.springframework.amqp.core.Message received =
                        template.receive(QUEUE, 5000);
                assertThat(received)
                        .as("⚠️⚠️ 消息必须真的到达 broker 并被取出"
                                + "（L-1 要证明的就是这一环）")
                        .isNotNull();
                // ⚠️ 不在此断言 getDeliveryMode() == PERSISTENT：quorum 队列在
                //    basic.get 回包里会<b>剥掉</b> delivery_mode（quorum 本就总落盘，
                //    该字段对它无意义），收回的消息此值恒为 null。
                //    持久化的正确性由两处分别保证：
                //    ① 生产者「进入 MQ 前设 PERSISTENT」→ RabbitMessageSenderTest.P-1；
                //    ② 队列「总持久化」→ 生产 quorum 拓扑（WorkerLoopConfig + 连线测试 C-5）。
                //    二者缺一都会丢消息，故必须分片覆盖，不能在一条链路里同时证明。
                assertThat((String) received.getMessageProperties()
                        .getHeader("x-agent-event-type"))
                        .as("⚠️ 事件类型头必须带（不含任何业务数据）")
                        .isEqualTo("RUN_DISPATCH");

                @SuppressWarnings("unchecked")
                Map<String, Object> payload =
                        (Map<String, Object>) converter.fromMessage(received);
                assertThat(payload)
                        .as("⚠️ 出口载荷只含 5 个坐标字段，不得含学生数据")
                        .containsKeys("runId", "tenantId", "attempt",
                                "fencingToken", "enqueuedAt");
                String actualRunId = String.valueOf(payload.get("runId"));
                String actualTenant = String.valueOf(payload.get("tenantId"));
                assertThat(actualRunId)
                        .as("⚠️ 回环载荷的 runId 必须与发出的一致")
                        .isEqualTo(runId);
                assertThat(actualTenant)
                        .as("⚠️ 回环载荷的 tenantId 必须与发出的一致")
                        .isEqualTo(tenantId);

                int drained = probe.depthOf(QUEUE);
                assertThat(drained)
                        .as("⚠️ 收走后深度应回落到 before")
                        .isEqualTo(before);
            } finally {
                close();
            }
        }
    }

    // ── E-3：深度探测失败 = 超限（fail-closed 契约，真 broker 验证）──

    @Nested
    @DisplayName("E-3 深度探测 fail-closed 契约")
    class ProbeFailClosed {

        @Test
        @DisplayName("⚠️⚠️ 探测不存在的队列必须抛异常（不得返回 0 伪装为空）")
        void missingQueueThrows() {
            connect();
            try {
                String fake = "agent.run.definitely-not-here-" + UUID.randomUUID();
                assertThatThrownBy(() -> probe.depthOf(fake))
                        .as("⚠️⚠️ 探测失败若返回 0，背压会在最需要它时失效"
                                + "（与 D8 限流故障反成 DoS 同源）")
                        .isInstanceOf(RuntimeException.class);
            } finally {
                close();
            }
        }
    }
}
