package cn.studykid.multiagent.application.cap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.ExecutionIntentPort;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.security.RolePermissionPort;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * CAP 执行准入编排器（P2-T1 · §5.3 唯一协议 · §7.2 编排 · 07 §2 调用契约顺序）。
 *
 * <p>本类把 §5.3 的步骤 1–15 落成一条<b>唯一</b>的执行准入链，
 * 产出 §5.1 的四字段结论（{@link CapToolDecision}）。
 *
 * <p>⚠️ <b>顺序是契约，不是实现细节</b>：§5.3 原文写明「本小节为全文档唯一顺序与语义」。
 * 本类<b>逐条对应</b>下列步骤，任何一步的插入/前移都必须先改契约：
 * <pre>
 *   步骤 1  构建可信上下文（租户/主体/Run 来自可信来源，不取客户端输入）
 *   步骤 2  PrincipalAuthorized（角色 + 权威 authzVersion 强一致读）
 *   步骤 3  PermissionResolver → EffectiveClosure
 *   步骤 4  AgentSkillAllowed → SkillToolAllowed → RiskWithinCeiling
 *   步骤 5  MCPBindingAllowed（§5.8 顺序）
 *   步骤 6  ParamPolicyPassed
 *   步骤 7  ConfigFingerprintValid
 *   步骤 8  幂等键校验
 *   步骤 9  SchemaValid（结构，仅一次）
 *   步骤 10 账本登记（PRE）
 *   步骤 11 Approval
 *   步骤 12 BudgetOk
 *   步骤 13 ExecutionAdmissionCheck（TOCTOU）
 *   步骤 13a 持久化执行意图（必须先于外部调用）
 *   步骤 13b 审批 → INTENT_SUBMITTED
 *   步骤 14 ToolHandler.execute
 *   步骤 14a 确定结果先入可恢复存储
 *   步骤 15 账本 POST
 * </pre>
 *
 * <p>⚠️ <b>与 §5.3 字面顺序的一处偏离（账本登记前移）——有外键强制依据</b>：
 * §5.3 把「账本 PRE」列在步骤 12（审批、预算之后），
 * 但既有表结构要求 {@code approval.operation_id} 与
 * {@code budget_reservation.operation_id} 都指向 {@code tool_operation}：
 * <ul>
 *   <li>{@code approval_operation_fk}：{@code FOREIGN KEY (tenant_id, operation_id)
 *       REFERENCES tool_operation (tenant_id, operation_id)}——审批记录必须引用一个
 *       <b>已存在</b>的逻辑操作；</li>
 *   <li>{@code part_uniq_reservation_operation}：预算预留按 {@code operation_id} 唯一，
 *       且悬挂预留扫描靠它与 {@code tool_operation} 做 JOIN。</li>
 * </ul>
 * 审批消费与预算预留都必须拿到 {@code operationId}，
 * 而 {@code operationId} 由账本登记产生 → <b>账本登记必然先于审批与预算</b>。
 * 本类据此把「步骤 10」定为账本登记，并保持
 * <b>审批 → 预算</b>的相对顺序不变（§5.3 的补偿链依赖它）。
 * 其余全部语义（尤其「Schema 失败不写 PRE」「准入失败释放预算」）完全保持。
 *
 * <p>⚠️ <b>审批在本阶段是过渡语义</b>：§5.3.3 的
 * {@code PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED} 状态机与 §5.3.1 的双哈希
 * 属 <b>P3</b>。P2 复用既有的 {@link ApprovalPort#consume}（一次性消费），
 * 其代价是<b>准入失败时审批已被消费、无法取消</b>（§5.3 步骤 13 要求「cancel 审批」）。
 * 该差距已登记为 P3 交接项。
 *
 * <p>⚠️ <b>本类无副作用地做判定，有副作用地做编排</b>：
 * 授权判定（步骤 2–7）委托 {@link InvocationValidator}（P1，无副作用）；
 * 副作用（账本/审批/预算/意图/结果事件）由本类按 §5.3 的顺序发起。
 */
public class CapExecutionFlow {

    private static final Logger log = LoggerFactory.getLogger(CapExecutionFlow.class);

    /** 默认主体：Worker 服务账号（与既有闸门的授权上下文一致）。 */
    public static final String DEFAULT_PRINCIPAL_ID = "worker";

    private final InvocationValidator validator;
    private final ClosureResolver closureResolver;
    private final ExecutionAdmissionChecker admission;
    private final IdempotencyGuard idempotency;
    private final AuthzVersionPort authzVersions;
    private final RolePermissionPort rolePermissions;
    private final CapabilitySnapshotPort snapshots;
    private final ApprovalPolicy approvalPolicy;
    private final RiskBasedApprovalPolicy.ApprovalSource approvalSource;
    private final ApprovalPort approvalPort;
    private final BudgetPort budget;
    private final RunToolLedgerPort ledger;
    private final ExecutionIntentPort intents;
    private final ResultEventPort resultEvents;
    private final BigDecimal reserveAmountPerToolCall;
    private final String principalId;
    private final CapEnforcementSwitch enforcementSwitch;

    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                              RiskBasedApprovalPolicy.@NonNull ApprovalSource approvalSource,
                             @NonNull ApprovalPort approvalPort,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalSource, approvalPort, budget, ledger, intents,
                resultEvents, reserveAmountPerToolCall, DEFAULT_PRINCIPAL_ID,
                new CapEnforcementSwitch(false));
    }

    /**
     * 带迁移开关的构造器（装配用）。
     *
     * @param enforcementSwitch CAP 授权链路开关（默认关闭；P5 之前不得在生产开启）
     */
    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                             RiskBasedApprovalPolicy.@NonNull ApprovalSource approvalSource,
                             @NonNull ApprovalPort approvalPort,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull CapEnforcementSwitch enforcementSwitch) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalSource, approvalPort, budget, ledger, intents,
                resultEvents, reserveAmountPerToolCall, DEFAULT_PRINCIPAL_ID, enforcementSwitch);
    }

    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                              RiskBasedApprovalPolicy.@NonNull ApprovalSource approvalSource,
                             @NonNull ApprovalPort approvalPort,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull String principalId) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalSource, approvalPort, budget, ledger, intents,
                resultEvents, reserveAmountPerToolCall, principalId,
                new CapEnforcementSwitch(false));
    }

    /**
     * 完整构造器（含迁移开关）。
     *
     * @param enforcementSwitch CAP 授权链路开关（默认关闭；P5 之前不得在生产开启）
     */
    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                             RiskBasedApprovalPolicy.@NonNull ApprovalSource approvalSource,
                             @NonNull ApprovalPort approvalPort,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull String principalId,
                             @NonNull CapEnforcementSwitch enforcementSwitch) {
        if (reserveAmountPerToolCall == null || reserveAmountPerToolCall.signum() <= 0) {
            // ⚠️ 构造期校验：缺失/非法配置在装配时就失败，
            //    而不是等某个工具调用时才被拒 —— 那会让「预算闸门形同虚设」。
            throw new IllegalArgumentException(
                    "单次工具调用的预算预留额必须为正数，收到: " + reserveAmountPerToolCall);
        }
        this.validator = validator;
        this.closureResolver = closureResolver;
        this.admission = admission;
        this.idempotency = idempotency;
        this.authzVersions = authzVersions;
        this.rolePermissions = rolePermissions;
        this.snapshots = snapshots;
        this.approvalPolicy = approvalPolicy;
        this.approvalSource = approvalSource;
        this.approvalPort = approvalPort;
        this.budget = budget;
        this.ledger = ledger;
        this.intents = intents;
        this.resultEvents = resultEvents;
        this.reserveAmountPerToolCall = reserveAmountPerToolCall;
        this.principalId = principalId;
        this.enforcementSwitch = enforcementSwitch;
    }

    /**
     * @return CAP 授权链路是否已启用（迁移开关）
     */
    public boolean isEnabled() {
        return enforcementSwitch.isEnabled();
    }

    // ── 主入口 ────────────────────────────────────────────────────

    /**
     * 执行一次工具调用的完整准入链（§5.3 步骤 1–15）。
     *
     * @param tenantId 租户
     * @param task     已领取任务（提供 runId / fencingToken / attempt）
     * @param call     模型请求的工具调用
     * @param handler  目标工具处理器
     * @return 四字段决策结论
     */
    public @NonNull CapToolDecision invoke(@NonNull String tenantId,
                                            RunClaimPort.@NonNull LeasedTask task,
                                            ModelResponse.@NonNull ToolCallRequest call,
                                           @NonNull ToolHandler handler) {

        // ══ 步骤 1–7：Phase A（授权）══════════════════════════════
        PhaseA phaseA = phaseA(tenantId, task, call);
        if (phaseA.denial() != null) {
            return new CapToolDecision(call.toolName(), null, phaseA.denial());
        }

        // ══ 步骤 8：幂等键校验 ════════════════════════════════════
        String idempotencyKey = buildIdempotencyKey(task.runId(), call);
        String requestDigest = InvocationDigest.of(call.arguments());

        IdempotencyGuard.Result idem =
                idempotency.check(tenantId, idempotencyKey, requestDigest);
        if (idem.outcome() != IdempotencyGuard.Outcome.PROCEED) {
            // ⚠️ 已完成 → 返回首次结果；进行中 / 冲突 / 存储不可用 → 拒绝。
            //    四种情形都<b>不</b>重新执行副作用。
            AuthorizationDecision decision = idem.decision();
            return new CapToolDecision(call.toolName(), idem.operationId(),
                    decision == null
                            ? AuthorizationDecision.executionDeny(ReasonCode.IDEMPOTENCY_UNAVAILABLE)
                            : decision);
        }

        // ══ 步骤 9：SchemaValid（结构校验，仅一次）════════════════
        try {
            handler.validate(call.arguments());
        } catch (RuntimeException e) {
            // ⚠️ 失败 → DENY(PARAM_SCHEMA_FAIL)；不预占审批、不预留预算、不写 PRE。
            log.info("Schema 校验失败，拒绝执行: tool={}, run={}, err={}",
                    call.toolName(), task.runId(), e.getMessage());
            return new CapToolDecision(call.toolName(), null,
                    AuthorizationDecision.executionDeny(ReasonCode.PARAM_SCHEMA_FAIL));
        }

        // ══ 步骤 10：账本登记（PRE）═══════════════════════════════
        RunToolLedgerPort.Registration registration;
        try {
            registration = ledger.register(tenantId, task.runId(), call.toolName(),
                    handler.riskLevel().name(), idempotencyKey, requestDigest,
                    call.arguments(), task.fencingToken());
        } catch (RuntimeException e) {
            // ⚠️ 账本登记失败 → 拒绝执行（fail-closed）。
            //    账本同时是幂等存储，故复用 IDEMPOTENCY_UNAVAILABLE 码。
            log.error("账本登记失败，fail-closed 拒绝执行: tool={}, run={}, err={}",
                    call.toolName(), task.runId(), e.getMessage());
            return new CapToolDecision(call.toolName(), null,
                    AuthorizationDecision.executionDeny(ReasonCode.IDEMPOTENCY_UNAVAILABLE));
        }
        String operationId = registration.operationId();

        // ══ 步骤 11：Approval ═════════════════════════════════════
        ApprovalGate approvalGate = approve(tenantId, task, call, handler, operationId,
                requestDigest);
        if (approvalGate.denial() != null) {
            recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                    "approval:" + approvalGate.denial().reasonCode());
            return new CapToolDecision(call.toolName(), operationId, approvalGate.denial());
        }

        // ══ 步骤 12：BudgetOk ═════════════════════════════════════
        String reservationId;
        try {
            BudgetPort.ReserveOutcome outcome = budget.reserve(tenantId, task.runId(),
                    operationId, reserveAmountPerToolCall);
            if (!outcome.granted()) {
                log.warn("预算预留被拒: tool={}, run={}, outcome={}",
                        call.toolName(), task.runId(), outcome);
                recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                        "budget:" + outcome);
                return new CapToolDecision(call.toolName(), operationId,
                        AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
            }
            reservationId = budget.findByOperation(tenantId, operationId)
                    .map(BudgetPort.Reservation::reservationId)
                    .orElse(null);
        } catch (RuntimeException e) {
            // ⚠️ 预留异常同样 fail-closed：降级放行等于「预算系统故障时无限花钱」。
            log.error("预算预留异常，fail-closed 拒绝执行: tool={}, run={}, err={}",
                    call.toolName(), task.runId(), e.getMessage());
            recordLedgerFailed(tenantId, operationId, task.fencingToken(), "budget:exception");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
        }
        if (reservationId == null) {
            // ⚠️ 预留成功但查不到预留记录 → 无法在终态结算/释放 → fail-closed。
            recordLedgerFailed(tenantId, operationId, task.fencingToken(), "budget:unconfirmed");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
        }

        // ══ 步骤 13：ExecutionAdmissionCheck（TOCTOU）═════════════
        ExecutionAdmissionChecker.AdmissionOutcome admissionOutcome = admission.check(
                new ExecutionAdmissionChecker.AdmissionRequest(
                        tenantId, principalId, phaseA.agentId(), task.runId(),
                        phaseA.authzVersion(), phaseA.fingerprint()));
        if (!admissionOutcome.admitted()) {
            // ⚠️ 准入失效 → 释放预算 + PRE 标失败，阻断新副作用（§5.3 步骤 13）。
            //    「cancel 审批」在 P2 不可实现（审批已被一次性消费），
            //    该差距由 P3 的审批状态机补齐（见类注释）。
            releaseQuietly(tenantId, reservationId, "exec-admission-stale");
            recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                    "admission:" + admissionOutcome.reasonCode());
            log.warn("执行准入失效，阻断新副作用: tool={}, run={}, reason={}",
                    call.toolName(), task.runId(), admissionOutcome.reasonCode());
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(admissionOutcome.reasonCode()));
        }

        // ══ 步骤 13a：持久化执行意图（必须先于外部调用）══════════
        ExecutionIntent intent = new ExecutionIntent(
                UUID.randomUUID().toString(),
                tenantId,
                task.runId(),
                operationId,
                call.toolName(),
                phaseA.toolVersion(),
                requestDigest,
                approvalGate.approvalId(),
                ExecutionIntent.IntentState.INTENT_SUBMITTED,
                Instant.now(),
                Instant.now());
        try {
            intents.record(intent);
        } catch (RuntimeException e) {
            // ⚠️ 意图写不进去 → 不得执行。
            //    否则崩溃后无从判断副作用是否已发生，只能全部转人工对账。
            log.error("执行意图持久化失败，fail-closed 拒绝执行: tool={}, run={}, err={}",
                    call.toolName(), task.runId(), e.getMessage());
            releaseQuietly(tenantId, reservationId, "intent-persist-failed");
            recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                    "intent-persist-failed");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.EXECUTION_PERSISTENCE_FAILED));
        }

        // ══ 步骤 13b：审批 RESERVED → INTENT_SUBMITTED ═══════════
        try {
            approvalPort.markIntentSubmitted(tenantId, operationId);
        } catch (RuntimeException e) {
            // ⚠️ 记账失败不阻断执行：审批在步骤 11 已 fail-closed 校验通过，
            //    本步骤只是状态记账（P3 落地状态机后才有可迁移的状态）。
            log.warn("审批意图状态标记失败（不影响放行）: tool={}, run={}, err={}",
                    call.toolName(), task.runId(), e.getMessage());
        }

        // ══ 步骤 14：执行 ═════════════════════════════════════════
        ToolHandler.ToolExecutionContext ctx = new ToolHandler.ToolExecutionContext(
                tenantId, task.runId(), principalId, idempotencyKey, task.attempt());

        try {
            ToolHandler.ToolResult toolResult = handler.execute(ctx, call.arguments());

            // ── 步骤 14a：确定结果先入可恢复存储，再更新 executionStatus ──
            boolean eventWritten = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.SUCCEEDED, toolResult.output(), ReasonCode.SUCCESS);

            // ── 步骤 15：账本 POST ──
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.SUCCEEDED, null, null);

            AuthorizationDecision.Ledger ledgerStatus = ledgerStatus(eventWritten, posted);
            if (ledgerStatus == AuthorizationDecision.Ledger.RECORDED) {
                consumeIntent(tenantId, operationId);
            }
            // ⚠️ 结算放在落账之后（§5.3 §7.4）：账本是事实源，
            //    结算失败不能反过来改写「工具已成功」这一事实。
            settleQuietly(tenantId, reservationId, call.toolName());
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionSucceeded(ledgerStatus));

        } catch (ToolExecutionException e) {
            if (e.isResultUnknown()) {
                // ⚠️ 结果未知：可能已产生副作用。
                //    不释放预算预留（成本可能已发生），不重试，进对账。
                log.warn("工具结果未知，落账转对账: tool={}, run={}, operation={}",
                        call.toolName(), task.runId(), operationId);
                boolean eventWritten = persistResult(tenantId, task, operationId,
                        ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
                boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                        ToolOperationState.UNKNOWN, null, e.getMessage());
                return new CapToolDecision(call.toolName(), operationId,
                        AuthorizationDecision.executionUnknown(ledgerStatus(eventWritten, posted),
                                ReasonCode.EXEC_UNKNOWN));
            }
            // 确定失败：无副作用，可重试 → 预算额度可安全退回。
            log.info("工具确定失败: tool={}, err={}", call.toolName(), e.getMessage());
            boolean eventWritten = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.FAILED, null, ReasonCode.TOOL_EXECUTION_FAILED);
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.FAILED, e.getClass().getSimpleName(), e.getMessage());
            releaseQuietly(tenantId, reservationId,
                    "tool-failed:" + e.getClass().getSimpleName());
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionFailed(ledgerStatus(eventWritten, posted),
                            ReasonCode.TOOL_EXECUTION_FAILED));

        } catch (RuntimeException e) {
            // ⚠️ 未预期异常按「结果未知」处理 —— 保守假设可能有副作用。
            //    若按确定失败处理而实际已写入，会导致重复写入 = Sev-0。
            log.error("工具抛出未预期异常，保守按 UNKNOWN 落账: tool={}", call.toolName(), e);
            boolean eventWritten = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.UNKNOWN, null, String.valueOf(e.getMessage()));
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionUnknown(ledgerStatus(eventWritten, posted),
                            ReasonCode.EXEC_UNKNOWN));
        }
    }

    // ── Phase A（步骤 1–7）─────────────────────────────────────────

    /**
     * Phase A 的结论。
     *
     * @param denial     授权拒绝决策；通过时为 {@code null}
     * @param agentId    目标 Agent（通过时有效）
     * @param authzVersion Phase A 读到的授权版本（准入比对基线）
     * @param fingerprint  Phase A 算出的闭包指纹（准入比对基线）
     * @param toolVersion 目标 Tool 的版本（写执行意图用）
     */
    private record PhaseA(@Nullable AuthorizationDecision denial,
                           @Nullable String agentId,
                           long authzVersion,
                           @NonNull String fingerprint,
                           @Nullable Integer toolVersion) {
    }

    private @NonNull PhaseA phaseA(@NonNull String tenantId,
                                    RunClaimPort.@NonNull LeasedTask task,
                                    ModelResponse.@NonNull ToolCallRequest call) {

        // ── 步骤 1：构建可信上下文 ─────────────────────────────────
        // ⚠️ 租户来自消息 + DB 上下文，主体是 Worker 服务账号，
        //    二者都<b>不得</b>取自客户端输入（§5.3 步骤 1）。
        Set<String> roles = Set.of(AuthorizationService.Roles.WORKER);

        // ── 步骤 2：PrincipalAuthorized（主体授权最先）──────────────
        long authzVersion;
        try {
            authzVersion = authzVersions.require(tenantId, principalId).authzVersion();
        } catch (RuntimeException e) {
            // ⚠️ 读不到权威版本 = 版本服务不可用 → fail-closed（§5.5）
            log.warn("授权版本不可用，fail-closed: tenant={}, principal={}, err={}",
                    tenantId, principalId, e.getMessage());
            return deny(ReasonCode.AUTHZ_UNAVAILABLE);
        }
        if (!rolePermissions.permits(roles, AuthorizationService.Actions.TOOL_INVOKE)) {
            return deny(ReasonCode.AUTHZ_ROLE_MISSING);
        }
        TenantContext context = new TenantContext(tenantId, principalId, roles, authzVersion);

        // ── 步骤 3：PermissionResolver → EffectiveClosure ───────────
        //    只在主体授权通过后才解析闭包：既避免向无权主体暴露能力面，
        //    也避免为无权调用做无用功。
        CapabilitySnapshot snapshot = snapshots.find(tenantId, task.runId()).orElse(null);
        if (snapshot == null) {
            // 没有 Run 快照就没有可校验的闭包证据 → 无法证明指纹有效 → 拒绝
            return deny(ReasonCode.RESOURCE_UNKNOWN);
        }
        String agentId = snapshot.agentId();

        EffectiveClosure closure;
        try {
            closure = closureResolver.resolveCurrent(tenantId, agentId);
        } catch (ClosureResolutionException e) {
            return deny(e.reasonCode());
        } catch (RuntimeException e) {
            return deny(ReasonCode.RESOURCE_UNKNOWN);
        }

        // ── 步骤 4–7：能力链判定（委托 P1 的 InvocationValidator）──
        List<String> candidateSkills = candidateSkills(closure, call.toolName());
        if (candidateSkills.isEmpty()) {
            // 目标工具不在任何已启用 Skill 的声明集内
            return deny(isMcp(closure, call.toolName())
                    ? ReasonCode.MCP_OP_NOT_ALLOWED
                    : ReasonCode.TOOL_NOT_DECLARED);
        }
        InvocationValidator.@NonNull McpTarget mcpTarget = mcpTarget(closure, call.toolName());

        AuthorizationDecision denial = null;
        for (String skillId : candidateSkills) {
            AuthorizationDecision decision = validator.validate(new InvocationValidator.InvocationRequest(
                    context, agentId, skillId, call.toolName(), call.arguments(),
                    task.runId(), mcpTarget));
            if (!decision.isAllowed()) {
                // ⚠️ 多个 Skill 声明同一工具时，<b>全部通过</b>才算通过 ——
                //    风险上限取最严者（§4.4「Skill 只能收紧」）。
                denial = decision;
                break;
            }
        }
        if (denial != null) {
            return deny(denial.reasonCode());
        }

        Integer toolVersion = closure.tool(call.toolName()).map(EffectiveClosure.ToolBinding::version)
                .orElse(null);
        return new PhaseA(null, agentId, authzVersion,
                ClosureFingerprint.of(closure), toolVersion);
    }

    private static @NonNull PhaseA deny(@NonNull ReasonCode reasonCode) {
        return new PhaseA(AuthorizationDecision.deny(reasonCode), null, 0L, "", null);
    }

    /**
     * 推导「声明了该工具的 Skill」候选集。
     *
     * <p>原生工具：Skill 的 {@code declaredTools} 含该工具 id；
     * MCP 工具：Skill 对该 Server 的允许操作集含该操作。
     *
     * <p>⚠️ 同一工具可能被多个 Skill 声明 —— 候选集保留全部，
     * 由调用方逐个送校验器（最严者胜出）。
     */
    private @NonNull List<String> candidateSkills(@NonNull EffectiveClosure closure,
                                                  @NonNull String toolId) {
        Optional<EffectiveClosure.ToolBinding> binding = closure.tool(toolId);
        if (binding.isPresent() && binding.get().isMcp()) {
            String serverId = binding.get().mcpServerId();
            String op = binding.get().mcpToolName();
            return closure.mcpServers().stream()
                    .filter(m -> m.serverId().equals(serverId))
                    .filter(m -> m.allowedOps().contains(op))
                    .map(EffectiveClosure.McpBinding::skillId)
                    .distinct()
                    .sorted()
                    .toList();
        }
        return closure.skills().stream()
                .filter(s -> s.declaredTools().contains(toolId))
                .map(EffectiveClosure.SkillBinding::skillId)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean isMcp(@NonNull EffectiveClosure closure, @NonNull String toolId) {
        return closure.tool(toolId).map(EffectiveClosure.ToolBinding::isMcp).orElse(false);
    }

    private static InvocationValidator.@NonNull McpTarget mcpTarget(@NonNull EffectiveClosure closure,
                                                                      @NonNull String toolId) {
        return closure.tool(toolId)
                .filter(EffectiveClosure.ToolBinding::isMcp)
                .filter(t -> t.mcpServerId() != null && t.mcpToolName() != null)
                .map(t -> new InvocationValidator.McpTarget(t.mcpServerId(), t.mcpToolName()))
                .orElse(null);
    }

    // ── 步骤 11：审批 ─────────────────────────────────────────────

    /**
     * 审批结论。
     *
     * @param denial    执行准入拒绝决策；通过时为 {@code null}
     * @param approvalId 审批标识（写执行意图用；无审批时为空）
     */
    private record ApprovalGate(@Nullable AuthorizationDecision denial,
                                 @Nullable String approvalId) {
    }

    private @NonNull ApprovalGate approve(@NonNull String tenantId,
                                            RunClaimPort.@NonNull LeasedTask task,
                                            ModelResponse.@NonNull ToolCallRequest call,
                                           @NonNull ToolHandler handler,
                                           @NonNull String operationId,
                                           @NonNull String requestDigest) {
        ApprovalPolicy.ApprovalDecision approval = approvalPolicy.evaluate(handler);
        if (!approval.requiresApproval()) {
            return new ApprovalGate(null, null);
        }

        // ⚠️ 先问审批权威：拿不到「明确批准」即拒绝。
        //    FAIL_CLOSED（来源不可用）同样拒绝 —— 不可用 ≠ 可放行。
        RiskBasedApprovalPolicy.ApprovalSource.Verdict verdict;
        try {
            verdict = approvalSource.verdictFor(tenantId, handler);
        } catch (RuntimeException e) {
            log.error("审批权威异常，fail-closed 拒绝: tool={}, tenant={}, err={}",
                    call.toolName(), tenantId, e.getMessage());
            verdict = RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED;
        }
        if (verdict != RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED) {
            log.warn("审批权威未批准，fail-closed 拒绝: tool={}, run={}, verdict={}",
                    call.toolName(), task.runId(), verdict);
            return new ApprovalGate(
                    AuthorizationDecision.executionDeny(ReasonCode.APPROVAL_NOT_FOUND), null);
        }

        // 审批 id 在消费前读取：消费后审批进入终态，findActive 可能读不到了。
        String approvalId = approvalPort.findActive(tenantId, operationId)
                .map(ApprovalPort.ApprovalView::approvalId)
                .orElse(null);

        ApprovalPort.ApprovalOutcome outcome = approvalPort.consume(tenantId, operationId,
                requestDigest, call.toolName(), task.runId());
        if (outcome != ApprovalPort.ApprovalOutcome.CONSUMED) {
            // ⚠️ 任何非 CONSUMED 结果都 fail-closed 拒绝。
            log.warn("审批消费未通过，fail-closed 拒绝: tool={}, run={}, outcome={}",
                    call.toolName(), task.runId(), outcome);
            return new ApprovalGate(
                    AuthorizationDecision.executionDeny(mapApprovalOutcome(outcome)), null);
        }
        return new ApprovalGate(null, approvalId);
    }

    /**
     * 审批结局 → §5.7 原因码映射。
     *
     * <p>⚠️ <b>P2 过渡映射</b>：§5.7 只给了 S6a–e 五个具体码，
     * 而既有 {@link ApprovalPort.ApprovalOutcome} 的取值更细
     * （含「目标不符」「Run 已取消」等）。
     * 本映射把语义等价者合并，<b>不新造码</b>；
     * P3 落地审批状态机与双哈希后，将按 §9.1 S6a–e 精确到码。
     */
    private static @NonNull ReasonCode mapApprovalOutcome(
             ApprovalPort.@NonNull ApprovalOutcome outcome) {
        return switch (outcome) {
            case CONSUMED -> ReasonCode.SUCCESS;
            case EXPIRED -> ReasonCode.APPROVAL_EXPIRED;
            case ARGUMENT_MISMATCH, TARGET_MISMATCH -> ReasonCode.APPROVAL_SCOPE_MISMATCH;
            case RUN_CANCELLED -> ReasonCode.RUN_CANCELLED;
            case NO_APPROVAL, REJECTED, NOT_APPROVED -> ReasonCode.APPROVAL_NOT_FOUND;
        };
    }

    // ── 结果落盘与账本 ────────────────────────────────────────────

    /**
     * 步骤 14a：把确定结果写入可恢复存储（结果事件表）。
     *
     * @return 是否写入成功（{@code false} 表示已存在或写入失败）
     */
    private boolean persistResult(@NonNull String tenantId,
                                   RunClaimPort.@NonNull LeasedTask task,
                                  @NonNull String operationId,
                                   ResultEvent.@NonNull Status status,
                                  @Nullable String payload,
                                  @NonNull ReasonCode reasonCode) {
        ResultEvent event = new ResultEvent(
                UUID.randomUUID().toString(),
                tenantId,
                task.runId(),
                operationId,
                null,
                status,
                payload,
                reasonCode.name(),
                Instant.now());
        try {
            // ⚠️ 幂等：同一 invocation 最多一条结果事件（先写入者为准）。
            return resultEvents.recordIfAbsent(event);
        } catch (RuntimeException e) {
            log.error("结果事件写入失败（确定结果未落盘）: operation={}, status={}, err={}",
                    operationId, status, e.getMessage());
            return false;
        }
    }

    /** 步骤 15：账本 POST。 */
    private boolean recordLedger(@NonNull String tenantId, @NonNull String operationId,
                                 long fencingToken, @NonNull ToolOperationState state,
                                 @Nullable String errorClass, @Nullable String errorMessage) {
        try {
            ledger.recordResult(tenantId, operationId, state, fencingToken, errorClass, errorMessage);
            return true;
        } catch (RuntimeException e) {
            // ⚠️ 账本 POST 失败 → 结果事件已存在则只重试落账（§7.4），
            //    绝不允许退化成「重新 execute」。
            log.error("账本 POST 失败（只可重试落账，禁止重新执行）: operation={}, state={}, err={}",
                    operationId, state, e.getMessage());
            return false;
        }
    }

    /**
     * 双维度结果合成（§7.4）。
     *
     * @param eventWritten 结果事件是否已落盘
     * @param posted       账本 POST 是否成功
     * @return 终态落账状态
     */
    private static  AuthorizationDecision.@NonNull Ledger ledgerStatus(boolean eventWritten,
                                                                     boolean posted) {
        if (eventWritten && posted) {
            return AuthorizationDecision.Ledger.RECORDED;
        }
        if (eventWritten) {
            // ⚠️ 结果事件已存在 → 只重试落账（ledger=RETRYING）
            return AuthorizationDecision.Ledger.RETRYING;
        }
        // ⚠️ 确定结果未落盘 → 恢复所需的证据缺失 → 需人工处置
        return AuthorizationDecision.Ledger.NEEDS_MANUAL;
    }

    private void consumeIntent(@NonNull String tenantId, @NonNull String operationId) {
        try {
            intents.transitionState(tenantId, operationId,
                    ExecutionIntent.IntentState.INTENT_SUBMITTED,
                    ExecutionIntent.IntentState.CONSUMED);
        } catch (RuntimeException e) {
            log.warn("执行意图状态迁移失败（意图仍在 INTENT_SUBMITTED，待恢复扫描处置）: operation={}, err={}",
                    operationId, e.getMessage());
        }
    }

    /** 账本记 FAILED（供「审批拒绝 / 预算失败 / 准入失效」等未执行分支复用）。 */
    private void recordLedgerFailed(@NonNull String tenantId, @NonNull String operationId,
                                    long fencingToken, @NonNull String errorMessage) {
        try {
            ledger.recordResult(tenantId, operationId, ToolOperationState.FAILED,
                    fencingToken, "CapGate", errorMessage);
        } catch (RuntimeException e) {
            log.error("账本记 FAILED 失败: operation={}, err={}", operationId, e.getMessage());
        }
    }

    /** 结算预留（失败不抛）。 */
    private void settleQuietly(@NonNull String tenantId, @Nullable String reservationId,
                               @NonNull String toolName) {
        if (reservationId == null) {
            return;
        }
        try {
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
    private void releaseQuietly(@NonNull String tenantId, @Nullable String reservationId,
                                @NonNull String reason) {
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
     * 构造幂等键。
     *
     * <p>⚠️ 键必须含真实 {@code runId}：{@code tool_operation} 的唯一约束是
     * {@code UNIQUE (tenant_id, idempotency_key)}（<b>租户级，不含 run 维度</b>），
     * 缺 run 维度会让同租户下不同 Run 的同名工具 + 同 callId 撞键。
     */
    private static @NonNull String buildIdempotencyKey(@NonNull String runId,
                                                       ModelResponse.@NonNull ToolCallRequest call) {
        Object studentId = call.arguments().get("studentId");
        Object knowledgePoint = call.arguments().get("knowledgePoint");
        if (studentId != null && knowledgePoint != null) {
            return IdempotencyKey.forRecordWeakness(
                    String.valueOf(studentId), runId,
                    String.valueOf(knowledgePoint)).composite();
        }
        return IdempotencyKey.forToolInvoke(
                runId, call.toolName(),
                Optional.ofNullable(call.callId()).orElse("no-call-id")).composite();
    }
}
