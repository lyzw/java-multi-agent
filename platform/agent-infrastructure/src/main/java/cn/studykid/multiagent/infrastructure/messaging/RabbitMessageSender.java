package cn.studykid.multiagent.infrastructure.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * RabbitMQ 消息发送（P4-D03 / ADR-001）。
 *
 * <p><b>⚠️ publisher confirms 是硬要求</b>（D-2）：
 * {@code MessageSender} 的契约写明「实现必须在 broker 确认后才返回」。
 * 若用默认的「发出即返回」，{@code publishOnce()} 会把
 * <b>尚未被 quorum 队列多数派复制</b>的消息标记为已发布——
 * broker 此刻宕机即<b>永久丢消息</b>，而 Outbox 已标记 published 不会重发。
 * 这正是 Outbox 模式要防的「消息丢失」，且**不报错**。
 *
 * <p><b>⚠️ 出口二次校验</b>（I-1）：即便 {@link OutboxPublisher} 已校验过
 * 载荷安全性，本实现仍在<b>真正进入 MQ 之前</b>再校验一次。
 * 纵深防御：任何绕过 Publisher 直接调用本类的路径都不会漏检。
 *
 * <p><b>持久化</b>：消息标记为 {@code PERSISTENT}，
 * 否则 broker 重启后队列消息全部丢失（内存队列不落盘）。
 *
 * <p><b>⚠️ 交换机与路由键由构造参数决定（第二十八批 · C28-1 · A9）</b>：
 * 此前本类把 {@code agent.run.dispatch} / {@code agent.run.execute}
 * <b>硬编码</b>在方法里，而队列名却来自配置
 * （{@code agent.mq.queues.execute.name}）——
 * 于是「队列名可配」这件事<b>是假的</b>：把队列改成别的名字后，
 * 队列建在新名字上，而本发送器仍往老路由键发消息，
 * <b>没有任何队列订阅那个路由键 → 消息全部丢失</b>，
 * 且症状是「API 返回 201、无报错、无告警、Run 永远 QUEUED」。
 */
public class RabbitMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessageSender.class);

    /**
     * 兼容构造器使用的默认交换机 = 改动前的硬编码值。
     *
     * <p>⚠️ 仅供<b>兼容</b>：它让拓扑**不跟随配置**。
     */
    public static final String DEFAULT_EXCHANGE = "agent.run.dispatch";

    /**
     * 兼容构造器使用的默认路由键 = 改动前的硬编码值。
     *
     * <p>⚠️ 仅供<b>兼容</b>：它让拓扑**不跟随配置**。
     */
    public static final String DEFAULT_ROUTING_KEY = "agent.run.execute";

    private final RabbitTemplate template;

    /** 发布用的交换机名。 */
    private final String exchange;

    /** 发布用的路由键。 */
    private final String routingKey;

    /**
     * 兼容构造器：拓扑固定为**改动前的生产默认值**。
     *
     * <p>⚠️ <b>新增装配路径必须用三参构造器</b>：
     * 本构造器让发送器<b>不跟随配置</b> —— 部署改了队列名之后，
     * 用本构造器装配的发送器仍往旧拓扑发消息，
     * 症状是「消息发出去没人收」且<b>不报错</b>。
     */
    public RabbitMessageSender(RabbitTemplate template) {
        this(template, DEFAULT_EXCHANGE, DEFAULT_ROUTING_KEY);
    }

    /**
     * 完整构造器：交换机与路由键由装配方给出。
     *
     * <p>⚠️ <b>名称为空即拒绝</b>（fail-closed）：
     * 空名会被拼成「往默认 exchange 发消息」或「路由到无订阅者的路由键」，
     * 症状是<b>消息静默丢失</b> —— 与「消息发到不存在的队列」完全同形。
     */
    public RabbitMessageSender(RabbitTemplate template, String exchange, String routingKey) {
        this.template = Objects.requireNonNull(template, "template 不能为空");
        this.exchange = requireTopologyName(exchange, "exchange");
        this.routingKey = requireTopologyName(routingKey, "routingKey");
    }

    /** 交换机名（供装配校验与排障使用）。 */
    public String exchange() {
        return exchange;
    }

    /** 路由键（供装配校验与排障使用）。 */
    public String routingKey() {
        return routingKey;
    }

    private static String requireTopologyName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "MQ 拓扑名不能为空（" + what + "）—— 空名会让消息发到无订阅者的路由键，"
                            + "症状是消息静默丢失且不报错");
        }
        return value.trim();
    }

    @Override
    public void send(@NonNull String eventType, @NonNull OutboxMessage message) {
        // ⚠️ I-1 纵深防御：出口再校验一次，不信任调用方
        Map<String, Object> payload = message.toPayload();
        OutboxMessage.assertPayloadSafe(payload);

        // ⚠️ 唯一 ID 用 runId + attempt：同一 Run 的多次尝试是不同消息，
        //    消费端可用它做幂等判断（比随机 UUID 更有业务含义）。
        CorrelationData correlation = new CorrelationData(
                message.runId() + "#" + message.attempt());

        template.convertAndSend(exchange, routingKey, payload, m -> {
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            m.getMessageProperties().setMessageId(correlation.getId());
            // ⚠️ 标记来源，便于消费端排障（不含任何业务数据）
            m.getMessageProperties().setHeader("x-agent-event-type", eventType);
            return m;
        }, correlation);

        // ⚠️ 等待 broker 确认——不等确认就标记 published 即上述丢消息路径
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get();
        } catch (InterruptedException e) {
            // ⚠️ 中断须恢复标志并转成「发布失败」：
            //    吞掉中断会让上层以为发布成功，Outbox 被标记 published → 丢消息。
            Thread.currentThread().interrupt();
            throw new MessagePublishException(
                    "等待 broker 确认时被中断（runId=" + message.runId() + ")", e);
        } catch (ExecutionException e) {
            throw new MessagePublishException(
                    "等待 broker 确认失败（runId=" + message.runId() + ")", e);
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "无确认" : confirm.getReason();
            throw new MessagePublishException(
                    "broker 未确认消息（runId=" + message.runId()
                            + ", attempt=" + message.attempt() + ", reason=" + reason + ")");
        }
        if (log.isDebugEnabled()) {
            log.debug("消息已确认: runId={}, attempt={}",
                    message.runId(), message.attempt());
        }
    }
}