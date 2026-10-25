package cn.studykid.multiagent.application.cap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.orchestration.RunBudgetProbe;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapCompensationTask;
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
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
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
 * <p>⚠️ <b>审批在本阶段是 CAP 模型</b>（P3 落地）：
 * §5.3.3 的 {@code PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED} 状态机与
 * §5.3.1 的双哈希绑定，由 {@link ApprovalStateMachine} 承担
 * （P2 曾复用既有 {@code ApprovalPort.consume} 的一次性消费语义，
 *  其代价是准入失败时审批已被消费、无法取消；该差距已在本阶段消除）。
 *
 * <p>⚠️ <b>参数摘要全链路同源</b>（P3 决策）：
 * 审批的有效参数哈希、账本的请求摘要、执行意图的参数指纹
 * 全部走 {@link ArgsNormalizer}（{@code args_norm_v1}），
 * 否则「审批绑定的参数」与「实际执行的参数」会算出两个值，防重放形同虚设。
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
    private final ApprovalStateMachine approvalStateMachine;
    private final CompensationFramework compensation;
    private final CapToolDefPort toolDefs;
    private final BudgetPort budget;
    private final RunToolLedgerPort ledger;
    private final ExecutionIntentPort intents;
    private final ResultEventPort resultEvents;
    private final BigDecimal reserveAmountPerToolCall;
    private final String principalId;
    private final CapEnforcementSwitch enforcementSwitch;
    /** 四字段决策日志（P5-T6 · §5.1 / 08 §7）。 */
    private final DecisionAuditService audit;
    /**
     * Run 级预算探针（第七批 · MA-T17 · 全调用树共享上限）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时 CAP 链路只受<b>租户级</b>预算约束。
     * 生产装配<b>必须</b>传入 —— 否则「这一棵调用树最多花多少」在
     * CAP 链路（生产主链路）上没有任何约束。
     */
    private final RunBudgetProbe runBudget;

    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                             @NonNull ApprovalStateMachine approvalStateMachine,
                             @NonNull CompensationFramework compensation,
                             @NonNull CapToolDefPort toolDefs,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalStateMachine, compensation, toolDefs,
                budget, ledger, intents, resultEvents, reserveAmountPerToolCall,
                DEFAULT_PRINCIPAL_ID, new CapEnforcementSwitch(false),
                DecisionAuditService.noop(), null);
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
                             @NonNull ApprovalStateMachine approvalStateMachine,
                             @NonNull CompensationFramework compensation,
                             @NonNull CapToolDefPort toolDefs,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull CapEnforcementSwitch enforcementSwitch,
                             @NonNull DecisionAuditService audit) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalStateMachine, compensation, toolDefs,
                budget, ledger, intents, resultEvents, reserveAmountPerToolCall,
                DEFAULT_PRINCIPAL_ID, enforcementSwitch, audit, null);
    }

    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                             @NonNull ApprovalStateMachine approvalStateMachine,
                             @NonNull CompensationFramework compensation,
                             @NonNull CapToolDefPort toolDefs,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull String principalId,
                             @NonNull DecisionAuditService audit) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalStateMachine, compensation, toolDefs,
                budget, ledger, intents, resultEvents, reserveAmountPerToolCall, principalId,
                new CapEnforcementSwitch(false), audit, null);
    }

    /**
     * 兼容构造器：带迁移开关，但<b>未装配</b> Run 级预算探针（第七批）。
     *
     * <p>⚠️ 走此路径时「全调用树共享预算上限」在 CAP 链路不生效
     * （仅租户级预算有效）—— 生产装配走带 {@code RunBudgetProbe} 的版本。
     */
    public CapExecutionFlow(@NonNull InvocationValidator validator,
                             @NonNull ClosureResolver closureResolver,
                             @NonNull ExecutionAdmissionChecker admission,
                             @NonNull IdempotencyGuard idempotency,
                             @NonNull AuthzVersionPort authzVersions,
                             @NonNull RolePermissionPort rolePermissions,
                             @NonNull CapabilitySnapshotPort snapshots,
                             @NonNull ApprovalPolicy approvalPolicy,
                             @NonNull ApprovalStateMachine approvalStateMachine,
                             @NonNull CompensationFramework compensation,
                             @NonNull CapToolDefPort toolDefs,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull String principalId,
                             @NonNull CapEnforcementSwitch enforcementSwitch,
                             @NonNull DecisionAuditService audit) {
        this(validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalStateMachine, compensation, toolDefs,
                budget, ledger, intents, resultEvents, reserveAmountPerToolCall, principalId,
                enforcementSwitch, audit, null);
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
                             @NonNull ApprovalStateMachine approvalStateMachine,
                             @NonNull CompensationFramework compensation,
                             @NonNull CapToolDefPort toolDefs,
                             @NonNull BudgetPort budget,
                             @NonNull RunToolLedgerPort ledger,
                             @NonNull ExecutionIntentPort intents,
                             @NonNull ResultEventPort resultEvents,
                             @NonNull BigDecimal reserveAmountPerToolCall,
                             @NonNull String principalId,
                             @NonNull CapEnforcementSwitch enforcementSwitch,
                             @NonNull DecisionAuditService audit,
                             RunBudgetProbe runBudget) {
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
        this.approvalStateMachine = approvalStateMachine;
        this.compensation = compensation;
        this.toolDefs = toolDefs;
        this.budget = budget;
        this.ledger = ledger;
        this.intents = intents;
        this.resultEvents = resultEvents;
        this.reserveAmountPerToolCall = reserveAmountPerToolCall;
        this.principalId = principalId;
        this.enforcementSwitch = enforcementSwitch;
        this.audit = audit;
        this.runBudget = runBudget;
        if (runBudget == null) {
            // ⚠️ 明确告警：未装配 Run 级预算 = CAP 链路没有「这一棵树」的额度约束。
            log.warn("未装配 Run 级预算探针：CAP 链路只受**租户级**预算约束，"
                    + "「全调用树共享预算上限」在该路径上不生效");
        }
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
     * <p>⚠️ <b>本方法只做「判定 + 副作用」，审计在返回值之后统一落库</b>：
     * 见 {@link #decide} 与 {@link #auditDecision}。
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
        CapToolDecision decision = decide(tenantId, task, call, handler, null, null);
        auditDecision(tenantId, task, call, decision);
        return decision;
    }

    /**
     * 带<b>执行作用域</b>的工具调用（多级 Agent v1.1 · FR-025）。
     *
     * <p>⚠️ <b>子 Agent 的工具调用必须走本重载</b>：默认路径按
     * <b>Run 快照里的根 Agent</b> 解析闭包，若子 Agent 沿用该口径，
     * 它就能调用父 Agent 的工具（权限被下放 = Sev-0），
     * 且自己的工具反而全部被拒。
     * 本重载按 {@code scope} 的<b>固定版本</b>解析闭包，
     * 并与委派时算出的闭包指纹比对（防子 Agent 版本漂移）。
     *
     * @param scope 执行作用域；{@code null} = 根 Agent（单 Agent 路径）
     * @return 四字段决策结论
     */
    public @NonNull CapToolDecision invoke(@NonNull String tenantId,
                                            RunClaimPort.@NonNull LeasedTask task,
                                            ModelResponse.@NonNull ToolCallRequest call,
                                           @NonNull ToolHandler handler,
                                           cn.studykid.multiagent.core.cap.@Nullable ExecutionScope scope) {
        // ⚠️ Agent 维度默认由作用域派生（第十七批）：本重载是「调用方自带作用域」的口径，
        //    作用域就是执行主体 → 键里必须带它。
        return invoke(tenantId, task, call, handler, scope,
                scope == null ? null : scope.agentRef());
    }

    /**
     * 带<b>显式 Agent 维度</b>的工具调用（多级 Agent v1.1 · 第十七批）。
     *
     * <p>⚠️ <b>为什么幂等键的 Agent 维度要由调用方显式传入</b>：
     * 幂等键必须按「<b>当前执行中的 Agent</b>」分域，而「当前 Agent 是谁」在两条链路上
     * 来源不同 —— CAP 链路看执行作用域，既有链路看委派上下文。
     * 若本类自行派生，就只覆盖了其中一条链路；
     * 由闸门算一次再透传，两条链路才是同一个值。
     *
     * <p>⚠️ 缺 Agent 维度的后果不是「多记一行账」，而是
     * <b>父 Agent 与子 Agent（或两个兄弟子 Agent）调用同名工具 + 同名 model call id
     * 时撞键</b> → 账本命中重复键 → <b>复用既有 operation、不再次执行工具</b> →
     * 后者那次调用<b>根本没发生</b>，模型拿到的还是<b>另一个 Agent 的结果</b>。
     *
     * @param agentRef 当前执行中的 Agent 引用（{@code agentId@version}）；
     *                 {@code null} = 单 Agent 路径（键值与历史一致）
     * @return 四字段决策结论
     */
    public @NonNull CapToolDecision invoke(@NonNull String tenantId,
                                            RunClaimPort.@NonNull LeasedTask task,
                                            ModelResponse.@NonNull ToolCallRequest call,
                                           @NonNull ToolHandler handler,
                                           cn.studykid.multiagent.core.cap.@Nullable ExecutionScope scope,
                                           @Nullable String agentRef) {
        CapToolDecision decision = decide(tenantId, task, call, handler, scope, agentRef);
        auditDecision(tenantId, task, call, decision);
        return decision;
    }

    /**
     * 落一条四字段决策日志（§5.1 / 08 §7）。
     *
     * <p>⚠️ <b>invocationId ≡ 账本 operationId</b>（P2 决策 2）：
     * 同一个 id 才能把「决策」与「账本」串起来 ——
     * 否则「每次调用可经 runId + invocationId 追踪」在跨表关联时就断了。
     * 尚未分配 operationId 的拒绝（Phase A 拒绝）没有账本行，
     * 此时用「工具名 + 尝试号」构造一个<b>稳定</b>的替代标识，
     * 保证同一调用的多条拒绝日志仍可归并。
     *
     * <p>⚠️ <b>审计失败的处置分级在 {@link DecisionAuditService} 内完成</b>：
     * 未执行的决策写不进去 → fail-closed（拒绝本次调用是安全的）；
     * 已执行的决策写不进去 → 只告警（不能把「已完成」变成「失败」造成重复副作用）。
     */
    private void auditDecision(@NonNull String tenantId,
                               RunClaimPort.@NonNull LeasedTask task,
                               ModelResponse.@NonNull ToolCallRequest call,
                               @NonNull CapToolDecision decision) {
        String invocationId = decision.operationId() != null
                ? decision.operationId()
                : "pending:" + task.runId() + ':' + call.toolName();
        audit.recordDecision(
                decision.decision(), tenantId, task.runId(), invocationId,
                null, null, principalId, call.toolName());
    }

    /**
     * 执行一次工具调用的完整准入链（§5.3 步骤 1–15）——<b>不含审计</b>。
     *
     * @param tenantId 租户
     * @param task     已领取任务
     * @param call     模型请求的工具调用
     * @param handler  目标工具处理器
     * @return 四字段决策结论
     */
    private @NonNull CapToolDecision decide(@NonNull String tenantId,
                                            RunClaimPort.@NonNull LeasedTask task,
                                            ModelResponse.@NonNull ToolCallRequest call,
                                           @NonNull ToolHandler handler,
                                           cn.studykid.multiagent.core.cap.@Nullable ExecutionScope scope,
                                           @Nullable String agentRef) {

        // ══ 步骤 1–7：Phase A（授权）══════════════════════════════
        PhaseA phaseA = phaseA(tenantId, task, call, scope);
        if (phaseA.denial() != null) {
            return new CapToolDecision(call.toolName(), null, phaseA.denial());
        }

        // ══ 步骤 8：幂等键校验 ════════════════════════════════════
        // ⚠️⚠️ Agent 维度（第十七批）：键缺 Agent 维度 → 父子/兄弟 Agent
        //    同名工具 + 同名 call id 撞键 → 后者被当成重放 → **那次调用没发生**。
        String idempotencyKey = buildIdempotencyKey(task.runId(), call, agentRef);
        // ⚠️⚠️ 参数摘要全链路<b>同源</b>（P3 决策）：
        //    审批绑定的有效参数哈希、账本的请求摘要、执行意图的参数指纹
        //    全部取这一个值（args_norm_v1）。若各算各的，
        //    「审批绑定的参数」与「实际执行的参数」就是两个值，防重放形同虚设。
        String argsDigest = argsDigest(call.toolName(), phaseA.toolVersion(), call.arguments());
        // ⚠️ 账本的「请求摘要」额外含<b>工具身份</b>（P1-9）：
        //    同键不同工具必须判为「冲突」而不是「重放」——
        //    幂等键虽已含工具名，但键是「谁写的」，摘要是「内容是否相同」的判据，
        //    两者都得带上工具身份，才不会出现「A 工具的调用被判成 B 工具的重放」。
        String requestDigest = requestDigest(call.toolName(), argsDigest);

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

        // ══ 步骤 11：Approval（CAP 状态机 · §5.3.3）════════════════
        ApprovalGate approvalGate = approve(tenantId, task, call, handler, operationId,
                phaseA);
        if (approvalGate.denial() != null) {
            // ⚠️ 审批未通过 → 账本记 FAILED（PRE 取消）。
            //    此时审批尚未预占（预占失败即未预占），无审批可补偿。
            recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                    "approval:" + approvalGate.denial().reasonCode());
            return new CapToolDecision(call.toolName(), operationId, approvalGate.denial());
        }

        // ══ 步骤 11.5：Run 级预算（MA-T17 · 全调用树共享上限）═════
        //    ⚠️ 必须在**预留之前**判：预留成功即已占额，判晚了等于「先花钱再检查」。
        //    所有深度的工具调用都记在同一个 run_id 下（budget_reservation.run_id），
        //    因此这一道判定就是「这一整棵树还能花多少」的守门人 ——
        //    嵌套 Agent 无法通过「自己再开一份额度」绕过它。
        //    ⚠️ 按**量**判（剩余额度 ≥ 本次预留额），不是按方向判：
        //    只判「还有没有余额」时，剩余 10 元的 Run 仍能预留 50 元 ——
        //    单次调用就能突破上限，「共享上限」名存实亡。
        if (runBudget != null) {
            RunBudgetProbe.Verdict verdict = runBudget.check(
                    tenantId, task.runId(), reserveAmountPerToolCall);
            if (!verdict.allowed()) {
                log.warn("Run 级预算不足，拒绝工具调用: tool={}, run={}, reason={}",
                        call.toolName(), task.runId(), verdict.denial());
                recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                        verdict.reasonCode());
                return new CapToolDecision(call.toolName(), operationId,
                        AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
            }
        }

        // ══ 步骤 12：BudgetOk ═════════════════════════════════════
        String reservationId;
        try {
            BudgetPort.ReserveOutcome outcome = budget.reserve(tenantId, task.runId(),
                    operationId, reserveAmountPerToolCall);
            if (!outcome.granted()) {
                log.warn("预算预留被拒: tool={}, run={}, outcome={}",
                        call.toolName(), task.runId(), outcome);
                // ⚠️ §5.3.5「预算失败 → 审批 CANCELLED」：审批已预占，必须作废，
                //    否则它悬在 RESERVED 挡住后续调用。
                compensate(tenantId, approvalGate.approvalId(), operationId,
                        findReservationId(tenantId, operationId),
                        "budget:" + outcome);
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
            compensate(tenantId, approvalGate.approvalId(), operationId,
                    findReservationId(tenantId, operationId), "budget:exception");
            recordLedgerFailed(tenantId, operationId, task.fencingToken(), "budget:exception");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
        }
        if (reservationId == null) {
            // ⚠️ 预留成功但查不到预留记录 → 无法在终态结算/释放 → fail-closed。
            compensate(tenantId, approvalGate.approvalId(), operationId, null,
                    "budget:unconfirmed");
            recordLedgerFailed(tenantId, operationId, task.fencingToken(), "budget:unconfirmed");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.BUDGET_EXCEEDED));
        }

        // ══ 步骤 13：ExecutionAdmissionCheck（TOCTOU）═════════════
        ExecutionAdmissionChecker.AdmissionOutcome admissionOutcome = admission.check(
                new ExecutionAdmissionChecker.AdmissionRequest(
                        tenantId, principalId, phaseA.agentId(), task.runId(),
                        phaseA.authzVersion(), phaseA.fingerprint(), phaseA.agentVersion()));
        if (!admissionOutcome.admitted()) {
            // ⚠️ 准入失效 → 取消审批 + 释放预算 + PRE 标失败，阻断新副作用（§5.3 步骤 13）。
            //    §5.3.5「准入失败 → CANCELLED / 释放 / PRE 取消」。
            compensate(tenantId, approvalGate.approvalId(), operationId, reservationId,
                    "exec-admission-stale:" + admissionOutcome.reasonCode());
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
                argsDigest,
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
            compensate(tenantId, approvalGate.approvalId(), operationId, reservationId,
                    "intent-persist-failed");
            recordLedgerFailed(tenantId, operationId, task.fencingToken(),
                    "intent-persist-failed");
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.EXECUTION_PERSISTENCE_FAILED));
        }

        // ══ 步骤 13b：审批 RESERVED → INTENT_SUBMITTED ═══════════
        if (approvalGate.approvalId() != null) {
            try {
                approvalStateMachine.markIntentSubmitted(tenantId,
                        approvalGate.approvalId(), operationId);
            } catch (RuntimeException e) {
                // ⚠️ 记账失败不阻断执行：审批在步骤 11 已 fail-closed 校验通过，
                //    本步骤只是状态记账。
                //    ⚠️ 但它有恢复语义：审批停在 RESERVED = 「尚未发出副作用」，
                //    停在 INTENT_SUBMITTED = 「可能已发出」——
                //    标记失败会让恢复方判错方向，故必须显式告警。
                log.warn("审批意图状态标记失败（影响恢复判读）: tool={}, run={}, err={}",
                        call.toolName(), task.runId(), e.getMessage());
            }
        }

        // ══ 步骤 14：执行 ═════════════════════════════════════════
        ToolHandler.ToolExecutionContext ctx = new ToolHandler.ToolExecutionContext(
                tenantId, task.runId(), principalId, idempotencyKey, task.attempt());

        try {
            ToolHandler.ToolResult toolResult = handler.execute(ctx, call.arguments());

            // ── 步骤 14a：确定结果先入可恢复存储，再更新 executionStatus ──
            ResultPersistence persistence = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.SUCCEEDED, toolResult.output(), ReasonCode.SUCCESS);

            // ⚠️⚠️ 14a 失败 = 确定结果<b>未落盘</b> → <b>不得</b>直接落账
            //    （§5.3「结果事件必须先落盘」）。
            //    账本若此时记 SUCCEEDED，对账就无从做起：
            //    运维看到 SUCCEEDED 便不会再去查结果载荷。
            if (persistence == ResultPersistence.FAILED) {
                boolean queued = enqueueLedgerRetry(tenantId, operationId, task.runId(),
                        ResultEvent.Status.SUCCEEDED, toolResult.output(), ReasonCode.SUCCESS);
                if (queued) {
                    // 结局已存进补偿任务 → 恢复流程补写事件再落账；本进程不落账。
                    // ⚠️ 成本已发生（工具确实成功）→ 结算而非释放。
                    settleQuietly(tenantId, reservationId, call.toolName());
                    // ⚠️ 工具**确实执行成功**（只是落盘待重试）→ 输出是确定结果，
                    //    仍须带出供模型消费；只是账本状态为 RETRYING。
                    return new CapToolDecision(call.toolName(), operationId,
                            AuthorizationDecision.executionSucceeded(
                                    AuthorizationDecision.Ledger.RETRYING),
                            toolResult.output());
                }
                // 结局彻底丢失（补偿任务也落不下去）→ 只能按 UNKNOWN 进对账，
                // 绝不谎报成功（§7.4：UNKNOWN 是唯一需要人工确认的状态）。
                log.error("确定结果既未落盘也未入补偿任务，按 UNKNOWN 进对账: operation={}",
                        operationId);
                boolean postedUnknown = recordLedger(tenantId, operationId, task.fencingToken(),
                        ToolOperationState.UNKNOWN, null, "result-persistence-lost");
                settleQuietly(tenantId, reservationId, call.toolName());
                return new CapToolDecision(call.toolName(), operationId,
                        AuthorizationDecision.executionUnknown(
                                postedUnknown ? AuthorizationDecision.Ledger.RECORDED
                                        : AuthorizationDecision.Ledger.NEEDS_MANUAL,
                                ReasonCode.EXEC_UNKNOWN));
            }

            // ── 步骤 15：账本 POST ──
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.SUCCEEDED, null, null);

            // ⚠️ 落账失败 → 把「已确定的结局」交给补偿任务（COMP-4 / COMP-6）：
            //    重启后由恢复流程<b>只重试落账</b>，绝不重新 execute。
            boolean retryQueued = false;
            if (!posted) {
                retryQueued = enqueueLedgerRetry(tenantId, operationId, task.runId(),
                        ResultEvent.Status.SUCCEEDED, toolResult.output(), ReasonCode.SUCCESS);
            }

            AuthorizationDecision.Ledger ledgerStatus =
                    ledgerStatus(persistence, posted, retryQueued);
            if (ledgerStatus == AuthorizationDecision.Ledger.RECORDED) {
                consumeIntent(tenantId, operationId);
                consumeApproval(tenantId, approvalGate.approvalId(), operationId, "SUCCEEDED");
            }
            // ⚠️ 结算放在落账之后（§5.3 §7.4）：账本是事实源，
            //    结算失败不能反过来改写「工具已成功」这一事实。
            settleQuietly(tenantId, reservationId, call.toolName());
            // ⚠️ 工具输出**带出**：模型必须能看到自己刚调了什么，
            //    否则多轮工具协作不成立（子 Agent 调完工具即结束，等于工具调用无意义）。
            //    输出是**不可信数据**，回灌时由调用方标记。
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionSucceeded(ledgerStatus),
                    toolResult.output());

        } catch (ToolExecutionException e) {
            if (e.isResultUnknown()) {
                // ⚠️ 结果未知：可能已产生副作用。
                //    不释放预算预留（成本可能已发生），不重试，进对账。
                log.warn("工具结果未知，落账转对账: tool={}, run={}, operation={}",
                        call.toolName(), task.runId(), operationId);
                ResultPersistence persistence = persistResult(tenantId, task, operationId,
                        ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
                boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                        ToolOperationState.UNKNOWN, null, e.getMessage());
                boolean retryQueued = false;
                if (!posted) {
                    retryQueued = enqueueLedgerRetry(tenantId, operationId, task.runId(),
                            ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
                }
                // ⚠️ UNKNOWN 不释放预算预留：成本可能已发生（红线）。
                // ⚠️ 也不自动收敛：结果不确定只能进对账（§7.4 / COMP-5）。
                return new CapToolDecision(call.toolName(), operationId,
                        AuthorizationDecision.executionUnknown(
                                ledgerStatus(persistence, posted, retryQueued),
                                ReasonCode.EXEC_UNKNOWN));
            }
            // 确定失败：无副作用，可重试 → 预算额度可安全退回。
            log.info("工具确定失败: tool={}, err={}", call.toolName(), e.getMessage());
            ResultPersistence persistence = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.FAILED, null, ReasonCode.TOOL_EXECUTION_FAILED);
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.FAILED, e.getClass().getSimpleName(), e.getMessage());
            boolean retryQueued = false;
            if (!posted) {
                retryQueued = enqueueLedgerRetry(tenantId, operationId, task.runId(),
                        ResultEvent.Status.FAILED, null, ReasonCode.TOOL_EXECUTION_FAILED);
            }
            // ⚠️ 确定失败 = 无副作用发生 → 额度可安全退回。
            releaseQuietly(tenantId, reservationId,
                    "tool-failed:" + e.getClass().getSimpleName());
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionFailed(
                            ledgerStatus(persistence, posted, retryQueued),
                            ReasonCode.TOOL_EXECUTION_FAILED));

        } catch (RuntimeException e) {
            // ⚠️ 未预期异常按「结果未知」处理 —— 保守假设可能有副作用。
            //    若按确定失败处理而实际已写入，会导致重复写入 = Sev-0。
            log.error("工具抛出未预期异常，保守按 UNKNOWN 落账: tool={}", call.toolName(), e);
            ResultPersistence persistence = persistResult(tenantId, task, operationId,
                    ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
            boolean posted = recordLedger(tenantId, operationId, task.fencingToken(),
                    ToolOperationState.UNKNOWN, null, String.valueOf(e.getMessage()));
            boolean retryQueued = false;
            if (!posted || persistence == ResultPersistence.FAILED) {
                retryQueued = enqueueLedgerRetry(tenantId, operationId, task.runId(),
                        ResultEvent.Status.UNKNOWN, null, ReasonCode.EXEC_UNKNOWN);
            }
            return new CapToolDecision(call.toolName(), operationId,
                    AuthorizationDecision.executionUnknown(
                            ledgerStatus(persistence, posted, retryQueued),
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
    /**
     * Phase A 的判定结果（授权结论 + 准入比对基线）。
     *
     * @param denial       授权拒绝结论；通过时为 {@code null}
     * @param agentId      执行主体（子 Agent 路径 = 子 Agent 自己）
     * @param authzVersion 准入比对的授权版本基线
     * @param fingerprint  准入比对的闭包指纹基线
     * @param toolVersion  目标 Tool 的版本（写执行意图用）
     * @param agentVersion 执行主体的<b>固定 release 版本</b>；
     *                     {@code null} = 单 Agent 路径（当前已发布版本）
     */
    private record PhaseA(@Nullable AuthorizationDecision denial,
                           @Nullable String agentId,
                           long authzVersion,
                           @NonNull String fingerprint,
                           @Nullable Integer toolVersion,
                           @Nullable Integer agentVersion) {
    }

    private @NonNull PhaseA phaseA(@NonNull String tenantId,
                                    RunClaimPort.@NonNull LeasedTask task,
                                    ModelResponse.@NonNull ToolCallRequest call,
                                    cn.studykid.multiagent.core.cap.@Nullable ExecutionScope scope) {

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
        // ⚠️ 子 Agent 路径：执行主体是**子 Agent 自己**，闭包按其固定 release 解析，
        //    指纹期望值也来自子 Agent 的闭包 —— 绝不沿用根 Agent 的闭包。
        String expectedFingerprint = null;
        int fixedVersion = 0;
        // ⚠️⚠️ 固定版本必须**一路带到底**（第十八批）：
        //    校验器与准入检查各自解析一次闭包，若它们按「当前已发布版本」解析，
        //    而 Phase A 按固定版本解析 → 三处比的是三个不同的东西。
        //    {@code null} = 单 Agent 路径（当前已发布版本）；非 null = 子 Agent 的固定 release。
        Integer agentVersion = null;

        EffectiveClosure closure;
        try {
            if (scope != null) {
                agentId = scope.agentId();
                fixedVersion = scope.agentVersion();
                agentVersion = fixedVersion;
                expectedFingerprint = scope.expectedFingerprint();
                closure = closureResolver.resolve(tenantId, agentId, fixedVersion);
            } else {
                closure = closureResolver.resolveCurrent(tenantId, agentId);
            }
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
                    task.runId(), mcpTarget, expectedFingerprint, agentVersion));
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
                ClosureFingerprint.of(closure), toolVersion, agentVersion);
    }

    private static @NonNull PhaseA deny(@NonNull ReasonCode reasonCode) {
        return new PhaseA(AuthorizationDecision.deny(reasonCode), null, 0L, "", null, null);
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
                                           @NonNull PhaseA phaseA) {
        ApprovalPolicy.ApprovalDecision approval = approvalPolicy.evaluate(handler);
        if (!approval.requiresApproval()) {
            return new ApprovalGate(null, null);
        }

        // ⚠️ 审批是 CAP 状态机：定位候选 → 校验双哈希绑定 → 原子预占（§5.3.3）。
        //    拿不到「明确批准」即拒绝（fail-closed）：不可用 ≠ 可放行。
        ApprovalStateMachine.Acquisition acquisition = approvalStateMachine.acquire(
                new ApprovalStateMachine.AcquireRequest(
                        tenantId,
                        principalId,
                        call.toolName(),
                        task.runId(),
                        operationId,
                        operationId,
                        call.arguments(),
                        phaseA.fingerprint(),
                        phaseA.authzVersion(),
                        Instant.now()));
        if (!acquisition.acquired()) {
            log.warn("审批未通过，fail-closed 拒绝: tool={}, run={}, reason={}, detail={}",
                    call.toolName(), task.runId(), acquisition.reasonCode(),
                    acquisition.detail());
            return new ApprovalGate(
                    AuthorizationDecision.executionDeny(acquisition.reasonCode()), null);
        }
        return new ApprovalGate(null, acquisition.approvalId());
    }

    // ── 参数摘要（args_norm_v1 · §5.3.2）───────────────────────────

    /**
     * 计算本次调用的参数摘要。
     *
     * <p>⚠️ <b>规范化依据必须取闭包固定版本</b>的工具定义：
     * Schema 默认值是「省略参数」与「显式传默认值」能算出同一摘要的前提。
     * 取不到定义时按「无默认值」处理 ——
     * 后果是审批绑定校验不通过（{@code APPROVAL_SCOPE_MISMATCH}），仍是 fail-closed。
     */
    private @NonNull String argsDigest(@NonNull String toolId,
                                       @Nullable Integer toolVersion,
                                       @NonNull Map<String, Object> arguments) {
        return ArgsNormalizer.hash(arguments, argsOptions(toolId, toolVersion));
    }

    private ArgsNormalizer.@NonNull Options argsOptions(@NonNull String toolId,
                                                         @Nullable Integer toolVersion) {
        if (toolVersion == null) {
            return ArgsNormalizer.Options.all();
        }
        return toolDefs.find(toolId, toolVersion)
                .map(def -> new ArgsNormalizer.Options(null,
                        ArgsNormalizer.defaultsFromSchema(def.inputSchema()), null))
                .orElse(ArgsNormalizer.Options.all());
    }

    /**
     * 账本的「请求摘要」：<b>工具身份 + 参数摘要</b>（P1-9）。
     *
     * <p>⚠️ <b>为什么要含工具身份</b>：
     * 账本用请求摘要判定「同键是否同参」（{@code IdempotencyGuard}）。
     * 若摘要只覆盖参数，则「A 工具的调用」与「B 工具的调用」只要参数恰好相同，
     * 就会被判成同一个逻辑操作 —— 后者要么被当成重放（<b>静默不执行</b>），
     * 要么在参数不同时被当成冲突（<b>被拒绝执行</b>）。
     *
     * <p>⚠️ <b>实现方式是「组合」而不是「重新规范化」</b>：
     * 把参数包一层再规范化会破坏 §5.3.2 规则 7
     * （Schema 默认值在<b>顶层</b>展开，包一层后默认值会落在错误的位置）。
     * 故这里对已算好的参数摘要做一次带工具名的二次摘要。
     */
    private static @NonNull String requestDigest(@NonNull String toolId,
                                                 @NonNull String argsDigest) {
        return ArgsNormalizer.hash(
                Map.of("tool", toolId, "args", argsDigest),
                ArgsNormalizer.Options.all());
    }

    // ── 补偿（§5.3.5）─────────────────────────────────────────────

    /**
     * 触发补偿：取消审批 + 释放预算（幂等；失败落补偿任务）。
     */
    private void compensate(@NonNull String tenantId,
                            @Nullable String approvalId,
                            @NonNull String operationId,
                            @Nullable String reservationId,
                            @NonNull String reason) {
        try {
            compensation.compensate(new CompensationFramework.Request(
                    tenantId, approvalId, operationId, reservationId, reason));
        } catch (RuntimeException e) {
            // ⚠️ 补偿框架自身异常不得掩盖主流程的失败结论；
            //    框架内部已把失败落成补偿任务。
            log.error("补偿执行异常: operation={}, reason={}, err={}",
                    operationId, reason, e.getMessage(), e);
        }
    }

    /** 查该逻辑操作的预算预留 id（不存在时为空）。 */
    private @Nullable String findReservationId(@NonNull String tenantId,
                                               @NonNull String operationId) {
        try {
            return budget.findByOperation(tenantId, operationId)
                    .map(BudgetPort.Reservation::reservationId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("查询预算预留失败（按无预留处理）: operation={}, err={}",
                    operationId, e.getMessage());
            return null;
        }
    }

    /**
     * 落一条「重试落账」补偿任务（COMP-4 / COMP-6）。
     *
     * <p>⚠️ 任务载荷携带<b>已确定的结局</b>：
     * 重启后由恢复流程据此补记结果事件与账本，
     * <b>绝不重新执行工具</b>。
     *
     * @return 是否成功入队（false = 结局无处留存，需人工处置）
     */
    private boolean enqueueLedgerRetry(@NonNull String tenantId,
                                       @NonNull String operationId,
                                       @Nullable String runId,
                                       ResultEvent.@NonNull Status status,
                                       @Nullable String payload,
                                       @NonNull ReasonCode reasonCode) {
        try {
            String encoded = ResultRecoveryService.encodeOutcome(new ResultRecoveryService.Outcome(
                    runId, operationId, status, payload, reasonCode.name()));
            return compensation.enqueueLedgerRetry(tenantId, operationId, operationId, encoded);
        } catch (RuntimeException e) {
            log.error("落账重试任务入队失败（结局无处留存，需人工处置）: operation={}, err={}",
                    operationId, e.getMessage(), e);
            return false;
        }
    }

    /** 审批与终态关联（步骤 15 之后）。 */
    private void consumeApproval(@NonNull String tenantId,
                                  @Nullable String approvalId,
                                  @NonNull String operationId,
                                  @NonNull String terminalStatus) {
        if (approvalId == null) {
            return;
        }
        try {
            approvalStateMachine.consume(tenantId, approvalId, operationId, terminalStatus);
        } catch (RuntimeException e) {
            log.warn("审批消费失败（账本已是事实源，留待对账）: operation={}, err={}",
                    operationId, e.getMessage());
        }
    }

    // ── 结果落盘与账本 ────────────────────────────────────────────

    /**
     * 步骤 14a：把确定结果写入可恢复存储（结果事件表）。
     *
     * ⚠️ <b>「已存在」不等于「没写成功」</b>：
     * {@code recordIfAbsent} 返回 {@code false} 表示「本次没插入」，
     * 而「没插入」最常见的原因是<b>幂等命中</b>（前一次已写）。
     * 若把它一律当成「写失败」，就会把「结果其实已落盘」误判为证据缺失，
     * 于是本可自动重试落账的场景被升级成人工处置。
     * 故必须回读确认到底是「已存在」还是「真失败」。
     */
    private @NonNull ResultPersistence persistResult(@NonNull String tenantId,
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
            if (resultEvents.recordIfAbsent(event)) {
                return ResultPersistence.WRITTEN;
            }
            return resultEvents.findByInvocation(tenantId, operationId).isPresent()
                    ? ResultPersistence.PRESENT
                    : ResultPersistence.FAILED;
        } catch (RuntimeException e) {
            log.error("结果事件写入失败（确定结果未落盘）: operation={}, status={}, err={}",
                    operationId, status, e.getMessage());
            return ResultPersistence.FAILED;
        }
    }

    /**
     * 结果事件持久化结论。
     */
    private enum ResultPersistence {
        /** 本次写入成功。 */
        WRITTEN,
        /** 已存在（幂等命中）—— 结局同样已落盘。 */
        PRESENT,
        /** 写入失败且读不到 —— 结局无处留存。 */
        FAILED
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
     * <p>⚠️ <b>口径是「关于账本」的</b>，不是「关于副作用是否发生」的：
     * <ul>
     *   <li>账本 POST 成功 → {@code RECORDED}（终态已可靠落账）；</li>
     *   <li>POST 失败，但结局仍被持久化（结果事件已存在，
     *       或结局已存进补偿任务）→ {@code RETRYING}
     *       （只重试落账，<b>禁止</b>重新 execute）；</li>
     *   <li>POST 失败<b>且</b>结局无处留存 → {@code NEEDS_MANUAL}
     *       （恢复所需证据缺失，只能人工处置）。</li>
     * </ul>
     * 副作用是否发生由 {@code executionStatus} 表达，两者<b>互不覆盖</b>。
     *
     * @param persistence 结果事件持久化结论
     * @param posted      账本 POST 是否成功
     * @param retryQueued 结局是否已存进补偿任务
     * @return 终态落账状态
     */
    private static AuthorizationDecision.@NonNull Ledger ledgerStatus(
            @NonNull ResultPersistence persistence,
            boolean posted,
            boolean retryQueued) {
        if (posted) {
            return AuthorizationDecision.Ledger.RECORDED;
        }
        if (persistence != ResultPersistence.FAILED || retryQueued) {
            // ⚠️ 结局仍被持久化 → 恢复流程可补记 → 只重试落账
            return AuthorizationDecision.Ledger.RETRYING;
        }
        // ⚠️ 确定结果无处留存 → 恢复所需证据缺失 → 需人工处置
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
     *
     * <p>⚠️ <b>「按工具分流」的口径收在 {@link IdempotencyKey#forToolCall}</b>（P1-9 修复）：
     * 修复前本方法只看「参数里有没有 {@code studentId} + {@code knowledgePoint}」，
     * 于是<b>任何</b>带这两个参数的工具都被塞进 {@code tool.record_weakness} 命名空间 ——
     * 两个不同工具算出同一个键 → 后者被判「同键不同参数」而拒绝执行，
     * 或被判「重放」而静默不执行。
     *
     * <p>⚠️⚠️ <b>必须带 Agent 维度</b>（第十七批 · 多级 Agent）：
     * 键缺 Agent 维度时，父 Agent 与子 Agent（或两个兄弟子 Agent）调用
     * <b>同名工具 + 同名 model call id</b>会算出同一个键 →
     * 账本命中重复键 → <b>复用既有 operation、不再次执行工具</b> →
     * 后者那次调用<b>根本没发生</b>，且模型拿到的是<b>另一个 Agent 的结果</b>。
     *
     * @param agentRef 当前执行中的 Agent 引用；{@code null} = 单 Agent 路径
     *                 （键值与历史完全一致，在途 {@code tool_operation} 行不受影响）
     */
    private static @NonNull String buildIdempotencyKey(@NonNull String runId,
                                                       ModelResponse.@NonNull ToolCallRequest call,
                                                       @Nullable String agentRef) {
        Object studentId = call.arguments().get("studentId");
        Object knowledgePoint = call.arguments().get("knowledgePoint");
        return IdempotencyKey.forToolCall(runId, agentRef, call.toolName(), call.callId(),
                        studentId == null ? null : String.valueOf(studentId),
                        knowledgePoint == null ? null : String.valueOf(knowledgePoint))
                .composite();
    }
}
