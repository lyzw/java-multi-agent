package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;
import cn.studykid.multiagent.ports.persistence.ExecutionIntentPort;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 结果事件恢复服务（W-PERM-P3-T6 · §5.3.4 阶段、持久化事实与恢复 · §7.4 双维度结果）。
 *
 * <p>⚠️ <b>本类绝不调用工具</b>。它只做一件事：
 * <b>把已经确定的结果补记到账本</b>（§5.3.4「只重试落账，禁止再次 execute」）。
 *
 * <p>⚠️ <b>为什么这条禁令是硬门槛</b>（COMP-6）：
 * 「确定成功 → 结果持久化失败 → 进程重启」这一崩溃窗口里，
 * 恢复方<b>唯一</b>能做的正确动作是「补记已知结果」。
 * 若恢复逻辑退化「重跑一次调用看看」，
 * 就等于在系统最脆弱的时刻制造<b>重复副作用</b> ——
 * 对「给学发奖励」这类工具，这直接是资损 + 重复发放。
 *
 * <p>⚠️ <b>恢复的两个入口</b>：
 * <ol>
 *   <li><b>补偿任务</b>（{@code RETRY_LEDGER_POST}）——
 *       进程在「结果已定、落账未成」时把完整结局写进补偿任务再退出，
 *       重启后由此重放。<b>这是 COMP-6 的主路径</b>：</b>
 *       即使结果事件本身也没写成功，结局仍然在补偿任务里；</li>
 *   <li><b>未终结的执行意图</b>—— 结果事件已存在（说明 14a 成功过），
 *       但账本仍是 IN_FLIGHT（说明 15 失败过），据此补记落账。</li>
 * </ol>
 *
 * <p>⚠️ <b>UNKNOWN 一律不自动收敛</b>：
 * 结果不确定意味着「副作用可能已发生」，
 * 自动把它判成成功/失败都是撒谎 —— 只能进对账（§7.4 / §9.2 COMP-5）。
 */
