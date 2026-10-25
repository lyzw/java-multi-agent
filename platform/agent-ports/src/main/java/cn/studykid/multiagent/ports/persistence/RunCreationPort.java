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
     * <p>⚠️ <b>兼容入口</b>：不声明业务截止时间（{@code null} = 不限时）。
     * 新代码请用带 {@link RunLimits} 的重载 —— 资源上限必须随 Run <b>一起</b>落库，
     * 事后单独 UPDATE 会留下「Run 已创建但无截止时间」的窗口。
     *
     * <p>⚠️ <b>本方法是抽象的，实现方必须显式提供</b>：它是本端口的<b>唯一</b>
     * 基础契约 —— 若它也是 default，两个重载就会互相委托成环。
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
     * 在<b>当前事务</b>中原子写入 Run、初始事件、幂等记录与 Outbox，
     * 并写入 Run 的<b>资源上限</b>（截止时间 + 预算额度）。
     *
     * <p>⚠️ <b>资源上限必须与 Run 同事务落库</b>：它是 Run 的固有属性
     * （「这个 Run 最多跑到什么时候、最多花多少钱」），不是可后补的元数据。
     * 若事后单独写入，中间崩溃会留下「无上限的 Run」——
     * 而超时/预算约束一旦缺失，委派链路的「全调用树共享上限」就形同虚设。
     *
     * <p>⚠️ <b>default 实现是「显式失败」而非「静默丢弃」</b>：
     * 未升级到本重载的实现方（以及测试替身）在收到<b>有限额</b>的请求时
     * <b>抛异常</b>，而不是悄悄按「不限制」写入 ——
     * 悄悄丢弃 limits 会让「这个 Run 最多花 100 元」的声明**完全失效**，
     * 而调用方看到的是一次**成功的**创建。
     * 收到 {@link RunLimits#UNLIMITED} 时则走旧签名（语义等价，无需显式实现）。
     *
     * <p>⚠️ 这也是本方法保持 default 的原因：新增抽象方法会**强制**所有既有
     * 实现方与测试替身同时改造，而它们分布在多个模块 ——
     * 端口一次小改就打断整条提交链。default + 显式失败让新旧实现方都能编译，
     * 同时把「没实现」变成运行期的**响亮失败**而不是静默降级。
     *
     * @param tenantId      租户标识
     * @param runId         新建 Run 标识（由调用方生成，便于重试时复用）
     * @param snapshotId    执行快照标识
     * @param key           幂等键（命名空间 + 键值）
     * @param digest        请求摘要
     * @param inputPayload  输入载荷；<b>不得含学生可识别信息</b>
     * @param correlationId 链路追踪标识
     * @param agentPayload  唤醒消息体，<b>载荷最小化</b>（见 OutboxMessage）
     * @param limits        Run 的资源上限（{@link RunLimits#UNLIMITED} = 不限制）
     * @return 实际写入的 Run ID
     */
    default @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                @NonNull String runId,
                                                @NonNull String snapshotId,
                                                @NonNull IdempotencyKey key,
                                                @NonNull RequestDigest digest,
                                                @NonNull Map<String, Object> inputPayload,
                                                @NonNull String correlationId,
                                                @NonNull OutboxMessage agentPayload,
                                                @NonNull RunLimits limits) {
        if (limits == null || !limits.anyLimit()) {
            // ⚠️ 无限制 → 语义等价于旧签名 → 走基础契约
            return insertRunAtomically(tenantId, runId, snapshotId, key, digest,
                    inputPayload, correlationId, agentPayload);
        }
        throw new UnsupportedOperationException(
                "该 RunCreationPort 实现未支持 Run 资源上限（拒绝静默丢弃 limits）: tenant="
                        + tenantId + ", run=" + runId);
    }

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