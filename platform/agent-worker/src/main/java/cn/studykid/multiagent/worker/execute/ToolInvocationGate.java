package cn.studykid.multiagent.worker.execute;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
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

    /**
     * 审批结论来源（P4-D01 / E-2）。
     *
     * <p>⚠️ <b>默认恒 {@code FAIL_CLOSED}</b>：未装配真实来源时，
     * 需要审批的工具一律被拒——与 D5 的默认行为一致，
     * 但路径从「恒拒」变为「向权威查询后拒绝」，且<b>已可被真实审批驱动</b>。
     */
    private final RiskBasedApprovalPolicy.ApprovalSource approvalSource;
    private final BudgetPort budget;
    private final RunToolLedgerPort ledger;
    /**
     * 审批消费端口（P3-D05 / FR-013）。
     *
     * <p>⚠️ <b>默认桩 = 恒返回 {@code NO_APPROVAL}</b>：在审批权威（人类审批台 / 策略引擎）
     * 接入前，需要审批的工具一律被拒——这与既有 fail-closed 行为一致，
     * 只是路径从「恒拒」变为「查不到有效审批即拒」，且具备了被真实审批驱动的能力。
     */
    private final ApprovalPort approvalPort;
    /**
     * 取消守卫（P3-D06 / FR-011）。
     *
     * <p>⚠️ <b>默认桩 = 永不取消</b>：在取消链路/Worker 主循环装配前不改变既有行为，
     * 装配为 {@code RunClaimPort.isCancelled} 后即生效。每工具开头检查，
     * 配合循环级检查（RunExecutionLoop）构成双保险，保证「取消后不启动新工具动作」。
     */
    private final CancelGuard cancelGuard;
    /**
     * 单次工具调用的预算预留额（P3-D04 §2.6 D-6）。
     *
     * <p>⚠️ <b>构造期校验，不接受非正数</b>：缺失/非法配置在装配时就失败，
     * 而不是等到某个工具调用时才被拒——后者会让「预算闸门形同虚设」
     * 在测试中不易察觉。
     */
    private final java.math.BigDecimal reserveAmountPerToolCall;

    /**
     * CAP 授权链路编排器（P2 · §5.3 唯一协议）。
     *
     * <p>⚠️ <b>可为空 = CAP 链路未装配</b>：此时本类走既有链路。
     * 开关（{@code CapEnforcementSwitch}）位于编排器内部，
     * 因此「开关开启却未装配编排器」这一配置错误<b>不可能</b>发生 ——
     * 不存在「以为走了新链路、其实没走」的静默降级。
     */
    private final cn.studykid.multiagent.application.cap.CapExecutionFlow capFlow;

    /**
     * MCP-backed 工具处理器的动态解析器（P4 · ADR-011 / 07 §3）。
     *
     * <p>⚠️ <b>可为空 = MCP 未装配</b>：此时只有静态注册的原生工具可用。
     * MCP 工具<b>不另开执行入口</b>——它与原生工具共用本类这一条路径，
     * 否则八道闸门对 MCP 全部失效。
     */
    private final cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers;

    /** 默认审批桩：恒返回 NO_APPROVAL（审批权威接入前的 fail-closed 占位）。 */
    private static final ApprovalPort NO_APPROVAL_PORT = new ApprovalPort() {
        @Override
        public ApprovalOutcome consume(String tenantId, String operationId,
                                       String argumentDigest, String targetResource,
                                       String runId) {
            return ApprovalOutcome.NO_APPROVAL;
        }

        @Override
        public String grant(String tenantId, String operationId, String argumentDigest,
                            String targetResource,
                            cn.studykid.multiagent.core.config.ApprovalRequirement requirement,
                            String approverScope, java.time.Instant expiresAt,
                            String approverId) {
            throw new UnsupportedOperationException("默认审批桩不支持授予");
        }

        @Override
        public void revoke(String tenantId, String operationId, String approverId,
                           String reason) {
            throw new UnsupportedOperationException("默认审批桩不支持撤销");
        }

        @Override
        public java.util.Optional<ApprovalView> findActive(String tenantId,
                                                          String operationId) {
            return java.util.Optional.empty();
        }
    };

    /**
     * 默认审批结论来源：恒 {@code FAIL_CLOSED}。
     *
     * <p>⚠️ 未装配真实审批权威时，需要审批的工具一律被拒——
     * 这是 fail-closed 的正确方向，绝不能默认 {@code APPROVED}。
     */
    private static final RiskBasedApprovalPolicy.ApprovalSource DENY_ALL_APPROVALS =
            (tenantId, handler) -> cn.studykid.multiagent.application.approval
                    .RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED;

    /** 默认取消守卫：永不取消（取消链路未接入前的占位）。 */
    private static final CancelGuard NO_CANCEL = (tenantId, runId) -> false;

    /** 取消守卫（P3-D06 / FR-011）：判断指定 Run 是否已取消。 */
    @FunctionalInterface
    public interface CancelGuard {
        boolean isCancelled(String tenantId, String runId);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger) {
        this(handlers, authorization, approvalPolicy, budget, ledger, NO_APPROVAL_PORT,
                cn.studykid.multiagent.application.config.BudgetPolicy.DEFAULT_RESERVE_AMOUNT);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              java.math.BigDecimal reserveAmountPerToolCall) {
        this(handlers, authorization, approvalPolicy, budget, ledger, NO_APPROVAL_PORT,
                reserveAmountPerToolCall);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                cn.studykid.multiagent.application.config.BudgetPolicy.DEFAULT_RESERVE_AMOUNT,
                NO_CANCEL);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort,
                              java.math.BigDecimal reserveAmountPerToolCall) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, NO_CANCEL);
    }

    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort,
                              java.math.BigDecimal reserveAmountPerToolCall,
                              @NonNull CancelGuard cancelGuard) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, DENY_ALL_APPROVALS);
    }

    /**
     * 完整构造器（P4-D01）：额外注入审批结论来源。
     *
     * <p>⚠️ 构造器数量已多，**新增依赖一律加在最完整的那一个**，
     * 其余保持不变并委托——避免再次出现「两个构造器导致 Spring 无法选择」（F-6）。
     */
    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort,
                              java.math.BigDecimal reserveAmountPerToolCall,
                              @NonNull CancelGuard cancelGuard,
                              RiskBasedApprovalPolicy.ApprovalSource approvalSource) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, null);
    }

    /**
     * 最完整构造器（P2-T1）：额外注入 CAP 授权链路编排器。
     *
     * <p>⚠️ {@code capFlow} 为 {@code null} 表示「CAP 链路未装配」，
     * 本类走既有链路；装配后由 {@code CapEnforcementSwitch} 决定实际走哪条。
     */
    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort,
                              java.math.BigDecimal reserveAmountPerToolCall,
                              @NonNull CancelGuard cancelGuard,
                              RiskBasedApprovalPolicy.ApprovalSource approvalSource,
                              cn.studykid.multiagent.application.cap.CapExecutionFlow capFlow) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, capFlow, null);
    }

    /**
     * 最完整构造器（P4）：额外注入 MCP-backed 工具解析器。
     *
     * <p>⚠️ <b>MCP 工具不另开执行入口</b>（ADR-011 / 07 §3）：
     * 它们与原生工具共用本类这一条路径，八道闸门一视同仁。
     */
    public ToolInvocationGate(@NonNull List<ToolHandler> handlers,
                              @NonNull AuthorizationService authorization,
                              @NonNull ApprovalPolicy approvalPolicy,
                              @NonNull BudgetPort budget,
                              @NonNull RunToolLedgerPort ledger,
                              @NonNull ApprovalPort approvalPort,
                              java.math.BigDecimal reserveAmountPerToolCall,
                              @NonNull CancelGuard cancelGuard,
                              RiskBasedApprovalPolicy.ApprovalSource approvalSource,
                              cn.studykid.multiagent.application.cap.CapExecutionFlow capFlow,
                              cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers) {
        this.handlers = List.copyOf(handlers);
        this.authorization = authorization;
        this.approvalPolicy = approvalPolicy;
        this.budget = budget;
        this.ledger = ledger;
        this.approvalPort = approvalPort;
        this.cancelGuard = cancelGuard;
        this.approvalSource = approvalSource;
        this.capFlow = capFlow;
        this.mcpHandlers = mcpHandlers;
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
     * <p>⚠️ <b>唯一执行入口</b>（07 §3 / ADR-011）：所有 Native 与 MCP-backed 工具
     * 都必须经由本方法进入，禁止业务代码直接调用 {@code ToolHandler.execute}。
     *
     * <p>P2 起本方法有<b>两条内部链路</b>，由 CAP 迁移开关决定：
     * <ul>
     *   <li><b>CAP 链路</b>（开关开启）：按 §5.3 唯一协议编排
     *       Phase A（能力链授权）→ Phase B（执行准入），产出四字段结论；</li>
     *   <li><b>既有链路</b>（开关关闭或未装配）：原有实现，行为不变。</li>
     * </ul>
     * 两条链路<b>都在本类内部</b>，不存在第三条路径。
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

        if (capFlow != null && capFlow.isEnabled()) {
            return invokeAllCap(tenantId, task, calls);
        }
        return invokeAllLegacy(tenantId, task, calls);
    }

    /**
     * CAP 授权链路（P2-T1 · §5.3 唯一协议）。
     *
     * <p>⚠️ <b>取消检查在 Phase A 之前</b>：取消是 Run 的状态而非授权结论，
     * 且「取消后不得发起任何副作用」是硬约束 —— 把它排在授权判定之前，
     * 才能保证被取消的 Run 连能力链解析都不必做。
     */
    private ToolInvocationResult invokeAllCap(
            String tenantId,
            RunClaimPort.LeasedTask task,
            Map<String, ModelResponse.ToolCallRequest> calls) {

        ToolInvocationResult result = new ToolInvocationResult();

        for (ModelResponse.ToolCallRequest call : calls.values()) {
            ToolHandler handler = findHandler(call.toolName());
            if (handler == null) {
                // 未登记工具：拒绝并把错误回给模型让它自我纠正。
                // ⚠️ 不静默忽略 —— 模型可能因此陷入循环。
                log.warn("未登记的工具被拒绝: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "未登记的工具");
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        call.toolName(), null,
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                                cn.studykid.multiagent.core.cap.ReasonCode.TOOL_NOT_DECLARED)));
                continue;
            }

            if (cancelGuard.isCancelled(tenantId, task.runId())) {
                log.info("Run 已取消，拒绝启动工具: tool={}, runId={}",
                        call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "Run 已取消");
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        call.toolName(), null,
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                                cn.studykid.multiagent.core.cap.ReasonCode.RUN_CANCELLED)));
                continue;
            }

            cn.studykid.multiagent.core.cap.CapToolDecision decision =
                    capFlow.invoke(tenantId, task, call, handler);
            result.recordDecision(decision);
            applyDecision(result, decision);

            if (decision.requiresReconciliation()) {
                // ⚠️ UNKNOWN 后立即返回：后续工具不得再启动
                //    （可能已有副作用发生，且需要人工/对账介入）。
                return result;
            }
        }

        return result;
    }

    /**
     * 把四字段决策落到汇总结果。
     *
     * <p>⚠️ 映射规则与 §5.1 一致：
     * {@code SUCCEEDED} → 成功；{@code FAILED} → 失败；
     * {@code UNKNOWN} → 结果未知（须转对账）；{@code NOT_STARTED} → 被拒。
     */
    private static void applyDecision(ToolInvocationResult result,
                                      cn.studykid.multiagent.core.cap.CapToolDecision decision) {
        var status = decision.decision().status();
        switch (status) {
            case SUCCEEDED -> result.recordSucceeded(decision.toolName());
            case FAILED -> result.recordFailed(decision.toolName(),
                    decision.decision().reasonCode().name());
            case UNKNOWN -> result.recordUnknown(decision.toolName(), decision.operationId());
            case NOT_STARTED, RUNNING -> result.recordRejected(decision.toolName(),
                    decision.decision().reasonCode().name());
        }
    }

    /**
     * 既有执行链路（P2 之前的实现，行为保持不变）。
     *
     * <p>⚠️ 本链路的顺序<b>不是</b> §5.3 的顺序（Schema 先于授权、账本 PRE 先于审批），
     * 它是 CAP 授权链路落地前的既有行为。开关开启后本方法<b>不被调用</b>。
     */
    private ToolInvocationResult invokeAllLegacy(
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

            // ②b ⚠️ 取消检查（P3-D06 / FR-011）：闸门级双保险。
            //    即使循环级检查已放过当前轮（取消在模型返回后到达），
            //    本检查保证批次内的后续工具不再启动。取消后不得发起任何副作用。
            if (cancelGuard.isCancelled(tenantId, task.runId())) {
                log.info("Run 已取消，拒绝启动工具: tool={}, runId={}",
                        call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "Run 已取消");
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
            // ⚠️ 必须传真实 runId：键缺 run 维度会跨 Run 撞键（P4-D08 R-2）
            String idemKey = buildIdempotencyKey(task.runId(), call);

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

            // ⑤ ⚠️⚠️ 幂等命中闸门：既有操作**不得再次执行**（防重复副作用 = Sev-0）
            //
            //    ⚠️ 此闸门是修复前的**真实缺陷**：账本登记在键已存在时返回
            //    **既有 operation 及其当前状态**，而此前**从不检查该状态**，
            //    于是流程径直走向 ⑦ 执行 → 同一逻辑操作产生**两次副作用**
            //    （重复发放 / 重复写入）；随后 ⑧ 记结果时因状态已非 IN_FLIGHT
            //    抛 IllegalStateTransitionException，而 catch 块里的兜底
            //    recordResult 又抛一次 —— **异常从 catch 内部逃出闸门**，
            //    Run 被标记 FAILED 且账本说 SUCCEEDED（两个事实互相矛盾），
            //    同时没有任何对账记录。
            //
            //    ⚠️ 为什么必须看 createdByThisCall 而不是只看状态：
            //    既有操作的状态**可能仍是 IN_FLIGHT**（上次调用崩在中途，
            //    或另一 worker 正在执行）——只看状态会把「本次新建」误判为
            //    「命中既有」，从而**连正常调用也执行不了**。
            if (!registration.createdByThisCall()) {
                ToolOperationState existingState = registration.state();
                log.warn("幂等键命中既有操作，不重复执行: tool={}, runId={}, operation={}, state={}",
                        call.toolName(), task.runId(), registration.operationId(), existingState);
                switch (existingState) {
                    case SUCCEEDED, RECONCILED_SUCCEEDED -> {
                        // ⚠️ 重放：上一次已成功。不执行工具，按「已成功」记账。
                        //    （原始载荷不在本路径可得，故只记成功不记载荷。）
                        result.recordSucceeded(call.toolName());
                    }
                    case UNKNOWN, RECONCILED_ABSENT, MANUAL_DISPOSED -> {
                        // ⚠️ 终态且结果未知/已处置：**不得盲目重试**，
                        //    转对账（记账行已由恢复流程负责）。
                        result.recordUnknown(call.toolName(), registration.operationId());
                        return result;
                    }
                    case FAILED -> {
                        // ⚠️ 工具明确失败（无副作用），但账本行已终态，
                        //    而 recordResult 只接受 IN_FLIGHT —— 不能复用该行。
                        //    故此处不执行；确需重试须用**新的幂等键**
                        //    （即新的逻辑操作），否则记结果会被状态机拒绝。
                        result.recordFailed(call.toolName(),
                                "幂等键命中已失败的既有操作，未重复执行");
                    }
                    case IN_FLIGHT, PENDING -> {
                        // ⚠️ 既有操作仍在途：可能崩在中途，或另一 worker 正在执行。
                        //    此时再次执行 = 重复副作用；按「结果未知」交对账。
                        result.recordUnknown(call.toolName(), registration.operationId());
                        return result;
                    }
                    default -> {
                        result.recordRejected(call.toolName(),
                                "幂等键命中未预期的既有操作状态：" + existingState);
                        return result;
                    }
                }
                continue;
            }

            // ⑥ 审批消费（P3-D05 / FR-013）
            //    ⚠️ 审批绑定参数摘要与目标资源、过期/撤销拒绝、消费与取消检查原子协调、
            //       崩溃后可恢复，全部由 ApprovalPort 在 DB 事务内保证。
            ApprovalPolicy.ApprovalDecision approval = approvalPolicy.evaluate(handler);
            if (approval.requiresApproval()) {
                // ⚠️ 先问审批权威（E-2 / P4-D01）：拿不到「明确批准」即拒绝。
                //    FAIL_CLOSED（来源不可用）同样拒绝——不可用 ≠ 可放行。
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict verdict;
                try {
                    verdict = approvalSource.verdictFor(tenantId, handler);
                } catch (RuntimeException e) {
                    // ⚠️ 权威异常同样 fail-closed（异常不得被当作「无异议」）
                    log.error("审批权威异常，fail-closed 拒绝: tool={}, tenant={}",
                            call.toolName(), tenantId, e);
                    verdict = cn.studykid.multiagent.application.approval
                            .RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED;
                }
                if (verdict != cn.studykid.multiagent.application.approval
                        .RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED) {
                    log.warn("审批权威未批准，fail-closed 拒绝: tool={}, runId={}, verdict={}",
                            call.toolName(), task.runId(), verdict);
                    result.recordRejected(call.toolName(), "审批未批准:" + verdict);
                    continue;
                }
                // ⚠️ 参数摘要与目标资源须与审批授予时绑定的一致（防篡改）：
                //    argumentDigest 与 ledger 的 requestDigest 同源（均来自 call.arguments()），
                //    targetResource 取工具名。
                String argumentDigest = String.valueOf(call.arguments());
                String targetResource = call.toolName();
                ApprovalPort.ApprovalOutcome outcome = approvalPort.consume(
                        tenantId, registration.operationId(), argumentDigest,
                        targetResource, task.runId());
                if (outcome != ApprovalPort.ApprovalOutcome.CONSUMED) {
                    // ⚠️ 任何非 CONSUMED 结果都 fail-closed 拒绝。
                    //    需要审批但查不到有效审批（NO_APPROVAL / EXPIRED / REJECTED /
                    //    已被取消的 Run / 参数被篡改）一律不得执行 ——
                    //    否则变成「静默跳过审批」的安全漏洞（Sev-0）。
                    log.warn("审批消费未通过，fail-closed 拒绝: tool={}, runId={}, outcome={}",
                            call.toolName(), task.runId(), outcome);
                    result.recordRejected(call.toolName(), "审批未通过:" + outcome);
                    continue;
                }
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
                    recordResultQuietly(tenantId, registration.operationId(),
                            ToolOperationState.UNKNOWN, task.fencingToken(), null,
                            e.getMessage());
                    result.recordUnknown(call.toolName(), registration.operationId());
                    // ⚠️ 立即返回：UNKNOWN 后不得继续执行后续工具
                    return result;
                }
                // 确定失败：无副作用，可重试 → 预算额度可安全退回
                log.info("工具确定失败: tool={}, err={}", call.toolName(), e.getMessage());
                recordResultQuietly(tenantId, registration.operationId(),
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
                recordResultQuietly(tenantId, registration.operationId(),
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

    /**
     * 记结果且<b>不让异常逃出</b>（专供 catch 块内部使用）。
     *
     * <p><b>⚠️ 为什么 catch 块里的记账必须吞异常</b>：
     * 修复前，catch 块里的 {@code ledger.recordResult(...)} 会再抛一次
     * （典型场景：上一次调用已把操作记成终态，本次
     * {@code recordResult(SUCCEEDED)} 抛 IllegalStateTransitionException，
     * 进入 catch 后 {@code recordResult(UNKNOWN)} 又因状态非 IN_FLIGHT 再抛）——
     * <b>异常从 catch 块内部逃出闸门</b>，一路冒泡到执行循环，
     * Run 被标记 FAILED 且账本说 SUCCEEDED（两个事实互相矛盾），
     * 同时<b>没有任何对账记录</b>（兜底记账本身就是失败的那一次）。
     *
     * <p>处置：catch 路径里的记账失败<b>只记日志</b>，
     * 闸门照常返回 UNKNOWN 交由对账流程处置 ——
     * 记账失败本身就是「结果未知」的信号，不该在这里再抛一次。
     *
     * @param state 目标状态
     */
    private void recordResultQuietly(String tenantId, String operationId,
                                     ToolOperationState state, long fencingToken,
                                     String errorClass, String errorMessage) {
        try {
            ledger.recordResult(tenantId, operationId, state, fencingToken,
                    errorClass, errorMessage);
        } catch (RuntimeException e) {
            log.error("账本记账失败（异常处理路径，不再上抛，转对账）: operation={}, state={}, err={}",
                    operationId, state, e.toString());
        }
    }

    private ToolHandler findHandler(String toolName) {
        ToolHandler registered = handlers.stream()
                .filter(h -> h.toolName().equals(toolName))
                .findFirst()
                .orElse(null);
        if (registered != null) {
            return registered;
        }
        // ⚠️ MCP 工具走**同一条**路径（ADR-011）：这里只做「解析」，
        //    后续的 Schema 校验 / 重授权 / 审批 / 预算 / 账本 / 准入 / 意图 / 落盘
        //    与原生工具完全一致 —— 不为 MCP 开任何旁路。
        if (mcpHandlers == null) {
            return null;
        }
        return mcpHandlers.resolve(toolName).orElse(null);
    }

    /**
     * 构造幂等键。
     *
     * <p>⚠️ 需按工具类型区分：`record_weakness` 用
     * {@code student_id + run_id + knowledge_point}（ADR-003），
     * 其他工具用 {@code run_id + tool_name + call_id}（ADR-003 / P4-D08）。
     *
     * <p>⚠️⚠️ <b>runId 必须是真实 Run 标识（P4-D08 R-2 修复）</b>：
     * {@code tool_operation} 的唯一约束是 {@code UNIQUE (tenant_id, idempotency_key)}——
     * <b>租户级，不含 run 维度</b>。而账本命中重复键时<b>复用既有 operation</b>（不新建）。
     * 若键中缺 runId（或填占位符），则同租户下<b>不同 Run 的同名工具 + 同 callId 会撞键</b>，
     * 新 Run 的调用被记到旧 Run 的 operation 上 → 对账链路错乱、副作用语义破裂。
     *
     * <p>⚠️⚠️ <b>「按工具分流」的口径收在 {@link IdempotencyKey#forToolCall}</b>（P1-9 修复）：
     * 修复前本方法只看「参数里有没有 {@code studentId} + {@code knowledgePoint}」，
     * 于是<b>任意</b>带这两个参数的工具都被塞进 {@code tool.record_weakness} 命名空间 ——
     * 两个不同工具算出同一个键 → 后者被判「同键不同参数」而拒绝执行，
     * 或被判「重放」而静默不执行。
     */
    private String buildIdempotencyKey(String runId, ModelResponse.ToolCallRequest call) {
        Object studentId = call.arguments().get("studentId");
        Object knowledgePoint = call.arguments().get("knowledgePoint");
        return IdempotencyKey.forToolCall(runId, call.toolName(), call.callId(),
                        studentId == null ? null : String.valueOf(studentId),
                        knowledgePoint == null ? null : String.valueOf(knowledgePoint))
                .composite();
    }

    private cn.studykid.multiagent.core.tenant.TenantContext systemContext(String tenantId) {
        // ⚠️⚠️ **必须携带 WORKER 角色**（P4-D08 R-4 修复）：
        //    授权按**角色**判定（RoleBasedAuthorizationService: roles → 动作集合），
        //    空角色集 → isAuthorized 恒 false → 工具执行时必被「无权调用该工具」拦下。
        //
        //    Roles.WORKER 本就含 TOOL_INVOKE（权限模型为 Worker 设计的既有角色），
        //    且本次修复**未扩大**该角色的动作集合。
        //    修复前 Worker 上下文无任何角色 = 授权链路根本没接上，
        //    那不是更严的安全姿态，而是「看起来有防线、实际不存在」。
        return new cn.studykid.multiagent.core.tenant.TenantContext(
                tenantId, "worker",
                java.util.Set.of(cn.studykid.multiagent.ports.security.AuthorizationService
                        .Roles.WORKER),
                0L);
    }

    /** 工具调用汇总结果。 */
    public static final class ToolInvocationResult {

        private final java.util.List<String> succeeded = new java.util.ArrayList<>();
        private final java.util.List<String> rejected = new java.util.ArrayList<>();
        private final java.util.List<String> failed = new java.util.ArrayList<>();
        /**
         * 每次调用的四字段决策（P2 · §5.1）。
         *
         * <p>⚠️ <b>既有链路下为空</b>：既有链路不产出结构化决策，
         * 只有 CAP 链路才按 §9.1 逐字段断言。
         */
        private final java.util.List<cn.studykid.multiagent.core.cap.CapToolDecision> decisions =
                new java.util.ArrayList<>();
        private String unknownTool;
        private String unknownOperationId;

        void recordSucceeded(String tool, ToolHandler.ToolResult r) {
            succeeded.add(tool);
        }

        void recordSucceeded(String tool) {
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

        /**
         * 记录一次调用的四字段决策（P2）。
         *
         * @param decision 决策记录
         */
        void recordDecision(cn.studykid.multiagent.core.cap.CapToolDecision decision) {
            decisions.add(decision);
        }

        /**
         * 每次调用的四字段决策（不可变副本）。
         *
         * <p>⚠️ 既有链路下为空列表（该链路不产出结构化决策）。
         */
        public java.util.List<cn.studykid.multiagent.core.cap.CapToolDecision> decisions() {
            return java.util.List.copyOf(decisions);
        }

        /**
         * 按工具名取决策（测试与决策日志用）。
         *
         * @param toolName 工具名
         * @return 该工具的决策；无则空
         */
        public java.util.Optional<cn.studykid.multiagent.core.cap.CapToolDecision> decisionOf(
                String toolName) {
            return decisions.stream()
                    .filter(d -> d.toolName().equals(toolName))
                    .findFirst();
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

        /**
         * 成功执行的工具名（不可变副本）。
         *
         * <p>⚠️ P4-D12 / G4：执行事件需要<b>工具名</b>（SSE 白名单字段 {@code toolName}），
         * 此前本类只暴露计数 → 调用方拿不到名字。
         * 计数用于「统计」，名字用于「**说清发生了什么**」，二者不可互相替代。
         */
        public java.util.List<String> succeededTools() {
            return java.util.List.copyOf(succeeded);
        }

        /** 被拒绝的工具名（不可变副本）。 */
        public java.util.List<String> rejectedTools() {
            return java.util.List.copyOf(rejected);
        }

        /** 执行失败的工具名（不可变副本）。 */
        public java.util.List<String> failedTools() {
            return java.util.List.copyOf(failed);
        }

        /** 结果未知的工具名；无 UNKNOWN 时为 {@code null}。 */
        public String unknownToolName() {
            return unknownTool;
        }

        public int rejectedCount() {
            return rejected.size();
        }

        public int failedCount() {
            return failed.size();
        }
    }

}