public class ResultRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ResultRecoveryService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExecutionIntentPort intents;
    private final ResultEventPort resultEvents;
    private final RunToolLedgerPort ledger;
    private final CompensationTaskPort tasks;
    private final CapApprovalPort approvals;

    public ResultRecoveryService(@NonNull ExecutionIntentPort intents,
                                 @NonNull ResultEventPort resultEvents,
                                 @NonNull RunToolLedgerPort ledger,
                                 @NonNull CompensationTaskPort tasks,
                                 @NonNull CapApprovalPort approvals) {
        this.intents = intents;
        this.resultEvents = resultEvents;
        this.ledger = ledger;
        this.tasks = tasks;
        this.approvals = approvals;
    }

    /**
     * 恢复报告。
     *
     * @param retriedLedger  补记落账成功次数
     * @param pendingUnknown 仍需人工对账的 UNKNOWN 次数
     * @param failed         恢复过程中失败的次数
     */
    public record Report(int retriedLedger, int pendingUnknown, int failed) {
    }

    /**
     * 执行一轮恢复（可重复调用，幂等）。
     *
     * @param tenantId 租户
     * @param limit    单轮处理上限
     * @return 恢复报告
     */
    public @NonNull Report recover(@NonNull String tenantId, int limit) {
        int retried = 0;
        int unknown = 0;
        int failed = 0;

        // ── 入口一：补偿任务（COMP-6 主路径）────────────────────────
        List<CapCompensationTask> pending = tasks.findPending(tenantId, limit);
        for (CapCompensationTask task : pending) {
            if (task.kind() != CapCompensationTask.CompensationKind.RETRY_LEDGER_POST) {
                continue;
            }
            try {
                Outcome outcome = readOutcome(task);
                if (outcome == null) {
                    log.warn("补偿任务载荷不可解析，标记失败待重试: task={}", task.taskId());
                    tasks.markFailed(tenantId, task.taskId(), "payload-unparseable",
                            Instant.now().plusSeconds(60));
                    failed++;
                    continue;
                }
                boolean done = applyOutcome(tenantId, outcome);
                if (done) {
                    tasks.markCompleted(tenantId, task.taskId());
                    retried++;
                } else {
                    // ⚠️ 结局已定但本次未落上 → 下轮再试，**绝不**重新执行副作用
                    tasks.markFailed(tenantId, task.taskId(), "ledger-post-pending",
                            Instant.now().plusSeconds(30));
                    failed++;
                }
            } catch (RuntimeException e) {
                log.error("补偿任务恢复失败: task={}, err={}", task.taskId(), e.getMessage(), e);
                tasks.markFailed(tenantId, task.taskId(), String.valueOf(e.getMessage()),
                        Instant.now().plusSeconds(60));
                failed++;
            }
        }

        // ── 入口二：未终结的执行意图 ─────────────────────────────────
        List<ExecutionIntent> pendingIntents = intents.findPending(tenantId, limit);
        for (ExecutionIntent intent : pendingIntents) {
            Optional<ResultEvent> event =
                    resultEvents.findByInvocation(tenantId, intent.invocationId());
            if (event.isEmpty()) {
                // ⚠️ 无结果事件 = 结局未知（可能未发出、也可能已发出但结果未落盘）。
                //    §5.3.4：默认禁止自动重发 → 只能进对账。
                //    ⚠️ 这里<b>不</b>把账本改写成 UNKNOWN：
                //    账本的 UNKNOWN 表示「副作用可能已发生」，
                //    而「无结果事件」连这个都不能断定 —— 改写会凭空造出幽灵对账项。
                //    保持 IN_FLIGHT + 意图未终结，交由对账流程按下游痕迹判定。
                unknown++;
                continue;
            }
            ResultEvent e = event.get();
            if (e.requiresReconciliation()) {
                // ⚠️ UNKNOWN 不自动收敛（§7.4）：只统计，留给对账
                unknown++;
                continue;
            }
            try {
                boolean done = postLedger(tenantId, intent.invocationId(),
                        e.resultStatus(), e.resultPayload(), e.reasonCode());
                if (done) {
                    retried++;
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                log.error("意图恢复落账失败: intent={}, err={}",
                        intent.invocationId(), ex.getMessage(), ex);
                failed++;
            }
        }

        return new Report(retried, unknown, failed);
    }

    /**
     * 把已知结局补记到账本（<b>不执行工具</b>）。
     *
     * @param tenantId     租户
     * @param invocationId 调用标识（= 账本 operationId）
     * @param status       结局
     * @param payload      结果载荷
     * @param reasonCode   原因码
     * @return 是否已落账（true = 已终态或本次落成）
     */
    private boolean postLedger(@NonNull String tenantId,
                               @NonNull String invocationId,
                               ResultEvent.@NonNull Status status,
                               @Nullable String payload,
                               @Nullable String reasonCode) {
        Optional<RunToolLedgerPort.Registration> registration =
                ledger.findByOperationId(tenantId, invocationId);
        if (registration.isEmpty()) {
            // ⚠️ 账本里没有这个操作 → 说明连 PRE 都没写成（更早的崩溃），
            //    无可落账，也不该凭空造一个操作。
            log.warn("账本中查无此操作，跳过落账: operation={}", invocationId);
            return true;
        }
        ToolOperationState current = registration.get().state();
        if (current != ToolOperationState.IN_FLIGHT) {
            // ⚠️ 已是终态 → 幂等命中（可能已被并发恢复处理过）
            consumeApproval(tenantId, invocationId, current.name());
            return true;
        }
        ToolOperationState target = switch (status) {
            case SUCCEEDED -> ToolOperationState.SUCCEEDED;
            case FAILED -> ToolOperationState.FAILED;
            case UNKNOWN -> ToolOperationState.UNKNOWN;
        };
        ledger.recordResult(tenantId, invocationId, target,
                registration.get().fencingToken(), reasonCode, payload);
        consumeApproval(tenantId, invocationId, target.name());
        return true;
    }

    /**
     * 把已知结局应用到账本与审批（补偿任务路径）。
     */
    private boolean applyOutcome(@NonNull String tenantId, @NonNull Outcome outcome) {
        // ⚠️ 先补写结果事件（幂等：先写入者为准）——
        //    事件是「确定结果」的存储层证据，账本是它的下游。
        ResultEvent event = new ResultEvent(
                UUID.randomUUID().toString(),
                tenantId,
                outcome.runId(),
                outcome.invocationId(),
                null,
                outcome.status(),
                outcome.resultPayload(),
                outcome.reasonCode(),
                Instant.now());
        try {
            resultEvents.recordIfAbsent(event);
        } catch (RuntimeException e) {
            // ⚠️ 事件写不进去**不阻断**落账：账本才是对账的事实源，
            //    且落账成功后仍有补偿任务可继续补写事件。
            log.warn("结果事件补写失败（继续落账）: invocation={}, err={}",
                    outcome.invocationId(), e.getMessage());
        }
        return postLedger(tenantId, outcome.invocationId(), outcome.status(),
                outcome.resultPayload(), outcome.reasonCode());
    }

    /**
     * 审批与终态关联（步骤 15 的补偿面）。
     *
     * <p>⚠️ 审批消费失败不阻断：审批的终态不影响「副作用是否已发生」这一事实，
     *    账本才是。审批会停在 INTENT_SUBMITTED，由对账流程处置。
     */
    private void consumeApproval(@NonNull String tenantId,
                                  @NonNull String invocationId,
                                  @NonNull String terminalStatus) {
        try {
            Optional<CapApproval> approval = approvals.findByInvocation(tenantId, invocationId);
            if (approval.isEmpty()) {
                return;
            }
            approvals.consume(tenantId, approval.get().approvalId(), invocationId, terminalStatus);
        } catch (RuntimeException e) {
            log.warn("审批消费失败（账本已是事实源，留待对账）: invocation={}, err={}",
                    invocationId, e.getMessage());
        }
    }

    // ── 补偿任务载荷 ────────────────────────────────────────────────

    /**
     * 已知结局（补偿任务载荷）。
     *
     * @param runId         关联 Run
     * @param invocationId  调用标识
     * @param status        结局
     * @param resultPayload 结果载荷
     * @param reasonCode    原因码
     */
    public record Outcome(@Nullable String runId,
                           @NonNull String invocationId,
                           ResultEvent.@NonNull Status status,
                           @Nullable String resultPayload,
                           @Nullable String reasonCode) {
    }

    /**
     * 把已知结局编码为补偿任务载荷。
     *
     * @param outcome 结局
     * @return JSON 文本
     */
    public static @NonNull String encodeOutcome(@NonNull Outcome outcome) {
        ObjectNode root = MAPPER.createObjectNode();
        if (outcome.runId() != null) {
            root.put("runId", outcome.runId());
        }
        root.put("invocationId", outcome.invocationId());
        root.put("status", outcome.status().name());
        if (outcome.resultPayload() != null) {
            root.put("resultPayload", outcome.resultPayload());
        }
        if (outcome.reasonCode() != null) {
            root.put("reasonCode", outcome.reasonCode());
        }
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 解析补偿任务载荷。
     *
     * @param task 补偿任务
     * @return 结局；载荷损坏时为 {@code null}
     */
    @Nullable
    private Outcome readOutcome(@NonNull CapCompensationTask task) {
        return decodeOutcome(task.payload());
    }

    /**
     * 解析补偿任务载荷。
     *
     * @param payload JSON 文本
     * @return 结局；载荷损坏时为 {@code null}
     */
    @Nullable
    public static Outcome decodeOutcome(@Nullable String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode invocation = root.get("invocationId");
            JsonNode status = root.get("status");
            if (invocation == null || status == null) {
                return null;
            }
            JsonNode runId = root.get("runId");
            JsonNode resultPayload = root.get("resultPayload");
            JsonNode reason = root.get("reasonCode");
            return new Outcome(
                    runId == null || runId.isNull() ? null : runId.asString(),
                    invocation.asString(),
                    ResultEvent.Status.valueOf(status.asString()),
                    resultPayload == null || resultPayload.isNull() ? null : resultPayload.asString(),
                    reason == null || reason.isNull() ? null : reason.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
