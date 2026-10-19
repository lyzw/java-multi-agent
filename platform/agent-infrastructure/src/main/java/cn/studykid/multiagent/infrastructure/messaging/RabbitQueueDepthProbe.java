package cn.studykid.multiagent.infrastructure.messaging;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * RabbitMQ 队列深度探测（P4-D05）。
 *
 * <p><b>为何用被动声明（{@code queueDeclarePassive}）而非 {@code AmqpAdmin.getQueueProperties}</b>：
 * 被动声明<b>不创建队列</b>——若队列不存在会直接报错。
 * 这正是我们要的语义：
 * <ul>
 *   <li>「队列不存在」不是「队列为空」；</li>
 *   <li>把它当 0 会让中继全速发布到一个根本不存在的队列，
 *       消息全部丢失而 Outbox 已被标记 published（不可重试）。</li>
 * </ul>
 * 故本实现的失败一律上抛，由背压控制器按「超限」处理（fail-closed）。
 *
 * <p><b>⚠️ 每次探测一次 AMQP 往返</b>：中继每轮调用一次，开销 ≈ 一次 RPC。
 * 这是背压的代价，也是为什么要有「降级后拉长轮询间隔」——
 * 队列已过载时不应再高频打扰 broker。
 */
public class RabbitQueueDepthProbe implements QueueDepthProbe {

    private static final Logger log = LoggerFactory.getLogger(RabbitQueueDepthProbe.class);

    private final RabbitTemplate template;

    public RabbitQueueDepthProbe(@NonNull RabbitTemplate template) {
        this.template = template;
    }

    @Override
    public int depthOf(@NonNull String queueName) {
        // ⚠️ 异常一律上抛（不得 catch 成 0）：见类注释。
        Integer depth = template.execute(
                channel -> channel.queueDeclarePassive(queueName).getMessageCount());
        if (depth == null) {
            // ⚠️ Spring 在通道不可用时可能返回 null——按失败处理，不当 0。
            throw new IllegalStateException(
                    "队列深度探测返回 null（视为失败，fail-closed）: queue=" + queueName);
        }
        if (log.isDebugEnabled()) {
            log.debug("队列深度: queue={}, depth={}", queueName, depth);
        }
        return depth;
    }
}
