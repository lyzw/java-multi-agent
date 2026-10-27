package cn.studykid.multiagent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.ports.messaging.MessageHandler;

/**
 * MQ 消费端的日志上下文（跨进程串联的接收半边）。
 *
 * <p><b>为什么单独测这一条</b>：容器线程是复用的，
 * 「上一条消息的租户出现在下一条消息的日志里」在这种线程上最容易发生，
 * 而且发生后<b>没有任何报错</b>——只有排障时会发现日志对不上。
 * 生产端把链路号放进消息头（见 {@link RabbitMessageSenderTest}），
 * 本类证明消费端确实接上、且用完即还原。
 */
@DisplayName("MQ 消费端 · 链路上下文与反串号")
class RabbitExecutionListenerLogContextTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String QUEUE = "agent.run.execute";

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        LogContext.clear();
    }

    private Message message(String traceHeader) {
        Message message = RabbitExecutionListener.toAmqpMessage(mapper, Map.of(
                "runId", "run-1", "tenantId", TENANT, "attempt", 0,
                "fencingToken", 1L, "enqueuedAt", Instant.now().toString()), QUEUE);
        if (traceHeader != null) {
            message.getMessageProperties().setHeader(LogContext.TRACE_HEADER, traceHeader);
        }
        return message;
    }

    @Test
    @DisplayName("处理线程看到的上下文 = 消息头链路号 + 消息体租户 + system 主体")
    void handlerSeesContextFromMessage() throws Exception {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        MessageHandler handler = (eventType, payload, workerId) -> {
            seen.set(LogContext.snapshot().asMap());
            return MessageHandler.Outcome.CLAIMED;
        };
        Channel channel = mock(Channel.class);

        new RabbitExecutionListener(handler, mapper)
                .onMessage(message("upstream-trace"), channel);

        assertThat(seen.get())
                .containsEntry(LogContext.TRACE_ID, "upstream-trace")
                .containsEntry(LogContext.TENANT_ID, TENANT)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.SYSTEM);
        verify(channel).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("消息头缺失 → 就地生成链路号，日志不留空洞")
    void generatesWhenHeaderMissing() throws Exception {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        MessageHandler handler = (eventType, payload, workerId) -> {
            seen.set(LogContext.snapshot().asMap());
            return MessageHandler.Outcome.CLAIMED;
        };

        new RabbitExecutionListener(handler, mapper)
                .onMessage(message(null), mock(Channel.class));

        assertThat(seen.get().get(LogContext.TRACE_ID)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("⚠️ 反串号：本条消息的租户不得残留到下一条（容器线程复用）")
    void clearsContextAfterEachDelivery() throws Exception {
        MessageHandler handler = (eventType, payload, workerId) -> MessageHandler.Outcome.CLAIMED;
        RabbitExecutionListener listener = new RabbitExecutionListener(handler, mapper);
        Channel channel = mock(Channel.class);

        listener.onMessage(message("first"), channel);
        assertThat(LogContext.traceId())
                .as("回调返回后容器线程不应带任何上下文")
                .isNull();

        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        MessageHandler capture = (eventType, payload, workerId) -> {
            seen.set(LogContext.snapshot().asMap());
            return MessageHandler.Outcome.CLAIMED;
        };
        new RabbitExecutionListener(capture, mapper)
                .onMessage(message("second"), channel);

        assertThat(seen.get())
                .containsEntry(LogContext.TRACE_ID, "second")
                .containsEntry(LogContext.TENANT_ID, TENANT);
    }

    @Test
    @DisplayName("坏消息进死信的路径同样有链路号，且不残留上下文")
    void hostileBodyStillScopesContext() throws Exception {
        Channel channel = mock(Channel.class);
        // 白名单外字段 → 消费侧纵深防御直接判死信
        Message broken = RabbitExecutionListener.toAmqpMessage(
                mapper, Map.of("prompt", "should never be here"), QUEUE);
        broken.getMessageProperties().setHeader(LogContext.TRACE_HEADER, "trace-broken");

        AtomicReference<String> traceDuringReject = new AtomicReference<>();
        MessageHandler neverCalled = (eventType, payload, workerId) -> {
            traceDuringReject.set(LogContext.traceId());
            return MessageHandler.Outcome.CLAIMED;
        };

        new RabbitExecutionListener(neverCalled, mapper).onMessage(broken, channel);

        assertThat(traceDuringReject.get()).isNull();
        verify(channel).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(LogContext.traceId()).isNull();
    }
}
