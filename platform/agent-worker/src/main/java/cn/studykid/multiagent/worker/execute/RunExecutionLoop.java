package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.application.config.ModelPricingPolicy;
import cn.studykid.multiagent.core.compliance.DataClassification;
import cn.studykid.multiagent.core.config.ModelPricing;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.application.orchestration.InFlightDelegationAdopter;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunEventPort;
import cn.studykid.multiagent.ports.persistence.RunStepPort;
import cn.studykid.multiagent.ports.persistence.RiskEventPort;
import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;

/**
 * Worker 执行循环（P2-D03 §2 / FR-004）。
 *
 * <p><b>只做编排</b>：串联模型调用、工具调用闸门、状态提交。
 * 工具的<b>实现</b>属 D4，本类不关心工具做什么。
 *
 * <p><b>⚠️ 重试只在 Worker 侧实现</b>（§4.1）。绝不引入 Spring 的
 * {@code @Retryable}：两者同时生效会双重重试 ——
 * 每次失败重试 2 次，对 {@code record_weakness} 意味着
 * <b>重复写入 = Sev-0</b>。
 */
public class RunExecutionLoop {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionLoop.class);

    /**
     * JSON 序列化器（Jackson 3）。
     *
     * <p>⚠️ 用它而不是手工拼字符串：{@code result_payload} 要落进 PG 的
     * {@code jsonb} 列，裸控制字符会被 PG 拒绝 → 提交失败 →
     * Run 被误报为失败。详见 {@link #jsonPayload(String, String)}。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObservableModelGateway modelGateway;
    private final RunClaimPort claimPort;
    private final ToolInvocationGate toolGate;
    private final Duration leaseDuration;

    /**
     * 预算端口（P0-3b · 模型成本入账）。
     *
     * <p>⚠️ <b>可为空 = 不做成本记账</b>：此时模型调用不预留额度、
     * 不写计量行，{@code usage_record} 的 token 两列永远为 0。
     * 生产装配<b>必须</b>传入（与闸门用<b>同一个</b>预算端口实例 ——
     * 两个实例意味着两条独立的额度视图）。
     */
    private final BudgetPort budget;

    /**
     * 模型单价表（P0-3b）。
     *
     * <p>⚠️ <b>空表 ≠ 按 0 元计费</b>：空表只意味着「查不到价格」，
     * 此时仍<b>如实记 token</b>、成本按 0 记并打 WARN。
     */
    private final ModelPricingPolicy pricing;
    /**
     * 本 Worker 实例的身份，必须与领取时传给 {@code claimTasks}/
     * {@code claimSpecificRun} 的 workerId <b>完全一致</b>——
     * {@code renewLease} 的 WHERE 子句按 {@code lease_owner} 匹配，
     * 身份不一致会导致续租永远失败，多步 Run 在第二步被误判为「已被接管」
     * （P3-D02 §2 F-1）。
     */
    private final String workerId;

    /**
     * 在途委派接管器（第九批 · MA-T12 · P3-MA-02）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时接管不会收敛在途委派，
     * 那些记录将<b>一直悬在 RUNNING</b>（无人能写终态），
     * 重放时只能拿到「既有 RUNNING 状态」→ 父 Agent 收到「执行失败」，
     * 而真实原因（Worker 崩溃）被掩盖。生产装配<b>必须</b>传入。
     */
    private final InFlightDelegationAdopter adopter;

    /**
     * 执行事件写入（P4-D12 / G4）。
     *
     * <p>⚠️ <b>此前生产里没有写入方</b>：{@code run_event} 只有
     * {@code RUN_CREATED} 由 Run 创建事务写入 → 进度页时间线<b>永远只有一条</b>。
     *
     * <p>⚠️ <b>默认「不写」而非「默认写」</b>：未注入时事件不落库，
     * 但**不报错**——单元测试与部分切片不需要事件。
     * 生产装配由 P4-D12 §3 D12-5 保证（连通性测试断言它真的被注入）。
     */
    private final RunEventPort eventPort;

    /**
     * 执行步骤写入端口（P7-2）。
     *
     * <p>⚠️ <b>默认「不写」而非「默认写」</b>：与 {@link #eventPort} 同纪律——
     * 未注入时步骤不落库（{@code run_step} 历史上即零写入方），但<b>不报错</b>。
     * 生产装配由 {@code WorkerExecutionConfig} 传入（与事件端口一并强制注入）。
     */
    private RunStepPort stepPort;

    /**
     * 安全事件写入端口（P7-4 · SEC-2/SEC-3 明细事实）。
     *
     * <p>⚠️ <b>默认「不写」而非「默认写」</b>：与 {@link #eventPort} / {@link #stepPort}
     * 同纪律——未注入时不落 {@code platform.agent_risk_event}，但<b>不报错</b>。
     * 发射点：SAFETY_BLOCK（编造出处拦截）· UNAUTHORIZED_CALL（越权工具拒绝）·
     * SENSITIVE_OUTPUT（输出命中隐私模式）。
     */
    private RiskEventPort riskEventPort;

    /**
     * Prompt 注入防护（P3-D09 / FR-016）。
     *
     * <p>⚠️ <b>默认用标准上限</b>：不设「无防护」默认值——
     * 那等于提供一个「忘记防护也能跑」的构造路径。
     */
    private final PromptGuard promptGuard;

    /**
     * 上下文 token 预算（第二十六批 · A7）。
     *
     * <p>⚠️ <b>只约束「发给模型的消息序列」</b>：系统提示与工具 Schema
     * <b>不计入</b>本预算 —— 它们的长度由 Agent 配置决定，
     * 不由运行期的轮次增长决定，混进来会让「预算」这个数字失去可解释性。
     */
    private final int contextTokenBudget;

    /**
     * 租户生命周期运行时探针（重规划 §14 / 验收矩阵 #4）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时跳过租户状态再验证（默认），
     * 但生产装配<b>必须</b>传入——否则平台暂停/退役租户后，
     * Worker 仍会执行该租户的新任务与在跑任务（Sev-1 越权执行）。
     * 装配纪律同事件端口/接管器：空值不报错但等于「忘记防护也能跑」。
     */
    private final TenantLifecycleProbe lifecycleProbe;

    /**
     * 执行观测者链（可选装配）。
     *
     * <p>⚠️ 可为空 = 未装配：此时不触发观测，但<b>不报错</b>。
     * 生产装配应传入，用于实时观测执行循环各阶段。
     */
    private final ExecutionObserverChain observerChain;

    /**
     * 单次长调用的<b>抢占式</b>超时执行器（P2-1）。
     *
     * <p>⚠️⚠️ <b>为什么必须有它</b>：{@code task.deadlineAt()} 的检查只发生在
     * <b>每轮迭代的开头</b>。一次模型调用卡住 10 分钟，中间不会有任何一次
     * deadline 检查 —— 单步调用可以<b>越过根截止时间</b>，
     * FR-024「整树共享超时上限」在单步粒度上形同虚设。
     * 本执行器把每一次模型/工具调用放进带 {@code Future.get(剩余时间)}
     * 的边界里，到期即 {@code cancel(true)} 中断。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时退回「只在每轮开头检查」的旧行为，
     * 并<b>打印 WARN</b>（同 {@link #adopter} / {@link #lifecycleProbe} 纪律：
     * 空值不报错，但绝不假装已受保护）。生产装配<b>必须</b>传入。
     */
    private final ExecutorService preemptionExecutor;

    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                new PromptGuard(), null);
    }

    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                promptGuard, null);
    }

    /**
     * 含事件写入的完整构造器（P4-D12 / G4）。
     *
     * @param eventPort 事件写入端口；{@code null} 表示**不写事件**（默认）
     */
    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                promptGuard, eventPort, null,
                ContextBudgetTrimmer.DEFAULT_CONTEXT_TOKEN_BUDGET, null, null, null, null, null);
    }

    /**
     * 最完整构造器（含事件写入与在途委派接管）。
     *
     * @param eventPort 事件写入端口；{@code null} 表示**不写事件**（默认）
     * @param adopter    在途委派接管器；{@code null} = 不做接管收敛
     */
    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort,
                             @Nullable InFlightDelegationAdopter adopter) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                promptGuard, eventPort, adopter,
                ContextBudgetTrimmer.DEFAULT_CONTEXT_TOKEN_BUDGET, null, null, null, null, null);
    }

    /**
     * 最完整构造器（含上下文 token 预算 · 第二十六批 · A7；步骤/安全事件端口 · P7）。
     *
     * @param contextTokenBudget 上下文 token 预算；必须为正数
     * @param lifecycleProbe     租户生命周期运行时探针；{@code null} 表示**不验证**
     *                           （默认），生产装配<b>必须</b>传入
     * @param stepPort           执行步骤写入端口（P7-2）；{@code null} 表示不写步骤
     * @param riskEventPort      安全事件写入端口（P7-4）；{@code null} 表示不写安全事件
     * @param observerChain      执行观测者链；{@code null} 表示不触发观测
     * @param preemptionExecutor 抢占式单步超时执行器（P2-1）；{@code null} = 退回
     *                           「只在每轮开头查 deadline」的旧行为（并打印 WARN）
     */
    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort,
                             @Nullable InFlightDelegationAdopter adopter,
                             int contextTokenBudget,
                             @Nullable TenantLifecycleProbe lifecycleProbe,
                             @Nullable RunStepPort stepPort,
                             @Nullable RiskEventPort riskEventPort,
                             @Nullable ExecutionObserverChain observerChain,
                             @Nullable ExecutorService preemptionExecutor) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId, promptGuard,
                eventPort, adopter, contextTokenBudget, lifecycleProbe, stepPort,
                riskEventPort, observerChain, preemptionExecutor, null, null);
    }

    /**
     * <b>生产装配构造器</b>（P0-3b · 模型成本入账）。
     *
     * <p>⚠️ <b>为什么必须装配预算端口与单价表</b>：
     * 二者缺一时，「模型调用花了多少钱」这件事在账上<b>不存在</b> ——
     * {@code usage_record.input_tokens} / {@code output_tokens} 两列永远为 0，
     * {@code cost_amount} 只有工具调用的固定代理值。
     * 于是「单 Run 成本上限」（{@code maxCostPerRun}）无从判定，
     * 成本类指标（COST-1/2/3）算出来的是错的。
     *
     * <p>⚠️ <b>单价表为空 ≠ 按 0 元计费</b>：
     * 空表只意味着「查不到这个模型的价格」，此时仍然<b>如实记 token</b>、
     * <b>成本按 0 记</b>并打 WARN —— 缺配置必须<b>可见</b>，
     * 不能变成「这次调用不存在」。
     *
     * @param budget  预算端口（模型调用的预留与结算）；{@code null} = 不做成本记账
     * @param pricing 模型单价表；{@code null} = 空表（无单价可查）
     */
    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort,
                             @Nullable InFlightDelegationAdopter adopter,
                             int contextTokenBudget,
                             @Nullable TenantLifecycleProbe lifecycleProbe,
                             @Nullable RunStepPort stepPort,
                             @Nullable RiskEventPort riskEventPort,
                             @Nullable ExecutionObserverChain observerChain,
                             @Nullable ExecutorService preemptionExecutor,
                             @Nullable BudgetPort budget,
                             @Nullable ModelPricingPolicy pricing) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId, promptGuard,
                eventPort, adopter, contextTokenBudget, lifecycleProbe, stepPort,
                riskEventPort, observerChain, preemptionExecutor, budget, pricing, null);
    }

    /**
     * 最完整构造器（M1-4b / G2）：额外注入<b>恢复续跑接缝</b>。
     *
     * <p>⚠️ 新增依赖只加在这一个构造器上（项目既有纪律）；
     * 其余构造器保持不变并委托——{@code continuation} 为 {@code null} 时
     * 恢复路径在批次执行成功后 fail-closed 收敛（见 {@link #resume}）。
     *
     * @param continuation 恢复续跑接缝；{@code null} = 未装配（恢复成功后收敛 FAILED）
     */
    public RunExecutionLoop(@NonNull ObservableModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort,
                             @Nullable InFlightDelegationAdopter adopter,
                             int contextTokenBudget,
                             @Nullable TenantLifecycleProbe lifecycleProbe,
                             @Nullable RunStepPort stepPort,
                             @Nullable RiskEventPort riskEventPort,
                             @Nullable ExecutionObserverChain observerChain,
                             @Nullable ExecutorService preemptionExecutor,
                             @Nullable BudgetPort budget,
                             @Nullable ModelPricingPolicy pricing,
                             @Nullable RunContinuation continuation) {
        this.modelGateway = modelGateway;
        this.claimPort = claimPort;
        this.toolGate = toolGate;
        this.leaseDuration = leaseDuration;
        this.eventPort = eventPort;
        this.stepPort = stepPort;
        this.riskEventPort = riskEventPort;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空（fencing 续租身份）");
        }
        this.workerId = workerId;
        this.promptGuard = promptGuard;
        this.adopter = adopter;
        if (contextTokenBudget <= 0) {
            throw new IllegalArgumentException("上下文 token 预算必须为正数，收到: "
                    + contextTokenBudget);
        }
        this.contextTokenBudget = contextTokenBudget;
        this.lifecycleProbe = lifecycleProbe;
        this.observerChain = observerChain;
        this.preemptionExecutor = preemptionExecutor;
        if (preemptionExecutor == null) {
            log.warn("未装配抢占式单步超时执行器：单次超长的模型/工具调用不能被中断，"
                    + "可越过 run.deadline_at（FR-024 整树共享超时在单步粒度失效）");
        }
        if (adopter == null) {
            log.warn("未装配在途委派接管器：Worker 接管后 RUNNING 的委派记录"
                    + "将无法收敛（一直悬着），真实原因会被掩盖成普通子调用失败");
        }
        if (lifecycleProbe == null) {
            log.warn("未装配租户生命周期探针：Worker 不会在执行前/中再验证租户状态，"
                    + "平台暂停/退役租户后仍可能执行其任务（验收矩阵 #4 不满足）");
        }
        this.budget = budget;
        this.pricing = pricing == null ? new ModelPricingPolicy(Map.of()) : pricing;
        if (budget == null) {
            log.warn("未装配预算端口：模型调用的成本**不入账**"
                    + "（usage_record 无 token 与真实成本，maxCostPerRun 无从判定）");
        } else if (this.pricing.isEmpty()) {
            log.warn("模型单价表为空：模型调用只能记 token、成本按 0 记，"
                    + "成本类指标不可用（请配置 agent.pricing.models）");
        }
        this.continuation = continuation;
        if (continuation == null) {
            log.warn("未装配恢复续跑接缝（RunContinuation）：审批恢复批次执行成功后"
                    + "无法继续对话，Run 将显式收敛 FAILED（RESUME_CONTINUATION_UNAVAILABLE）");
        }
    }

    /**
     * 执行一次已领取的任务。
     *
     * @param task      D2 已领取的任务（含 fencingToken）
     * @param snapshot  Run 创建时的配置快照（<b>不是当前配置</b>）
     * @param input     Run 的输入载荷
     * @return 执行结果
     */
    public ExecutionOutcome execute(String tenantId,
                                    RunClaimPort.LeasedTask task,
                                    AgentModelConfig agentConfig,
                                    RunConfigSnapshot snapshot,
                                    Map<String, Object> input) {
        // ── §2.1 执行前检查世代（为省成本，不是安全保证）──────────────
        if (!claimPort.isCurrentGeneration(tenantId, task.runId(), task.fencingToken())) {
            // 租约已过期并被接管。此时执行模型 = 白花钱，提交也会被拒
            log.info("世代已失效，放弃执行以节省成本: runId={}", task.runId());
            notifyLoopComplete(tenantId, task.runId(),
                    ExecutionOutcome.skippedStaleGeneration(), 0);
            return ExecutionOutcome.skippedStaleGeneration();
        }

        // ── §2.1.1 执行前租户生命周期再验证（重规划 §14 / 验收矩阵 #4）──
        // ⚠️ 平台暂停/退役租户后，不能只信入队时的检查：必须在真正执行前
        //    以 DB 当前状态确认租户仍可执行。缺失/非 ACTIVE 一律拒绝新执行。
        ExecutionOutcome blocked = rejectIfTenantBlocked(tenantId, task.runId());
        if (blocked != null) {
            return blocked;
        }

        int steps = 0;
        // ⚠️ P7-2：步骤序号（单调递增，供 run_step.step_number）。与 steps 不同——
        //    steps 是循环轮次上限计数器，stepSeq 是「已落库步骤」的序号。
        int stepSeq = 0;
        // ⚠️⚠️ P0-3a：模型调用次数计数（**此前只声明、从不执行**）。
        //    RunConfigSnapshot.maxLlmCalls 与 allowsLlmCall() 一直存在，
        //    但生产代码零调用 —— 于是「单 Run 最多 12 次模型调用」是纸面承诺。
        //    它与 steps 是**两个不同的上限**：steps 数「轮次」，
        //    llmCalls 数「模型调用」—— 一轮里可能有多次模型调用
        //    （子 Agent 委派后再回到父模型），两者不得互相替代。
        int llmCalls = 0;
        // ⚠️⚠️ P0-3b：本 Run 已发生的**模型成本累计**（元）。
        //    maxCostPerRun（DEC-9，默认 ¥2.00）此前只进快照、只用于配置展示，
        //    不参与任何拦截 —— 「单 Run 最多花 ¥2.00」是纸面承诺。
        //    现在它是真实闸门：每次模型调用结算后累加，超限即停。
        BigDecimal accumulatedCost = BigDecimal.ZERO;

        // ── 接管收敛（第九批 · MA-T12 · P3-MA-02）────────────────────
        //    ⚠️ 必须在**世代检查通过之后**：世代已失效说明这个 Run
        //    已被别人接管，我们没有任何写权限。
        //    ⚠️ 首次领取时没有任何在途记录，影响 0 行 → 无需区分首次/接管。
        if (adopter != null) {
            try {
                int converged = adopter.adopt(tenantId, task.runId(), task.fencingToken());
                if (converged > 0) {
                    log.warn("接管收敛在途委派 {} 条（结果不可知，交对账）: runId={}",
                            converged, task.runId());
                }
            } catch (RuntimeException e) {
                // ⚠️ 接管收敛失败**不得**中断本次执行：本次执行仍可正常推进，
                //    失败的只是「旧记录不再悬着」这件事 —— 交由对账兜底。
                log.error("接管收敛在途委派失败（不影响本次执行）: runId={}",
                        task.runId(), e);
            }
        }

        // ⚠️⚠️ 对话消息序列（第八批 · 消除「父子两套语义」）：
        //    首轮是用户输入，之后每轮追加「模型意图 + 工具结果」。
        //    修复前每轮都用**同一份** renderInput(input) ——
        //    模型在第 2 轮看到的上下文与第 1 轮完全相同，
        //    于是「调完工具」之后无法基于结果继续推理，
        //    多轮工具协作根本不成立（工具调用等于白调）。
        //    现在与子 Agent 执行器**共用同一套渲染口径**
        //    （ToolOutcomeRenderer：不可信标记 / 长度封顶 / 失败也回灌）。
        java.util.List<cn.studykid.multiagent.ports.model.ModelMessage> messages =
                new java.util.ArrayList<>();
        messages.add(cn.studykid.multiagent.ports.model.ModelMessage.user(
                renderInput(input)));

        // ⚠️ M 决策（§8.2）：同一 callId 的工具被拒累计 3 次 → 终止 Run（FAILED）。
        //    否则一次参数不合规的拒绝会让整个 Run 直接失败。
        //    key = 工具名（callId 在跨模型轮次不可得，工具名已足够区分不同调用意图）；
        //    拒绝原因经 ToolOutcomeRenderer 回灌给模型（见下方「回灌」段）。
        java.util.Map<String, Integer> rejectionStrikes = new java.util.HashMap<>();

        // ⚠️ P4-D12 / G4：开始执行 → RUN_STARTED
        appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_STARTED,
                java.util.Map.of("state", "RUNNING", "attempt", task.attempt()));

        // 观测者：执行循环开始
        if (observerChain != null) {
            observerChain.onLoopStart(tenantId, task.runId(), snapshot.maxSteps());
        }

        try {
            while (steps < snapshot.maxSteps()) {
                steps++;

                // ── §2.1.2 每轮迭代前租户生命周期再验证（验收矩阵 #4）────
                // ⚠️ 运行中租户被暂停/退役：及时停止，不再发起新一轮模型调用
                //    与工具动作（避免白白消耗成本、产生副作用）。
                ExecutionOutcome midBlocked = rejectIfTenantBlocked(tenantId, task.runId());
                if (midBlocked != null) {
                    return midBlocked;
                }

                // ── §2.2 取消检查（协作式，P3-D06 / FR-011）─────────────
                // ⚠️ 每轮迭代开头（模型调用前）即查取消。一旦观察到取消，
                //    立即停止，不再发起新一轮模型调用与工具动作。
                //    状态终态已由 requestCancel 置 CANCELLED（claimSpecificRun /
                //    commitResult 均拒绝已取消 Run），双重保险。
                if (claimPort.isCancelled(tenantId, task.runId())) {
                    log.info("检测到取消请求，停止执行: runId={}", task.runId());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_CANCELLED,
                            java.util.Map.of("state", "CANCELLED"));
                    notifyLoopComplete(tenantId, task.runId(), ExecutionOutcome.cancelled(), steps);
                    return ExecutionOutcome.cancelled();
                }

                // ── §2.1.3 根层业务超时检查（FR-024 整树共享超时上限）──
                // ⚠️ 业务 deadline（run.deadline_at）与租约（lease_expires_at）是两回事：
                //    租约过期只意味着「该换手」，业务 deadline 过期意味着「这个 Run 该结束了」。
                //    若只靠租约续期，已超时的 Run 会被无限续租跑下去，违背 FR-024
                //    （声明了 timeoutMs 的 Run 跑到 maxSteps 才 MAX_STEPS 失败）。
                //    每轮开头即查，超时立即 commitFailure(TIMEOUT) 并停止。
                if (task.deadlineAt() != null
                        && java.time.Instant.now().isAfter(task.deadlineAt())) {
                    log.warn("根 Run 业务超时，停止执行: runId={}, deadline={}",
                            task.runId(), task.deadlineAt());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            java.util.Map.of("state", "TIMED_OUT"));
                    notifyLoopComplete(tenantId, task.runId(),
                            commitFailure(tenantId, task, "业务超时", "TIMEOUT"), steps);
                    return commitFailure(tenantId, task, "业务超时", "TIMEOUT");
                }

                // ── §2 循环体 ────────────────────────────────────────────
                // ⚠️ 模型参数来自 Agent 配置（Run 关联的 Agent），
                //    运行约束（maxSteps）来自 Run 创建时的快照。
                //    两者不可混用：改 Agent 影响新 Run，改配置快照也影响新 Run，
                //    但已创建的 Run 必须按创建时的约束执行。
                //
                // ⚠️ 上下文裁剪（第二十六批 · A7）：消息序列随轮次线性增长，
                //    不裁就会顶爆上下文窗口。裁剪**只影响发给模型的内容**，
                //    累积序列本身不删 —— 否则裁一次就永久丢历史。
                ContextBudgetTrimmer.Trimmed trimmed =
                        ContextBudgetTrimmer.trim(messages, contextTokenBudget);
                if (trimmed.trimmed()) {
                    log.info("上下文按 token 预算裁剪: runId={}, 丢弃轮数={}, 丢弃 token≈{}",
                            task.runId(), trimmed.droppedRounds(), trimmed.droppedTokens());
                }
                if (!trimmed.withinBudget()) {
                    // ⚠️ 连「只留原始输入」都超预算 → 预算本身小于单条输入。
                    //    此时**不得静默截断用户输入**（那是任务本身），
                    //    也不得假装裁剪成功 —— 显式告警，让运维知道预算配错了。
                    log.warn("⚠️ 上下文预算({})小于原始输入本身，裁剪无法满足预算: runId={}",
                            contextTokenBudget, task.runId());
                }
                String systemPrompt = agentConfig.systemPrompt();
                if (trimmed.notice() != null) {
                    // ⚠️ 裁剪说明走**系统提示**（可信指令载体）：
                    //    混进消息序列会引入角色交替问题，
                    //    而伪装成用户输入等于把系统事实交给不可信通道。
                    systemPrompt = systemPrompt + "\n\n" + trimmed.notice();
                }

                // 观测者：步骤开始 + 裁剪事件
                if (observerChain != null) {
                    observerChain.onStepStart(tenantId, task.runId(), steps);
                    if (trimmed.trimmed()) {
                        observerChain.onContextTrimmed(tenantId, task.runId(),
                                trimmed.droppedRounds(), trimmed.droppedTokens(),
                                trimmed.droppedTools());
                    }
                }

                ModelRequest request = new ModelRequest(
                        agentConfig.modelId(),
                        systemPrompt,
                        renderInput(input),
                        agentConfig.temperature(),
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.toolSchemas(),
                        // ⚠️ 带消息历史：非空时以 messages 为准（userContent 仅冗余保留）
                        trimmed.messages());

                // ── §2 P0-3a：模型调用次数闸门（此前只声明、从不执行）──
                //    ⚠️ 放在**发起调用之前**而不是之后：
                //    超限意味着「这一次调用根本不该发生」，
                //    放在之后等于先花钱再发现超限 —— 闸门失去意义。
                //
                //    ⚠️ 与 steps 上限是**两个独立闸门**：
                //    steps 数轮次、llmCalls 数模型调用。maxSteps=20 与 maxLlmCalls=12
                //    不等值（见 RunConfigSnapshot 初值），只守 steps 会让模型调用数
                //    达到 20 次 —— 是承诺上限的近 2 倍。
                if (!snapshot.allowsLlmCall(llmCalls)) {
                    log.warn("模型调用次数超限，Run 收敛为 FAILED: runId={}, 已调用={}, 上限={}",
                            task.runId(), llmCalls, snapshot.maxLlmCalls());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            java.util.Map.of("state", "FAILED", "errorCode", "MAX_LLM_CALLS"));
                    ExecutionOutcome limited = commitFailure(tenantId, task,
                            "模型调用次数超限（" + llmCalls + "/" + snapshot.maxLlmCalls() + "）",
                            "MAX_LLM_CALLS");
                    notifyLoopComplete(tenantId, task.runId(), limited, steps);
                    return limited;
                }

                // ── §2 P0-3b：模型调用成本预留（**发起调用之前**）──
                //    ⚠️ 预留必须在调用前：调用后才知道花多少，
                //    那时额度已经花出去了 —— 预留的意义就是「先占住」。
                //
                //    ⚠️ 预留额取**保守上界**（输入估算 × 安全系数 + 输出上限）：
                //    token 估算是粗估（见 ContextBudgetTrimmer），
                //    若预留额小于真实成本，结算会被 DB 的
                //    CHECK (settled <= reserved) 拒绝，账就断了。
                //    多预留的部分在结算时自动退回（预留额全额扣回、只计实结额）。
                String modelCallId = modelCallId(task.runId(), task.attempt(), llmCalls);
                Optional<ModelCostReservation> modelReservation =
                        reserveModelCost(tenantId, task.runId(), agentConfig, trimmed,
                                systemPrompt, modelCallId);
                if (modelReservation.isEmpty()) {
                    // ⚠️ 预留被拒（额度不足 / 未开通账户）→ fail-closed：
                    //    「钱不够」不能变成「照跑不误」。
                    log.warn("模型调用预算预留被拒，Run 收敛为 FAILED: runId={}, model={}",
                            task.runId(), agentConfig.modelId());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            java.util.Map.of("state", "FAILED", "errorCode", "BUDGET_EXHAUSTED"));
                    ExecutionOutcome noBudget = commitFailure(tenantId, task,
                            "模型调用预算预留被拒", "BUDGET_EXHAUSTED");
                    notifyLoopComplete(tenantId, task.runId(), noBudget, steps);
                    return noBudget;
                }

                // ── §2 P7-2：记录 THINK 步骤（模型推理耗时）──
                //    started/completed 同时取，单条 INSERT 落库；失败不影响主流程。
                java.time.Instant thinkStart = java.time.Instant.now();
                ModelResponse response;
                // steps 会在循环里自增，不是有效 final —— lambda 只读这份副本
                final int round = steps;
                try {
                    // ⚠️ P2-1：单步必须受剩余截止时间约束，否则循环开头的
                    //    deadline 检查会被一次卡死的模型调用整段跳过。
                    response = withinRemaining(task, "MODEL_CALL",
                            () -> modelGateway.complete(tenantId, task.runId(), round, request));
                } catch (StepDeadlineExceededException e) {
                    releaseModelCost(tenantId, modelReservation.get());
                    log.warn("模型调用越过根截止时间，Run 收敛为 TIMEOUT: {}", e.getMessage());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            Map.of("state", "TIMED_OUT", "phase", "MODEL_CALL"));
                    ExecutionOutcome timedOut =
                            commitFailure(tenantId, task, "单步模型调用超时", "TIMEOUT");
                    notifyLoopComplete(tenantId, task.runId(), timedOut, steps);
                    return timedOut;
                } catch (RuntimeException e) {
                    // ⚠️ P0-3b：模型调用失败（可重试 / 不可重试 / 其他）时**必须释放预留**：
                    //    本次调用没有产出任何东西，钱却已经占住 ——
                    //    不释放就是「凭空少了一笔额度」，且会拖垮后续所有 Run。
                    releaseModelCost(tenantId, modelReservation.get());
                    throw e;
                }
                // ⚠️ P0-3a：调用**成功**才计数。
                //    失败（含超时）时本次调用没有产出，也已由上面的分支收敛 Run，
                //    不该占额度 —— 否则「失败的调用」也会把额度耗光，
                //    让真正的业务调用被上限挡住。
                llmCalls++;

                // ── §2 P0-3b：模型调用成本结算（调用后）──
                //    ⚠️ 结算按**真实 token** 计价，不是按预留额 ——
                //    预留是上界，结算才是事实；多预留的部分自动退回。
                BigDecimal callCost = settleModelCost(tenantId, task.runId(),
                        agentConfig.modelId(), response, modelReservation.get());
                accumulatedCost = accumulatedCost.add(callCost);
                // ⚠️ 单 Run 成本上限（DEC-9 maxCostPerRun）此前**只用于配置展示**，
                //    不参与任何拦截 —— 于是「单 Run 最多花 ¥2.00」是纸面承诺。
                //    现在它是真实的闸门：与 Run 级 budget_amount 各自独立，
                //    任一触顶即停（两者都生效 = 取更严者）。
                if (accumulatedCost.compareTo(snapshot.maxCostPerRun()) > 0) {
                    log.warn("单 Run 模型成本超限，Run 收敛为 FAILED: runId={}, 累计={}, 上限={}",
                            task.runId(), accumulatedCost, snapshot.maxCostPerRun());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            java.util.Map.of("state", "FAILED", "errorCode", "MAX_COST"));
                    ExecutionOutcome overCost = commitFailure(tenantId, task,
                            "单 Run 模型成本超限（" + accumulatedCost + "/"
                                    + snapshot.maxCostPerRun() + "）",
                            "MAX_COST");
                    notifyLoopComplete(tenantId, task.runId(), overCost, steps);
                    return overCost;
                }

                java.time.Instant thinkEnd = java.time.Instant.now();
                appendStep(tenantId, task, ++stepSeq, RunStepPort.Types.THINK,
                        thinkStart, thinkEnd);

                // 观测者：模型调用完成
                if (observerChain != null) {
                    observerChain.onModelCallComplete(
                            tenantId, task.runId(), steps, response,
                            java.time.Duration.between(thinkStart, thinkEnd));
                }

                // ⚠️ P1-D：带工具调用的中间轮同样可能携带正文（思考过程/半句结论），
                //    这段文本此前**完全不经检**：既不过内容安全层，也不过编造检测，
                //    理由是「只有终答才算输出」。但它会进上下文、进步骤、进观测，
                //    同样是面向学生可见的内容。此处对其执行与终答一致的两项检查。
                String intermediate = response.content();
                if (intermediate != null && !intermediate.isBlank()
                        && !response.toolCalls().isEmpty()) {
                    ScreenResult mid = screenContent(tenantId, task, steps, intermediate);
                    if (mid.blocked() != null) {
                        return mid.blocked();
                    }
                }

                if (response.toolCalls().isEmpty()) {
                    String content = response.content();
                    // ⚠️ 终答：内容安全层 + 编造检测（见 screenContent）
                    //    ∆ P1-A：此处**原本原样下发**；改为先经平台侧输出 moderation
                    //    再决定下发形态 / 是否阻断。SEC-3 事实仍写 SENSITIVE_OUTPUT，
                    //    摘要只含模式代号，绝不落原文（合规红线不变）。
                    ScreenResult screened = screenContent(tenantId, task, steps, content);
                    if (screened.blocked() != null) {
                        return screened.blocked();
                    }
                    // BLOCK 时已提前返回；此处只有非阻断，content 为脱敏后正文
                    content = screened.content();
                    // 无工具调用 → 生成结果，正常完成
                    ExecutionOutcome succ = commitSuccess(tenantId, task, content);
                    notifyLoopComplete(tenantId, task.runId(), succ, steps);
                    return succ;
                }

                // ── §4 工具调用（逐个走完整闸门）───────────────────────
                java.time.Instant toolStart = java.time.Instant.now();
                ToolInvocationGate.ToolInvocationResult toolResult;
                try {
                    // ⚠️ P2-1：同上。工具是自己实现的时间预算执行者，
                    //    但「实际能跑多久」必须由祖先 Run 的剩余时间封顶。
                    toolResult = withinRemaining(task, "TOOL_CALL",
                            () -> toolGate.invokeAll(tenantId, task, response.toolCalls()));
                } catch (StepDeadlineExceededException e) {
                    log.warn("工具调用越过根截止时间，Run 收敛为 TIMEOUT: {}", e.getMessage());
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                            Map.of("state", "TIMED_OUT", "phase", "TOOL_CALL"));
                    ExecutionOutcome timedOut =
                            commitFailure(tenantId, task, "单步工具调用超时", "TIMEOUT");
                    notifyLoopComplete(tenantId, task.runId(), timedOut, steps);
                    return timedOut;
                }
                java.time.Instant toolEnd = java.time.Instant.now();
                appendStep(tenantId, task, ++stepSeq, RunStepPort.Types.TOOL,
                        toolStart, toolEnd);

                // 观测者：工具调用完成
                if (observerChain != null) {
                    observerChain.onToolCallComplete(
                            tenantId, task.runId(), steps,
                            adaptToolResult(toolResult),
                            java.time.Duration.between(toolStart, toolEnd));
                }

                // ⚠️ P4-D12 / G4：工具调用后记录事件（含 UNKNOWN）
                //    operationState 取工具账本状态：SUCCEEDED / FAILED / REJECTED / UNKNOWN。
                //    ⚠️ 不放账本 ID——它不在 SSE 白名单里。
                for (String tool : toolResult.succeededTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "SUCCEEDED"));
                }
                for (String tool : toolResult.failedTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "FAILED"));
                }
                for (String tool : toolResult.rejectedTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "REJECTED"));
                    // ── §4 P7-4：越权调用尝试（SEC-2 明细事实）──
                    //    拒绝原因须区分「越权」（无权/审批拒绝 → SEC-2）与
                    //    「无效调用」（未登记/Schema 非法 → BEH-3），
                    //    把 Schema 拒绝也算越权会让安全告警被噪声淹没。
                    //    ⚠️ 摘要只含工具名与原因码，不含参数原文。
                    String reason = toolResult.rejectionReasonOf(tool).orElse("");
                    if (isAuthorizationDenial(reason)) {
                        emitRiskEvent(tenantId, task, RiskEventPort.UNAUTHORIZED_CALL,
                                "TOOL:" + tool + ":" + reason);
                    }
                }
                if (toolResult.unknownToolName() != null) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", toolResult.unknownToolName(),
                                    "operationState", "UNKNOWN"));
                }

                if (toolResult.unknownResultOccurred()) {
                    // ── §3.2 UNKNOWN 处置 ────────────────────────────────
                    // ⚠️ 绝不当普通失败重试。可能有副作用已发生。
                    //    不释放预算预留（成本可能已发生）
                    log.warn("工具结果未知，停止执行并转对账: runId={}, operation={}",
                            task.runId(), toolResult.unknownOperationId());
                    ExecutionOutcome unknown = ExecutionOutcome.unknownResult(toolResult.unknownOperationId());
                    notifyLoopComplete(tenantId, task.runId(), unknown, steps);
                    return unknown;
                }

                if (toolResult.hasParked()) {
                    // ── M1-4 / G2 修复核心：PARK（挂起等待审批）────────────
                    // ⚠️ Run 迁 WAITING_APPROVAL、写检查点、释放租约、让出 worker 线程。
                    //    审批决定到达后由 ApprovalDecidedWakeupConsumer 唤醒并 RESUME
                    //    （不重跑模型、不重问模型，复用同一 operationId 幂等收敛）。
                    java.util.Set<String> parkedOps = new java.util.LinkedHashSet<>(
                            toolResult.parkedTools().values());
                    // 写检查点步骤（保存本轮工具调用信息，供 RESUME 重建上下文）
                    appendStep(tenantId, task, ++stepSeq, RunStepPort.Types.TOOL,
                            toolStart, java.time.Instant.now());
                    // 先提交 WAITING_APPROVAL（fencing 校验），成功后再释放租约让出资源。
                    // ⚠️ fail-closed：提交失败（世代失效）不释放租约、不当成挂起成功。
                    boolean parkedOk = claimPort.commitResult(tenantId, task.runId(),
                            task.fencingToken(), "WAITING_APPROVAL",
                            jsonPayload("parkedOperations", String.join(",", parkedOps)));
                    if (parkedOk) {
                        appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_PARKED,
                                java.util.Map.of("state", "WAITING_APPROVAL"));
                        // 释放租约：让出 worker 资源；Run 在 WAITING_APPROVAL 等待唤醒
                        // （claimSpecificRun 只收 QUEUED/RUNNING，故不会被正常领取路径误拾）。
                        claimPort.releaseLease(tenantId, task.runId(), workerId, task.fencingToken());
                    }
                    ExecutionOutcome parkedOutcome = ExecutionOutcome.parked(parkedOps);
                    notifyLoopComplete(tenantId, task.runId(), parkedOutcome, steps);
                    return parkedOutcome;
                }

                // ⚠️ 拒绝回灌 + 3 次终止（§8.2）：累计同一工具被拒次数，满 3 次 → 终止 Run。
                //    单次被拒不直接终止，而是经下方「回灌」把失败调用交还模型重新决策。
                for (String rejectedTool : toolResult.rejectedTools()) {
                    int strikes = rejectionStrikes.merge(rejectedTool, 1, Integer::sum);
                    if (strikes >= 3) {
                        log.warn("工具 [{}] 累计被拒 {} 次，终止 Run: runId={}", rejectedTool, strikes, task.runId());
                        ExecutionOutcome terminated = commitFailure(tenantId, task,
                                "工具被拒达上限:" + rejectedTool, "TOOL_REJECTED_3X");
                        notifyLoopComplete(tenantId, task.runId(), terminated, steps);
                        return terminated;
                    }
                }

                if (toolResult.allRejected()) {
                    // 工具全被拒（如授权失败）→ 交由模型重新决策或失败
                    log.info("工具调用全部被拒: runId={}", task.runId());
                    ExecutionOutcome rejected = commitFailure(tenantId, task, "工具调用被拒绝", "TOOL_REJECTED");
                    notifyLoopComplete(tenantId, task.runId(), rejected, steps);
                    return rejected;
                }

                // ── 回灌：把「模型意图 + 工具结果」并入下一轮上下文 ──────
                // ⚠️ 与子 Agent 执行器**共用**渲染口径：不可信标记、长度封顶、
                //    失败与被拒也回灌 —— 三条规则逐字一致，杜绝父子语义漂移。
                messages.addAll(ToolOutcomeRenderer.renderRound(
                        response.toolCalls(), toolResult));

                // ── §5 续租（长任务必须，否则会被接管）──────────────────
                // ⚠️ 身份必须是构造时的 workerId（与领取时一致），
                //    不可从 task 现场拼凑 —— 那会让 lease_owner 永远不匹配（F-1）
                if (!claimPort.renewLease(tenantId, task.runId(),
                        workerId, task.fencingToken(), leaseDuration)) {
                    log.info("续租失败（已被接管），停止执行: runId={}", task.runId());
                    notifyLoopComplete(tenantId, task.runId(),
                            ExecutionOutcome.skippedStaleGeneration(), steps);
                    return ExecutionOutcome.skippedStaleGeneration();
                }
            }

            // ── §5.1 步数超限 ──────────────────────────────────────────
            log.warn("步数超限: runId={}, steps={}, max={}",
                    task.runId(), steps, snapshot.maxSteps());
            notifyLoopComplete(tenantId, task.runId(),
                    commitFailure(tenantId, task, "步数超限", "MAX_STEPS"), steps);
            return commitFailure(tenantId, task, "步数超限", "MAX_STEPS");

        } catch (ModelGateway.ModelGatewayException e) {
            // ── §3 三种异常语义 ────────────────────────────────────────
            if (e.kind().retryable()) {
                log.info("模型调用可重试失败: runId={}, kind={}",
                        task.runId(), e.kind());
                notifyLoopComplete(tenantId, task.runId(),
                        ExecutionOutcome.retryableFailure(e.kind().name()), steps);
                return ExecutionOutcome.retryableFailure(e.kind().name());
            }
            log.info("模型调用不可重试失败: runId={}, kind={}",
                    task.runId(), e.kind());
            ExecutionOutcome modelErr = commitFailure(tenantId, task,
                    "模型调用失败：" + e.kind(), "MODEL_" + e.kind().name());
            notifyLoopComplete(tenantId, task.runId(), modelErr, steps);
            return modelErr;

        } catch (ToolExecutionException e) {
            // ⚠️ 能走到这里说明工具闸门没有正确处理 UNKNOWN ——
            //    闸门应在内部转为 unknownResultOccurred()，而非抛出
            log.error("工具异常逃逸出闸门（不应发生）: runId={}", task.runId(), e);
            notifyLoopComplete(tenantId, task.runId(),
                    commitFailure(tenantId, task, "工具执行异常", "TOOL_INTERNAL"), steps);
            return commitFailure(tenantId, task, "工具执行异常", "TOOL_INTERNAL");

        } catch (RuntimeException e) {
            log.error("执行过程未预期异常: runId={}", task.runId(), e);
            notifyLoopComplete(tenantId, task.runId(),
                    commitFailure(tenantId, task, "执行异常", "INTERNAL_ERROR"), steps);
            return commitFailure(tenantId, task, "执行异常", "INTERNAL_ERROR");
        }
    }

    /**
     * 追加一条执行事件（观测用途，**失败不影响主流程**）。
     *
     * <p>⚠️ <b>为什么吞掉异常</b>：事件是<b>观测</b>，不是业务事实。
     * Run 的成败**不依赖**事件是否写成功。
     * 若事件写失败就让 Run 失败 → 会触发重试 → <b>重复副作用</b>（Sev-0）。
     *
     * <p>⚠️ <b>但不得静默</b>：必须留 WARN——
     * 否则「时间线空了」无人知晓，这正是本项目反复吃的「静默失效」亏。
     *
     * @param tenantId 租户
     * @param runId    Run
     * @param type     事件类型
     * @param payload  载荷（只含白名单标量）
     */
    /**
     * 租户生命周期再验证（重规划 §14 / 验收矩阵 #4）。
     *
     * <p>⚠️ 以 DB 当前状态为准（不信任消息体）。仅 {@link TenantLifecycle#ACTIVE}
     * 允许执行；其余状态（SUSPENDED/DECOMMISSIONED/DECOMMISSIONING/
     * PENDING_SETUP/CANCELLED）与「租户不存在」一律拒绝。
     *
     * <p>⚠️ {@code lifecycleProbe == null} 时跳过（未装配，向后兼容），
     * 但生产<b>必须</b>装配，否则本方法恒返回 {@code null}（不拦截）。
     *
     * @return 应被拒绝时返回 {@link ExecutionOutcome#tenantBlocked()}；
     *         可继续执行时返回 {@code null}
     */
    private @Nullable ExecutionOutcome rejectIfTenantBlocked(String tenantId, String runId) {
        if (lifecycleProbe == null) {
            return null;
        }
        String state = lifecycleProbe.lifecycleState(tenantId).orElse(null);
        if (state == null) {
            log.warn("租户不存在，拒绝执行: tenantId={}, runId={}", tenantId, runId);
            notifyLoopComplete(tenantId, runId,
                    ExecutionOutcome.tenantBlocked("租户不存在"), 0);
            return ExecutionOutcome.tenantBlocked("租户不存在");
        }
        if (!TenantLifecycle.isActive(state)) {
            log.info("租户状态不可执行({})，拒绝执行: tenantId={}, runId={}",
                    state, tenantId, runId);
            notifyLoopComplete(tenantId, runId,
                    ExecutionOutcome.tenantBlocked("租户状态=" + state), 0);
            return ExecutionOutcome.tenantBlocked("租户状态=" + state);
        }
        return null;
    }

    private void appendEvent(String tenantId, String runId, String type,
                             java.util.Map<String, Object> payload) {
        if (eventPort == null) {
            return;
        }
        try {
            eventPort.append(tenantId, runId, type, payload);
        } catch (RuntimeException e) {
            log.warn("⚠️ 追加执行事件失败（不影响本次执行结果）: runId={}, type={}, cause={}",
                    runId, type, e.toString());
        }
    }

    /**
     * 追加一条执行步骤（P7-2 事实写入）。
     *
     * <p>⚠️ <b>与 {@link #appendEvent} 同纪律</b>：步骤是 P7 指标与结果页的真实数据源，
     * 但写入失败（DB 抖动 / 未注入端口 / fencing 拦截）<b>绝不冒泡到主流程</b>——
     * 一次本可成功的 Run 不能因「步骤没记下」而变成失败。异常在此吞掉并记 WARN。
     *
     * <p>⚠️ 幂等：{@code stepPort.append} 按 {@code (tenant, run, step_number)} 冲突覆盖，
     * 重放不重复造步骤。
     */
    private void appendStep(String tenantId, RunClaimPort.LeasedTask task,
                            int stepNumber, String stepType,
                            java.time.Instant startedAt, java.time.Instant completedAt) {
        if (stepPort == null) {
            return;
        }
        try {
            stepPort.append(tenantId, task.runId(), task.fencingToken(),
                    stepNumber, stepType, startedAt, completedAt);
        } catch (RuntimeException e) {
            log.warn("⚠️ 写入执行步骤失败（不影响本次执行结果）: runId={}, step={}, type={}, cause={}",
                    task.runId(), stepNumber, stepType, e.toString());
        }
    }

    /**
     * 发射一条安全事件（P7-4 事实写入）。
     *
     * <p>⚠️ <b>与 {@link #appendStep} 同纪律</b>：端口未注入（{@code null}）直接跳过；
     * 写入失败绝不冒泡到主流程——安全明细缺一条是可接受的观测损失，
     * 让一次本可成功的 Run 失败不可接受。
     */
    private void emitRiskEvent(String tenantId, RunClaimPort.LeasedTask task,
                               String eventType, String detailDigest) {
        if (riskEventPort == null) {
            return;
        }
        try {
            riskEventPort.record(tenantId, task.runId(), eventType, detailDigest);
        } catch (RuntimeException e) {
            log.warn("⚠️ 写入安全事件失败（不影响本次执行结果）: runId={}, type={}, cause={}",
                    task.runId(), eventType, e.toString());
        }
    }

    /**
     * 单步调用越过根截止时间（FR-024 在单步粒度被击穿）。
     *
     * <p>⚠️ 必须能被上方的 {@code try/catch} 精确识别并转成 {@code TIMEOUT} 终态：
     * 若不识别，一次卡死的长调用会被当成「未知异常」落成
     * {@code INTERNAL_ERROR}，运维无法把「超时」与「故障」区分开。
     */
    private static final class StepDeadlineExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StepDeadlineExceededException(String message) {
            super(message);
        }
    }

    /**
     * 单次长调用的<b>抢占式</b>剩余时间边界（P2-1）。
     *
     * <p>⚠️⚠️ <b>补的正是这个洞</b>：循环体每轮开头才查 {@code task.deadlineAt()}，
     * 那么「一次的卡死」天然处在两次检查之间。三个后果必须区分清楚：
     * <ul>
     *   <li>模型侧 HTTP 有超时但不是硬性的 → 单步可拖过 deadline；</li>
     *   <li>工具里若有不受 {@code HttpToolExecutor} 约束的实现 → 同样可拖过；</li>
     *   <li>结果是 Run 越过 {@code run.deadline_at} 仍在燃烧 token 与预算。</li>
     * </ul>
     * 故把单次调用提交到 {@link #preemptionExecutor}，用「当前到 deadline 的剩余时间」
     * 作为 {@code Future.get} 上限，到期 {@code cancel(true)} 中断。
     *
     * <p>⚠️ <b>不可中断的任务怎么办</b>：{@code cancel(true)} 只发中断信号，
     * 若任务自身不响应中断仍会继续跑。因此超时后本方法<b>立即抛出</b>
     * {@link StepDeadlineExceededException} 让本 Run 收敛为 {@code TIMEOUT}——
     * 结果不等废弃物线程；这是「结果正确」与「线程立即消失」之间的正确取舍。
     *
     * <p>⚠️ <b>未装配执行器时不做伪保护</b>：不做超时伪装，直接同步执行，
     * 即可观测性上的 WARN 已在构造期打印。此处<b>不重复打日志</b>。
     *
     * @throws StepDeadlineExceededException 剩余时间已耗尽（含调用被拖到超时）
     */
    private <T> T withinRemaining(RunClaimPort.LeasedTask task, String phase,
                                  @NonNull Callable<T> body) {
        Instant deadline = task.deadlineAt();
        if (preemptionExecutor == null || deadline == null) {
            // deadline == null 是「创建时声明不限时」的既定语义，非缺陷，不予中断
            return callBody(phase, body);
        }
        long remainingMs;
        try {
            remainingMs = Duration.between(Instant.now(), deadline).toMillis();
        } catch (ArithmeticException overflow) {
            // Instant.MAX 之类的超远期 deadline：量级溢出 = 实质不限时
            return callBody(phase, body);
        }
        if (remainingMs <= 0) {
            throw new StepDeadlineExceededException(phase + " 开始前剩余时间已耗尽: runId="
                    + task.runId());
        }
        Future<T> future = preemptionExecutor.submit(body);
        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StepDeadlineExceededException(phase + " 超过根 Run 截止时间，已中断: runId="
                    + task.runId() + ", 预算剩余=" + remainingMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(phase + " 执行失败: runId=" + task.runId(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(phase + " 等待被中断: runId=" + task.runId(), e);
        }
    }

    /** 同步执行体并还原受检异常为本类约定的形态。 */
    private static <T> T callBody(String phase, @NonNull Callable<T> body) {
        try {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(phase + " 执行失败", e);
        }
    }

    /**
     * 内容筛查结果。
     *
     * @param blocked 非空表示应中止并返回该失败 outcome
     * @param content 应下发的正文（脱敏后）；blocked 非空时被填上原文，调用方不得采用
     */
    private record ScreenResult(ExecutionOutcome blocked, String content) {
    }

    // ── P0-3b：模型成本预留 / 结算 / 释放 ───────────────────────────

    /**
     * 输入 token 估算的<b>安全系数</b>。
     *
     * <p>⚠️ <b>为什么需要它</b>：预留额必须 <b>≥</b> 真实成本，
     * 否则结算会被 DB 的 {@code CHECK (settled <= reserved)} 拒绝，账就断了。
     * 而 {@link ContextBudgetTrimmer} 的 token 估算是<b>粗估</b>
     * （非真 tokenizer），且系统提示与工具 Schema 不在消息序列的估算里。
     * 故对输入侧取 2 倍上界作为预留，输出侧取 {@code maxTokens} 上界。
     *
     * <p>⚠️ <b>多预留的部分会自动退回</b>：结算时「预留额全额扣回、只计实结额」，
     * 所以取上界不会造成额度损失，只是占用时间略长。
     */
    private static final int INPUT_TOKEN_ESTIMATE_SAFETY_FACTOR = 2;

    /**
     * 模型调用的<b>成本预留</b>（P0-3b）。
     *
     * @param reservationId 预算预留标识；{@code null} = 无预留（未装配预算端口）
     * @param modelPricing  命中的单价规则；{@code null} = 该模型未配价
     * @param modelCallId   模型调用标识（幂等键）
     */
    private record ModelCostReservation(
            @Nullable String reservationId,
            @Nullable ModelPricing modelPricing,
            @NonNull String modelCallId) {
    }

    /**
     * 生成模型调用标识（<b>确定性</b> UUID）。
     *
     * <p>⚠️ <b>必须确定性</b>：它是 {@code usage_record} 的幂等键 ——
     * 同一 Run 的同一次重试若算出不同的键，就会<b>重复计费</b>。
     * 故由「runId + 领取代次 + 调用序号」派生。
     *
     * <p>⚠️ <b>必须含领取代次（attempt）</b>：同一个 Run 被重投时 attempt 会变，
     * 若只用「runId + 序号」，第二次尝试会命中第一次的幂等键 →
     * 第二次的真实消耗<b>不会被记账</b>（账实不符）。
     */
    private static @NonNull String modelCallId(@NonNull String runId, int attempt, int ordinal) {
        String seed = runId + "|" + attempt + "|" + ordinal;
        return java.util.UUID.nameUUIDFromBytes(
                seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * 预留模型调用的成本额度（P0-3b）。
     *
     * <p>⚠️ <b>查不到单价时</b>：不预留（金额为 0 的预留会被 DB 拒绝），
     * 但<b>如实记 token</b>（见 {@link #settleModelCost}）。
     * 缺配置必须<b>可见</b>（WARN），不能让「漏配单价」静默变成「这次调用不存在」。
     *
     * @return 预留结果；预留被拒时返回空（调用方须 fail-closed）
     */
    private @NonNull Optional<ModelCostReservation> reserveModelCost(
            @NonNull String tenantId,
            @NonNull String runId,
            @NonNull AgentModelConfig agentConfig,
            ContextBudgetTrimmer.Trimmed trimmed,
            @NonNull String systemPrompt,
            @NonNull String modelCallId) {
        if (budget == null) {
            return Optional.of(new ModelCostReservation(null, null, modelCallId));
        }
        Optional<ModelPricing> price = pricing.pricingOf(agentConfig.modelId());
        if (price.isEmpty()) {
            log.warn("模型未配置单价，成本按 0 记（token 仍如实记录）: model={}, runId={}",
                    agentConfig.modelId(), runId);
            return Optional.of(new ModelCostReservation(null, null, modelCallId));
        }
        ModelPricing rule = price.get();

        long estimatedInput = (long) trimmed.estimatedTokens()
                + ContextBudgetTrimmer.estimateTokens(systemPrompt);
        long reservedInput = estimatedInput * INPUT_TOKEN_ESTIMATE_SAFETY_FACTOR;
        BigDecimal reserved = rule.cost(reservedInput, Math.max(0, agentConfig.maxTokens()));
        if (reserved.signum() <= 0) {
            // 预留金额必须为正（DB CHECK）：单价为 0 的模型无需预留额度。
            return Optional.of(new ModelCostReservation(null, rule, modelCallId));
        }

        BudgetPort.ReserveOutcome outcome =
                budget.reserve(tenantId, runId, modelCallId, reserved);
        if (!outcome.granted()) {
            log.warn("模型调用预算预留被拒: tenant={}, run={}, model={}, 预留={}, outcome={}",
                    tenantId, runId, agentConfig.modelId(), reserved, outcome);
            return Optional.empty();
        }
        Optional<BudgetPort.Reservation> reservation = budget.findByOperation(tenantId, modelCallId);
        if (reservation.isEmpty()) {
            // ⚠️ 预留已生效却查不到预留记录 → 无法结算，fail-closed：
            //    否则这次调用的成本会成为一笔无人认领的开销。
            log.error("模型预算预留已生效但查不到预留记录，fail-closed: run={}, call={}",
                    runId, modelCallId);
            return Optional.empty();
        }
        return Optional.of(new ModelCostReservation(
                reservation.get().reservationId(), rule, modelCallId));
    }

    /**
     * 结算模型调用的成本并写入真实 token（P0-3b）。
     *
     * <p>⚠️ <b>两条路径</b>：
     * <ul>
     *   <li><b>有预留</b>：{@code settle}（预留额全额扣回、只计实结额，
     *       并把真实 token 写进计量行）；</li>
     *   <li><b>无预留</b>（未配价 / 未装配预算端口）：{@code recordModelUsage}
     *       —— <b>只计量不扣款</b>：token 是事实必须记，
     *       金额按 0 记（没有价格就没有金额）。</li>
     * </ul>
     *
     * @return 本次调用的真实成本（无单价时为 0）
     */
    private @NonNull BigDecimal settleModelCost(@NonNull String tenantId,
                                                @NonNull String runId,
                                                @NonNull String modelId,
                                                @NonNull ModelResponse response,
                                                @NonNull ModelCostReservation reservation) {
        if (budget == null) {
            return BigDecimal.ZERO;
        }
        long inputTokens = response.usage().inputTokens();
        long outputTokens = response.usage().outputTokens();
        BigDecimal actual = reservation.modelPricing() == null
                ? BigDecimal.ZERO
                : reservation.modelPricing().cost(inputTokens, outputTokens);

        try {
            if (reservation.reservationId() != null) {
                if (!budget.settle(tenantId, reservation.reservationId(), actual, modelId,
                        inputTokens, outputTokens)) {
                    log.warn("模型预算结算未生效（预留不存在或已终态），将由对账兜底: "
                            + "run={}, reservation={}", runId, reservation.reservationId());
                }
            } else {
                budget.recordModelUsage(tenantId, runId, modelId, inputTokens, outputTokens,
                        actual, reservation.modelCallId());
            }
        } catch (RuntimeException e) {
            // ⚠️ 结算失败**不得**改写调用结局：调用确实发生了，成本也确实发生了。
            //    失败留下 RESERVED 预留，由预算对账兜底处置。
            log.error("模型成本结算异常，预留保持 RESERVED 待对账: run={}, model={}, err={}",
                    runId, modelId, e.toString());
        }
        return actual;
    }

    /**
     * 释放模型调用的成本预留（调用失败时）。
     *
     * <p>⚠️ <b>失败不抛</b>：释放失败只留下悬挂预留（由对账兜底），
     * 不得反过来把「调用失败」变成「调用异常」。
     */
    private void releaseModelCost(@NonNull String tenantId,
                                  @NonNull ModelCostReservation reservation) {
        if (budget == null || reservation.reservationId() == null) {
            return;
        }
        try {
            budget.release(tenantId, reservation.reservationId(), "模型调用未产出");
        } catch (RuntimeException e) {
            log.error("模型预算预留释放异常，留待对账兜底: reservation={}, err={}",
                    reservation.reservationId(), e.toString());
        }
    }

    /**
     * 对一段模型输出执行<b>输出内容安全层 + 编造出处检测</b>（P1-A / P1-D）。
     *
     * <p>⚠️ <b>为什么必须只留这一份实现</b>：终答与带工具调用的中间正文
     * 需要同一套判据。若两处各写一份，迟早漂移成
     * 「中间步放行、终答才拦」——而模型完全可以把编造塞进中间步。
     *
     * @return 筛查结果；{@code blocked} 非空时应立即返回该 outcome
     */
    private ScreenResult screenContent(String tenantId, RunClaimPort.LeasedTask task,
                                       int steps, String content) {
        cn.studykid.multiagent.core.compliance.OutputModeration.Decision decision =
                cn.studykid.multiagent.core.compliance.OutputModeration.moderate(content);
        for (String piiLabel : decision.piiLabels()) {
            emitRiskEvent(tenantId, task, RiskEventPort.SENSITIVE_OUTPUT,
                    "PATTERN:" + piiLabel);
        }
        if (decision.blocked()) {
            log.error("⚠️⚠️ 输出未通过平台侧内容安全层，拒绝下发: runId={}, category={}, "
                            + "labels={}", task.runId(), decision.category(),
                    decision.labels());
            emitRiskEvent(tenantId, task, RiskEventPort.SAFETY_BLOCK,
                    "OUTPUT_MODERATION:" + decision.category());
            ExecutionOutcome outcome = commitFailure(tenantId, task,
                    "输出未通过内容安全检查", "SAFETY_OUTPUT_MODERATION");
            notifyLoopComplete(tenantId, task.runId(), outcome, steps);
            return new ScreenResult(outcome, decision.content());
        }
        try {
            cn.studykid.multiagent.application.evaluation.HallucinationDetector
                    .assertNoFabricatedSource(decision.content());
        } catch (cn.studykid.multiagent.application.evaluation.HallucinatedSourceException e) {
            log.error("⚠️⚠️ 输出涉嫌编造出处，拒绝提交: runId={}, rule={}",
                    task.runId(), e.rule());
            // P7-4：SEC-1/SEC-2 之外的明细事实——安全拦截落一条 SAFETY_BLOCK
            emitRiskEvent(tenantId, task, RiskEventPort.SAFETY_BLOCK,
                    "FABRICATED_SOURCE:" + e.rule());
            ExecutionOutcome outcome = commitFailure(tenantId, task,
                    "输出涉嫌编造资料出处", "SAFETY_FABRICATED_SOURCE");
            notifyLoopComplete(tenantId, task.runId(), outcome, steps);
            return new ScreenResult(outcome, decision.content());
        }
        return new ScreenResult(null, decision.content());
    }

    /**
     * 判定工具拒绝原因是否属于「越权 / 审批拒绝」（SEC-2）。
     *
     * <p>⚠️ 既有链路的拒绝原因是文本（非结构化码），此处按已知文案匹配；
     * 误判的代价是 SEC-2 少计（fail-open 于观测），而不是把无效调用误报成
     * 安全事件（fail-noisy 于告警）——两个方向的代价不对称，选前者。
     */
    private static boolean isAuthorizationDenial(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.contains("无权") || reason.contains("权限")
                || reason.startsWith("审批未");
    }

    private ExecutionOutcome commitSuccess(String tenantId, RunClaimPort.LeasedTask task,
                                          String content) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "SUCCEEDED", jsonPayload("content", content));
        if (ok) {
            // ⚠️ 只在**真的提交成功**后才记终态事件：
            //    提交失败 = 世代失效（白干了），不是「执行失败」。
            appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_SUCCEEDED,
                    java.util.Map.of("state", "SUCCEEDED"));
        }
        // ⚠️ 提交失败（世代不符）不是执行失败，是「白干了」
        return ok ? ExecutionOutcome.succeeded()
                  : ExecutionOutcome.skippedStaleGeneration();
    }

    private ExecutionOutcome commitFailure(String tenantId, RunClaimPort.LeasedTask task,
                                           String reason, String errorCode) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "FAILED", jsonPayload("reason", reason), errorCode);
        if (ok) {
            // ⚠️ 失败载荷**只放 state**，不放 reason 原文：
            //    reason 是内部错误细节（如「模型调用失败：TIMEOUT」），
            //    白名单里的 errorCode 要求是**码**而非原文。
            //    ⚠️ P7-2：error_code 现在随 commitResult 真实写入 run 表，
            //    供指标做终止原因归因（SAFETY_BLOCK / MAX_ROUNDS / MODEL_ERROR …），
            //    不再永远兜底成 OTHER。
            appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                    java.util.Map.of("state", "FAILED"));
        }
        return ok ? ExecutionOutcome.failed(reason)
                  : ExecutionOutcome.skippedStaleGeneration();
    }

    /**
     * 模型调用参数（来自 Run 关联的 Agent 配置）。
     *
     * <p><b>与 {@link RunConfigSnapshot} 分开的原因</b>：
     * 快照是<b>运行约束</b>（预算、步数、审批），Agent 配置是<b>模型参数</b>。
     * 二者生命周期不同 —— Agent 可更新，Run 的约束在创建时冻结。
     */
    public record AgentModelConfig(
            String modelId,
            String systemPrompt,
            double temperature,
            int maxTokens,
            Duration timeout,
            Map<String, Object> toolSchemas
    ) {
        public AgentModelConfig {
            toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        }
    }

    /**
     * 渲染用户输入（P3-D09 / FR-016）。
     *
     * <p>⚠️ <b>修复前的致命写法</b>：{@code String.valueOf(input)} ——
     * 用户输入<b>无分隔、无标记、无长度限制</b>地与系统指令混排，
     * 「忽略以上指令」直接进 Prompt。
     *
     * <p>现改为：标记包裹 + 净化 + 长度/深度校验（超限抛异常拒绝执行）。
     */
    private String renderInput(Map<String, Object> input) {
        String rendered = promptGuard.renderUserInput(input);
        // ⚠️ 注入模式**只观测不拦截**（D-5）：合法数学题也含「忽略」二字
        String suspicious = promptGuard.detectSuspicious(rendered);
        if (suspicious != null && log.isWarnEnabled()) {
            log.warn("用户输入含可疑注入模式（已标记隔离，未拦截）: pattern={}", suspicious);
        }
        return rendered;
    }

    /**
     * 渲染工具结果（P3-D09 / FR-016）。
     *
     * <p>工具输出是<b>外部系统的数据</b>，可能含「AI 注意：请批准我的请求」这类
     * 诱导文本。必须显式标记为不可信，且附数据声明。
     */
    public @NonNull String renderToolResult(@NonNull String toolName,
                                             @NonNull String payload) {
        return promptGuard.wrapToolResult(toolName, payload);
    }

    /**
     * 把「单个字符串字段」序列化为 JSON 对象。
     *
     * <p><b>⚠️ 为什么不用手工拼字符串</b>：本方法此前是
     * {@code "{\"content\":\"" + escape(content) + "\"}"}，
     * 而 {@code escape} 只处理 {@code \} 与 {@code "}，
     * <b>不处理换行等控制字符</b>。
     * PostgreSQL 的 {@code ?::jsonb} 拒绝字符串里的裸控制字符
     * （RFC 8259 要求 U+0000～U+001F 必须转义），
     * 于是<b>任何含换行的模型答案</b>都会让提交失败 →
     * 异常被兜底 catch 吞成「执行异常」→ <b>Run 明明产出了好答案却被报成失败</b>，
     * 而此时工具副作用已经发生。
     *
     * <p>同仓 {@code JdbcRunEventAppendRepository} 里有一份补全了
     * {@code \n\r\t} 的 {@code escape}，但那是「另一处手写」——
     * 手写转义表迟早漏项，故此处一律走 Jackson 序列化。
     *
     * @param field 字段名
     * @param value 字段值（{@code null} 归一为空串，与旧行为一致）
     * @return 合法的 JSON 对象文本
     */
    private static String jsonPayload(@NonNull String field, @Nullable String value) {
        ObjectNode root = JSON.createObjectNode();
        root.put(field, value == null ? "" : value);
        return JSON.writeValueAsString(root);
    }

    /**
     * RESUME 的续跑接缝（M1-4b / G2）。
     *
     * <p>⚠️ <b>为什么不直接在 {@link RunExecutionLoop} 内重入主循环</b>：
     * 模型参数（Agent 配置）与 Run 输入由装配层（{@code WorkerExecutionConfig}）
     * 从 DB 加载，执行循环自身没有这些依赖。续跑接缝由装配层注入：
     * 恢复工具批次执行完后，交回主执行路径继续对话。
     *
     * <p>⚠️ <b>fail-closed</b>：未装配（{@code null}）时恢复路径不得静默
     * 把 Run 悬在 RUNNING —— 由 {@link #resume} 显式收敛终态。
     */
    @FunctionalInterface
    public interface RunContinuation {
        /**
         * @param tenantId 租户
         * @param task     已重新领取租约的 Run 任务
         * @return 续跑结局
         */
        @NonNull ExecutionOutcome continueRun(@NonNull String tenantId,
                                              RunClaimPort.@NonNull LeasedTask task);
    }

    /**
     * 续跑接缝（M1-4b）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时 {@link #resume} 在工具批次执行成功后
     * 显式收敛 Run 为 FAILED（错误码 {@code RESUME_CONTINUATION_UNAVAILABLE}），
     * 绝不把 Run 悬在 RUNNING 等租约过期被接管 —— 那会导致整单重跑。
     * 生产装配<b>必须</b>传入。
     */
    private final RunContinuation continuation;

    /**
     * RESUME（恢复 · M1-5 / G2 修复核心）。
     *
     * <p>审批决定到达后由 {@code ApprovalDecidedWakeupConsumer} 调用。
     * ⚠️ <b>不调用模型</b>：调用方按 {@code tool_operation.tool_arguments}（经
     * {@code RunToolLedgerPort#findInvocation} 重建）传入已批准的操作对应的
     * {@link ModelResponse.ToolCallRequest}，本方法直接重入 {@code toolGate.invokeAll} 闸门；
     * 此时 {@code approvalPort.consume} 命中已 APPROVED 行 → 执行。
     *
     * <p>⚠️ 恢复后若仍要推进对话，交由续跑接缝（{@link RunContinuation}）继续；
     * 对话连续性依赖 Run 持久化上下文（属更大的「对话持久化」范畴，本变更只交付
     * PARK/RESUME 的<b>闸门原语</b>：挂起→唤醒→重入→执行）。
     *
     * @param tenantId 租户
     * @param task     已重新领取租约的 Run 任务（RESUME 需重新建立租约）
     * @param calls    重建后的待执行工具调用（已含被批准的操作）
     */
    public @NonNull ExecutionOutcome resume(@NonNull String tenantId,
                                           RunClaimPort.@NonNull LeasedTask task,
                                           java.util.@NonNull List<ModelResponse.ToolCallRequest> calls) {
        // ⚠️ invokeAll 以 Map 为入参（键 = 模型 callId）：恢复重建的调用按 callId 装回，
        //    缺失 callId 的用序号兜底（键只用于遍历，不进幂等键——幂等键由
        //    ToolInvocationGate 按 runId+agentRef+toolName+callId 自行构造）。
        java.util.Map<String, ModelResponse.ToolCallRequest> callsByKey =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < calls.size(); i++) {
            ModelResponse.ToolCallRequest call = calls.get(i);
            String key = (call.callId() == null || call.callId().isBlank())
                    ? "resume-" + i : call.callId();
            callsByKey.putIfAbsent(key, call);
        }
        java.time.Instant toolStart = java.time.Instant.now();
        ToolInvocationGate.ToolInvocationResult toolResult;
        try {
            toolResult = withinRemaining(task, "TOOL_CALL_RESUME",
                    () -> toolGate.invokeAll(tenantId, task, callsByKey));
        } catch (StepDeadlineExceededException e) {
            log.warn("恢复工具调用越过根截止时间，Run 收敛为 TIMEOUT: {}", e.getMessage());
            return commitFailure(tenantId, task, "恢复单步工具调用超时", "TIMEOUT");
        }
        java.time.Instant toolEnd = java.time.Instant.now();
        appendStep(tenantId, task, 1, RunStepPort.Types.TOOL, toolStart, toolEnd);

        if (toolResult.hasParked()) {
            // 恢复后仍被判挂起（如审批被撤销/过期）→ 重新挂起，保持 WAITING_APPROVAL。
            java.util.Set<String> parkedOps = new java.util.LinkedHashSet<>(
                    toolResult.parkedTools().values());
            boolean parkedOk = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                    "WAITING_APPROVAL",
                    jsonPayload("parkedOperations", String.join(",", parkedOps)));
            if (parkedOk) {
                appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_PARKED,
                        java.util.Map.of("state", "WAITING_APPROVAL"));
                claimPort.releaseLease(tenantId, task.runId(), workerId, task.fencingToken());
            }
            ExecutionOutcome parkedOutcome = ExecutionOutcome.parked(parkedOps);
            notifyLoopComplete(tenantId, task.runId(), parkedOutcome, 1);
            return parkedOutcome;
        }
        if (toolResult.unknownResultOccurred()) {
            ExecutionOutcome unknown = ExecutionOutcome.unknownResult(toolResult.unknownOperationId());
            notifyLoopComplete(tenantId, task.runId(), unknown, 1);
            return unknown;
        }
        if (toolResult.allRejected()) {
            // ⚠️ fail-closed：恢复重入被拒（如决定恰好被撤销/拒绝竞态）→ Run 收敛 FAILED。
            //    工具未执行（账本行保持原状），无副作用。
            ExecutionOutcome rejected = commitFailure(tenantId, task, "恢复工具调用被拒绝", "TOOL_REJECTED");
            notifyLoopComplete(tenantId, task.runId(), rejected, 1);
            return rejected;
        }
        // 执行成功：交由续跑接缝继续主循环（不重问已批准批次，模型据上下文推进）。
        if (continuation == null) {
            // ⚠️ fail-closed：未装配续跑接缝时，绝不能把 Run 悬在 RUNNING
            //    （租约过期被接管 = 整单重跑 = 违背「恢复不重跑」的正确性要求）。
            //    显式收敛终态并留可定位的错误码。
            log.error("⚠️ 未装配续跑接缝（RunContinuation），恢复批次执行完后无法继续对话，"
                    + "Run 显式收敛 FAILED: runId={}", task.runId());
            return commitFailure(tenantId, task,
                    "恢复后续跑接缝未装配", "RESUME_CONTINUATION_UNAVAILABLE");
        }
        return continuation.continueRun(tenantId, task);
    }

    /**
     * 构造一条合法的失败结果载荷（jsonb）。
     *
     * <p>⚠️ 统一走 Jackson 序列化，禁止手工拼字符串——
     * 手写转义表迟早漏项（见 {@link #jsonPayload} 的教训）。
     * 供孤儿 Run 兜底收敛等场景复用，避免各处重复拼 JSON。
     *
     * @param reason 失败原因摘要（内部常量，非用户输入）
     * @return 合法 JSON 文本，如 {@code {"reason":"config_unavailable"}}
     */
    public static String failurePayload(@NonNull String reason) {
        ObjectNode root = JSON.createObjectNode();
        root.put("reason", reason == null ? "" : reason);
        return JSON.writeValueAsString(root);
    }

    /**
     * 执行结果。
     *
     * <p><b>⚠️ 各结果对应不同后续处置，不可混淆</b>：
     * <ul>
     *   <li>{@code RETRYABLE_FAILURE} —— 可重新投递消息重试；</li>
     *   <li>{@code UNKNOWN_RESULT} —— <b>禁止重试</b>，转对账；</li>
     *   <li>{@code SKIPPED_STALE_GENERATION} —— 不重试，任务已被接管。</li>
     * </ul>
     */
    public record ExecutionOutcome(Kind kind, String detail) {

        public enum Kind {
            SUCCEEDED,
            FAILED,
            /** 可重试失败：重新投递即可。 */
            RETRYABLE_FAILURE,
            /** ⚠️ 结果未知：**禁止重试**，须对账。 */
            UNKNOWN_RESULT,
            /** 世代已失效：已被接管，不重试。 */
            SKIPPED_STALE_GENERATION,
            /** ⚠️ 用户取消：协作式停止，不再启动新动作。 */
            CANCELLED,
            /**
             * ⚠️ 租户生命周期不可执行（SUSPENDED/DECOMMISSIONED/未激活或不存在）。
             * 重规划 §14 / 验收矩阵 #4：平台暂停/退役租户后拒绝新执行与在跑任务。
             * <b>不可重试</b>——重试只会再次被拒，且暂停态重投无意义。
             */
            TENANT_BLOCKED,
            /**
             * ⚠️ 已挂起等待人工审批（M1-4 / G2 · PARK）。
             * Run 进入 WAITING_APPROVAL、释放租约、让出 worker 线程；
             * 审批决定到达后由 {@code ApprovalDecidedWakeupConsumer} 唤醒并 RESUME。
             * <b>不可重试</b>——挂起是暂停而非失败。
             */
            PARKED
        }

        public ExecutionOutcome {
            if (kind == null) {
                throw new IllegalArgumentException("kind 不能为空");
            }
        }

        public static ExecutionOutcome succeeded() {
            return new ExecutionOutcome(Kind.SUCCEEDED, null);
        }

        public static ExecutionOutcome failed(String reason) {
            return new ExecutionOutcome(Kind.FAILED, reason);
        }

        public static ExecutionOutcome retryableFailure(String kind) {
            return new ExecutionOutcome(Kind.RETRYABLE_FAILURE, kind);
        }

        public static ExecutionOutcome unknownResult(String operationId) {
            return new ExecutionOutcome(Kind.UNKNOWN_RESULT, operationId);
        }

        public static ExecutionOutcome skippedStaleGeneration() {
            return new ExecutionOutcome(Kind.SKIPPED_STALE_GENERATION, null);
        }

        public static ExecutionOutcome cancelled() {
            return new ExecutionOutcome(Kind.CANCELLED, null);
        }

        /**
         * 已挂起等待审批（M1-4 / G2）。
         *
         * @param operationIds 被挂起的操作标识集合（供唤醒后 RESUME 重入闸门）
         */
        public static ExecutionOutcome parked(java.util.@NonNull Set<String> operationIds) {
            return new ExecutionOutcome(Kind.PARKED, String.join(",", operationIds));
        }

        /** 租户生命周期不可执行（暂停/退役/未激活/不存在）。不可重试。 */
        public static ExecutionOutcome tenantBlocked(String reason) {
            return new ExecutionOutcome(Kind.TENANT_BLOCKED, reason);
        }

        /** @return 是否允许重新投递重试。UNKNOWN / 世代失效 / PARKED 均**不允许**。 */
        public boolean retryable() {
            return kind == Kind.RETRYABLE_FAILURE;
        }
    }

    // ── 观测者适配器（将 worker 模块类型转为 ports 层中性类型） ──────────

    /**
     * 从 ToolInvocationGate.ToolInvocationResult 适配为 ToolCallSummary。
     */
    private static ExecutionObserver.ToolCallSummary adaptToolResult(
            ToolInvocationGate.ToolInvocationResult result) {
        return new ExecutionObserver.ToolCallSummary(
                result.succeededTools(),
                result.failedTools(),
                result.rejectedTools(),
                result.unknownResultOccurred(),
                result.unknownOperationId());
    }

    /**
     * 从 ExecutionOutcome 适配为 LoopOutcome。
     */
    private static ExecutionObserver.LoopOutcome adaptOutcome(
            ExecutionOutcome outcome) {
        return new ExecutionObserver.LoopOutcome(
                ExecutionObserver.LoopOutcome.Kind.valueOf(outcome.kind().name()),
                outcome.detail());
    }

    /**
     * 触发 onLoopComplete 观测事件。
     *
     * @param tenantId   租户标识
     * @param runId      Run 标识
     * @param outcome    执行结局
     * @param totalSteps 已执行的总轮次
     */
    private void notifyLoopComplete(@NonNull String tenantId, @NonNull String runId,
                                    @NonNull ExecutionOutcome outcome, int totalSteps) {
        if (observerChain != null) {
            observerChain.onLoopComplete(tenantId, runId, adaptOutcome(outcome), totalSteps);
        }
    }
}