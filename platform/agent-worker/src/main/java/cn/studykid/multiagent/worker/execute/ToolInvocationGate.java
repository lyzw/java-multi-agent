package cn.studykid.multiagent.worker.execute;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
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
 * <p>⚠️ <b>禁止使用 Spring AI {@code ToolCallingAdvisor} 的自动执行路径</b>——
 * 它会绕过上述全部闸门（ADR-011 §3.3）。本类是唯一入口。
 */
public class ToolInvocationGate {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationGate.class);

    private final List<ToolHandler> handlers;
    private final AuthorizationService authorization;
    private final ApprovalPolicy approvalPolicy;
    private final BudgetReserver budget;

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetReserver budget) {
        this.handlers = List.copyOf(handlers);
        this.authorization = authorization;
        this.approvalPolicy = approvalPolicy;
        this.budget = budget;
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

            // ⑥ 预算预留
            if (!budget.reserve(tenantId, task.runId(), call.toolName())) {
                log.warn("预算不足，拒绝工具: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "预算不足");
                continue;
            }

            // ⑦ 执行
            ToolHandler.ToolExecutionContext ctx = new ToolHandler.ToolExecutionContext(
                    tenantId, task.runId(), "worker",
                    idemKey, task.attempt());

            try {
                ToolHandler.ToolResult toolResult = handler.execute(ctx, call.arguments());
                result.recordSucceeded(call.toolName(), toolResult);

            } catch (cn.studykid.multiagent.ports.tool.ToolExecutionException e) {
                // ⚠️ §3 三种异常语义 —— 最易出现的 Sev-0
                if (e.isResultUnknown()) {
                    // 结果未知：可能已产生副作用
                    // ⚠️ 不释放预算预留（成本可能已发生），不重试
                    log.warn("工具结果未知，转对账: tool={}, runId={}",
                            call.toolName(), task.runId());
                    result.recordUnknown(call.toolName(), idemKey);
                    // ⚠️ 立即返回：UNKNOWN 后不得继续执行后续工具
                    return result;
                }
                // 确定失败：无副作用，可重试
                log.info("工具确定失败: tool={}, err={}", call.toolName(), e.getMessage());
                result.recordFailed(call.toolName(), e.getMessage());

            } catch (RuntimeException e) {
                // ⚠️ 未预期异常按「结果未知」处理 —— 保守假设可能有副作用。
                //    若按确定失败处理而实际已写入，会导致重复写入 = Sev-0
                log.error("工具抛出未预期异常，保守按 UNKNOWN 处理: tool={}",
                        call.toolName(), e);
                result.recordUnknown(call.toolName(), idemKey);
                return result;
            }
        }

        return result;
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

    /** 预算预留。 */
    @FunctionalInterface
    public interface BudgetReserver {
        /** @return 是否预留成功（false 表示预算不足）。 */
        boolean reserve(@NonNull String tenantId, @NonNull String runId,
                        @NonNull String toolName);
    }
}