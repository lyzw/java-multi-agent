package cn.studykid.multiagent.bootstrap.messaging;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 指标采集事件的 <b>DB（PostgreSQL 队列）通道</b>消费处理器（P0 · G2）。
 *
 * <p><b>为什么需要它</b>：消息通道有两条（
 * {@code agent.messaging.mode} = {@code rabbitmq} / {@code database}），
 * <b>默认是 {@code database}</b>（以 PG 表为队列，不依赖外部 broker）。
 * Rabbit 通道的采集消费端是 {@link TraceMetricCollectorListener}（{@code @RabbitListener}），
 * 但它在 DB 模式下<b>根本不会启动</b>（监听容器的 autoStartup 与「Rabbit 是否活动」求与）。
 * <p>若只做 Rabbit 侧，默认部署下采集事件会<b>发出去却无人消费</b> ——
 * 宽表永远空白，而系统表面完全正常（API 正常、Run 正常、看板空白）。
 *
 * <p><b>⚠️ 与 Rabbit 侧的三态映射必须一致</b>：
 * <ul>
 *   <li>载荷非法（缺 runId/tenantId、runId 不是 UUID）→ {@code REJECTED}（进死信，不重投）——
 *       同一坏消息重投多少次都一样，只会白白耗尽次数；</li>
 *   <li>采集抛异常（DB 抖动、事实读取失败）→ {@code RETRY}（重投）——
 *       这是<b>可重试故障</b>，重投后仍可能成功；</li>
 *   <li>采集成功（或该 Run 不存在/未收敛，采集器返回空）→ {@code CLAIMED}（ack 丢弃）。</li>
 * </ul>
 *
 * <p>⚠️ <b>绝不能把采集事件交给 {@code RunDispatchConsumer}</b>：
 * 那会让已终态的 Run 被<b>重新执行</b>（重复副作用 = Sev-0）。
 * 故本类只处理 {@link #EVENT_TYPE}，由
 * {@code EventRoutedMessageHandler} 按 eventType 分流。
 */
public class TraceCollectMessageHandler implements MessageHandler {

    /** 采集事件类型（与发送侧路由键同名）。 */
    public static final String EVENT_TYPE = TraceCollectEvent.TYPE;

    private static final Logger log = LoggerFactory.getLogger(TraceCollectMessageHandler.class);

    private final AgentMetricCollector collector;

    public TraceCollectMessageHandler(@NonNull AgentMetricCollector collector) {
        this.collector = collector;
    }

    @Override
    public @NonNull Outcome handle(@NonNull String eventType,
                                    @NonNull Map<String, Object> payload,
                                    @NonNull String workerId) {
        // ⚠️ 只处理采集事件：其它事件走到这里说明分流配错了，
        //    按「不可恢复」处理（进死信），绝不落到采集逻辑上。
        if (!EVENT_TYPE.equals(eventType)) {
            log.error("采集处理器收到非采集事件，按死信处理: eventType={}", eventType);
            return Outcome.REJECTED;
        }

        UUID runId;
        String tenantId;
        try {
            OutboxMessage message = OutboxMessage.fromPayload(payload);
            tenantId = message.tenantId();
            runId = UUID.fromString(message.runId());
        } catch (RuntimeException e) {
            log.error("采集事件载荷非法，进死信: error={}", e.getMessage());
            return Outcome.REJECTED;
        }

        try {
            collector.collect(tenantId, runId);
            return Outcome.CLAIMED;
        } catch (RuntimeException e) {
            // ⚠️ 可重试：事实读取失败（DB 抖动）等属临时故障。
            //    重投是安全的 —— 采集是**幂等**的（按 trace_id 覆盖写）。
            log.error("采集失败，退回重试: tenantId={}, runId={}, error={}",
                    tenantId, runId, e.getMessage(), e);
            return Outcome.RETRY;
        }
    }
}
