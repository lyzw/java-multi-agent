package cn.studykid.multiagent.ports.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 任务领取与租约端口（P1-06 / 红线 5）。
 *
 * <p><b>红线原文</b>（P0-D03 §4 红线 5）：
 * <blockquote>
 * Worker 接管前必须持有有效租约；租约<b>超时后可被强制接管</b>，接管后<b>旧世代不得提交</b>结果。
 * </blockquote>
 *
 * <p><b>为什么需要 fencing token 而不只是租约</b>：
 * <blockquote>
 * 租约只能「劝阻」，无法「阻止」。若 Worker A 租约超时但尚未察觉，
 * Worker B 接管后 A 又提交了结果——此时若只校验租约时间，双方都认为自己是合法持有者。
 * <b>fencing token 是单调递增的世代号，旧世代提交会被数据库直接拒绝</b>，
 * 提供强保证而非软约束。
 * </blockquote>
 *
 * <p>数据库层已有 {@code enforce_fencing_on_write} 触发器兜底——
 * 本端口只负责<b>正确递增</b>世代号，最终防线在 DB。
 */
public interface RunClaimPort {

    /** 默认租约时长：30 秒。超出后允许被接管。 */
    Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    /**
     * 领取一批待执行任务并建立租约。
     *
     * <p>实现<b>必须</b>使用 {@code SELECT ... FOR UPDATE SKIP LOCKED}，
     * 否则多个 Worker 会争抢同一行（高并发下退化为串行，且可能重复领取）。
     *
     * @param tenantId    租户
     * @param workerId    Worker 标识
     * @param leaseDuration 租约时长
     * @param limit       领取上限
     * @return 已领取的任务；无任务时返回空列表
     */
    @NonNull List<LeasedTask> claimTasks(@NonNull String tenantId,
                                         @NonNull String workerId,
                                         @NonNull Duration leaseDuration,
                                         int limit);

    /**
     * 领取<b>指定</b> Run 的租约（P2-D02 §4.1）。
     *
     * <p><b>为何需要独立方法</b>：MQ 消息已经指名了 runId，
     * 若走批量领取 {@link #claimTasks}，会拿到消息里<b>没有</b>的任务，
     * 导致消息与执行错配 —— 执行的任务不是消息指定的那个。
     *
     * <p><b>返回 {@code null} 是正常语义</b>，不是错误：
     * 表示该 Run 已被他人领取（重复投递）或不存在。
     * 调用方<b>不应</b>重试 —— 重试会让消息在多个 Worker 间来回打转。
     *
     * @param tenantId      租户；须用于绑定 RLS 上下文
     * @param runId         目标 Run
     * @param workerId      Worker 标识
     * @param leaseDuration 租约时长
     * @return 领取成功返回任务；已被领取或不存在返回 {@code null}
     */
    java.util.Optional<LeasedTask> claimSpecificRun(@NonNull String tenantId,
                                                     @NonNull String runId,
                                                     @NonNull String workerId,
                                                     @NonNull Duration leaseDuration);

    /**
     * 续租。仅当<b>持有相同世代</b>时成功——旧世代续租会失败。
     *
     * @return 是否成功（false 表示已被接管，调用方须停止工作）
     */
    boolean renewLease(@NonNull String tenantId, @NonNull String runId,
                       @NonNull String workerId, long expectedFencingToken,
                       @NonNull Duration leaseDuration);

    /**
     * 释放租约（如协作式取消时）。
     *
     * @return 是否成功
     */
    boolean releaseLease(@NonNull String tenantId, @NonNull String runId,
                         @NonNull String workerId, long expectedFencingToken);

    /**
     * 以指定世代提交结果。
     *
     * <p>实现<b>必须</b>在 UPDATE 条件中带上 {@code fencing_token = ?}，
     * 使旧世代的提交影响行数为 0——即便应用层漏判，DB 也已拒绝。
     *
     * @return 是否成功（false 表示世代已过期，结果被拒绝）
     */
    boolean commitResult(@NonNull String tenantId, @NonNull String runId,
                         long expectedFencingToken, @NonNull String state,
                         @NonNull String resultPayload);

    /**
     * 检查当前世代是否仍有效。
     *
     * @return true 表示仍是合法持有者
     */
    boolean isCurrentGeneration(@NonNull String tenantId, @NonNull String runId,
                                long fencingToken);

    /**
     * 协作式取消（P3-D06 / FR-011）。
     *
     * <p><b>原子</b>将 {@code RUNNING/QUEUED → CANCELLED} 并置 {@code cancel_requested_at}。
     * 终态不可回退：已终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT）的 Run 取消不生效。
     *
     * <p>⚠️ 直接置终态而非只置 flag：即便 Worker 进程已死，取消也不会被「复活」；
     * {@code claimSpecificRun} 只收 {@code QUEUED/RUNNING}、{@code commitResult} 要求
     * {@code state='RUNNING'}，已取消 Run 的重新领取与提交覆盖均被拒。
     *
     * @return 是否生效（受影响行数 > 0）
     */
    boolean requestCancel(@NonNull String tenantId, @NonNull String runId);

    /**
     * 是否已取消（P3-D06 / FR-011）。
     *
     * <p>{@code state = 'CANCELLED'} 或 {@code cancel_requested_at} 已置均视为已取消。
     *
     * @return true 表示已取消
     */
    boolean isCancelled(@NonNull String tenantId, @NonNull String runId);

    /** 已建立租约的任务。 */
    record LeasedTask(
            @NonNull String runId,
            long fencingToken,
            @NonNull Instant leaseExpiresAt,
            int attempt
    ) {
        /** @return 租约是否已过期。 */
        public boolean isLeaseExpired(@NonNull Instant now) {
            return now.isAfter(leaseExpiresAt);
        }
    }
}