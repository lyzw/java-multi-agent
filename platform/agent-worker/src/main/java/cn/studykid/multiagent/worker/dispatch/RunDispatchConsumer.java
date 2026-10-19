package cn.studykid.multiagent.worker.dispatch;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * MQ 消费者骨架（P2-D02 §4 / FR-005）。
 *
 * <p><b>只负责把消息变成一次租约领取</b>，执行逻辑在 D3。
 * 刻意不在此写业务逻辑 —— 消费路径越短，
 * 「消息到了却没做事」这类静默卡死越不容易发生。
 *
 * <p><b>⚠️ 核心：重复消息必须丢弃而非重试</b>（P2-D02 §4.1）。
 * Outbox 是 at-least-once，重复是<em>正常形态</em>而非异常。
 * 领取失败说明已被他人领走，重试会让消息在多个 Worker 间来回打转。
 */
public class RunDispatchConsumer {

    private static final Logger log = LoggerFactory.getLogger(RunDispatchConsumer.class);

    /** 本消费者关心的事件类型。 */
    public static final String RUN_DISPATCH = "RUN_DISPATCH";

    private final RunClaimPort claimPort;
    private final Duration leaseDuration;

    public RunDispatchConsumer(@NonNull RunClaimPort claimPort,
                               @NonNull Duration leaseDuration) {
        this.claimPort = claimPort;
        this.leaseDuration = leaseDuration;
    }

    /**
     * 消费判定结果。
     *
     * <p>区分三种结果，因为后续处置完全不同 ——
     * 混在一起会导致重试策略错误。
     */
    public enum ConsumeResult {
        /** 已领取租约，交由 D3 执行。 */
        CLAIMED,
        /** 已被他人领取（重复投递），**丢弃不重试**。 */
        DUPLICATE_DISCARDED,
        /** 消息非法或事件类型不符，**不重试**。 */
        REJECTED
    }

    /**
     * 消费一条消息。
     *
     * @param eventType 事件类型
     * @param payload   消息体
     * @param workerId  Worker 标识
     * @return 消费判定
     */
    public @NonNull ConsumeResult consume(@NonNull String eventType,
                                          @NonNull Map<String, Object> payload,
                                          @NonNull String workerId) {
        if (!RUN_DISPATCH.equals(eventType)) {
            log.debug("忽略非本消费者事件: eventType={}", eventType);
            return ConsumeResult.REJECTED;
        }

        OutboxMessage message;
        try {
            // ⚠️ 发布端已校验，这里再校验一次：
            //    消费者是消息进入系统的最后关口，不能假设上游一定正确
            OutboxMessage.assertPayloadSafe(payload);
            message = OutboxMessage.fromPayload(payload);
        } catch (RuntimeException e) {
            // 非法消息 → 拒绝入死信，不重试（重试同一坏消息无意义）
            log.warn("消息非法，拒绝处理: {}", e.getMessage());
            return ConsumeResult.REJECTED;
        }

        // ⚠️ 用消息中的 tenantId 绑定 RLS 上下文，
        //    否则查询恒返回 0 行（未绑定即默认拒绝）
        Optional<RunClaimPort.LeasedTask> claimed =
                claimPort.claimSpecificRun(message.tenantId(), message.runId(),
                        workerId, leaseDuration);

        if (claimed.isEmpty()) {
            // ⚠️ 重复投递的正常形态：他人已领走
            //    **丢弃而非重试** —— 重试会让消息在 Worker 间打转
            log.debug("消息重复（任务已被领取），丢弃: runId={}", message.runId());
            return ConsumeResult.DUPLICATE_DISCARDED;
        }

        RunClaimPort.LeasedTask task = claimed.get();
        log.debug("已领取任务: runId={}, fencingToken={}", task.runId(), task.fencingToken());
        return ConsumeResult.CLAIMED;
    }
}