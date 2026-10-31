package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.ApprovalWakeupPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 审批决定唤醒器（M1-6 / G2 · PARK → RESUME 的后半段）。
 *
 * <p>⚠️ <b>为什么必须有它</b>：Run 在工具需要审批时进入
 * {@code WAITING_APPROVAL} 并<b>释放租约让出 worker 线程</b>（PARK）。
 * 人在审批台点了「批准」之后，<b>如果没有代码把 Run 拉回执行</b>，
 * 待办就只是一条永远躺在表里的记录 —— 审批台显示「已批准」，
 * 工具却永远没有执行。这正是 G2「HITL 恒拒」的最后一环。
 *
 * <p>⚠️ <b>恢复绝不重跑模型</b>：本类用
 * {@link RunToolLedgerPort#findInvocation} 按 operationId 反查
 * <b>原始调用</b>（工具名 + 参数 + callId），重建
 * {@link ModelResponse.ToolCallRequest} 后直接重入闸门。
 * 模型不会重现同一个 callId，若让模型重来一次，幂等键会全变，
 * 同一逻辑操作会被<b>执行两次</b>（重复发放 / 重复写入 = Sev-0）。
 *
 * <p>⚠️ <b>fail-closed 清单</b>（每一处都是「不确定即拒绝」）：
 * <ul>
 *   <li>审批行读不到 → 丢弃并告警，<b>不抛</b>（不能让人工审批失败影响接口可用性）；</li>
 *   <li>审批仍是 PENDING → 说明决定尚未落库，丢弃（不该被调用）；</li>
 *   <li>Run 已不在 WAITING_APPROVAL → 空结果属正常（已被唤醒 / 已终态），丢弃不重试；</li>
 *   <li>原始调用反查不到 → <b>绝不臆造参数</b>重入，Run 显式收敛 FAILED。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类不消费 MQ</b>：审批决定由 API 侧
 * （{@code ApprovalController#decide}）在决定落库成功后<b>同步</b>调用
 * {@link #wakeup}。同进程部署下这与「事件驱动」等价，且省去一条
 * MQ 拓扑；崩溃窗口（决定已落库但唤醒未执行）由
 * {@code RunRecoverySweeper} 的 WAITING_APPROVAL 超时兜底收敛，
 * <b>不会造成永久挂起</b>。
 */
public class ApprovalDecidedWakeupConsumer implements ApprovalWakeupPort {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalDecidedWakeupConsumer.class);

    private final ApprovalQueryPort approvals;
    private final RunClaimPort claimPort;
    private final RunToolLedgerPort ledger;
    private final RunExecutionLoop loop;
    private final String workerId;
    private final Duration leaseDuration;

    public ApprovalDecidedWakeupConsumer(@NonNull ApprovalQueryPort approvals,
                                         @NonNull RunClaimPort claimPort,
                                         @NonNull RunToolLedgerPort ledger,
                                         @NonNull RunExecutionLoop loop,
                                         @NonNull String workerId,
                                         @NonNull Duration leaseDuration) {
        this.approvals = approvals;
        this.claimPort = claimPort;
        this.ledger = ledger;
        this.loop = loop;
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
    }

    /**
     * 审批决定落库后唤醒对应的挂起 Run。
     *
     * @param tenantId   租户
     * @param approvalId 审批行标识
     * @return 唤醒结果（供调用方观测；失败不抛异常）
     */
    @Override
    public ApprovalWakeupPort.@NonNull Result wakeup(@NonNull String tenantId,
                                   @NonNull String approvalId) {
        ApprovalQueryPort.ApprovalItem item = approvals.find(tenantId, approvalId);
        if (item == null) {
            // ⚠️ fail-closed：读不到审批行就不猜。丢弃 + 告警，不抛
            //    （人工审批的可用性不应因唤醒失败而崩）。
            log.warn("审批行不可读，放弃唤醒（不抛异常）: tenant={}, approvalId={}",
                    tenantId, approvalId);
            return ApprovalWakeupPort.Result.NOT_FOUND;
        }
        String state = item.state();
        if ("PENDING".equals(state)) {
            // ⚠️ 决定尚未落库 —— 不唤醒（避免用「还没决定」的审批去消费）。
            log.info("审批仍为 PENDING，无需唤醒: tenant={}, approvalId={}",
                    tenantId, approvalId);
            return ApprovalWakeupPort.Result.NOT_DECIDED;
        }

        Optional<RunClaimPort.LeasedTask> leased =
                claimPort.claimParkedRunForResume(tenantId, item.runId(),
                        workerId, leaseDuration);
        if (leased.isEmpty()) {
            // ⚠️ 正常形态：Run 已不在 WAITING_APPROVAL（已被唤醒 / 已终态 / 不存在）。
            //    不得重试（重试 = 反复抢一个已不在挂起态的 Run）。
            log.info("Run 不处于 WAITING_APPROVAL，放弃唤醒: tenant={}, runId={}, state={}",
                    tenantId, item.runId(), state);
            return ApprovalWakeupPort.Result.NOT_PARKED;
        }
        RunClaimPort.LeasedTask task = leased.get();

        Optional<RunToolLedgerPort.Invocation> invocation =
                ledger.findInvocation(tenantId, item.operationId());
        if (invocation.isEmpty()) {
            // ⚠️⚠️ fail-closed：读不到原始调用就**不能**重入闸门 ——
            //    臆造参数等于执行一个从未被审批过的操作。
            //    Run 显式收敛 FAILED，工具未执行，无副作用。
            log.error("无法反查原始调用，Run 显式收敛 FAILED: tenant={}, runId={}, operationId={}",
                    tenantId, item.runId(), item.operationId());
            claimPort.commitResult(tenantId, item.runId(), task.fencingToken(),
                    "FAILED",
                    RunExecutionLoop.failurePayload("resume_invocation_not_found"),
                    "RESUME_INVOCATION_NOT_FOUND");
            return ApprovalWakeupPort.Result.INVOCATION_MISSING;
        }

        RunToolLedgerPort.Invocation inv = invocation.get();
        ModelResponse.ToolCallRequest call = new ModelResponse.ToolCallRequest(
                inv.toolName(), inv.arguments(), inv.callId());

        RunExecutionLoop.ExecutionOutcome outcome =
                loop.resume(tenantId, task, List.of(call));
        log.info("审批决定已消费并恢复执行: tenant={}, runId={}, approvalState={}, outcome={}",
                tenantId, item.runId(), state, outcome.kind());
        return ApprovalWakeupPort.Result.RESUMED;
    }

    /** @return 当前唤醒器持有的 Worker 标识（可观测）。 */
    public @NonNull String workerId() {
        return workerId;
    }

    /** @return 恢复租约时长（可观测）。 */
    public @NonNull Duration leaseDuration() {
        return leaseDuration;
    }
}
