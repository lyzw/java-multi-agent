package cn.studykid.multiagent.bootstrap.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * P0 自动采集接线（G2）：验证消费者收到 {@code agent.trace.collect} 消息后，
 * 调既有 {@link AgentMetricCollector}，成功则 ack、采集异常则 nack 重投。
 */
class TraceMetricCollectorListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Message traceMessage(String runId, String tenantId, long tag) throws Exception {
        OutboxMessage outbox = new OutboxMessage(runId, tenantId, 0, 1L, Instant.now());
        Message msg = new Message(mapper.writeValueAsBytes(outbox.toPayload()),
                new MessageProperties());
        msg.getMessageProperties().setDeliveryTag(tag);
        return msg;
    }

    @Test
    void onMessage_acksAndCollects() throws Exception {
        AgentMetricCollector collector = mock(AgentMetricCollector.class);
        TraceMetricCollectorListener listener = new TraceMetricCollectorListener(collector, mapper);
        com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);

        String runId = UUID.randomUUID().toString();
        listener.onMessage(traceMessage(runId, "tenant-xyz", 5L), channel);

        verify(collector).collect(eq("tenant-xyz"), eq(UUID.fromString(runId)));
        verify(channel).basicAck(5L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void onMessage_collectThrows_nackRetry() throws Exception {
        AgentMetricCollector collector = mock(AgentMetricCollector.class);
        doThrow(new RuntimeException("db down")).when(collector).collect(anyString(), any());
        TraceMetricCollectorListener listener = new TraceMetricCollectorListener(collector, mapper);
        com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);

        String runId = UUID.randomUUID().toString();
        listener.onMessage(traceMessage(runId, "tenant-xyz", 9L), channel);

        // ⚠️ 可重试故障 → nack 重投（requeue=true），而非丢弃
        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onMessage_unparseablePayload_deadLetters() throws Exception {
        AgentMetricCollector collector = mock(AgentMetricCollector.class);
        TraceMetricCollectorListener listener = new TraceMetricCollectorListener(collector, mapper);
        com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);

        // 非 JSON 载荷 → 解析失败 → 直接死信（requeue=false）
        Message msg = new Message("not-json".getBytes(), new MessageProperties());
        msg.getMessageProperties().setDeliveryTag(11L);
        listener.onMessage(msg, channel);

        verify(channel).basicNack(11L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(collector, never()).collect(anyString(), any());
    }
}
