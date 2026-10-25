package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;

/**
 * MQ 拓扑<b>配置化</b>的装配断言（第二十八批 · C28-1 · A9）。
 *
 * <p><b>⚠️ 本类要钉死的是三件此前全是「假的」或「缺���」的事</b>：
 *
 * <table border="1">
 *   <caption>改动前的三个缺口</caption>
 *   <tr><th>缺口</th><th>改动前的症状</th></tr>
 *   <tr>
 *     <td>配置项形同虚设</td>
 *     <td>{@code agent.mq.exchange} / {@code agent.mq.dead-letter-exchange} /
 *         {@code agent.mq.queues.execute.dead-letter-*} 在 application.yml 里
 *         <b>定义了却没有任何读取方</b>；而 {@code RabbitMessageSender} 的
 *         交换机与路由键是<b>硬编码</b>的。于是「队列名可配」是<b>假的</b> ——
 *         改了队列名，队列建在新名字上，发送器仍往老路由键发消息，
 *         <b>没有任何队列订阅那个路由键</b> → 消息全部丢失，
 *         症状是「API 返回 201、无报错、无告警、Run 永远 QUEUED」。</td>
 *   </tr>
 *   <tr>
 *     <td><b>生产拓扑缺绑定</b></td>
 *     <td>生产代码只声明了交换机与队列，<b>从未声明两者之间的绑定</b>。
 *         没有绑定就没有路由 —— 消息<b>无处可去</b>，消费者永远收不到。
 *         之所以一直没被发现：集成测试<b>自己调了
 *         {@code admin.declareBinding(...)}</b>，
 *         测试路径上拓扑是完整的 —— <b>测试替生产兜底</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>集成测试污染生产队列</td>
 *     <td>集成测试直接用生产队列名，每次跑都往生产队列写消息。</td>
 *   </tr>
 * </table>
 *
 * <p><b>⚠️ 本类用「覆盖配置 + 断言 Bean 图」的方式验证</b>：
 * 只测「配置项有读取方」是不够的 —— 读取方可能读了却不用。
 * 故这里把 {@code agent.mq.*} 覆盖成<b>一眼可辨的测试值</b>，
 * 再断言 Bean 图里各处用的都是那个值。
 *
 * <p>⚠️ 本类**覆盖配置值**本身也顺带避免了「测试上下文声明生产队列」这种污染。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 关掉容器启动（避免连 broker），但**保留全部 Bean 定义**——
        //    本类要断言的正是「Bean 图里各处用的是不是配置值」。
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ⚠️ 上下文启动时 RabbitAdmin 会尝试声明拓扑（不需要连 broker 也建 Bean，
        //    但声明动作会发连接）—— 给上凭据免得日志里一片认证失败噪音。
        "spring.rabbitmq.username=${RABBITMQ_USER:agent}",
        "spring.rabbitmq.password=${RABBITMQ_PASSWORD:}",
        "spring.rabbitmq.virtual-host=${RABBITMQ_VHOST:agent}",
        // ⚠️ 本类断言的是 **RabbitMQ 通道**的装配，故显式声明走 Rabbit 通道。
        "agent.messaging.mode=rabbitmq",
        // ⚠️⚠️ **覆盖 MQ 拓扑名**：值取得一眼可辨，
        //    任何「又用回硬编码默认值」的退化都会让下面的断言立刻红。
        "agent.mq.exchange=wiring-test.dispatch",
        "agent.mq.dead-letter-exchange=wiring-test.dlx",
        "agent.mq.queues.execute.name=wiring-test.execute",
        "agent.mq.queues.execute.dead-letter-exchange=wiring-test.dlx",
        "agent.mq.queues.execute.dead-letter-routing-key=wiring-test.dead",
        "agent.mq.queues.dead.name=wiring-test.dead"
})
@DisplayName("C28-1 MQ 拓扑配置化的装配断言")
class MqTopologyWiringTest {

    /** 覆盖后的期望值（与上面的 @TestPropertySource 一一对应）。 */
    private static final String EXPECTED_EXCHANGE = "wiring-test.dispatch";
    private static final String EXPECTED_DLX = "wiring-test.dlx";
    private static final String EXPECTED_QUEUE = "wiring-test.execute";
    private static final String EXPECTED_DEAD_ROUTING_KEY = "wiring-test.dead";
    private static final String EXPECTED_DEAD_QUEUE = "wiring-test.dead";

    @Autowired
    private ApplicationContext ctx;

    @Nested
    @DisplayName("拓扑各部件必须用配置值（不得回落硬编码默认值）")
    class TopologyFollowsConfig {

        @Test
        @DisplayName("⚠️⚠️ 发布交换机必须用 agent.mq.exchange（此前是硬编码的）")
        void exchangeFollowsConfig() {
            TopicExchange exchange = ctx.getBean("runDispatchExchange", TopicExchange.class);
            assertThat(exchange.getName())
                    .as("⚠️⚠️ 交换机名必须来自配置 —— 此前硬编码，"
                            + "application.yml 里的 agent.mq.exchange 是**摆设**")
                    .isEqualTo(EXPECTED_EXCHANGE);
        }

        @Test
        @DisplayName("⚠️⚠️ 死信交换机必须用 agent.mq.dead-letter-exchange（此前是硬编码的）")
        void deadLetterExchangeFollowsConfig() {
            TopicExchange dlx = ctx.getBean("runDeadLetterExchange", TopicExchange.class);
            assertThat(dlx.getName())
                    .as("⚠️⚠️ 死信交换机名必须来自配置 —— 此前硬编码")
                    .isEqualTo(EXPECTED_DLX);
        }

        @Test
        @DisplayName("⚠️⚠️ 执行队列必须用 agent.mq.queues.execute.name")
        void executeQueueFollowsConfig() {
            Queue q = ctx.getBean("runExecuteQueue", Queue.class);
            assertThat(q.getName())
                    .as("⚠️⚠️ 队列名必须来自配置")
                    .isEqualTo(EXPECTED_QUEUE);
            assertThat(q.getArguments())
                    .as("⚠️ 死信交换机必须来自配置（此前硬编码）")
                    .containsEntry("x-dead-letter-exchange", EXPECTED_DLX);
            assertThat(q.getArguments())
                    .as("⚠️ 死信路由键必须来自配置（此前硬编码）")
                    .containsEntry("x-dead-letter-routing-key", EXPECTED_DEAD_ROUTING_KEY);
        }

        @Test
        @DisplayName("⚠️⚠️ 死信队列必须用 agent.mq.queues.dead.name")
        void deadQueueFollowsConfig() {
            Queue dead = ctx.getBean("runDeadQueue", Queue.class);
            assertThat(dead.getName())
                    .as("⚠️ 死信队列名必须来自配置")
                    .isEqualTo(EXPECTED_DEAD_QUEUE);
        }
    }

    @Nested
    @DisplayName("⚠️⚠️ 生产拓扑必须包含「交换机 → 队列」的绑定（此前根本不存在）")
    class BindingExists {

        @Test
        @DisplayName("⚠️⚠️ 绑定 Bean 必须存在，且把执行队列绑到发布交换机")
        void bindingBeanExists() {
            Binding binding = ctx.getBean("runExecuteBinding", Binding.class);
            assertThat(binding)
                    .as("⚠️⚠️ **没有绑定就没有路由** —— 消息按路由键发布，"
                            + "而没有任何队列订阅它 → 消息无处可去，"
                            + "消费者永远收不到，Run 永远 QUEUED，"
                            + "而 API 照常返回 201、无报错、无告警。"
                            + "此前生产代码从未声明绑定，只有集成测试自己补了，"
                            + "于是「测试替生产兜底」")
                    .isNotNull();
            assertThat(binding.getExchange())
                    .as("⚠️ 绑定必须指向发布交换机")
                    .isEqualTo(EXPECTED_EXCHANGE);
            assertThat(binding.getDestination())
                    .as("⚠️ 绑定必须指向执行队列")
                    .isEqualTo(EXPECTED_QUEUE);
        }

        @Test
        @DisplayName("⚠️⚠️ 绑定的路由键必须与发送器的路由键一致（失配即消息无处可去）")
        void bindingRoutingKeyMatchesSender() {
            Binding binding = ctx.getBean("runExecuteBinding", Binding.class);
            RabbitMessageSender sender = ctx.getBean(RabbitMessageSender.class);

            assertThat(binding.getRoutingKey())
                    .as("⚠️⚠️ 绑定的路由键与发送器的路由键必须一致 —— "
                            + "不一致时消息会被 broker 判为 unroutable 丢弃，"
                            + "症状是「发出去没人收」且不报错")
                    .isEqualTo(sender.routingKey());
            assertThat(binding.getRoutingKey())
                    .as("⚠️ 路由键与队列名同源（一个配置项驱动三处引用）")
                    .isEqualTo(EXPECTED_QUEUE);
        }
    }

    @Nested
    @DisplayName("发送器必须用配置值发布（此前是硬编码的）")
    class SenderFollowsConfig {

        @Test
        @DisplayName("⚠️⚠️ 发送器持有的交换机与路由键必须来自配置")
        void senderUsesConfiguredTopology() {
            RabbitMessageSender sender = ctx.getBean(RabbitMessageSender.class);

            assertThat(sender.exchange())
                    .as("⚠️⚠️ 发送器的交换机必须来自配置 —— 此前硬编码，"
                            + "于是「队列名可配」是假的：改了队列名，"
                            + "发送器仍往老路由键发，没有任何队列订阅它 → 消息全丢")
                    .isEqualTo(EXPECTED_EXCHANGE);
            assertThat(sender.routingKey())
                    .as("⚠️⚠️ 发送器的路由键必须来自配置")
                    .isEqualTo(EXPECTED_QUEUE);
        }
    }
}
