package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 补偿任务（W-PERM-P3 · §5.3.4 阶段、持久化事实与恢复 · §5.3.5 跨系统恢复表）。
 *
 * <p>⚠️ <b>补偿失败必须持久化，不能只记日志</b>（§5.3.5）：
 * 预算已预留但释放失败时，额度仍被占用；
 * 审批已预占但取消失败时，审批悬在 {@code RESERVED} 挡住后续调用。
 * 这两种「悬空」如果不落库，就只能靠人肉排查 ——
 * 故补偿框架在补偿失败时<b>必须</b>落一条任务，由独立调度重放。
 *
 * <p>⚠️ <b>补偿必须幂等</b>：同一补偿重复执行不得产生二次效果
 * （重复释放额度 = 额度凭空放大，比超发更隐蔽）。
 *
 * @param taskId        任务标识
 * @param tenantId      租户
 * @param kind          补偿类型
 * @param targetId      补偿对象标识（审批 id / 预留 id / 操作 id）
 * @param invocationId  关联调用标识
 * @param payload       补偿参数（JSON 文本）
 * @param state         任务状态
 * @param attempts      已尝试次数
 * @param lastError     最近一次失败原因
 * @param createdAt     创建时间
 * @param updatedAt     最后更新时间
 * @param nextRetryAt   下次重试时刻
 */
public record CapCompensationTask(
        @NonNull String taskId,
        @NonNull String tenantId,
        @NonNull CompensationKind kind,
        @NonNull String targetId,
        @Nullable String invocationId,
        @Nullable String payload,
        @NonNull TaskState state,
        int attempts,
        @Nullable String lastError,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt,
        @Nullable Instant nextRetryAt) {

    public CapCompensationTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts 不得为负");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("时间字段不能为空");
        }
    }

    /** 补偿类型。 */
    public enum CompensationKind {
        /** 取消审批（预占后因预算/PRE/准入失败而作废）。 */
        CANCEL_APPROVAL,
        /** 释放预算预留。 */
        RELEASE_BUDGET,
        /** 重试账本落账（确定结果已持久化，仅落账失败）。 */
        RETRY_LEDGER_POST
    }

    /** 任务状态。 */
    public enum TaskState {
        /** 待执行。 */
        PENDING,
        /** 执行中。 */
        RUNNING,
        /** 已完成（补偿成功或确认无需补偿）。 */
        COMPLETED,
        /** 失败待重试。 */
        FAILED
    }
}
