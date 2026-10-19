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