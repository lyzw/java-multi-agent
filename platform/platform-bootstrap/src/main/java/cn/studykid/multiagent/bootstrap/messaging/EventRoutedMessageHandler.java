package cn.studykid.multiagent.bootstrap.messaging;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;

/**
 * 按事件类型分流的复合消息处理器（P0 · G2 · DB 通道）。
 *
 * <p><b>为什么需要它</b>：DB（PostgreSQL 队列）通道下，
 * {@code PgQueueListener} <b>按租户领取</b>该租户的全部 PENDING 消息，
 * 而执行消息与采集消息<b>同表</b>（{@code agent_message_queue}）。
 * 若把整张表都交给执行消费者，采集事件会被判成「非执行事件」→ 进死信；
 * 若把整张表都交给采集处理器，已终态的 Run 会被<b>重新执行</b>（<b>Sev-0</b>）。
 * <p>故必须<b>按 eventType 分流</b>，且分流的判据必须是<b>事件类型本身</b>，
 * 而不是「谁先到」。
 *
 * <p>⚠️ <b>默认分支必须交给执行消费者</b>：
 * 它对未知事件类型返回 {@code REJECTED}（进死信），这与 P0 之前的语义<b>完全一致</b>。
 * 若这里改成「静默丢弃」，那些本该进死信供人工排查的消息会<b>无声消失</b>。
 */
public class EventRoutedMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(EventRoutedMessageHandler.class);

    /** 执行事件（唤醒 Run 执行）。 */
    private final MessageHandler runDispatch;

    /** 采集事件（派生指标）。 */
    private final MessageHandler traceCollect;

    public EventRoutedMessageHandler(@NonNull MessageHandler runDispatch,
                                     @NonNull MessageHandler traceCollect) {
        this.runDispatch = runDispatch;
        this.traceCollect = traceCollect;
    }

    @Override
    public @NonNull Outcome handle(@NonNull String eventType,
                                    @NonNull Map<String, Object> payload,
                                    @NonNull String workerId) {
        if (TraceCollectEvent.TYPE.equals(eventType)) {
            return traceCollect.handle(eventType, payload, workerId);
        }
        // ⚠️ 其余一律走执行消费者（含它对未知类型的 REJECTED 兜底）
        return runDispatch.handle(eventType, payload, workerId);
    }

    /** @return 执行事件处理器（供装配断言读取） */
    public @NonNull MessageHandler runDispatchHandler() {
        return runDispatch;
    }

    /** @return 采集事件处理器（供装配断言读取） */
    public @NonNull MessageHandler traceCollectHandler() {
        return traceCollect;
    }
}
