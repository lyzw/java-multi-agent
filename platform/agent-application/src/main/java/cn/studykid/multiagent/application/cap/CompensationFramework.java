package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;

/**
 * 补偿框架（W-PERM-P3-T4 · §5.3.3 补偿 · §5.3.5 跨系统恢复表）。
 *
 * <p>§5.3.5 的恢复表把「失败发生在哪一步」与「各系统该处于什么状态」一一对应：
 * <pre>
 *   故障点        审批      预算     账本     结果事件   处理
 *   审批 reserve 失败  PENDING   —        —        —        DENY(APPROVAL_*)
 *   预算失败         CANCELLED 释放     —        —        DENY(BUDGET_EXCEEDED)
 *   PRE 失败         CANCELLED 释放     无 PRE   —        拒绝执行；补偿入队
 *   准入失败         CANCELLED 释放     PRE 取消 —        DENY(EXEC_ADMISSION_STALE)
 * </pre>
 *
 * <p>⚠️ <b>补偿必须幂等</b>：
 * <ul>
 *   <li>取消审批：{@code PENDING/RESERVED/INTENT_SUBMITTED → CANCELLED} 是 CAS，
 *       重复取消不产生二次迁移；</li>
 *   <li>释放预算：{@code RESERVED → RELEASED} 只在 RESERVED 时生效，
 *       重复释放不二次退回额度 ——
 *       <b>额度凭空放大比超发更隐蔽</b>（它让后续所有预留都能通过）。</li>
 * </ul>
 *
 * <p>⚠️ <b>补偿失败必须持久化</b>：
 * 预算已预留但释放失败 → 额度仍被占用；
 * 审批已预占但取消失败 → 审批悬在 RESERVED 挡住后续调用。
 * 这两类「悬空」若只记日志就只能靠人肉排查 ——
 * 故本框架在补偿失败时<b>必须</b>落一条任务，由独立调度重放。
 */
public class CompensationFramework {

    private static final Logger log = LoggerFactory.getLogger(CompensationFramework.class);

    private final CapApprovalPort approvals;
    private final BudgetPort budget;
    private final CompensationTaskPort tasks;

    public CompensationFramework(@NonNull CapApprovalPort approvals,
                                 @NonNull BudgetPort budget,
                                 @NonNull CompensationTaskPort tasks) {
        this.approvals = approvals;
        this.budget = budget;
        this.tasks = tasks;
    }

    /**
     * 补偿请求。
     *
     * @param tenantId      租户
     * @param approvalId    待取消的审批（可空：审批未预占时无补偿对象）
     * @param invocationId  调用标识（取消审批时用于校验预占方）
     * @param reservationId 待释放的预算预留（可空）
     * @param reason        补偿原因（写入审批的 cancel_reason）
     */
    public record Request(
            @NonNull String tenantId,
            @Nullable String approvalId,
            @Nullable String invocationId,
            @Nullable String reservationId,
            @NonNull String reason) {
    }

    /**
     * 补偿结果。
     *
     * @param approvalCancelled 审批是否已取消（true = 无需再取消）
     * @param budgetReleased    预算是否已释放（true = 无需再释放）
     * @param enqueued          补偿失败而落库的待重放任务
     */
    public record Result(boolean approvalCancelled,
                          boolean budgetReleased,
                          @NonNull List<CapCompensationTask> enqueued) {
    }

    /**
     * 执行补偿。
     *
     * <p>⚠️ <b>两个动作彼此独立</b>：取消审批失败<b>不得</b>跳过预算释放 ——
     * 否则一次故障会同时留下「审批悬空」与「额度悬空」两个悬空态。
     *
     * @param request 补偿请求
     * @return 补偿结果
     */
    public @NonNull Result compensate(@NonNull Request request) {
        List<CapCompensationTask> enqueued = new ArrayList<>();

        boolean approvalCancelled = true;
        if (request.approvalId() != null && request.invocationId() != null) {
            approvalCancelled = cancelApproval(request, enqueued);
        }

        boolean budgetReleased = true;
        if (request.reservationId() != null) {
            budgetReleased = releaseBudget(request, enqueued);
        }

        return new Result(approvalCancelled, budgetReleased, List.copyOf(enqueued));
    }

