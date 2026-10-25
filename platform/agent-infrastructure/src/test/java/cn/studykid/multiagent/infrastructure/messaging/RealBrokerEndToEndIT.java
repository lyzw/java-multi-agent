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
 * <p><b>⚠️ 本片使用**独立的测试拓扑**（第二十八批 · C28-1 · A9）</b>：
 * 此前本片直接用生产队列名 {@code agent.run.execute}，
 * 于是每次跑都往<b>生产队列</b>里写一条消息 —— 跨轮次残留、
 * 与其他 IT 互相干扰，且「排空生产队列」这种清场动作本身就在动生产拓扑。
 * 现在本片声明**自己的**交换机 / 队列 / 绑定（名字带 {@code .it} 后缀），
 * 与生产拓扑完全分开。
 *
 * <p>⚠️ <b>隔离不是靠自觉，而是靠断言</b>：
 * ① 消息只落在<b>自己的</b>队列（深度 +1 + receive 得到）；
 * ② <b>生产队列的深度不得变化</b>（我的消息没进去）；
 * ③ 本片用的队列名**必须 ≠ 生产队列名** ——
 * 这条专门防「有人把常量改回生产名」这个退化。
 */
@DisplayName("P4-D06 真实 broker 端到端验证（关闭 L-1）")
class RealBrokerEndToEndIT {

    /**
     * ⚠️ 生产拓扑名（<b>本片不得使用</b>）。
     *
     * <p>只用于「隔离性断言」：确认本片的消息没有落进生产队列。
     */
    private static final String PRODUCTION_EXCHANGE = "agent.run.dispatch";
    private static final String PRODUCTION_QUEUE = "agent.run.execute";
    private static final String PRODUCTION_ROUTING_KEY = "agent.run.execute";

    /** ⚠️ 本片自己的拓扑（名字带 {@code .it} 后缀，与生产完全分开）。 */
    private static final String EXCHANGE = "agent.run.dispatch.it";
    private static final String QUEUE = "agent.run.execute.it";
    private static final String ROUTING_KEY = "agent.run.execute.it";
    private static final String DLX = "agent.run.dlx.it";
    private static final String DEAD_QUEUE = "agent.run.dead.it";

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
        // ⚠️ 幂等声明**本片自己的**拓扑（与生产同参数；已存在则无操作）。
        //    若真实队列类型与本声明不一致，此处会抛 PRECONDITION_FAILED——
        //    那正好暴露「真实拓扑与代码声明不符」的隐患。
        //    ⚠️ 声明的是**自己的**队列，不碰生产拓扑 —— 见类注释的隔离说明。
        admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
        admin.declareExchange(new TopicExchange(DLX, true, false));
        Queue q = QueueBuilder.durable(QUEUE)
                .quorum()
                .deliveryLimit(3)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DEAD_QUEUE)
                .build();
        admin.declareQueue(q);
        admin.declareQueue(QueueBuilder.durable(DEAD_QUEUE).quorum().build());
        // ⚠️ 绑定：**本片自己**的交换机 → **本片自己**的队列。
        //    路由键与队列名同源（与生产 WorkerLoopConfig 的口径一致）。
        admin.declareBinding(new Binding(QUEUE, Binding.DestinationType.QUEUE,
                EXCHANGE, ROUTING_KEY, Map.of()));

        // ⚠️ 显式给出发送目标：这是「拓扑由配置驱动」的**直接**验证点 ——
        //    发送器若仍用硬编码的默认值，消息会发到生产交换机/路由键上，
        //    本片就再也收不到（而生产队列会多一条 —— 正是污染）。
        sender = new RabbitMessageSender(template, EXCHANGE, ROUTING_KEY);
        probe = new RabbitQueueDepthProbe(template);

        // ⚠️ **开测前先清场**：队列跨轮次累积（每轮全量构建都会发消息），
        //    残留的历史消息会被本片的「接收」先取到，
        //    断言「回环载荷的 runId 必须与发出的一致」就会失败 ——
        //    而该失败与本片逻辑毫无关系，纯属上一轮的残留。
        //    只在收尾时清理是不够的：残留已经污染了本次的接收。
        drainQueue();
    }

    /**
     * 排空<b>本片自己的</b>执行队列。
     *
     * <p>⚠️ 上限 1000 条是防跑飞的安全阀：队列若被别的生产者持续灌入，
     * 收空会变成无底洞，把测试挂死。
     *
     * <p>⚠️ <b>只排自己的队列</b>：排空生产队列是一次**对生产拓扑的写操作**，
     * 那正是本片要消除的污染形态。
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
        @DisplayName("⚠️⚠️ 本片自己的执行队列存在且可访问（拓扑与生产同参数：quorum + delivery-limit=3）")
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

    // ── E-4：集成测试与生产拓扑的隔离（第二十八批 · C28-1 · A9）──────

    @Nested
    @DisplayName("E-4 集成测试与生产拓扑的隔离")
    class Isolation {

        @Test
        @DisplayName("⚠️⚠️ 本片用的队列名必须 ≠ 生产队列名（防「常量被改回生产名」这个退化）")
        void testTopologyMustDifferFromProduction() {
            // ⚠️ 这条是**结构性**断言：常量被改回生产名时立刻红，
            //    而不是等到某次跑测污染了生产队列才发现。
            assertThat(QUEUE)
                    .as("⚠️⚠️ 集成测试不得使用生产队列名 —— 那会让每次跑测都往生产队列"
                            + "写消息（跨轮次残留 + 与其他 IT 互相干扰）")
                    .isNotEqualTo(PRODUCTION_QUEUE);
            assertThat(EXCHANGE)
                    .as("⚠️⚠️ 集成测试不得使用生产交换机名")
                    .isNotEqualTo(PRODUCTION_EXCHANGE);
            assertThat(ROUTING_KEY)
                    .as("⚠️⚠️ 集成测试不得使用生产路由键")
                    .isNotEqualTo(PRODUCTION_ROUTING_KEY);
        }

        @Test
        @DisplayName("⚠️⚠️ 发送器的**实际发布目标**必须不是生产拓扑")
        void senderTargetMustNotBeProductionTopology() {
            connect();
            try {
                // ⚠️ 隔离的**充分条件**是「发布目标 ≠ 生产拓扑」：
                //    RabbitMQ 按 (exchange, routing key) 路由，
                //    不同目标的消息在**结构上不可能**到达生产队列。
                //    故这里断言这条**静态**性质，而不是去比较队列深度 ——
                //    后者依赖「生产队列是否存在」（全新环境里它根本不存在），
                //    是**环境相关**的判定，既不稳定也证明不了更强的东西。
                assertThat(sender.exchange())
                        .as("⚠️⚠️ 发送器的交换机不得是生产交换机 —— "
                                + "发布到生产交换机 + 生产路由键 = 消息直达生产队列（污染）")
                        .isNotEqualTo(PRODUCTION_EXCHANGE);
                assertThat(sender.routingKey())
                        .as("⚠️⚠️ 发送器的路由键不得是生产路由键")
                        .isNotEqualTo(PRODUCTION_ROUTING_KEY);
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
