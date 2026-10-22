package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.infrastructure.messaging.CompositeQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueDepthProbe;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * 小客群灰度装配测试（P4-D17 · 按租户切换通道）。
 *
 * <p><b>⚠️ 本类证明的是「同一实例上两条通道并存且各管各的租户」</b>——
 * 这正是试运营小客群阶段需要的形态：
 * 先让一个租户走数据库队列，其余仍走 RabbitMQ，稳了再全量。
 *
 * <p><b>⚠️ 若路由错把 A 租户的消息发到 Rabbit 通道</b>：
 * DB 通道的消费者不会认领（它只认自己的租户），
 * 消息滞留且<b>无任何报错</b>——与本项目记录的「断链」形态同族。
 * 故此处直接断言「每个租户实际用哪个发送器」。
 *
 * <p><b>⚠️ 背压必须同时看到两条通道</b>：
 * 只探测其中一条，另一条会无限堆积而无人看见（L-6 的护栏失效）。
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
        // ⚠️ 关掉 Rabbit 容器启动：本类断言的是**装配**，不是连通性
        //    （真实连通由 RealBrokerEndToEndIT 在有 broker 的环境验证）。
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ⚠️ 全局仍是 RabbitMQ（存量租户不受影响）
        "agent.messaging.mode=rabbitmq",
        // ⚠️ 灰度：t-a 单独切到数据库队列
        "agent.messaging.tenant-modes=t-a=database",
        // ⚠️ 试点上限提到 2：默认 1（灰度起步）会拒绝两个租户
        "agent.worker.tenant-slugs=t-a,t-b",
        "deployment.pilot.max-tenants=2"
})
@DisplayName("P4-D17 小客群灰度装配测试（t-a 走 DB / t-b 走 RabbitMQ）")
class MessagingCanaryWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("⚠️⚠️ 灰度租户走数据库通道，其余租户仍走 RabbitMQ")
    void canaryTenantUsesDatabaseChannel() {
        TenantRoutingMessageSender routing = (TenantRoutingMessageSender) ctx.getBean(MessageSender.class);
        MessagingChannels channels = ctx.getBean(MessagingChannels.class);

        // ⚠️ 按 **slug** 定位租户，不依赖列表下标：
        //    租户顺序是实现细节（且历史上踩过 Map 无序的坑），
        //    用下标断言会让「顺序变了」伪装成「路由错了」。
        String tenantA = tenantIdOf(channels, "t-a");
        String tenantB = tenantIdOf(channels, "t-b");

        assertThat(channels.modeOf(tenantA).key()).isEqualTo("database");
        assertThat(channels.modeOf(tenantB).key()).isEqualTo("rabbitmq");

        assertThat(routing.senderFor(tenantA))
                .as("⚠️⚠️ 灰度租户必须走数据库队列发送器")
                .isInstanceOf(PgQueueMessageSender.class);
        assertThat(routing.senderFor(tenantB))
                .as("⚠️⚠️ 其余租户必须仍走 RabbitMQ 发送器")
                .isInstanceOf(RabbitMessageSender.class);
        assertThat(routing.overriddenTenantCount()).isEqualTo(1);
    }

    /** 按 slug 找租户 ID（不依赖列表顺序）。 */
    private static String tenantIdOf(MessagingChannels channels, String slug) {
        return channels.tenantIds().stream()
                .filter(id -> slug.equals(channels.slugOf(id)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("本实例清单里没有租户: " + slug));
    }

    @Test
    @DisplayName("⚠️ 租户顺序必须与配置顺序一致（Map 无序会静默打乱发布器顺序）")
    void tenantOrderMatchesConfiguration() {
        MessagingChannels channels = ctx.getBean(MessagingChannels.class);

        assertThat(channels.tenantIds())
                .as("⚠️ 租户顺序必须与 agent.worker.tenant-slugs 的配置顺序一致："
                        + "发布器清单与深度探测都依赖它。"
                        + "（曾因用 Map.copyOf 存有序数据导致顺序不确定）")
                .extracting(channels::slugOf)
                .containsExactly("t-a", "t-b");
    }

    @Test
    @DisplayName("⚠️ 两条通道同时活动时，背压必须同时看到两者")
    void bothChannelsAreProbed() {
        MessagingChannels channels = ctx.getBean(MessagingChannels.class);
        assertThat(channels.rabbitActive()).isTrue();
        assertThat(channels.databaseActive()).isTrue();

        QueueDepthProbe probe = ctx.getBean(QueueDepthProbe.class);
        assertThat(probe).isInstanceOf(CompositeQueueDepthProbe.class);
        CompositeQueueDepthProbe composite = (CompositeQueueDepthProbe) probe;

        assertThat(composite.activeProbeCount())
                .as("⚠️ 两条通道并存时必须两个探测都在（否则另一条无限堆积而无人看见）")
                .isEqualTo(2);
        assertThat(composite.probes())
                .as("⚠️ 两个探测必须分别覆盖 Rabbit 与 PG 通道")
                .anyMatch(p -> p instanceof PgQueueDepthProbe)
                .anyMatch(p -> !(p instanceof PgQueueDepthProbe));
    }
}
