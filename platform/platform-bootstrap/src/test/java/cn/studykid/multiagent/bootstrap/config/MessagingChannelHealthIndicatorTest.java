package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link MessagingChannelHealthIndicator} 的反证测试。
 *
 * <p><b>⚠️ 本类的核心不是「测通」，而是「测死两种误判」</b>：
 * <ol>
 *   <li><b>误判一：把检查关掉换绿</b> —— Rabbit 在用且 broker 挂了，若仍报 UP，
 *       就是用「看起来健康」换掉了「消息永远不消费」，
 *       与本项目反复记录的「断链而系统表面正常」同族。故用例 B 必须红。</li>
 *   <li><b>误判二：连都不该连却去连</b> —— Rabbit 不在用时去连 broker，
 *       就是让一个**根本没部署**的中间件给实例健康下判决，
 *       并且每次探针轮询刷一整页连接堆栈。故用例 A/D 必须断言「零交互」。</li>
 * </ol>
 *
 * <p><b>⚠️ 不需要数据库</b>：通道分配（{@link MessagingChannels}）在测试里用 mock 代替，
 * 本类只验证「健康判定该不该看 broker」，不涉及 SQL 与 RLS。
 */
class MessagingChannelHealthIndicatorTest {

    @Test
    @DisplayName("✅ 反证 A：Rabbit 不在用 → UP，且**一个字节都不碰 broker**")
    void rabbitNotInUseIsUpWithoutTouchingBroker() {
        MessagingChannels channels = mock(MessagingChannels.class);
        when(channels.rabbitActive()).thenReturn(false);
        RabbitTemplate template = mock(RabbitTemplate.class);

        Health health = new MessagingChannelHealthIndicator(provider(channels), provider(template)).health();

        assertThat(health.getStatus())
                .as("⛔ Rabbit 不在用时不得报 DOWN —— 否则默认（database）部署的健康端点永远 503")
                .isEqualTo(Status.UP);
        // ⚠️ 关键断言：**连都没连**。这才是「不刷连接堆栈」的保证，
        //    也保证纯 DB 部署不会因为一个不存在的 broker 被判不健康。
        verifyNoInteractions(template);
        assertThat(health.getDetails())
                .as("⚠️ 必须写清「为什么是 UP」，免得后来人误以为检查被关了")
                .containsKey("reason");
    }

    @Test
    @DisplayName("⛔ 反证 B：Rabbit 在用且 broker 连不上 → 必须 DOWN（不能为了绿而关检查）")
    void rabbitInUseWithUnreachableBrokerIsDown() {
        MessagingChannels channels = mock(MessagingChannels.class);
        when(channels.rabbitActive()).thenReturn(true);
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(template.execute(any(ChannelCallback.class)))
                .thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        Health health = new MessagingChannelHealthIndicator(provider(channels), provider(template)).health();

        assertThat(health.getStatus())
                .as("⛔ Rabbit 在用而 broker 不可达 = 消息永远不消费，必须报 DOWN")
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("⛔ 反证 C：Rabbit 在用却没有 RabbitTemplate → DOWN（fail-closed，不许报 UP）")
    void rabbitInUseWithoutTemplateIsDown() {
        MessagingChannels channels = mock(MessagingChannels.class);
        when(channels.rabbitActive()).thenReturn(true);

        Health health = new MessagingChannelHealthIndicator(provider(channels), provider(null)).health();

        assertThat(health.getStatus())
                .as("⛔ 通道在用却没有发送器 = 消息发不出去，不得报 UP")
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("✅ 反证 D：worker 未启用（无通道分配 Bean）→ UP 且不碰 broker")
    void workerDisabledMeansNoChannelIsInUse() {
        RabbitTemplate template = mock(RabbitTemplate.class);

        // ⚠️ 通道分配 Bean 位于 WorkerLoopConfig，而它带
        //    @ConditionalOnProperty("agent.worker.enabled") → worker 关闭时不存在。
        //    本实例不消费任何消息 → 没有租户在用 Rabbit 通道 → 不参与健康判定。
        Health health = new MessagingChannelHealthIndicator(provider(null), provider(template)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(template);
    }

    /** 构造一个「总是返回该值」的 {@link ObjectProvider}（null 表示该 Bean 不存在）。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
