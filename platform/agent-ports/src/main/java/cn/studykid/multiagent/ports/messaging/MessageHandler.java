package cn.studykid.multiagent.ports.messaging;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 消息处理端口（P4-D03）。
 *
 * <p>⚠️ <b>为何需要它</b>：MQ 消费容器需要 spring-amqp 与 Jackson，
 * 而 agent-worker 不依赖它们。让 {@code RunDispatchConsumer} 直接实现本端口，
 * 再由 infrastructure 的 {@code RabbitExecutionListener} 调用，
 * 依赖方向保持 {@code infrastructure → ports ← worker}，
 * <b>无反向依赖</b>。
 *
 * <p>与 P4-D01 的 {@code ApprovalPolicy}、D03 的 {@code MessageSender}
 * 前移到 ports 完全同源——本项目已第三次遇到「端口定义在消费方模块」的问题。
 */
public interface MessageHandler {

    /**
     * 消费判定（与 MQ ack/nack 动作严格对应）。
     *
     * <p>⚠️ <b>枚举顺序即默认安全方向</b>：新增枚举值若忘记在消费端映射，
     * 消费端的兜底分支按<b>丢弃</b>处理（见 {@code RabbitExecutionListener}），
     * 因为「重投」意味着重复副作用（Sev-0），「丢弃」只丢一次执行。
     */
    enum Outcome {
        /** 已完成（含 UNKNOWN 与终态）——ack 丢弃，<b>不得重投</b>。 */
        CLAIMED,
        /** 可重试失败——nack 重投。 */
        RETRY,
        /** 不可恢复（消息非法 / 死信）——nack 不重投。 */
        REJECTED
    }

    /**
     * 处理一条消息。
     *
     * @param eventType 事件类型
     * @param payload   消息体（{@code OutboxMessage} 白名单内字段）
     * @param workerId  处理者标识
     * @return 消费判定（决定 ack / nack）
     */
    @NonNull Outcome handle(@NonNull String eventType,
                             @NonNull Map<String, Object> payload,
                             @NonNull String workerId);
}