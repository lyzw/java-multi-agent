package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.run.ToolOperationState;

/**
 * ToolOperation 账本端口（P3-D03 / FR-009 / 红线 7）。
 *
 * <p><b>为什么这个账本必须存在</b>：工具「请求已发出但响应丢失」时，
 * 副作用可能已发生。若只在内存记 UNKNOWN，请求结束即蒸发 ——
 * 无人能对账、盲目重试无法被拦截（红线 7 在持久层不成立）。
 *
 * <p><b>状态机即契约</b>：所有迁移先经
 * {@link ToolOperationState#requireTransitionTo} 校验，
 * DB 触发器 {@code forbid_attempt_after_terminal} 是第二道防线。
 *
 * <p>三层 Attempt 区分（ADR-004）：
 * Run attempt ⊃ ToolOperation（逻辑操作，对账最小单位）⊃ ToolOperationAttempt（调用尝试）。
 */
public interface RunToolLedgerPort {

    /**
     * 调用前登记逻辑操作（PENDING → IN_FLIGHT）。
     *
     * <p>⚠️ 幂等键唯一约束命中时<b>返回既有操作</b>而非新建 ——
     * 重复调用指向同一逻辑操作，这是对账与幂等的最小单位（FR-003）。
     *
     * @return 登记结果（新建或命中的既有操作，均为 IN_FLIGHT 语义）
     */
    @NonNull Registration register(@NonNull String tenantId,
                                   @NonNull String runId,
                                   @NonNull String toolName,
                                   @NonNull String riskLevel,
                                   @NonNull String idempotencyKey,
                                   @NonNull String requestDigest,
                                   @NonNull Map<String, Object> arguments,
                                   long fencingToken);

    /**
     * 记录单次调用结局（IN_FLIGHT → SUCCEEDED / FAILED / UNKNOWN）。
     *
     * <p>⚠️ UNKNOWN 时 attempt 行强制 {@code result_unknown=true}（CHECK 约束）；
     * ⚠️ 若操作此前已是终态，本方法必须抛出（新增 attempt 被 DB 拒绝）。
     *
     * @param result 本次调用结局（SUCCEEDED / FAILED / UNKNOWN 之一）
     */
    void recordResult(@NonNull String tenantId,
                      @NonNull String operationId,
                      @NonNull ToolOperationState result,
                      long fencingToken,
                      String errorClass,
                      String errorMessage);

    /**
     * 对账收敛（UNKNOWN → RECONCILED_SUCCEEDED / RECONCILED_ABSENT）。
     *
     * <p>⚠️ 仅当当前状态为 UNKNOWN 时允许调用 —— 对任意状态收敛
     * 会绕过状态机，让「成功被改判」成为可能。
     *
     * @param reconciled 目标终态（仅 RECONCILED_SUCCEEDED / RECONCILED_ABSENT）
     * @param source     收敛依据（如 "profile-lookup:hit" / "profile-lookup:absent-after-grace"）
     */
    void reconcile(@NonNull String tenantId,
                   @NonNull String operationId,
                   @NonNull ToolOperationState reconciled,
                   @NonNull String source);

    /**
     * 人工兜底处置（UNKNOWN → MANUAL_DISPOSED），记责任人与结论。
     */
    void manualDispose(@NonNull String tenantId,
                       @NonNull String operationId,
                       @NonNull String disposedBy,
                       @NonNull String note);

    /** @return 该租户全部处于 UNKNOWN 的操作（对账扫描入口，按创建时间升序）。 */
    @NonNull List<UnknownOperation> findUnknownOperations(@NonNull String tenantId);

    /** @return 幂等键对应的既有操作（对账时查询下游依据）。 */
    @NonNull Optional<Registration> findByIdempotencyKey(@NonNull String tenantId,
                                                         @NonNull String idempotencyKey);

    /**
     * 读取幂等键对应的<b>请求摘要</b>（§5.3 步骤 8「同键不同参数」判定用）。
     *
     * <p>⚠️ <b>默认实现返回空 = 「读不到摘要」</b>，调用方据此<b>不得</b>判定冲突，
     * 但也<b>不得</b>据此重新执行同一幂等键 —— 键已存在即意味着该逻辑操作已在进行，
     * 重新执行会造成重复副作用（§5.3.4「同幂等键重试 → 禁止重复副作用」）。
     *
     * <p>生产实现<b>必须</b>覆写本方法返回真实的请求摘要，
     * 否则「同键不同参数 → {@code IDEMPOTENCY_CONFLICT}」这条契约无法成立。
     *
     * @param tenantId      租户
     * @param idempotencyKey 幂等键
     * @return 已登记的请求摘要；无记录或读不到时为空
     */
    default @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                         @NonNull String idempotencyKey) {
        return Optional.empty();
    }

    /**
     * 按<b>操作标识</b>读取逻辑操作（崩溃恢复入口 · §5.3.4）。
     *
     * <p>⚠️ 恢复方拿到的是<b>执行意图</b>，意图上记的是 {@code invocationId}
     * （= 账本 {@code operationId}），不是幂等键 ——
     * 故恢复流程需要按 operationId 反查账本状态，
     * 才能判定「外部是否已执行、结果是否已知」。
     *
     * @param tenantId    租户
     * @param operationId 操作标识
     * @return 登记信息；无记录时为空
     */
    default @NonNull Optional<Registration> findByOperationId(@NonNull String tenantId,
                                                              @NonNull String operationId) {
        return Optional.empty();
    }

    /**
     * 登记结果。{@code state} 为 IN_FLIGHT 表示新登记；
     * 命中既有操作时返回其当前状态与既定 attempt 数。
     *
     * <p><b>⚠️ {@code createdByThisCall} 是防重复副作用的关键</b>：
     * 幂等键命中既有操作时，{@code state} <b>可能仍是 IN_FLIGHT</b>
     * （上一次调用崩在中途，或另一 worker 正在执行），
     * 因此<b>光看状态无法区分「本次新建」与「命中别人留下的」</b>。
     * 调用方据此决定「是否执行工具」——
     * 命中既有操作时再次执行 = 同一逻辑操作产生两次副作用 = Sev-0。
     *
     * @param operationId   逻辑操作标识
     * @param state         当前状态
     * @param attemptCount  已记录的调用尝试数
     * @param fencingToken  该操作所属的租约世代（红线 5）。
     *                       ⚠️ 崩溃恢复补记 attempt 时用它 ——
     *                       恢复方没有自己的租约，但 attempt 记录的是
     *                       「这次调用发生在哪一代租约下」，不能写假值。
     * @param createdByThisCall {@code true} = 本次登记**新建**了操作行；
     *                           {@code false} = 幂等键命中**既有**操作行。
     */
    record Registration(String operationId,
                        ToolOperationState state,
                        int attemptCount,
                        long fencingToken,
                        boolean createdByThisCall) {
    }

    /** 对账扫描条目。 */
    record UnknownOperation(String operationId,
                            String runId,
                            String toolName,
                            String idempotencyKey,
                            java.time.Instant createdAt) {
    }
}
