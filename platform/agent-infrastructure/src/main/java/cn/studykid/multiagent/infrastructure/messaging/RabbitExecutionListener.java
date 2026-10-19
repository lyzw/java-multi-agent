package cn.studykid.multiagent.infrastructure.messaging;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * RabbitMQ 消费容器（P4-D03 / D-2）。
 *
 * <p>⚠️ <b>为何落在 infrastructure 而非 worker</b>：
 * {@code @RabbitListener} 需要 spring-amqp 与 Jackson，
 * 而 agent-worker 不依赖它们（保持 worker 只含业务语义）。
 * 本类通过 {@link MessageHandler} 端口回调 worker ——
 * 依赖方向仍是 {@code infrastructure → ports ← worker}，
 * 无反向依赖（同 P4-D01 的 {@code ApprovalPolicy} 前移）。
 *
 * <p><b>⚠️ 手动 ack 是硬要求</b>：auto-ack 下 nack 无效（消息已确认），
 * {@code x-delivery-limit=3} → 死信的控制会<b>完全失效</b>，
 * 失败消息被静默丢弃且无人知晓。
 *
 * <p><b>ack / nack 映射</b>：
 * <ul>
 *   <li>{@code CLAIMED} → ack（含 UNKNOWN，<b>不得重投</b>）；</li>
 *   <li>{@code RETRY} → nack(requeue=true)；</li>
 *   <li>{@code REJECTED} → nack(requeue=false) 进死信。</li>
 * </ul>
 *
 * <p><b>⚠️ UNKNOWN 必须 ack 丢弃</b>：UNKNOWN 表示「不确定副作用是否已发生」。
 * 重投 = 同一个 {@code record_weakness} 再执行 = <b>重复写入</b>（Sev-0）。
 * 对账由 D3 的工具台账负责，<b>不靠消息重投</b>——
 * 重投解决不了「是否已写入」这个问题。
 */
public class RabbitExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitExecutionListener.class);

    private final MessageHandler handler;

    private final ObjectMapper mapper;

    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();

    public RabbitExecutionListener(MessageHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    /**
     * 消费一条消息。
     *
     * <p>⚠️ <b>解析失败直接死信</b>：同一坏消息重投多少次都一样，
     * 重投只会耗尽 {@code x-delivery-limit} 后才进死信，白白延迟。
     */
    // ⚠️ 容器的启停由 {@code spring.rabbitmq.listener.simple.auto-startup} 控制
    //    （在 application.yml 与 E2E 的 @TestPropertySource 中与
    //    {@code agent.worker.enabled} 同步设置）。
    //    ⚠️ 教训：{@code @RabbitListener} 的端点注册由 RabbitBootstrap 处理，
    //    **不受本项目 @Configuration 的 @ConditionalOnProperty 影响**——
    //    关掉 WorkerLoopConfig 仍会启动容器并尝试连 broker，
    //    使「关掉 worker」成为假象（E2E 实测踩到）。
    @RabbitListener(queues = "${agent.mq.queues.execute.name}",
            ackMode = "MANUAL")
    public void onMessage(Message raw, com.rabbitmq.client.Channel channel)
            throws Exception {
        long tag = raw.getMessageProperties().getDeliveryTag();

        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(raw.getBody(), Map.class);
            // ⚠️ I-1 纵深防御：消费侧也校验，堵住「绕过 Publisher 直接投消息」的路径
            OutboxMessage.assertPayloadSafe(parsed);
            body = parsed;
        } catch (RuntimeException e) {
            deadLettered.incrementAndGet();
            log.error("消息不可解析/不安全，直接进死信: queue={}",
                    raw.getMessageProperties().getConsumerQueue(), e);
            channel.basicNack(tag, false, false);
            return;
        }

        MessageHandler.Outcome outcome;
        try {
            outcome = handler.handle("RUN_DISPATCH", body, workerId());
        } catch (RuntimeException e) {
            // ⚠️ 处理器异常 → 重投（可重试故障）。
            //    注意：**不是** UNKNOWN 语义——UNKNOWN 由 handler 显式返回，
            //    异常路径无法判定副作用，故按可重试处理并留日志。
            retried.incrementAndGet();
            log.error("消费处理异常，nack 重投: {}", e.getMessage(), e);
            channel.basicNack(tag, false, true);
            return;
        }

        switch (outcome) {
            case CLAIMED -> {
                acked.incrementAndGet();
                channel.basicAck(tag, false);
            }
            case RETRY -> {
                retried.incrementAndGet();
                log.warn("可重试失败，nack 重投");
                channel.basicNack(tag, false, true);
            }
            case REJECTED -> {
                deadLettered.incrementAndGet();
                log.error("消费被拒，进死信");
                channel.basicNack(tag, false, false);
            }
            default -> {
                // ⚠️ 枚举新增值时的兜底：**默认丢弃而非重投**。
                // 新增枚举值若忘记映射，「重投」会造成重复副作用（Sev-0），
                // 「丢弃」只是丢一次执行——安全方向的默认值。
                deadLettered.incrementAndGet();
                log.error("未知 Outcome={}，按丢弃处理（安全默认值）", outcome);
                channel.basicAck(tag, false);
            }
        }
    }

    /** Worker 标识（进程内唯一即可；多实例部署时应含实例 ID）。 */
    private String workerId() {
        return "worker-" + ProcessHandle.current().pid();
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

    /** 构建一条测试用 AMQP 消息（供连通性测试）。 */
    public static Message toAmqpMessage(ObjectMapper mapper, Map<String, Object> body,
                                        String queue) {
        org.springframework.amqp.core.MessageProperties props =
                new org.springframework.amqp.core.MessageProperties();
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setConsumerQueue(queue);
        try {
            return new org.springframework.amqp.core.Message(
                    mapper.writeValueAsBytes(body), props);
        } catch (RuntimeException e) {
            throw new IllegalStateException("构造 AMQP 消息失败", e);
        }
    }
}