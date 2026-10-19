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

    /**
     * 执行器（P4-D01）。
     *
     * <p>⚠️ <b>这是修复「断链」的关键</b>：此前 {@code consume} 领到租约后
     * 直接返回 {@code CLAIMED}，{@code task} 拿到手就被丢弃——
     * <b>D5–D11 交付的全部闸门在生产中一次都不会执行</b>。
     *
     * <p>抽成接口而非直接依赖 {@code RunExecutionLoop}：
     * 消费者只关心「执行结局 → MQ 处置」的映射，不应知道执行细节。
     */
    private final RunExecutor executor;

    /** 执行器：领取后真正跑执行循环。 */
    @FunctionalInterface
    public interface RunExecutor {
        /**
         * @param tenantId 租户（来自已验签消息，RLS 绑定依据）
         * @param task已领取的任务
         * @return 执行结局
         */
        Outcome execute(String tenantId, RunClaimPort.LeasedTask task);
    }

    /** 执行结局（决定 MQ 处置）。 */
    public enum Outcome {
        /** 正常完成。 */
        SUCCEEDED,
        /** 可归因失败（已终结，重投无意义）。 */
        FAILED,
        /** 用户取消。 */
        CANCELLED,
        /** 可重试失败 → <b>nack 重投</b>。 */
        RETRYABLE_FAILURE,
        /** ⚠️ 结果未知 → <b>ack 丢弃，绝不重投</b>（重投 = 重复副作用 = Sev-0）。 */
        UNKNOWN_RESULT,
        /** 世代失效（已被接管）→ ack 丢弃。 */
        STALE_GENERATION
    }

    public RunDispatchConsumer(@NonNull RunClaimPort claimPort,
                               @NonNull Duration leaseDuration) {
        this(claimPort, leaseDuration, null);
    }

    /**
     * @param executor 执行器；<b>null 表示不执行</b>（仅领取租约）。
     *        ⚠️ 保留 null 仅为兼容既有调用方与单测；生产<b>必须</b>传入。
     */
    public RunDispatchConsumer(@NonNull RunClaimPort claimPort,
                               @NonNull Duration leaseDuration,
                               @org.jspecify.annotations.Nullable RunExecutor executor) {
        this.claimPort = claimPort;
        this.leaseDuration = leaseDuration;
        this.executor = executor;
    }

    /**
     * 消费判定结果。
     *
     * <p>区分三种结果，因为后续处置完全不同 ——
     * 混在一起会导致重试策略错误。
     */
    /**
     * 执行结局 → MQ 处置。
     *
     * <p>⚠️ {@link Outcome#UNKNOWN_RESULT} 必须映射为<b>丢弃</b>——
     * 重投意味着同一个工具动作再执行一次，对 {@code record_weakness}
     * 就是<b>重复写入</b>（Sev-0）。这是全项目最危险的一行映射。
     */
    private ConsumeResult toConsumeResult(Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED, FAILED, CANCELLED, UNKNOWN_RESULT, STALE_GENERATION ->
                    ConsumeResult.CLAIMED;
            case RETRYABLE_FAILURE -> ConsumeResult.RETRY;
        };
    }

    public enum ConsumeResult {
        /** 已领取租约并执行完毕（含终态与 UNKNOWN——<b>均不得重投</b>）。 */
        CLAIMED,
        /** 可重试失败 → <b>nack 重投</b>。 */
        RETRY,
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

        // ⚠️⚠️ **断链修复点（P4-D01）**：此前这里直接 return CLAIMED，
        //    task 拿到手即丢弃 —— 执行循环从未被调用。
        //    现在真正执行，并按结局决定 MQ 处置。
        if (executor == null) {
            log.warn("⚠️ 未装配执行器，仅领取租约不执行（生产不应出现）: runId={}",
                    task.runId());
            return ConsumeResult.CLAIMED;
        }
        Outcome outcome;
        try {
            outcome = executor.execute(message.tenantId(), task);
        } catch (RuntimeException e) {
            // ⚠️ 执行异常按「可重试」处理，但**不得吞掉**——
            //    异常消息会走死信，便于排查。
            log.error("执行循环抛出未捕获异常，按可重试处理: runId={}", task.runId(), e);
            return ConsumeResult.REJECTED;
        }
        return toConsumeResult(outcome);
    }
}