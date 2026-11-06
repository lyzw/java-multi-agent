package cn.studykid.multiagent.infrastructure.messaging;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * PostgreSQL 队列消息发送（P4-D17 · RabbitMQ 的可切换替代）。
 *
 * <p><b>⚠️ 契约对齐</b>：{@link MessageSender} 要求「确认后才返回」。
 * 本实现的「确认」= <b>事务已提交</b>（消息行已落盘）。
 * 入队失败即抛 {@link MessagePublishException}，
 * 调用方会保留 Outbox 的 PENDING 状态并重试——
 * <b>不存在「发出即返回、实际没落库」的丢消息路径</b>。
 *
 * <p><b>⚠️ 出口二次校验</b>（I-1，与 Rabbit 实现同源）：
 * 即便 {@code OutboxPublisher} 已校验过载荷安全性，
 * 本实现仍在<b>真正入队之前</b>再校验一次。
 * 纵深防御：任何绕过 Publisher 直接调用本类的路径都不会漏检。
 */
public class PgQueueMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(PgQueueMessageSender.class);

    private final PgQueueRepository repository;

    public PgQueueMessageSender(@NonNull PgQueueRepository repository) {
        this.repository = repository;
    }

    @Override
    public void send(@NonNull String eventType, @NonNull OutboxMessage message) {
        // ⚠️ I-1 纵深防御：出口再校验一次，不信任调用方
        Map<String, Object> payload = message.toPayload();
        OutboxMessage.assertPayloadSafe(payload);

        try {
            repository.enqueue(message.tenantId(), eventType, message.runId(), payload);
        } catch (RuntimeException e) {
            throw new MessagePublishException(
                    "消息入队失败（runId=" + message.runId()
                            + ", tenantId=" + message.tenantId() + "）", e);
        }
        if (log.isDebugEnabled()) {
            log.debug("消息已入队: runId={}, attempt={}", message.runId(), message.attempt());
        }
    }
}
