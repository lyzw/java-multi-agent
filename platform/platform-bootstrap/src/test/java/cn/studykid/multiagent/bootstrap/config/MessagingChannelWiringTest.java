package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.infrastructure.messaging.CompositeQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueListener;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessagingMode;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * 消息通道装配连通性测试（P4-D17 · database 模式）。
 *
 * <p><b>⚠️ 本类存在的理由</b>：通道切换是<b>装配层</b>的行为——
 * 代码写了 {@code PgQueueMessageSender} 不代表它真的被接到了发布器上。
 * 本项目的 D01 断链正是如此：「全部写好了，但没有任何东西调用它」，
 * 且<b>系统表面完全正常</b>。故必须有连���性测试。
 *
 * <p><b>⚠️ 必须跑在有库的环境</b>：上下文启动期即查询 tenant 表解析 slug
 * （fail-closed），无库即启动失败——这是既有约定，不是本类引入的。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // ⚠️ P4-D18：关闭 Outbox 中继。
        //    本类的断言目标与中继无关，而中继一旦启动会把**其它用例**
        //    遗留的 PENDING outbox 事件投进 agent_message_queue，
        //    随后 MessagingSwitchGuard 因「通道切换残留」fail-closed，
        //    使后续 rabbitmq 模式用例无法启动上下文——
        //    症状指向通道配置，真因是跨用例的数据库共享。
        "agent.worker.relay-enabled=false",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 刻意**不覆盖** spring.rabbitmq.listener.simple.auto-startup：
        //    yml 缺省为 true，只有「通道模式把它关掉」才能证明
        //    「DB 模式下 Rabbit 容器确实不会启动」。
        //    若覆盖成 false，本用例就永远为真——变成「看着在查其实没查」。
        //
        // ⚠️ 本类断言的是 **database 通道**的装配
        "agent.messaging.mode=database"
})
@DisplayName("P4-D17 消息通道装配连通性测试（database 模式）")
class MessagingChannelWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Nested
    @DisplayName("W-1 database 模式下的 Bean 图")
    class DatabaseModeWiring {

        @Test
        @DisplayName("⚠️ W-1 发送端是路由发送器，且默认通道为 PG 队列")
        void senderIsRoutedToDatabase() {
            MessageSender sender = ctx.getBean(MessageSender.class);

            assertThat(sender)
                    .as("⚠️ 发送端必须是路由发送器（否则按租户灰度无从谈起）")
                    .isInstanceOf(TenantRoutingMessageSender.class);

            TenantRoutingMessageSender routing = (TenantRoutingMessageSender) sender;
            assertThat(routing.defaultSender())
                    .as("⚠️ database 模式下默认通道必须是 PG 队列发送器")
                    .isInstanceOf(PgQueueMessageSender.class);
            assertThat(routing.defaultSender())
                    .isNotInstanceOf(RabbitMessageSender.class);
        }

        @Test
        @DisplayName("⚠️ W-2 背压探测只纳入活动通道（DB 模式不得探测 Rabbit）")
        void depthProbeOnlyCoversActiveChannel() {
            QueueDepthProbe probe = ctx.getBean(QueueDepthProbe.class);

            assertThat(probe).isInstanceOf(CompositeQueueDepthProbe.class);
            CompositeQueueDepthProbe composite = (CompositeQueueDepthProbe) probe;
            assertThat(composite.activeProbeCount())
                    .as("⚠️ 纯 DB 部署只应有 1 个活动通道的探测")
                    .isEqualTo(1);
            assertThat(composite.probes().get(0))
                    .as("⚠️ 唯一活动通道必须是 PG 队列探测")
                    .isInstanceOf(PgQueueDepthProbe.class);
        }

        @Test
        @DisplayName("⚠️ W-3 PG 队列监听器存在且绑定了租户")
        void pgListenerWired() {
            PgQueueListener listener = ctx.getBean(PgQueueListener.class);

            assertThat(listener).isNotNull();
            assertThat(listener.tenantCount())
                    .as("⚠️ DB 模式至少要有一个租户被监听，否则消息永远滞留")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ W-4 Rabbit 消费容器不得启动（无 broker 环境会启动失败）")
        void rabbitContainerNotStarted() {
            SimpleRabbitListenerContainerFactory factory = ctx.getBean(
                    SimpleRabbitListenerContainerFactory.class);

            // ⚠️ auto-startup 无公开读回方法，用反射取字段（同 WorkerLoopWiringTest 的做法）。
            //    反射取不到会让本断言转红（而非静默通过）——
            //    字段改名时测试会提醒更新，而不是悄悄失效。
            Object autoStartup = readFactoryField(factory, "autoStartup");
            assertThat(autoStartup)
                    .as("⚠️⚠️ DB 模式下 Rabbit 容器必须不自动启动："
                            + "否则会连一个根本不存在的 broker，无 broker 的环境（CI）启动失败。"
                            + "若因字段改名取不到，本用例会转红——请更新反射字段名，不要删掉断言")
                    .isNotNull();
            assertThat(Boolean.valueOf(String.valueOf(autoStartup)))
                    .as("⚠️ DB 模式下 autoStartup 必须为 false")
                    .isFalse();
        }

        @Test
        @DisplayName("⚠️ W-5 通道分配与切换守卫均已装配")
        void channelsAndGuardWired() {
            MessagingChannels channels = ctx.getBean(MessagingChannels.class);

            assertThat(channels.globalMode()).isEqualTo(MessagingMode.DATABASE);
            assertThat(channels.databaseActive()).isTrue();
            assertThat(channels.rabbitActive()).isFalse();
            assertThat(ctx.getBean(MessagingSwitchGuard.class))
                    .as("⚠️ 切换守卫必须在（否则切换后残留消息静默滞留）")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("W-6 覆盖表解析（fail-closed）")
    class OverrideParsing {

        @Test
        @DisplayName("⛔ 模式值非法 → 拒绝启动（灰度静默失效比报错危险）")
        void illegalModeRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            MessagingChannels.parseOverrides("t-a=kafka"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 格式非法（缺 = / 值）→ 拒绝启动")
        void illegalFormatRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            MessagingChannels.parseOverrides("t-a"))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            MessagingChannels.parseOverrides("t-a="))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 同一租户重复覆盖 → 拒绝（配置有歧义必须显式）")
        void duplicateRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            MessagingChannels.parseOverrides("t-a=database,t-a=rabbitmq"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ 空覆盖表 = 全部跟随全局模式")
        void emptyOverridesMeansGlobal() {
            assertThat(MessagingChannels.parseOverrides("")).isEmpty();
            assertThat(MessagingChannels.parseOverrides(null)).isEmpty();
            assertThat(MessagingChannels.parseOverrides("  ")).isEmpty();
        }

        @Test
        @DisplayName("✅ 合法覆盖被正确解析")
        void validOverridesParsed() {
            assertThat(MessagingChannels.parseOverrides("t-a=database,t-b=rabbitmq"))
                    .containsEntry("t-a", MessagingMode.DATABASE)
                    .containsEntry("t-b", MessagingMode.RABBITMQ);
        }
    }

    @Nested
    @DisplayName("W-7 组合探测的活动通道")
    class CompositeProbe {

        @Test
        @DisplayName("⛔ 空组合被拒（深度恒 0 = 背压永久失效）")
        void emptyRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            new CompositeQueueDepthProbe(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 反射读取工厂私有字段（沿用 WorkerLoopWiringTest 的同一手法）。 */
    private static Object readFactoryField(Object factory, String name) {
        Class<?> c = factory.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(factory);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读取 " + name + " 失败", e);
            }
        }
        return null;
    }
}
