package cn.studykid.multiagent.ports.messaging;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 消息发送端口（P4-D03）。
 *
 * <p>⚠️ <b>为何从 {@code agent-worker} 前移到 {@code agent-ports}</b>：
 * 原先 {@code MessageSender} 是 {@code OutboxPublisher} 的嵌套接口，
 * 而 {@code OutboxPublisher} 在 agent-worker —— 于是 agent-infrastructure
 * 的 RabbitMQ 实现必须反向依赖 worker，<b>违反模块依赖方向</b>
 * （{@code core → ports → application → infrastructure → worker}）。
 * 与 P4-D01 把 {@code ApprovalPolicy} 前移到 ports 完全同源。
 *
 * <p><b>契约要点</b>：实现<b>必须在 broker 确认后才返回</b>。
 * 若「发出即返回」，调用方会把尚未被 quorum 队列多数派复制的消息标记为已发布，
 * broker 此刻宕机即<b>永久丢消息</b>，而 Outbox 已标记 published 不会重发 ——
 * 这正是 Outbox 模式要防的丢消息，且不报错。
 */
@FunctionalInterface
public interface MessageSender {

    /**
     * 发送消息。
     *
     * @param eventType 事件类型（仅用于日志与消息头，不含业务数据）
     * @param message   消息体（载荷已由 {@code OutboxMessage} 白名单约束）
     * @throws MessagePublishException 发布失败（含确认失败、载荷不安全）
     */
    void send(@NonNull String eventType, @NonNull OutboxMessage message);

    /** 发布失败（含确认失败）。 */
    class MessagePublishException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MessagePublishException(String message) {
            super(message);
        }

        public MessagePublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