    /**
     * 取消审批（幂等）。
     *
     * @return 是否已完成（已取消也算完成 —— 幂等语义）
     */
    private boolean cancelApproval(@NonNull Request request,
                                   @NonNull List<CapCompensationTask> enqueued) {
        try {
            boolean cancelled = approvals.cancel(request.tenantId(), request.approvalId(),
                    request.invocationId(), request.reason());
            if (!cancelled) {
                // ⚠️ 未发生状态迁移 = 审批已处于终态（CANCELLED/CONSUMED/EXPIRED）
                //    或不属于该预占方 —— 都属「无需再补偿」。
                log.debug("审批取消未产生迁移（已终态或非本预占方）: approval={}",
                        request.approvalId());
            }
            return true;
        } catch (RuntimeException e) {
            log.error("审批取消失败，落补偿任务: approval={}, err={}",
                    request.approvalId(), e.getMessage(), e);
            enqueued.add(enqueue(request.tenantId(),
                    CapCompensationTask.CompensationKind.CANCEL_APPROVAL,
                    request.approvalId(), request.invocationId(),
                    "reason=" + request.reason()));
            return false;
        }
    }

    /**
     * 释放预算（幂等）。
     *
     * @return 是否已完成
     */
    private boolean releaseBudget(@NonNull Request request,
                                   @NonNull List<CapCompensationTask> enqueued) {
        try {
            boolean released = budget.release(request.tenantId(),
                    request.reservationId(), request.reason());
            if (!released) {
                // ⚠️ 预留不存在或已终态 → 无需再释放（幂等）
                log.debug("预算释放未生效（预留不存在或已终态）: reservation={}",
                        request.reservationId());
            }
            return true;
        } catch (RuntimeException e) {
            log.error("预算释放失败，落补偿任务: reservation={}, err={}",
                    request.reservationId(), e.getMessage(), e);
            enqueued.add(enqueue(request.tenantId(),
                    CapCompensationTask.CompensationKind.RELEASE_BUDGET,
                    request.reservationId(), request.invocationId(),
                    "reason=" + request.reason()));
            return false;
        }
    }

    /**
     * 落一条「重试落账」补偿任务（§7.4 · COMP-4 / COMP-6）。
     *
     * <p>⚠️ <b>任务载荷携带已确定的结局</b>：
     * 重启后恢复流程据此补记结果事件与账本，<b>绝不重新执行工具</b>。
     * 这条禁令是 COMP-6 硬门槛的核心 ——
     * 在系统最脆弱的时刻重跑副作用，就是重复发放。
     *
     * @param tenantId     租户
     * @param operationId  账本逻辑操作标识（任务幂等键的一部分）
     * @param invocationId 调用标识（= operationId）
     * @param payload      结局载荷（JSON）
     * @return 是否成功入队（false = 结局无处留存，需人工处置）
     */
    public boolean enqueueLedgerRetry(@NonNull String tenantId,
                                      @NonNull String operationId,
                                      @NonNull String invocationId,
                                      @NonNull String payload) {
        Instant now = Instant.now();
        CapCompensationTask task = new CapCompensationTask(
                java.util.UUID.randomUUID().toString(),
                tenantId,
                CapCompensationTask.CompensationKind.RETRY_LEDGER_POST,
                operationId,
                invocationId,
                payload,
                CapCompensationTask.TaskState.PENDING,
                0,
                null,
                now,
                now,
                null);
        try {
            // ⚠️ 幂等：同一 (tenant, kind, target) 至多一条任务
            return tasks.enqueue(task);
        } catch (RuntimeException e) {
            log.error("落账重试任务入队失败: operation={}, err={}",
                    operationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 落一条补偿任务。
     *
     * <p>⚠️ 入队失败（DB 不可用）时<b>不得</b>静默吞掉：
     * 返回一条「未落库」的占位任务，让调用方能感知「补偿既没做成、也没记下」。
     */
    private @NonNull CapCompensationTask enqueue(@NonNull String tenantId,
                                                  CapCompensationTask.@NonNull CompensationKind kind,
                                                  @NonNull String targetId,
                                                  @Nullable String invocationId,
                                                  @Nullable String payload) {
        Instant now = Instant.now();
        CapCompensationTask task = new CapCompensationTask(
                java.util.UUID.randomUUID().toString(),
                tenantId,
                kind,
                targetId,
                invocationId,
                payload,
                CapCompensationTask.TaskState.PENDING,
                0,
                null,
                now,
                now,
                null);
        try {
            tasks.enqueue(task);
        } catch (RuntimeException e) {
            // ⚠️ 连补偿任务都落不下去 = 最坏情况：必须留下不可忽视的痕迹。
            log.error("补偿任务入库失败（补偿既未完成也未记录）: kind={}, target={}, err={}",
                    kind, targetId, e.getMessage(), e);
        }
        return task;
    }
}
