package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 复合消息处理器（按 eventType 分流）单测。
 *
 * <p><b>为什么这是 Sev-0 级的判据</b>：DB 通道下执行消息与采集消息<b>同表</b>，
 * 领取是「按租户取全部 PENDING」。分流错了就是两种灾难之一：
 * <ul>
 *   <li>采集事件落到执行消费者 → 已终态的 Run 被<b>重新执行</b>（重复副作用 = Sev-0）；</li>
 *   <li>采集事件被当执行事件拒绝 → 采集静默失效，宽表永远空白。</li>
 * </ul>
 */
class EventRoutedMessageHandlerTest {

    private final MessageHandler runDispatch = mock(MessageHandler.class);

    private final MessageHandler traceCollect = mock(MessageHandler.class);

    private final EventRoutedMessageHandler handler =
            new EventRoutedMessageHandler(runDispatch, traceCollect);

    @Test
    @DisplayName("⚠️ 采集事件必须走采集处理器（绝不能落到执行处理器）")
    void traceEventGoesToTraceHandler() {
        Map<String, Object> payload = Map.of("runId", UUID.randomUUID().toString());
        org.mockito.Mockito.when(traceCollect.handle(any(), any(), any()))
                .thenReturn(MessageHandler.Outcome.CLAIMED);

        MessageHandler.Outcome outcome = handler.handle(
                TraceCollectEvent.TYPE, payload, "worker-1");

        assertThat(outcome).isEqualTo(MessageHandler.Outcome.CLAIMED);
        verify(traceCollect).handle(eq(TraceCollectEvent.TYPE), eq(payload), eq("worker-1"));
        verifyNoInteractions(runDispatch);
    }

    @Test
    @DisplayName("✅ 其它事件一律走执行处理器（保持 P0 之前的语义不变）")
    void otherEventsGoToRunDispatch() {
        Map<String, Object> payload = Map.of("runId", UUID.randomUUID().toString());
        org.mockito.Mockito.when(runDispatch.handle(any(), any(), any()))
                .thenReturn(MessageHandler.Outcome.REJECTED);

        MessageHandler.Outcome outcome = handler.handle("RUN_DISPATCH", payload, "worker-1");

        assertThat(outcome).isEqualTo(MessageHandler.Outcome.REJECTED);
        verify(runDispatch).handle(eq("RUN_DISPATCH"), eq(payload), eq("worker-1"));
        verifyNoInteractions(traceCollect);
    }

    @Test
    @DisplayName("⚠️ 载荷原样透传（不得被改写）")
    void payloadIsPassedThrough() {
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID().toString(), "tenant-1", 0, 1L, java.time.Instant.now());
        Map<String, Object> payload = message.toPayload();

        handler.handle(TraceCollectEvent.TYPE, payload, "worker-1");

        verify(traceCollect).handle(eq(TraceCollectEvent.TYPE), eq(payload), eq("worker-1"));
    }
}
