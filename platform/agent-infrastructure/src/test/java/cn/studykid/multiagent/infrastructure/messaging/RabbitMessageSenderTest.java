package cn.studykid.multiagent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 生产者侧行为测试（P4-D06 的「行为测试」半边，与 {@link RealBrokerEndToEndIT}
 * 的真实 broker「连通性测试」互补，二者合起来才覆盖「消息不丢」）。
 *
 * <p><b>为何另起一片</b>：quorum 队列在 {@code basic.get} 回包里会<b>剥掉</b>
 * {@code delivery_mode}（quorum 本就总是持久化，该字段对它无意义），
 * 故 {@link RealBrokerEndToEndIT} 收回到的消息 {@code getDeliveryMode()} 恒为
 * {@code null}，无法在那条链路里证明「生产者设了 PERSISTENT」。
 * 持久化的正确性保证由两处分别构成：
 * <ul>
 *   <li>① 本片证明 {@link RabbitMessageSender} 在<b>进入 MQ 前</b>把消息标为 PERSISTENT；</li>
 *   <li>② 生产拓扑是 quorum 队列（WorkerLoopConfig + 连线测试 C-5 保证，总是落盘）。</li>
 * </ul>
 * 二者缺一都会丢消息，故必须分片覆盖，不能在一条链路里同时证明。
 */
@DisplayName("P4-D06 RabbitMessageSender 生产者行为")
class RabbitMessageSenderTest {

    private static final String EXCHANGE = "agent.run.dispatch";
    private static final String ROUTING_KEY = "agent.run.execute";

    private RabbitMessageSender senderUnderTest(RabbitTemplate template) {
        return new RabbitMessageSender(template);
    }

    private OutboxMessage sample() {
        return new OutboxMessage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                0, 1L, Instant.now());
    }

    // ── P-1：进入 MQ 前必须标 PERSISTENT + 带事件类型头 ──────────────

    @Nested
    @DisplayName("P-1 出口消息属性")
    class OutgoingMessage {

        @Test
        @DisplayName("⚠️⚠️ 发送前把消息标为 PERSISTENT（否则 broker 重启丢消息）")
        void marksPersistentAndSetsHeader() {
            RabbitTemplate template = mock(RabbitTemplate.class);
            AtomicReference<MessagePostProcessor> mpp = new AtomicReference<>();
            AtomicReference<CorrelationData> cd = new AtomicReference<>();
            // ⚠️ convertAndSend 是 void：用 doAnswer 捕获后置处理器与 CorrelationData，
            //    并模拟 broker 已确认，否则 send() 会阻塞在 future.get()。
            doAnswer(inv -> {
                mpp.set(inv.getArgument(3));
                CorrelationData data = inv.getArgument(4);
                cd.set(data);
                data.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            }).when(template).convertAndSend(anyString(), anyString(), any(),
                    any(MessagePostProcessor.class), any(CorrelationData.class));

            senderUnderTest(template).send("RUN_DISPATCH", sample());

            verify(template).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY),
                    any(Map.class), any(MessagePostProcessor.class), any(CorrelationData.class));

            Message processed = mpp.get().postProcessMessage(
                    MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build());
            assertThat(processed.getMessageProperties().getDeliveryMode())
                    .as("⚠️⚠️ 必须是 PERSISTENT：quorum 队列虽总落盘，"
                            + "但非 quorum 场景一旦非持久化即重启丢消息；"
                            + "且 D02 出口校验 I-1 要求此项")
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat((String) processed.getMessageProperties().getHeader("x-agent-event-type"))
                    .as("⚠️ 事件类型头必须带（且不含业务数据）")
                    .isEqualTo("RUN_DISPATCH");
        }
    }

    // ── P-2：broker 未确认（nack）必须抛 MessagePublishException ─────

    @Nested
    @DisplayName("P-2 确认失败处理")
    class ConfirmFailure {

        @Test
        @DisplayName("⚠️⚠️ broker 未确认必须抛异常（不得静默标记 published 致丢消息）")
        void nackThrows() {
            RabbitTemplate template = mock(RabbitTemplate.class);
            doAnswer(inv -> {
                CorrelationData data = inv.getArgument(4);
                // ⚠️ 模拟 broker 拒绝（nack）：这正是 D-2 要防的「发出即返回」路径
                data.getFuture().complete(new CorrelationData.Confirm(false, "queue full"));
                return null;
            }).when(template).convertAndSend(anyString(), anyString(), any(),
                    any(MessagePostProcessor.class), any(CorrelationData.class));

            assertThatThrownBy(() -> senderUnderTest(template).send("RUN_DISPATCH", sample()))
                    .as("⚠️⚠️ nack 不抛异常 = Outbox 被误标 published = 永久丢消息且不报错")
                    .isInstanceOf(MessagePublishException.class);
        }
    }
}
