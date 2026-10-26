package cn.studykid.multiagent.worker.execute;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.orchestration.RunBudgetProbe;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.DelegationContext;
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

    /**
     * HTTP-backed 工具处理器的动态解析器（第三十七批 · 设计文档 §13.1 · ADR-011）。
     *
     * <p>⚠️ <b>可为空 = HTTP 出站未装配</b>：此时 HTTP 工具<b>解析不到</b>，
     * 会被记为 {@code TOOL_NOT_DECLARED} 而拒绝 —— 不存在「静默放行」。
     *
     * <p>⚠️ <b>同样不另开执行入口</b>：它与原生工具、MCP-backed 工具共用
     * 本类这一条路径，否则八道闸门对 HTTP 工具全部失效。
     */
    private final cn.studykid.multiagent.ports.http.HttpToolHandlerProvider httpToolHandlers;

    /**
     * 委派执行器（多级 Agent v1.1 · P2-MA-02）。
     *
     * <p>⚠️ <b>可为空 = 多级委派未装配</b>：此时模型若请求委派，
     * 会按「未登记的工具」被拒 —— 不存在「静默放行委派」的路径。
     */
    private final cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation;

    /**
     * 时钟源（用于计算根 Run 的剩余业务时间）。
     *
     * <p>⚠️ <b>可注入是为了可测</b>：「剩余时间已耗尽 → 在新增子调用前拒绝」
     * 这条分支若用 {@code Instant.now()} 就无法被稳定覆盖。
     */
    private final Clock clock;

    /**
     * Run 级预算探针（第七批 · MA-T17）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时不做 Run 级判定（仅租户级预算生效）。
     * 生产装配<b>必须</b>传入 —— 否则「全调用树共享预算上限」在
     * <b>工具调用路径</b>上是空的（子 Agent 可以把 Run 额度烧穿）。
     */
    private final RunBudgetProbe runBudget;

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
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, capFlow, mcpHandlers,
                null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1）：额外注入委派执行器。
     *
     * <p>⚠️ 委派分支就在<b>本类内部</b>：所有工具调用（含委派）共用这一个入口，
     * 不存在第二条路径（ADR-011 防绕过）。
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
                              cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers,
                              cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, capFlow, mcpHandlers,
                delegation, Clock.systemUTC(), null);
    }

    /**
     * 兼容构造器（第六批）：带时钟源，但<b>未装配</b> Run 级预算探针。
     *
     * <p>⚠️ 走此路径时「全调用树共享预算上限」在工具调用路径上不生效
     * （仅租户级预算有效）—— 生产装配走 14 参版本。
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
                              cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers,
                              cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation,
                              @NonNull Clock clock) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, capFlow, mcpHandlers,
                delegation, clock, null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1 · 第六批）：额外注入时钟源。
     *
     * <p>⚠️ <b>时钟必须可注入</b>：委派分支要用「根 Run 的真实剩余时间」
     * 决定是否允许新增子调用。若直接调 {@code Instant.now()}，
     * 「剩余时间已耗尽」这个分支就<b>没法被测试覆盖</b> ——
     * 用例只能靠「睡一会儿」去撞时间窗，那类测试要么慢、要么不稳定。
     *
     * @param clock 时钟源（生产用 {@code Clock.systemUTC()}）
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
                              cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers,
                              cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation,
                              @NonNull Clock clock,
                              RunBudgetProbe runBudget) {
        this(handlers, authorization, approvalPolicy, budget, ledger, approvalPort,
                reserveAmountPerToolCall, cancelGuard, approvalSource, capFlow, mcpHandlers,
                delegation, clock, runBudget, null);
    }

    /**
     * 最完整构造器（第三十七批）：额外注入 HTTP-backed 工具解析器。
     *
     * <p>⚠️ <b>HTTP 工具同样不另开执行入口</b>（ADR-011 / 07 §3）：
     * 它与原生工具、MCP-backed 工具共用本类这一条路径，
     * 八道闸门（Schema 校验 / 执行时重授权 / 审批 / 预算 / 账本 /
     * 执行准入 / 执行意图 / 结果落盘）一视同仁。
     *
     * <p>⚠️ <b>新增依赖只加在这一个构造器上</b>（项目既有纪律）：
     * 其余构造器保持不变并委托 —— 否则构造器数量继续膨胀，
     * 而 Spring 在多个同形构造器之间无法选择（F-6）。
     *
     * @param httpToolHandlers HTTP 工具解析器（可为 null = 未装配 → HTTP 工具一律解析不到）
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
                              cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlers,
                              cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation,
                              @NonNull Clock clock,
                              RunBudgetProbe runBudget,
                              cn.studykid.multiagent.ports.http.HttpToolHandlerProvider httpToolHandlers) {
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
        this.delegation = delegation;
        this.clock = clock;
        this.runBudget = runBudget;
        this.httpToolHandlers = httpToolHandlers;
        if (runBudget == null) {
            // ⚠️ 明确告警：未装配 Run 级预算 = 工具调用路径上没有「这一棵树」的额度约束。
            //    这不是错误（测试与旧装配走此路径），但生产必须装配。
            log.warn("未装配 Run 级预算探针：工具调用路径只受**租户级**预算约束，"
                    + "「全调用树共享预算上限」在该路径上不生效");
        }
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
        return invokeAll(tenantId, task, calls, null);
    }

    /**
     * 带<b>执行作用域</b>的工具调用（多级 Agent v1.1 · FR-025）。
     *
     * <p>⚠️ <b>子 Agent 的工具调用必须走本重载</b>：默认路径按 Run 快照里的
     * <b>根 Agent</b> 解析闭包 —— 若子 Agent 沿用该口径，
     * 它就能调用父 Agent 的工具（权限下放 = Sev-0）。
     *
     * @param scope 执行作用域；{@code null} = 根 Agent（单 Agent 路径）
     * @return 汇总结果
     */
    public ToolInvocationResult invokeAll(
            String tenantId,
            RunClaimPort.LeasedTask task,
            Map<String, ModelResponse.ToolCallRequest> calls,
            @Nullable ExecutionScope scope) {
        return invokeAll(tenantId, task, calls, scope, null);
    }

    /**
     * 带<b>委派上下文</b>的工具调用（多级 Agent v1.1 · 第十五批）。
     *
     * <p>⚠️ <b>子 Agent 发起委派时必须带本重载</b>：
     * {@code delegationContext} 说明「当前执行中的 Agent 是谁、处在调用树的哪一层」。
     * 缺失它（传 {@code null}）时，委派会被按 <b>Run 快照里的根 Agent</b> 判定 ——
     * 于是子 Agent 能借根 Agent 的编排声明，越权委派给「根声明过、而自己没声明」的 Agent，
     * 且「最大委派深度」在嵌套路径上完全失效。
     *
     * <p>⚠️ <b>与 {@code scope} 的一致性会被校验</b>：两者同时存在时，
     * Agent 标识与版本必须一致 —— 不一致说明上下文串了（多半是调用方拼错了），
     * 此时<b>委派一律拒绝</b>（fail-closed），不猜哪个是对的。
     *
     * @param scope             执行作用域；{@code null} = 根 Agent（单 Agent 路径）
     * @param delegationContext 委派上下文；{@code null} = 根 Agent（按 Run 快照解析父）
     * @return 汇总结果
     */
    public ToolInvocationResult invokeAll(
            String tenantId,
            RunClaimPort.LeasedTask task,
            Map<String, ModelResponse.ToolCallRequest> calls,
            @Nullable ExecutionScope scope,
            @Nullable DelegationContext delegationContext) {

        if (capFlow != null && capFlow.isEnabled()) {
            return invokeAllCap(tenantId, task, calls, scope, delegationContext);
        }
        // ⚠️ 既有链路同样带委派分支（第十九批）：委派不是 CAP 授权链路的能力，
        //    它的授权与边界由 DelegationGuard 承担 ——
        //    CAP 开关管的是「用哪条授权链路」，不是「多级 Agent 是否可用」。
        return invokeAllLegacy(tenantId, task, calls, scope, delegationContext);
    }

    /**
     * 解析「当前执行中的 Agent 引用」（幂等键的 Agent 维度 · 第十七批）。
     *
     * <p>⚠️ <b>取值优先级：执行作用域 → 委派上下文 → 无</b>。
     * 两者都存在时闸门已校验二者标识与版本一致（不一致 → 拒绝委派），
     * 故这里取哪个都一样；只有一个存在时取那一个；
     * 都没有 = 根 Agent（单 Agent 路径）→ <b>不带 Agent 维度</b>，
     * 键值与历史完全一致（在途的 {@code tool_operation} 行因此不受影响）。
     *
     * @param scope            执行作用域；{@code null} = 根 Agent
     * @param delegationContext 委派上下文；{@code null} = 根 Agent
     * @return Agent 引用；{@code null} = 无（单 Agent 路径）
     */
    private static @Nullable String resolveAgentRef(
            @Nullable ExecutionScope scope,
            @Nullable DelegationContext delegationContext) {
        if (scope != null) {
            return scope.agentRef();
        }
        if (delegationContext != null) {
            return delegationContext.agentRef();
        }
        return null;
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
            Map<String, ModelResponse.ToolCallRequest> calls,
            @Nullable ExecutionScope scope,
            @Nullable DelegationContext delegationContext) {

        ToolInvocationResult result = new ToolInvocationResult();
        // ⚠️⚠️ Agent 维度（第十七批）：幂等键必须按「当前执行中的 Agent」分域，
        //    否则父 Agent 与子 Agent（或两个兄弟子 Agent）调用
        //    **同名工具 + 同名 model call id**（模型通常都叫 call_1）会算出同一个键
        //    → 账本命中重复键 → 后者被当成前者的重放 → **那次工具调用根本没发生**，
        //    且模型拿到的还是**另一个 Agent 的结果**。
        //    键在这里算一次，透传给 CAP 执行流 —— 两条链路不得各算各的。
        String agentRef = resolveAgentRef(scope, delegationContext);
        // ⚠️ 父声明的失败策略（FAIL_FAST / CONTINUE_ON_ERROR）决定：
        //    本轮里某个子调用失败后，**后续的委派调用**还跑不跑。
        boolean delegationHalted = false;

        for (ModelResponse.ToolCallRequest call : calls.values()) {
            // ── 委派分支（两条执行链路共用 · 第十九批）────────────
            //    ⚠️ 委派**不是**普通工具：它不进 Skill→Tool 能力闭包，
            //    授权走 DelegationGuard（逐跳授权 + 深度/次数/超时/预算）。
            //    但它仍在本类内部执行 —— 保持「唯一执行入口」不变。
            //    ⚠️ **两条链路必须共用同一段代码**：修复前委派分支只存在于
            //    CAP 链路，既有链路里委派调用被当成「未登记的工具」拒掉 ——
            //    而既有链路正是**生产默认路径**（CAP 开关默认 false），
            //    于是多级 Agent 在生产上根本不可用。
            if (isDelegationCall(call)) {
                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {
                    delegationHalted = true;
                }
                continue;
            }
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
                    capFlow.invoke(tenantId, task, call, handler, scope, agentRef);
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
     * 是否是委派调用（委派**不是**普通工具，不注册 {@code ToolHandler}）。
     *
     * <p>⚠️ <b>未装配委派执行器时返回 {@code false}</b>：
     * 那时委派调用会落回「未登记的工具」被拒（而不是静默放行）——
     * 两条链路同口径，既有测试 {@code delegationIsRejectedWhenExecutorIsMissing} 钉死这一点。
     *
     * @param call 模型请求的工具调用
     * @return 是否走委派分支
     */
    private boolean isDelegationCall(ModelResponse.ToolCallRequest call) {
        return delegation != null
                && cn.studykid.multiagent.application.orchestration.DelegationExecutor
                        .DELEGATION_TOOL_NAME.equals(call.toolName());
    }

    /**
     * 委派分支（<b>两条执行链路共用</b> · 第十九批）。
     *
     * <p>⚠️ <b>为什么必须共用</b>：修复前这段逻辑只写在 CAP 链路里，
     * 于是同一个委派调用在两条链路上给出<b>不同的结论</b>
     * （CAP 链路：委派成功；既有链路：「未登记的工具」被拒）。
     * 既有链路是**生产默认路径**（CAP 开关默认 false）——
     * 那意味着「多级 Agent 在生产上根本不可用」，而代码看上去是齐的。
     *
     * <p>⚠️ 委派**不经过**普通工具的那几道闸门（Schema → 授权 → 幂等 → 账本）：
     * 委派不是工具，它的授权与边界由 {@code DelegationGuard} 承担
     * （逐跳授权 + 深度/次数/超时/预算），副作用由 {@code agent_invocation} 承担。
     *
     * @param delegationHalted 本轮是否已因 FAIL_FAST 停止后续委派
     * @return 是否应当停止本轮的后续委派（失败策略生效 / 结果不确定）
     */
    private boolean handleDelegationCall(String tenantId,
                                         RunClaimPort.LeasedTask task,
                                         ModelResponse.ToolCallRequest call,
                                         ToolInvocationResult result,
                                         @Nullable ExecutionScope scope,
                                         @Nullable DelegationContext delegationContext,
                                         boolean delegationHalted) {
        // ⚠️ FAIL_FAST 之后不再发起后续委派（父声明的失败策略）
        if (delegationHalted) {
            log.info("失败策略 FAIL_FAST：跳过本轮后续委派: tool={}, runId={}",
                    call.toolName(), task.runId());
            result.recordRejected(call.toolName(),
                    cn.studykid.multiagent.core.cap.ReasonCode.DELEGATION_NOT_ALLOWED.name());
            return false;
        }
        // ⚠️ 上下文一致性校验（第十五批）：scope 与 delegationContext 同时存在时，
        //    Agent 标识与版本必须一致 —— 不一致说明上下文串了。
        //    此时**委派一律拒绝**（fail-closed）：猜哪个是对的，等于在越权路径上
        //    替调用方做决定。
        if (scope != null && delegationContext != null
                && (!scope.agentId().equals(delegationContext.agentId())
                        || scope.agentVersion() != delegationContext.agentVersion())) {
            log.error("执行作用域与委派上下文不一致，拒绝委派: runId={}, scopeAgent={},"
                            + " ctxAgent={}", task.runId(), scope.agentId(),
                    delegationContext.agentId());
            result.recordRejected(call.toolName(),
                    cn.studykid.multiagent.core.cap.ReasonCode.ORCHESTRATION_INVALID.name());
            result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                    call.toolName(), null,
                    cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                            cn.studykid.multiagent.core.cap.ReasonCode.ORCHESTRATION_INVALID)));
            return false;
        }
        return invokeDelegation(tenantId, task, call, result, delegationContext);
    }

    /**
     * 委派分支（多级 Agent v1.1 · P2-MA-02 · FR-024 / FR-025 / FR-026）。
     *
     * <p>⚠️ <b>委派不是普通工具</b>：它不进 Skill→Tool 能力闭包，
     * 授权与边界由 {@code DelegationGuard} 判定（逐跳授权 + 深度/次数/超时/预算），
     * 幂等与可追溯由 {@code agent_invocation} 承担。
     *
     * <p>⚠️ <b>取消检查在委派之前</b>：取消后不得发起新的子执行
     * （与工具动作同一口径，MA-T11）。
     *
     * <p>⚠️ <b>被拒的委派不产生任何子执行</b>：不调度子 Agent、不写副作用。
     *
     * <p>⚠️ <b>「拒绝」与「执行失败」必须分开记账</b>（§5.1 四字段）：
     * 子执行<b>从未启动</b> → 授权/边界拒绝（{@code authorizationDecision=DENY}）；
     * 子执行<b>已启动但失败</b> → 执行失败（{@code authorizationDecision=ALLOW +
     * executionDecision=DENY}）。混为一谈会让审计把「执行失败」说成「越权被拒」。
     *
     * @return 是否应当停止本轮的后续委派（失败策略生效 / 结果不确定）
     */
    private boolean invokeDelegation(String tenantId,
                                     RunClaimPort.LeasedTask task,
                                     ModelResponse.ToolCallRequest call,
                                     ToolInvocationResult result,
                                     @Nullable DelegationContext delegationContext) {
        if (cancelGuard.isCancelled(tenantId, task.runId())) {
            log.info("Run 已取消，拒绝发起委派: runId={}", task.runId());
            result.recordRejected(call.toolName(),
                    cn.studykid.multiagent.core.cap.ReasonCode.RUN_CANCELLED.name());
            result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                    call.toolName(), null,
                    cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                            cn.studykid.multiagent.core.cap.ReasonCode.RUN_CANCELLED)));
            return false;
        }

        cn.studykid.multiagent.application.orchestration.DelegationArguments.Parsed parsed;
        try {
            parsed = cn.studykid.multiagent.application.orchestration.DelegationArguments
                    .parse(call.arguments());
        } catch (IllegalArgumentException e) {
            // ⚠️ 参数非法 → 与普通工具同码（结构校验失败），不预占任何资源
            log.info("委派参数非法，拒绝执行: runId={}, err={}", task.runId(), e.getMessage());
            result.recordRejected(call.toolName(),
                    cn.studykid.multiagent.core.cap.ReasonCode.PARAM_SCHEMA_FAIL.name());
            result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                    call.toolName(), null,
                    cn.studykid.multiagent.core.cap.AuthorizationDecision.executionDeny(
                            cn.studykid.multiagent.core.cap.ReasonCode.PARAM_SCHEMA_FAIL)));
            return false;
        }

        // ⚠️ 父调用标识参与 invocationId 派生（第十五批）：
        //    派生材料的定义是「同一 Run + **同一父调用** + 同一模型 tool call」，
        //    而此处此前恒传 null → 嵌套路径下，不同子 Agent 的**同名** tool call
        //    （例如各自的 call_1）会派生出**同一个** invocationId
        //    → 后者被当成前者的幂等重放，第二次委派**根本没发生**。
        String invocationId = cn.studykid.multiagent.application.orchestration
                .DelegationInvocationId.derive(task.runId(),
                        delegationContext == null ? null : delegationContext.invocationId(),
                        call.callId());
        // ⚠️ 根 Run 的**真实**剩余时间：来自 Run 的业务截止时间（run.deadline_at）。
        //    「全调用树共享超时上限」要求根 Run 的剩余时间是整棵树的共同上界，
        //    任何一跳都不得重置它 —— 此前这里传的是上界 Long.MAX_VALUE，
        //    于是该约束在根层从未真正生效：父 Agent 可以无限次委派，
        //    每一跳的子 Agent 都能跑满自己的 timeoutMs。
        //    有效预算 = min(根 Run 剩余时间, 本级声明的 timeoutMs)，
        //    由守卫计算并**逐跳下传**给子执行实施墙钟截止。
        long remainingMillis = task.remainingMillis(clock.instant());
        if (remainingMillis <= 0) {
            // ⚠️⚠️ 根 Run 的时间已耗尽 → **在新增子调用前**拒绝（MA-T09）。
            //    此时不得调度子 Agent：子执行一旦启动就会产生真实副作用与成本，
            //    而它注定跑不完 —— 那是「明知会超时还要先花钱」。
            log.warn("根 Run 剩余时间已耗尽，拒绝新增子调用: runId={}, tool={}",
                    task.runId(), call.toolName());
            result.recordRejected(call.toolName(),
                    cn.studykid.multiagent.core.cap.ReasonCode.DELEGATION_TIMEOUT.name());
            result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                    call.toolName(), null,
                    cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                            cn.studykid.multiagent.core.cap.ReasonCode.DELEGATION_TIMEOUT)));
            return false;
        }
        // ── 父身份与深度（第十五批）────────────────────────────────
        // ⚠️ 有委派上下文 → 父就是**当前执行中的 Agent**（嵌套委派）；
        //    无委派上下文 → 父由 Run 能力快照解析（根 Agent），深度为 1。
        String parentAgentId = null;
        Integer parentAgentVersion = null;
        String parentInvocationId = null;
        int parentDepth = 1;
        if (delegationContext != null) {
            parentAgentId = delegationContext.agentId();
            parentAgentVersion = delegationContext.agentVersion();
            parentInvocationId = delegationContext.invocationId();
            parentDepth = delegationContext.depth();
        }
        // ⚠️⚠️ Run 级预算（第十六批 · MA-T17 · 全调用树共享上限）：
        //    委派**必须**与工具调用走同一套预算口径。
        //    修复前委派分支直接绕过「Run 级金额判定 + 预算预留」——
        //    一次委派会启动子 Agent 的模型调用（**真实成本**）却**不占任何额度**，
        //    于是「全调用树共享预算上限」对委派完全失效：
        //    `maxChildInvocations` 是**每父 Agent** 的（8 × 8 × 8 = 512 次子调用），
        //    Run 额度本是唯一的兜底，而它恰好对委派不管用。
        //
        //    ⚠️ **判在预留之前**：预留成功即已占额，判晚了就是「先花钱再检查」。
        String reservationId = reserveDelegationQuota(tenantId, task, invocationId,
                call.toolName(), result);
        if (reservationId == null) {
            // ⚠️ 预算不足 / 预留失败 → **零子执行**：
            //    子 Agent 一旦启动就是真实成本，而它注定会被预算拒绝。
            return false;
        }

        var outcome = delegation.delegate(
                new cn.studykid.multiagent.application.orchestration.DelegationExecutor
                        .DelegationCommand(
                        tenantId,
                        task.runId(),
                        // ⚠️⚠️ 父身份必须来自**当前执行中的 Agent**（第十五批），
                        //    不能恒为 null（= 按 Run 快照解析根 Agent）：
                        //    恒为 null 时，子 Agent 发起的委派会被按**根 Agent** 的
                        //    编排声明判定 → 子 Agent 能借根的声明把工作交给
                        //    「根声明过、而自己没声明」的 Agent —— 越权路径。
                        //    深度同理：恒为 1 会让「最大委派深度」在嵌套路径上失效。
                        parentAgentId,
                        parentAgentVersion,
                        parentInvocationId,
                        parentDepth,
                        parsed.targetAgentId(),
                        null,
                        parsed.input(),
                        invocationId,
                        task.runId(),
                        remainingMillis,
                        task.fencingToken(),
                        task.attempt(),
                        // ⚠️ 根 Run 的业务截止时刻**逐跳下传**（第十五批 · C15-2）：
                        //    task.deadlineAt 在根路径上就是 run.deadline_at，
                        //    在嵌套路径上由 WorkerChildAgentRunner 原样传下来 ——
                        //    它是整棵调用树共享的**绝对**时间上界。
                        //    只传「本跳剩余毫秒数」的话，每跳都会按自己的声明重新起算，
                        //    根 Run 的剩余时间在嵌套路径上会被重置。
                        task.deadlineAt()));

        var reasonCode = cn.studykid.multiagent.core.cap.ReasonCode
                .parse(outcome.reasonCode());
        if (reasonCode == null) {
            reasonCode = cn.studykid.multiagent.core.cap.ReasonCode.TOOL_EXECUTION_FAILED;
        }

        switch (outcome.status()) {
            case SUCCEEDED -> {
                // ⚠️ 成本已发生 → **结算**预留（第十六批）：
                //    子 Agent 跑过了，模型调用的钱已经花出去，不能退回额度。
                settleQuietly(tenantId, reservationId, call.toolName());
                result.recordSucceeded(call.toolName());
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        call.toolName(), outcome.invocationId(),
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.executionSucceeded(
                                cn.studykid.multiagent.core.cap.AuthorizationDecision.Ledger.RECORDED)));
                return false;
            }
            case UNKNOWN -> {
                // ⚠️ 结果不确定 → 走对账，不盲目重放（MA-T13）
                //    ⚠️ 且**立即停止本轮后续调用**：可能已有副作用发生，
                //    继续启动新动作会扩大不确定面（与普通工具路径同口径）。
                //    ⚠️ **预留既不结算也不释放**（R-007 红线）：
                //    成本是否发生**未知** → 既不能认定已花（结算），
                //    也不能当没花（释放 = 白送额度）。留在 RESERVED 待对账。
                result.recordUnknown(call.toolName(), outcome.invocationId());
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        call.toolName(), outcome.invocationId(),
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.executionUnknown(
                                cn.studykid.multiagent.core.cap.AuthorizationDecision.Ledger.RECORDED,
                                cn.studykid.multiagent.core.cap.ReasonCode.EXEC_UNKNOWN)));
                return true;
            }
            default -> {
                if (!outcome.childStarted()) {
                    // ⚠️ 子执行**从未启动** → 这是授权/边界拒绝（Phase A），
                    //    不是「子调用失败」，故**不触发** failurePolicy。
                    //    ⚠️ **释放**预留（第十六批）：子 Agent 从未运行 → 零成本 →
                    //        额度必须原样退回（与工具「确定失败 → 释放」同口径）。
                    releaseQuietly(tenantId, reservationId, "delegation-rejected");
                    result.recordRejected(call.toolName(), reasonCode.name());
                    result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                            call.toolName(), outcome.invocationId(),
                            cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(reasonCode)));
                    return false;
                }
                // ⚠️ 子执行**已启动但失败** → 执行失败（Phase B）：
                //    授权已通过，失败发生在执行阶段（§5.1）。
                //    ⚠️ **结算**预留：子 Agent 已经跑过（模型调用已发生）→
                //    成本是真的，不能退回额度（与工具「确定失败 → 释放」**刻意不同**：
                //    工具的确定失败 = 工具没产生副作用；子执行的失败 = 子 Agent 已运行）。
                settleQuietly(tenantId, reservationId, call.toolName());
                result.recordFailed(call.toolName(), reasonCode.name());
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        call.toolName(), outcome.invocationId(),
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.executionFailed(
                                cn.studykid.multiagent.core.cap.AuthorizationDecision.Ledger.RECORDED,
                                reasonCode)));
                // ⚠️ 失败策略（07 第 6 步）：FAIL_FAST 时本轮后续委派不再发起；
                //    CONTINUE_ON_ERROR 时继续（由调用方汇总）。
                return shouldHaltAfterFailure(tenantId, task.runId());
            }
        }
    }

    /**
     * 委派路径的预算预留（第十六批 · MA-T17）。
     *
     * <p>⚠️ <b>为什么委派也必须占额度</b>：
     * 一次委派会启动子 Agent，子 Agent 会调模型 —— 那是<b>真实成本</b>，
     * 且多级场景下它能<b>指数放大</b>（每层 Agent 各自的 {@code maxChildInvocations}
     * 相乘）。Run 额度是唯一的 Run 级上限，若委派不占额，
     * 「这一棵调用树最多花多少」在多级场景下就是<b>一句空话</b>。
     *
     * <p>⚠️ <b>预留额取「一次调用当量」</b>：与工具调用同额
     * （{@code reserveAmountPerToolCall}）。
     * <b>这是本批取舍，非评审冻结的定价模型</b>：预算体系当前按「调用当量」计量，
     * 真实成本由 {@code usage_record} 独立记录（P3-D04 §6 L-5）；
     * 一次委派的真实成本（子 Agent 的多轮模型调用）**高于**一次工具调用当量，
     * 因此本口径是<b>保守下界</b>，不是精确计价。
     * ⚠️ **模型调用本身仍未计入 Run 额度** —— 那需要冻结成本模型（token 计价），
     * 属未闭环范围 → 已登记为缺口。
     *
     * @param tenantId    租户标识
     * @param task        根 Run 任务
     * @param invocationId 委派调用标识（<b>幂等键</b>：重复投递复用同一笔预留）
     * @param toolName    委派工具名（仅用于日志与拒绝原因）
     * @param result      汇总结果；被拒时已写入拒绝原因
     * @return 预留 ID；<b>null 表示已被拒绝</b>（此时已写入拒绝原因，且<b>零子执行</b>）
     */
    private @Nullable String reserveDelegationQuota(String tenantId,
                                                    RunClaimPort.LeasedTask task,
                                                    String invocationId,
                                                    String toolName,
                                                    ToolInvocationResult result) {
        // ⚠️ ① Run 级**金额**判定（先判、后预留）：
        //    只判「还有没有余额」时，剩余 10 元的 Run 仍能预留 50 元 ——
        //    单次委派即突破上限，共享上限名存实亡。
        if (runBudget != null) {
            RunBudgetProbe.Verdict verdict = runBudget.check(tenantId, task.runId(),
                    reserveAmountPerToolCall);
            if (!verdict.allowed()) {
                log.warn("Run 级预算不足，拒绝委派: runId={}, reason={}",
                        task.runId(), verdict.denial());
                result.recordRejected(toolName, "Run 级预算不足:" + verdict.denial());
                result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                        toolName, null,
                        cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                                cn.studykid.multiagent.core.cap.ReasonCode.BUDGET_EXCEEDED)));
                return null;
            }
        }
        // ⚠️ ② 权威预留（与工具调用同一份核心）：Run 级上限在 reserve 的同一事务里
        //    原子生效（锁 run 行 → 聚合 → 判定 → 扣减）。
        QuotaOutcome outcome = reserveQuota(tenantId, task.runId(), invocationId, toolName);
        if (!outcome.granted()) {
            // ⚠️ 委派**不记账本**（委派有独立的 agent_invocation 记录），
            //    但拒绝原因必须留痕（否则这次委派会从系统里彻底消失）。
            log.warn("委派预算预留被拒，零子执行: runId={}, operation={}, reason={}",
                    task.runId(), invocationId, outcome.resultReason());
            result.recordRejected(toolName, outcome.resultReason());
            result.recordDecision(new cn.studykid.multiagent.core.cap.CapToolDecision(
                    toolName, null,
                    cn.studykid.multiagent.core.cap.AuthorizationDecision.deny(
                            cn.studykid.multiagent.core.cap.ReasonCode.BUDGET_EXCEEDED)));
            return null;
        }
        return outcome.reservationId();
    }

    /**
     * 判断子调用失败后是否应停止本轮的后续委派。
     *
     * <p>⚠️ <b>读不到策略时按 FAIL_FAST</b>（最保守）：
     * 「继续跑」意味着失败被静默吞掉，父 Agent 拿到的是残缺聚合结果，
     * 却以为那是完整的 —— 那比直接失败更危险。
     *
     * @param tenantId 租户标识
     * @param runId    根 Run 标识
     * @return 是否停止后续委派
     */
    private boolean shouldHaltAfterFailure(String tenantId, String runId) {
        var policy = delegation.failurePolicyOf(tenantId, runId);
        boolean halt = policy == cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy
                .FAIL_FAST;
        if (halt) {
            log.info("失败策略 FAIL_FAST：停止本轮后续委派: runId={}", runId);
        }
        return halt;
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
            // ⚠️ 成功时把工具输出一并记下（回灌用）；拒绝/失败没有可回灌的结果
            case SUCCEEDED -> result.recordSucceeded(decision.toolName(), decision.output());
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
            Map<String, ModelResponse.ToolCallRequest> calls,
            @Nullable ExecutionScope scope,
            @Nullable DelegationContext delegationContext) {

        ToolInvocationResult result = new ToolInvocationResult();
        // ⚠️ 既有链路也必须带 Agent 维度（第十七批）：幂等键是**两条链路共用**的账本键，
        //    只在 CAP 链路加分域的话，迁移开关一关就退回「父子互相吞调用」。
        String agentRef = resolveAgentRef(scope, delegationContext);
        // ⚠️ 父声明的失败策略（FAIL_FAST / CONTINUE_ON_ERROR）决定：
        //    本轮里某个子调用失败后，**后续的委派调用**还跑不跑。
        boolean delegationHalted = false;

        for (ModelResponse.ToolCallRequest call : calls.values()) {
            // ── 委派分支（两条执行链路共用 · 第十九批）────────────
            //    ⚠️ 必须排在「未登记的工具」判定**之前**：
            //    委派不是普通工具（不注册 ToolHandler），
            //    若先做 handler 查找，委派调用会被当成「未登记的工具」拒掉。
            if (isDelegationCall(call)) {
                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {
                    delegationHalted = true;
                }
                continue;
            }
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
            // ⚠️ 必须传 Agent 维度（第十七批）：键缺 Agent 维度会父子/兄弟 Agent 互相吞调用
            String idemKey = buildIdempotencyKey(task.runId(), call, agentRef);

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

            // ⑥ ⚠️⚠️ Run 级预算判定（MA-T17 · 全调用树共享上限）：
            //    必须在**预留之前**判 —— 预留成功即已占额，判晚了就等于
            //    「先花钱再检查」。所有深度的工具调用都记在同一个 run_id 下，
            //    因此这一道判定就是「这一整棵树还能花多少」的守门人；
            //    嵌套 Agent 无法通过「自己再开一份额度」来绕过它。
            //    ⚠️ 按**量**判（剩余额度 ≥ 本次预留额）：只判「还有没有余额」时，
            //    剩余 10 元的 Run 仍能预留 50 元 —— 单次调用就能突破上限。
            if (runBudget != null) {
                RunBudgetProbe.Verdict verdict = runBudget.check(
                        tenantId, task.runId(), reserveAmountPerToolCall);
                if (!verdict.allowed()) {
                    log.warn("Run 级预算不足，拒绝工具调用: tool={}, runId={}, reason={}",
                            call.toolName(), task.runId(), verdict.denial());
                    recordFailed(tenantId, registration.operationId(), task.fencingToken(),
                            verdict.reasonCode());
                    result.recordRejected(call.toolName(),
                            "Run 级预算不足:" + verdict.denial());
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
                // ⚠️ 已记录拒绝原因与账本状态（见 reserveBudget 的契约），
                //    继续下一个工具；不得在此静默丢弃该次调用。
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
     * 预算预留核心（<b>工具调用与委派共用同一份</b> · 第十六批）。
     *
     * <p>⚠️ <b>为什么必须共用一份</b>：委派与工具调用消耗的是<b>同一个 Run 的同一笔额度</b>
     * （{@code budget_reservation.run_id}），口径一旦分叉就会出现
     * 「工具算一次、委派不算」的裂缝 —— 那正是本批修掉的缺陷。
     * 两份实现必然漂移，而漂移就是「两个真相」本身。
     *
     * <p>⚠️ <b>本方法不记账本</b>：账本只登记<b>工具操作</b>，
     * 委派有独立的 {@code agent_invocation} 记录 —— 记账与否由调用方决定。
     *
     * @param tenantId    租户标识
     * @param runId       根 Run 标识（额度共享域）
     * @param operationId 幂等键（工具用账本 operationId，委派用 invocationId）
     * @param toolName    工具/委派名（仅用于日志与拒绝原因）
     * @return 预留结论
     */
    private @NonNull QuotaOutcome reserveQuota(String tenantId, String runId,
                                                String operationId, String toolName) {
        BudgetPort.ReserveOutcome outcome = budget.reserve(
                tenantId, runId, operationId, reserveAmountPerToolCall);

        if (outcome == BudgetPort.ReserveOutcome.RESERVED
                || outcome == BudgetPort.ReserveOutcome.REPLAYED) {
            // ⚠️ REPLAYED 也返回预留 ID：同 operation 重复调用复用既有预留，
            //    这是「预留有唯一记录」在执行链上的体现
            Optional<BudgetPort.Reservation> reservation =
                    budget.findByOperation(tenantId, operationId);
            if (reservation.isPresent()) {
                return new QuotaOutcome(true, reservation.get().reservationId(), null, null);
            }
            // ⚠️⚠️ **预留已生效却查不到预留记录 → 不得静默丢弃这次调用**
            //
            //    没有预留 ID 就无法结算/释放，执行了必然留下一笔无人认领的成本；
            //    故 fail-closed：调用**不执行**，并留下明确的拒绝原因。
            //    若预留行确实已落库，它会变成悬挂预留，由对账（findOrphaned）释放。
            log.error("预算预留已生效但查不到预留记录，fail-closed 拒绝执行: tool={}, runId={}, operation={}",
                    toolName, runId, operationId);
            return new QuotaOutcome(false, null,
                    "预算预留记录缺失（预留已生效但不可见）", "预算预留记录缺失");
        }

        log.warn("预算预留被拒: tool={}, runId={}, outcome={}",
                toolName, runId, outcome);
        // ⚠️ INSUFFICIENT 与 NO_ACCOUNT 严格区分：
        //    前者要加额度，后者要**开通账户** —— 运维处置方向完全不同
        String reason = switch (outcome) {
            case INSUFFICIENT -> "预算不足";
            case NO_ACCOUNT -> "未开通预算账户";
            case FAIL_CLOSED -> "预算配置缺失";
            default -> "预算预留被拒";
        };
        return new QuotaOutcome(false, null, reason, "预算预留被拒:" + outcome);
    }

    /**
     * 预算预留结论。
     *
     * @param granted        是否已获得额度
     * @param reservationId  预留 ID（获得额度时非空）
     * @param resultReason   面向调用方/模型的拒绝原因（拒绝时非空）
     * @param ledgerReason   面向工具账本的拒绝原因（拒绝时非空；委派路径不用）
     */
    private record QuotaOutcome(boolean granted,
                                @Nullable String reservationId,
                                @Nullable String resultReason,
                                @Nullable String ledgerReason) {
    }

    /**
     * 预留预算（工具调用路径）。
     *
     * @param result 汇总结果；被拒时已写入拒绝原因
     * @return 预留 ID；<b>null 表示已被拒绝</b> —— 且此时<b>必然已</b>写入拒绝原因
     *         并把账本记为 FAILED（工具根本没发出），调用方只需停止该次调用，
     *         不得再执行；⚠️ 绝不允许「返回 null 却不留痕」（那次调用会凭空消失）
     * @throws RuntimeException 预留层异常 —— 调用方按 fail-closed 处理
     */
    private String reserveBudget(String tenantId, RunClaimPort.LeasedTask task,
                                 RunToolLedgerPort.Registration registration,
                                 String toolName,
                                 ToolInvocationResult result) {
        QuotaOutcome outcome = reserveQuota(tenantId, task.runId(),
                registration.operationId(), toolName);
        if (outcome.granted()) {
            return outcome.reservationId();
        }
        // ⚠️ 工具路径必须记账本：工具**根本没有发出**，结果是确定的「未执行」，
        //    记 UNKNOWN 会造出待对账的幽灵操作（见 recordFailed 的契约）。
        recordFailed(tenantId, registration.operationId(), task.fencingToken(),
                outcome.ledgerReason());
        result.recordRejected(toolName, outcome.resultReason());
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
        if (mcpHandlers != null) {
            ToolHandler mcp = mcpHandlers.resolve(toolName).orElse(null);
            if (mcp != null) {
                return mcp;
            }
        }
        // ⚠️ HTTP 工具同样走**同一条**路径（第三十七批 · ADR-011）：
        //    「远程 HTTP 请求」只是 Tool 的一种种类，不是另一条执行链路。
        //    把它放到闸门之外，等于让八道闸门对全部 HTTP 工具失效。
        if (httpToolHandlers != null) {
            return httpToolHandlers.resolve(toolName).orElse(null);
        }
        return null;
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
    private String buildIdempotencyKey(String runId, ModelResponse.ToolCallRequest call,
                                        @Nullable String agentRef) {
        Object studentId = call.arguments().get("studentId");
        Object knowledgePoint = call.arguments().get("knowledgePoint");
        return IdempotencyKey.forToolCall(runId, agentRef, call.toolName(), call.callId(),
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
         * 成功工具的输出（工具名 → 输出文本）。
         *
         * <p>⚠️ <b>这是「工具结果回灌」的载体</b>：模型必须能看到自己刚调了什么，
         * 否则多轮工具协作不成立（子 Agent 调完工具即结束，等于工具调用无意义）。
         * <p>⚠️ 输出是<b>不可信数据</b>：回灌进上下文时必须标记边界，
         * 不得让工具输出被当成系统/开发者指令（MA-T14）。
         */
        private final java.util.Map<String, String> outputs = new java.util.LinkedHashMap<>();
        /**
         * 拒绝原因（工具名 → 原因文本）。
         *
         * <p>⚠️ <b>拒绝原因必须留痕</b>：08 §4 要求「非笼统 DENY 占比 100%」——
         * 只记「哪个工具被拒」而不记「为什么」，审计就只能说「被拒了」，
         * 既无法分类，也无法分诊。
         */
        private final java.util.Map<String, String> rejectionReasons =
                new java.util.LinkedHashMap<>();

        void recordSucceeded(String tool, ToolHandler.ToolResult r) {
            recordSucceeded(tool, r == null ? null : r.output());
        }

        void recordSucceeded(String tool, String output) {
            succeeded.add(tool);
            if (output != null && !output.isBlank()) {
                outputs.put(tool, output);
            }
        }

        void recordSucceeded(String tool) {
            recordSucceeded(tool, (String) null);
        }

        /**
         * @param tool 工具名
         * @return 该工具的输出（成功且有输出时）；否则为空
         */
        public @NonNull Optional<String> outputOf(String tool) {
            return Optional.ofNullable(outputs.get(tool));
        }

        /**
         * @return 成功工具的输出（不可变副本，工具名 → 输出）
         */
        public @NonNull Map<String, String> outputs() {
            return Map.copyOf(outputs);
        }

        /**
         * 每次调用的四字段决策（P2 · §5.1）。
         *
         * <p>⚠️ <b>既有链路下为空</b>：既有链路不产出结构化决策，
         * 只有 CAP 链路才按 §9.1 逐字段断言。
         */
        private final List<CapToolDecision> decisions = new ArrayList<>();
        private String unknownTool;
        private String unknownOperationId;

        void recordRejected(String tool, String reason) {
            rejected.add(tool);
            if (reason != null && !reason.isBlank()) {
                rejectionReasons.put(tool, reason);
            }
        }

        /**
         * @param tool 工具名
         * @return 该工具被拒的原因；无记录时为空
         */
        public @NonNull Optional<String> rejectionReasonOf(String tool) {
            return Optional.ofNullable(rejectionReasons.get(tool));
        }

        /**
         * @return 拒绝原因（不可变副本，工具名 → 原因）
         */
        public @NonNull Map<String, String> rejectionReasons() {
            return Map.copyOf(rejectionReasons);
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
