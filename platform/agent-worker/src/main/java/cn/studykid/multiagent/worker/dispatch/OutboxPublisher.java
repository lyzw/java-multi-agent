package cn.studykid.multiagent.worker.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;

/**
 * Outbox 发布器（P2-D02 §3 / FR-005）。
 *
 * <p><b>为何需要</b>：若 Run 落库后直接发消息，发消息前崩溃会导致
 * <b>任务永久丢失且无任何报错</b>。Outbox 把「待发消息」与业务数据
 * 放在同一事务，消息丢失与业务数据丢失绑定在一起。
 *
 * <p><b>单租户设计</b>（P2-D02 §3.2）：一个实例只处理一个租户。
 * 全租户轮询需要能读所有租户的权限，一旦连接配置错误就会
 * <b>跨租户发布消息</b> —— 隔离在最弱的地方被打破。
 *
 * <p>代价：租户数增加时需多实例。试点期 max-tenants=1，推迟到 P4。
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final RunCreationPort outbox;
    private final cn.studykid.multiagent.ports.messaging.MessageSender sender;
    private final String tenantId;
    private final Clock clock;

    /** 单次轮询批量上限。 */
    private final int batchSize;

    /** FAILED 状态累计多少次后停止重试并告警。 */
    private final int maxAttempts;

    public OutboxPublisher(@NonNull RunCreationPort outbox,
                           @NonNull MessageSender sender,
                           @NonNull String tenantId,
                           @NonNull Clock clock,
                           int batchSize,
                           int maxAttempts) {
        this.outbox = outbox;
        this.sender = sender;
        this.tenantId = tenantId;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 执行一轮发布。
     *
     * @return 本轮成功发布的条数
     */
    public int publishOnce() {
        RunCreationPort.OutboxBatch batch = outbox.loadPendingOutbox(tenantId, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (RunCreationPort.PendingOutbox event : batch.events()) {
            if (publishOne(event)) {
                published++;
            }
        }
        return published;
    }

    private boolean publishOne(RunCreationPort.PendingOutbox event) {
        try {
            // ⚠️ 安全校验：即便 Outbox 事件由 D1 写入，
            // 此处仍复核一遍——发布器是消息进入 MQ 的最后关口
            OutboxMessage.assertPayloadSafe(event.payload());
            OutboxMessage message = OutboxMessage.fromPayload(event.payload());

            // ⚠️ 必须等 broker 确认（publisher confirms）。
            //    发出即标记的话，消息未到达时 Outbox 已不再重试 → 任务静默丢失
            sender.send(event.eventType(), message);

            outbox.markOutboxPublished(tenantId, event.outboxId(), clock.instant());
            if (log.isDebugEnabled()) {
                log.debug("Outbox 已发布: outboxId={}, runId={}",
                        event.outboxId(), event.runId());
            }
            return true;

        } catch (MessagePublishException e) {
            handleFailure(event, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // ⚠️ 未预期异常同样标记失败，不吞掉
            //    吞掉会让坏消息永远停在 PENDING，持续重试打满日志
            handleFailure(event, e.getClass().getSimpleName());
            return false;
        }
    }

    private void handleFailure(RunCreationPort.PendingOutbox event, String error) {
        try {
            outbox.markOutboxFailed(tenantId, event.outboxId(), error);
        } catch (RuntimeException markError) {
            // 标记本身失败不能掩盖原始异常——否则会丢失根因
            log.error("标记 Outbox 失败状态时出错: outboxId={}", event.outboxId(), markError);
        }

        if (event.attempts() + 1 >= maxAttempts) {
            // ⚠️ 达到阈值不自动重置（§3.3）：自动重试会让坏消息无限循环。
            //    超过阈值意味着可能是数据问题，应人工介入
            log.error("Outbox 发布连续失败达阈值，需人工介入: outboxId={}, runId={}, error={}",
                    event.outboxId(), event.runId(), error);
        } else {
            log.warn("Outbox 发布失败: outboxId={}, attempts={}, error={}",
                    event.outboxId(), event.attempts() + 1, error);
        }
    }
}
