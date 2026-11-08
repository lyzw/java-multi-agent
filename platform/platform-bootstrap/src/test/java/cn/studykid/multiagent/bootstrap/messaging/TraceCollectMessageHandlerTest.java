package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 采集事件处理器（DB 通道）单测。
 *
 * <p><b>重点验证三态映射</b>——它与 Rabbit 通道必须<b>逐条对齐</b>，
 * 否则「切换通道」就等于「改变消息语义」：
 * <ol>
 *   <li>载荷非法 → {@code REJECTED}（死信，不重投）；</li>
 *   <li>采集抛异常 → {@code RETRY}（可重试故障）；</li>
 *   <li>采集成功 → {@code CLAIMED}（ack 丢弃）。</li>
 * </ol>
 */
class TraceCollectMessageHandlerTest {

    private final AgentMetricCollector collector = mock(AgentMetricCollector.class);

    private final TraceCollectMessageHandler handler = new TraceCollectMessageHandler(collector);

    @Test
    @DisplayName("✅ 采集成功 → CLAIMED（ack 丢弃，不重投）")
    void successIsClaimed() {
        UUID runId = UUID.randomUUID();
        MessageHandler.Outcome outcome = handler.handle(
                TraceCollectEvent.TYPE, payload(runId, "tenant-1"), "worker-1");

        assertThat(outcome).isEqualTo(MessageHandler.Outcome.CLAIMED);
        verify(collector).collect(eq("tenant-1"), eq(runId));
    }

    @Test
    @DisplayName("⚠️ 载荷缺 runId/tenantId → REJECTED（进死信，不重投）")
    void brokenPayloadIsRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("attempt", 0);

        MessageHandler.Outcome outcome = handler.handle(
                TraceCollectEvent.TYPE, payload, "worker-1");

        assertThat(outcome)
                .as("坏消息重投多少次都一样，只会白白耗尽重试次数")
                .isEqualTo(MessageHandler.Outcome.REJECTED);
        verify(collector, never()).collect(any(), any());
    }

    @Test
    @DisplayName("⚠️ runId 不是 UUID → REJECTED（不得让非法 id 进采集器）")
    void nonUuidRunIdIsRejected() {
        Map<String, Object> payload = payload(UUID.randomUUID(), "tenant-1");
        payload.put("runId", "not-a-uuid");

        MessageHandler.Outcome outcome = handler.handle(
                TraceCollectEvent.TYPE, payload, "worker-1");

        assertThat(outcome).isEqualTo(MessageHandler.Outcome.REJECTED);
        verify(collector, never()).collect(any(), any());
    }

    @Test
    @DisplayName("⚠️ 采集抛异常 → RETRY（DB 抖动属可重试故障）")
    void collectorFailureIsRetry() {
        UUID runId = UUID.randomUUID();
        doThrow(new IllegalStateException("读取 Trace 事实失败"))
                .when(collector).collect(eq("tenant-1"), eq(runId));

        MessageHandler.Outcome outcome = handler.handle(
                TraceCollectEvent.TYPE, payload(runId, "tenant-1"), "worker-1");

        assertThat(outcome)
                .as("采集是幂等的（按 trace_id 覆盖写），重投安全")
                .isEqualTo(MessageHandler.Outcome.RETRY);
    }

    @Test
    @DisplayName("⚠️ 非采集事件 → REJECTED（绝不落到采集逻辑上）")
    void foreignEventIsRejected() {
        MessageHandler.Outcome outcome = handler.handle(
                "RUN_DISPATCH", payload(UUID.randomUUID(), "tenant-1"), "worker-1");

        assertThat(outcome).isEqualTo(MessageHandler.Outcome.REJECTED);
        verify(collector, never()).collect(any(), any());
    }

    private static Map<String, Object> payload(UUID runId, String tenantId) {
        Map<String, Object> payload = new HashMap<>(new OutboxMessage(
                runId.toString(), tenantId, 0, 1L, java.time.Instant.now()).toPayload());
        return payload;
    }
}
