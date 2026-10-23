package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapCompensationTask;

/**
 * 补偿任务仓储端口（W-PERM-P3 · §5.3.5 跨系统恢复表）。
 *
 * <p>⚠️ <b>补偿失败必须落库</b>：预算已预留但释放失败、审批已预占但取消失败，
 * 这两类「悬空」若只记日志，就只能靠人肉排查 ——
 * 故补偿框架在补偿失败时必须落一条任务，由独立调度重放。
 *
 * <p>⚠️ <b>补偿必须幂等</b>：同一任务重复执行不得产生二次效果
 * （重复释放额度 = 额度凭空放大，比超发更隐蔽）。
 */
public interface CompensationTaskPort {

    /**
     * 落一条补偿任务。
     *
     * <p>幂等：同一 {@code (tenantId, taskId)} 已存在时返回既有记录，不覆盖。
     *
     * @param task 任务
     * @return 是否为本次写入（{@code false} = 已存在）
     */
    boolean enqueue(@NonNull CapCompensationTask task);

    /** @return 按 id 读取任务。 */
    @NonNull Optional<CapCompensationTask> findById(@NonNull String tenantId,
                                                     @NonNull String taskId);

    /**
     * 扫描待执行任务（补偿重放入口）。
     *
     * @param tenantId 租户
     * @param limit    单次上限
     * @return 待执行任务（按创建时间升序）
     */
    @NonNull List<CapCompensationTask> findPending(@NonNull String tenantId, int limit);

    /**
     * 标记任务完成。
     *
     * @return 是否发生状态迁移
     */
    boolean markCompleted(@NonNull String tenantId, @NonNull String taskId);

    /**
     * 标记任务失败（待重试）。
     *
     * @param error        失败原因
     * @param nextRetryAt  下次重试时刻
     * @return 是否发生状态迁移
     */
    boolean markFailed(@NonNull String tenantId, @NonNull String taskId,
                       @NonNull String error, @NonNull Instant nextRetryAt);
}
