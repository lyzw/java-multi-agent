package cn.studykid.multiagent.worker.execute;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 工具调用闸门（P2-D03 §4 / FR-008 / P0-D03 §4 红线 9）。
 *
 * <p><b>⚠️ 本类是不可绕过的安全边界</b>。执行链必须为：
 * <pre>
 *   Schema 校验 → <b>执行时重新授权</b> → 幂等键 → 账本 → 审批 → 预算 → 执行 → 账本更新
 * </pre>
 *
 * <p><b>预算环节的三条规则</b>（P3-D04）：
 * <ol>
 *   <li><b>预留失败即拒绝</b>（fail-closed）。降级放行等于
 *       「预算系统故障时无限花钱」——这正是 P1-10 记录的 Sev-0 类；</li>
 *   <li><b>预留失败记 FAILED 而非 UNKNOWN</b>：工具根本没发出，
 *       结果确定是「未执行」。记 UNKNOWN 会凭空造出待对账的幽灵操作；</li>
 *   <li><b>UNKNOWN 绝不释放预留</b>（R-007）：成本可能已发生，
 *       只有确定失败（FAILED）才退回额度。</li>
 * </ol>
 *
 * <p>⚠️ <b>禁止使用 Spring AI {@code ToolCallingAdvisor} 的自动执行路径</b>——
 * 它会绕过上述全部闸门（ADR-011 §3.3）。本类是唯一入口。
 */
