package cn.studykid.multiagent.bootstrap.messaging;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 指标自动采集消费者（P0 自动采集接线，G2）。
 *
 * <p><b>⚠️ 与 {@code RabbitExecutionListener} 同纪律</b>：手动 ack + 失败 nack 重投，
 * 保证 {@code x-delivery-limit} 的有限重试与死信控制生效——
 * 否则 auto-ack 下 nack 无效，失败消息被静默丢弃且无人知晓。
 *
 * <p><b>消费语义</b>：消息体是 {@link OutboxMessage} 的白名单载荷（runId/tenantId/…），
 * 取出 tenantId + runId 后用既有 {@link AgentMetricCollector} 派生一条
 * {@code agent_trace_metric} 行（幂等 ON CONFLICT(trace_id)）。
 * 采集器只依赖端口、事实读取自带 RLS 租户绑定，本类不碰 JDBC、不碰连接。
 *
 * <p>⚠️ <b>队列独立性</b>：本监听器只订阅 {@code agent.trace.collect}，
 * 与执行队列 {@code agent.run.execute} 完全隔离——绝不会触发 Run 重跑。
 */
public class TraceMetricCollectorListener {

    private static final Logger log = LoggerFactory.getLogger(TraceMetricCollectorListener.class);

    private final AgentMetricCollector collector;
    private final ObjectMapper mapper;

    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();

    public TraceMetricCollectorListener(AgentMetricCollector collector, ObjectMapper mapper) {
        this.collector = collector;
        this.mapper = mapper;
    }

    // ⚠️ 容器的启停由 spring.rabbitmq.listener.simple.auto-startup 控制
    //    （与 agent.worker.enabled 同步）。队列由 traceCollectQueue Bean 声明，
    //    监听容器启动前队列已存在。
    @RabbitListener(queues = "${agent.mq.queues.trace-collect.name}", ackMode = "MANUAL")
    public void onMessage(Message raw, com.rabbitmq.client.Channel channel) throws Exception {
        long tag = raw.getMessageProperties().getDeliveryTag();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(raw.getBody(), Map.class);
            // ⚠️ 消费侧也校验白名单：堵住「绕过 Publisher 直接投消息」的路径
            OutboxMessage.assertPayloadSafe(body);
            String tenantId = String.valueOf(body.get("tenantId"));
            String runId = String.valueOf(body.get("runId"));
            // 租户来自消息体（权威来源）；主体固定为 system（Worker 不是登录用户）
            LogContext.setIdentity(tenantId, LogContext.SYSTEM);
            try {
                collector.collect(tenantId, java.util.UUID.fromString(runId));
                acked.incrementAndGet();
                channel.basicAck(tag, false);
            } catch (RuntimeException e) {
                // ⚠️ 采集失败（DB 抖动 / 租户绑定异常）：按可重试处理，nack 重投。
                retried.incrementAndGet();
                log.error("trace 指标采集失败，nack 重投: runId={}, cause={}", runId, e.toString());
                channel.basicNack(tag, false, true);
            }
        } catch (RuntimeException e) {
            // ⚠️ 解析/不安全载荷：重投无用，直接死信。
            deadLettered.incrementAndGet();
            log.error("trace 采集消息不可解析/不安全，进死信: queue={}, cause={}",
                    raw.getMessageProperties().getConsumerQueue(), e.toString());
            channel.basicNack(tag, false, false);
        } finally {
            // 容器线程会被复用，finally 的 clear() 不是可选的——
            // 漏清会把上一条消息的租户带进下一条消息的日志（跨请求串号）。
            LogContext.clear();
        }
    }

    public long ackedCount() {
        return acked.get();
    }

    public long retriedCount() {
        return retried.get();
    }

    public long deadLetterCount() {
        return deadLettered.get();
    }
}
