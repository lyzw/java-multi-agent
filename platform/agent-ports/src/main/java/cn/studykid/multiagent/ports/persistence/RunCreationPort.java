package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;

/**
 * Run 创建端口 —— <b>原子提交契约</b>（P0-D03 §4 红线 3）。
 *
 * <p><b>红线原文</b>：
 * <blockquote>
 * Run 创建、初始事件、幂等记录和 Outbox 必须在<b>同一事务中</b>提交。
 * </blockquote>
 *
 * <p><b>为什么必须原子</b>：四者任一缺失都会导致不可恢复的不一致：
 * <ul>
 *   <li>缺 Outbox → Run 永远不会被 Worker 领取，任务静默卡死；</li>
 *   <li>缺初始事件 → SSE 订阅方看不到 Run 起始状态，无法正确续传；</li>
 *   <li>缺幂等记录 → 客户端重试会创建重复 Run（对有副作用场景是 Sev-0）。</li>
 * </ul>
 *
 * <p>⚠️ <b>实现约束</b>：本接口的每个方法都必须由调用方置于
 * <b>同一个数据库事务</b>内执行。实现方<b>不得</b>自行提交或回滚——
 * 事务边界由应用层控制，避免出现「实现内部提交」导致半成品状态。
 */
public interface RunCreationPort {

    /**
     * 在<b>当前事务</b>中原子写入 Run、初始事件、幂等记录与 Outbox。
     *
     * @param tenantId      租户标识
     * @param runId         新建 Run 标识（由调用方生成，便于重试时复用）
     * @param snapshotId    执行快照标识
     * @param key           幂等键（命名空间 + 键值）
     * @param digest        请求摘要
     * @param inputPayload  输入载荷；<b>不得含学生可识别信息</b>
     * @param correlationId 链路追踪标识
     * @param agentPayload  唤醒消息体，<b>载荷最小化</b>（见 OutboxMessage）
     * @return 实际写入的 Run ID
     */
    @NonNull String insertRunAtomically(@NonNull String tenantId,
                                         @NonNull String runId,
                                         @NonNull String snapshotId,
                                         @NonNull IdempotencyKey key,
                                         @NonNull RequestDigest digest,
                                         @NonNull Map<String, Object> inputPayload,
                                         @NonNull String correlationId,
                                         @NonNull OutboxMessage agentPayload);

    /**
     * 查询幂等记录并给出判定（ADR-003）。
     *
     * <p>在<b>当前事务</b>内查询（若外层有事务）。返回：
     * <ul>
     *   <li>键不存在 → {@code FIRST_EXECUTION}；</li>
     *   <li>键存在且摘要相同 → {@code REPLAY}，携带首次的资源 ID；</li>
     *   <li>键存在但摘要不同 → {@code CONFLICT}。</li>
     * </ul>
     *
     * @param tenantId 租户
     * @param key      幂等键
     * @param digest   本次请求摘要
     */
    @NonNull IdempotencyDecision findExistingResource(
            @NonNull String tenantId,
            @NonNull IdempotencyKey key,
            @NonNull RequestDigest digest);

    /**
     * 查询 Run 当前状态。
     *
     * @return 不存在时返回 {@code null}
     */
    @Nullable RunRecord findRun(@NonNull String tenantId, @NonNull String runId);

    /** 查询待发布的 Outbox 事件。 */
    @NonNull OutboxBatch loadPendingOutbox(@NonNull String tenantId, int limit);

    /**
     * 标记 Outbox 事件已发布。
     *
     * <p>⚠️ 幂等：重复标记同一事件不得报错（发布成功但标记失败的场景下会重试）。
     */
    void markOutboxPublished(@NonNull String tenantId, @NonNull String outboxId, @NonNull Instant publishedAt);

    /** 记录 Outbox 发布失败，供后续重试与告警。 */
    void markOutboxFailed(@NonNull String tenantId, @NonNull String outboxId, @NonNull String error);

    /** Run 只读视图。 */
    record RunRecord(
            @NonNull String runId,
            @NonNull String state,
            int attempt,
            long fencingToken,
            @Nullable String leaseOwner,
            @Nullable Instant leaseExpiresAt,
            @NonNull Instant createdAt
    ) {
    }

    /** 待发布事件批次。 */
    record OutboxBatch(@NonNull List<PendingOutbox> events) {
        public OutboxBatch {
            events = events == null ? List.of() : List.copyOf(events);
        }

        public boolean isEmpty() {
            return events.isEmpty();
        }
    }

    /** 单条待发布事件。 */
    record PendingOutbox(
            @NonNull String outboxId,
            @NonNull String runId,
            @NonNull String eventType,
            @NonNull Map<String, Object> payload,
            int attempts
    ) {
    }
}