public class ToolInvocationGate {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationGate.class);

    private final List<ToolHandler> handlers;
    private final AuthorizationService authorization;
    private final ApprovalPolicy approvalPolicy;
    private final BudgetPort budget;
    private final RunToolLedgerPort ledger;
    /**
     * 单次工具调用的预算预留额（P3-D04 §2.6 D-6）。
     *
     * <p>⚠️ <b>构造期校验，不接受非正数</b>：缺失/非法配置在装配时就失败，
     * 而不是等到某个工具调用时才被拒——后者会让「预算闸门形同虚设」
     * 在测试中不易察觉。
     */
    private final java.math.BigDecimal reserveAmountPerToolCall;

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger) {
        this(handlers, authorization, approvalPolicy, budget, ledger,
                cn.studykid.multiagent.application.config.BudgetPolicy.DEFAULT_RESERVE_AMOUNT);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              java.math.BigDecimal reserveAmountPerToolCall) {
        this.handlers = List.copyOf(handlers);
        this.authorization = authorization;
        this.approvalPolicy = approvalPolicy;
        this.budget = budget;
        this.ledger = ledger;
        if (reserveAmountPerToolCall == null
                || reserveAmountPerToolCall.signum() <= 0) {
            throw new IllegalArgumentException(
                    "单次工具调用的预算预留额必须为正数（缺失即 fail-closed，"
                            + "禁止退回默认值放行）。收到：" + reserveAmountPerToolCall);
        }
        this.reserveAmountPerToolCall = reserveAmountPerToolCall;
    }

    /**
     * 执行模型请求的全部工具调用。
     *
     * @param tenantId 租户（由消息携带，须与 DB 上下文一致）
     * @param task     已领取任务
     * @param calls    模型请求的工具调用
     * @return 汇总结果
     */
    public ToolInvocationResult invokeAll(
            String tenantId,
            RunClaimPort.LeasedTask task,
            Map<String, ModelResponse.ToolCallRequest> calls) {

        ToolInvocationResult result = new ToolInvocationResult();

        for (ModelResponse.ToolCallRequest call : calls.values()) {
            ToolHandler handler = findHandler(call.toolName());
            if (handler == null) {
                // 未登记工具：拒绝，并把错误回给模型让它自我纠正
                // ⚠️ 不静默忽略 —— 模型可能因此陷入循环
                log.warn("未登记的工具被拒绝: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "未登记的工具");
                continue;
            }

            // ① Schema 校验（失败不执行）
            try {
                handler.validate(call.arguments());
            } catch (RuntimeException e) {
                log.info("Schema 校验失败，拒绝执行: tool={}, err={}",
                        call.toolName(), e.getMessage());
                result.recordRejected(call.toolName(), "Schema 校验失败");
                continue;
            }

            // ② ⚠️ 执行时重新授权（红线 9）—— 不是创建 Run 时授权一次就够
            if (!authorization.isAuthorized(systemContext(tenantId),
                    AuthorizationService.Actions.TOOL_INVOKE, call.toolName())) {
                log.warn("工具执行授权被拒: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "无权调用该工具");
                continue;
            }

            // ③ 幂等键（ADR-003）
            String idemKey = buildIdempotencyKey(call);

            // ④ ⚠️ 账本登记（P3-D03）：失败即拒绝执行（fail-closed）。
            //    无账可对的调用，其 UNKNOWN 无处安放——对账无从做起。
            RunToolLedgerPort.Registration registration;
            try {
                registration = ledger.register(tenantId, task.runId(),
                        call.toolName(), handler.riskLevel().name(),
                        idemKey, String.valueOf(call.arguments()),
                        call.arguments(), task.fencingToken());
            } catch (RuntimeException e) {
                log.error("账本登记失败，fail-closed 拒绝执行: tool={}, runId={}",
                        call.toolName(), task.runId(), e);
                result.recordRejected(call.toolName(), "账本登记失败");
                continue;
            }

            // ⑤ 审批检查
            ApprovalPolicy.ApprovalDecision approval = approvalPolicy.evaluate(handler);
            if (approval.requiresApproval()) {
                // ⚠️ TODO(P3): 审批流未实现。
                //    当前仅支持「不需审批」的工具。
                //    ⚠️⚠️ 若某工具从 OPTIONAL 改为 REQUIRED，
                //    此处必须拒绝而非静默通过 —— 否则变成「静默跳过审批」的安全漏洞。
                //    临时用 fail-closed 保证安全：需要审批时直接拒绝。
                log.warn("工具需要审批但审批流未实现，fail-closed 拒绝: tool={}",
                        call.toolName());
                result.recordRejected(call.toolName(), "需要审批（暂不支持）");
                continue;
            }

            // ⑥ ⚠️ 预算预留（P3-D04）：失败即拒绝执行（fail-closed）。
            //    预留失败 ≠ 结果未知 —— 工具**根本没有发出**，
            //    故账本记 FAILED 而非 UNKNOWN（后者会凭空造出待对账的幽灵操作）。
            String reservationId;
            try {
                reservationId = reserveBudget(tenantId, task, registration,
                        call.toolName(), result);
            } catch (RuntimeException e) {
                // ⚠️ 预留异常同样 fail-closed：降级放行等于「预算系统故障时无限花钱」
                log.error("预算预留异常，fail-closed 拒绝执行: tool={}, runId={}",
                        call.toolName(), task.runId(), e);
                recordFailed(tenantId, registration.operationId(), task.fencingToken(),
                        "预算预留异常");
                result.recordRejected(call.toolName(), "预算预留异常");
                continue;
            }
            if (reservationId == null) {
                // ⚠️ 已记录拒绝原因与账本状态，继续下一个工具
                continue;
            }

            // ⑦ 执行
            ToolHandler.ToolExecutionContext ctx = new ToolHandler.ToolExecutionContext(
                    tenantId, task.runId(), "worker",
                    idemKey, task.attempt());

            try {
                ToolHandler.ToolResult toolResult = handler.execute(ctx, call.arguments());
                // ⑧ 账本记结果（P3-D03）：账本失败会让执行循环按 UNKNOWN 处置（fail-closed）
                ledger.recordResult(tenantId, registration.operationId(),
                        ToolOperationState.SUCCEEDED, task.fencingToken(), null, null);
                // ⚠️ 结算放在账本之后（D-4）：账本是事实源，
                //    结算失败不能反过来改写「工具已成功」这一事实
                settleQuietly(tenantId, reservationId, call.toolName());
                result.recordSucceeded(call.toolName(), toolResult);

            } catch (cn.studykid.multiagent.ports.tool.ToolExecutionException e) {
                // ⚠️ §3 三种异常语义 —— 最易出现的 Sev-0
                if (e.isResultUnknown()) {
                    // 结果未知：可能已产生副作用
                    // ⚠️⚠️ 不释放预算预留（成本可能已发生，R-007），不重试
                    log.warn("工具结果未知，落账转对账: tool={}, runId={}, operation={}",
                            call.toolName(), task.runId(), registration.operationId());
                    ledger.recordResult(tenantId, registration.operationId(),
                            ToolOperationState.UNKNOWN, task.fencingToken(), null,
                            e.getMessage());
                    result.recordUnknown(call.toolName(), registration.operationId());
                    // ⚠️ 立即返回：UNKNOWN 后不得继续执行后续工具
                    return result;
                }
                // 确定失败：无副作用，可重试 → 预算额度可安全退回
                log.info("工具确定失败: tool={}, err={}", call.toolName(), e.getMessage());
                ledger.recordResult(tenantId, registration.operationId(),
                        ToolOperationState.FAILED, task.fencingToken(),
                        e.getClass().getSimpleName(), e.getMessage());
                releaseQuietly(tenantId, reservationId,
                        "tool-failed:" + e.getClass().getSimpleName());
                result.recordFailed(call.toolName(), e.getMessage());

            } catch (RuntimeException e) {
                // ⚠️ 未预期异常按「结果未知」处理 —— 保守假设可能有副作用。
                //    若按确定失败处理而实际已写入，会导致重复写入 = Sev-0
                log.error("工具抛出未预期异常，保守按 UNKNOWN 落账: tool={}",
                        call.toolName(), e);
                ledger.recordResult(tenantId, registration.operationId(),
                        ToolOperationState.UNKNOWN, task.fencingToken(), null,
                        String.valueOf(e.getMessage()));
                result.recordUnknown(call.toolName(), registration.operationId());
                return result;
            }
        }

        return result;
    }

    // ── 预算辅助（P3-D04）──────────────────────────────────────────

    /**
     * 预留预算。
     *
     * @param result 汇总结果；被拒时已写入拒绝原因
     * @return 预留 ID；<b>null 表示已被拒绝</b>（此时账本已记 FAILED）
     * @throws RuntimeException 预留层异常 —— 调用方按 fail-closed 处理
     */
    private String reserveBudget(String tenantId, RunClaimPort.LeasedTask task,
                                 RunToolLedgerPort.Registration registration,
                                 String toolName,
                                 ToolInvocationResult result) {
        BudgetPort.ReserveOutcome outcome = budget.reserve(
                tenantId, task.runId(), registration.operationId(), reserveAmountPerToolCall);

        if (outcome == BudgetPort.ReserveOutcome.RESERVED
                || outcome == BudgetPort.ReserveOutcome.REPLAYED) {
            // ⚠️ REPLAYED 也返回预留 ID：同 operation 重复调用复用既有预留，
            //    这是「预留有唯一记录」在执行链上的体现
            return budget.findByOperation(tenantId, registration.operationId())
                    .map(BudgetPort.Reservation::reservationId)
                    .orElse(null);
        }

        log.warn("预算预留被拒: tool={}, runId={}, outcome={}",
                toolName, task.runId(), outcome);
        // ⚠️ INSUFFICIENT 与 NO_ACCOUNT 严格区分：
        //    前者要加额度，后者要**开通账户** —— 运维处置方向完全不同
        String reason = switch (outcome) {
            case INSUFFICIENT -> "预算不足";
            case NO_ACCOUNT -> "未开通预算账户";
            case FAIL_CLOSED -> "预算配置缺失";
            default -> "预算预留被拒";
        };
        recordFailed(tenantId, registration.operationId(), task.fencingToken(),
                "预算预留被拒:" + outcome);
        result.recordRejected(toolName, reason);
        return null;
    }

    /**
     * 结算预留（失败不抛）。
     *
     * <p>⚠️ 结算失败<b>不得</b>改写工具结局：工具确实成功了，
     * 成本也真实发生了。结算失败留下 RESERVED 预留，
     * 由预算对账（{@code findOrphaned}）兜底处置。
     */
    private void settleQuietly(String tenantId, String reservationId, String toolName) {
        if (reservationId == null) {
            return;
        }
        try {
            // ⚠️ 按预留额全额结算：工具调用无按量计费明细，
            //    真实成本由 usage_record 独立记录（P3-D04 §6 L-5）
            if (!budget.settle(tenantId, reservationId, reserveAmountPerToolCall, toolName)) {
                log.warn("预算结算未生效（预留不存在或已终态），将由对账兜底: "
                        + "tool={}, reservation={}", toolName, reservationId);
            }
        } catch (RuntimeException e) {
            log.error("预算结算异常，预留保持 RESERVED 待对账: tool={}, reservation={}",
                    toolName, reservationId, e);
        }
    }

    /** 释放预留（失败不抛）。 */
    private void releaseQuietly(String tenantId, String reservationId, String reason) {
        if (reservationId == null) {
            return;
        }
        try {
            budget.release(tenantId, reservationId, reason);
        } catch (RuntimeException e) {
            log.error("预算释放异常，额度仍被占用待对账: reservation={}", reservationId, e);
        }
    }

    /**
     * 账本记 FAILED（供「预留失败 / 审批拒绝」等未执行分支复用）。
     *
     * <p>⚠️ 记 FAILED 而非 UNKNOWN：这些分支里工具<b>根本没有发出</b>，
     * 结果是确定的「未执行」。记 UNKNOWN 会造出待对账的幽灵操作。
     */
    private void recordFailed(String tenantId, String operationId,
                              long fencingToken, String errorMessage) {
        try {
            ledger.recordResult(tenantId, operationId, ToolOperationState.FAILED,
                    fencingToken, "BudgetGate", errorMessage);
        } catch (RuntimeException e) {
            log.error("账本记 FAILED 失败: operation={}", operationId, e);
        }
    }

    private ToolHandler findHandler(String toolName) {
        return handlers.stream()
                .filter(h -> h.toolName().equals(toolName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构造幂等键。
     *
     * <p>⚠️ 需按工具类型区分：`record_weakness` 用
     * {@code student_id + run_id + knowledge_point}（ADR-003），
     * 其他工具用 {@code run_id + tool_name + 调用序号}。
     */
    private String buildIdempotencyKey(ModelResponse.ToolCallRequest call) {
        Object studentId = call.arguments().get("studentId");
        Object knowledgePoint = call.arguments().get("knowledgePoint");

        if (studentId != null && knowledgePoint != null) {
            return IdempotencyKey.forRecordWeakness(
                    String.valueOf(studentId), "pending-run",
                    String.valueOf(knowledgePoint)).composite();
        }
        return IdempotencyKey.forRunCreate(
                call.toolName() + "-" + Optional.ofNullable(call.callId()).orElse("no-call-id"))
                .composite();
    }

    private cn.studykid.multiagent.core.tenant.TenantContext systemContext(String tenantId) {
        return new cn.studykid.multiagent.core.tenant.TenantContext(
                tenantId, "worker", java.util.Set.of(), 0L);
    }

    /** 工具调用汇总结果。 */
    public static final class ToolInvocationResult {

        private final java.util.List<String> succeeded = new java.util.ArrayList<>();
        private final java.util.List<String> rejected = new java.util.ArrayList<>();
        private final java.util.List<String> failed = new java.util.ArrayList<>();
        private String unknownTool;
        private String unknownOperationId;

        void recordSucceeded(String tool, ToolHandler.ToolResult r) {
            succeeded.add(tool);
        }

        void recordRejected(String tool, String reason) {
            rejected.add(tool);
        }

        void recordFailed(String tool, String reason) {
            failed.add(tool);
        }

        void recordUnknown(String tool, String operationId) {
            this.unknownTool = tool;
            this.unknownOperationId = operationId;
        }

        /** @return 是否出现 UNKNOWN（须停止执行并转对账）。 */
        public boolean unknownResultOccurred() {
            return unknownTool != null;
        }

        public String unknownOperationId() {
            if (unknownOperationId == null) {
                throw new IllegalStateException("无 UNKNOWN 操作");
            }
            return unknownOperationId;
        }

        /** @return 是否全部被拒（无成功也无失败）。 */
        public boolean allRejected() {
            return succeeded.isEmpty() && failed.isEmpty() && !unknownResultOccurred();
        }

        public int succeededCount() {
            return succeeded.size();
        }

        public int rejectedCount() {
            return rejected.size();
        }

        public int failedCount() {
            return failed.size();
        }
    }

    /** 审批策略（DEC-11）。 */
    @FunctionalInterface
    public interface ApprovalPolicy {
        /** @return 决策；默认不需审批（生产实现按 riskLevel 决定）。 */
        ApprovalDecision evaluate(ToolHandler handler);

        /** 审批决策。 */
        record ApprovalDecision(boolean requiresApproval, String reason) {
            public static ApprovalDecision notRequired() {
                return new ApprovalDecision(false, null);
            }

            public static ApprovalDecision required(String reason) {
                return new ApprovalDecision(true, reason);
            }
        }
    }
}
