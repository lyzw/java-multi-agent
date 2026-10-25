package cn.studykid.multiagent.bootstrap.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.application.config.BudgetPolicy;
import cn.studykid.multiagent.application.config.EffectiveConfigResolver;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.RunConfigSnapshotFactory;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;import cn.studykid.multiagent.worker.execute.ToolInvocationGate;

/**
 * Worker 执行链装配（P4-D01 · 关闭 P3 退出条件 C-3）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复一处**断链**——
 * {@code RunDispatchConsumer.consume} 领到租约后<b>直接返回</b>，
 * {@code task} 拿到手即被丢弃，导致
 * {@code RunExecutionLoop} / {@code ToolInvocationGate} <b>从未被实例化</b>。
 * 后果是 D5–D11 交付的<b>全部</b>闸门（审批 / 预算 / 取消 / 注入防护 /
 * 编造出处拦截）在生产中<b>一次都不会执行</b>，
 * 而所有集成测试都直接 {@code new} 这些类，因此<b>每一项都测得通过</b>。
 *
 * <p><b>⚠️ 本类刻意独立于 {@link ApplicationConfiguration}</b>：
 * 执行链有 8 个依赖，堆在主装配类里会让其不可读。
 * 主类只留 {@code @Import}。
 *
 * <p><b>⚠️ 关于 Worker 主循环</b>（L-3）：本类只接通
 * 「MQ 消息 → 领取 → 执行」这一条链路；
 * 批量轮询 / 优雅停机 / 并发度控制属后续切片。
 */
@Configuration
public class WorkerExecutionConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecutionConfig.class);

    /** 租约时长：须与 {@code RunClaimPort.DEFAULT_LEASE} 同源。 */
    @Value("${agent.worker.lease-seconds:30}")
    long leaseSeconds;

    // ── C8 接线（P4-D11 D11-1）────────────────────────────────────
    //
    // ⚠️⚠️ 这三个值此前**完全没有生产读取点**：生产快照是硬编码的
    //    （maxCost=10.00 / maxSteps=20 / maxLlmCalls=8），
    //    而 yml 声明 max-cost-per-run=2.00 —— **声明比实际紧 5 倍**。
    //    运维读配置页看到的数字与真实成本敞口不一致。
    //
    // ⚠️ **不加默认值**：缺配置必须**拒绝**（fail-closed），
    //    退回默认值等于「悄悄用了一个没人审过的额度」。
    @Value("${agent.run.max-cost-per-run}")
    String maxCostPerRun;

    @Value("${agent.run.max-llm-calls}")
    String maxLlmCalls;

    @Value("${agent.run.max-steps}")
    String maxSteps;

    /**
     * 平台级**映射型**配置（风险映射 + 审批要求）。
     *
     * <p>⚠️ 必须经 {@link PlatformConfigMaps}（Binder）绑定：
     * {@code @Value} 无法绑定 YAML map（会抛 Could not resolve placeholder）。
     * 缺失 → 该 Bean 的创建失败 → <b>拒绝启动</b>（fail-closed）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    PlatformConfigMaps configMaps;

    /** Worker 身份（生产应为实例唯一标识）。 */
    @Value("${agent.worker.id:worker-local}")
    String workerId;

    /**
     * 模型网关（P4-D01 / L-4）。
     *
     * <p>⚠️ <b>当前为占位实现</b>：真实供应商接入依赖 <b>D-002</b>
     * （API 访问、配额、零保留确认）。此处用 {@code FakeModelGateway}
     * 以保证链路<b>结构完整、可端到端跑通</b>，
     * 但它<b>不是</b>真实模型调用。
     *
     * <p>⚠️ <b>不得</b>把本占位实现当作「模型已接通」的证据：
     * 真实接入须替换本 Bean 并复核 {@code ZeroRetentionGate} 按供应商判定。
     */
    @Bean
    public ModelGateway modelGateway() {
        log.warn("⚠️⚠️ 使用 FakeModelGateway 占位（D-002 未提供真实模型服务）——"
                + "链路可跑通，但**不是真实模型调用**");
        return new cn.studykid.multiagent.infrastructure.model.FakeModelGateway(
                cn.studykid.multiagent.infrastructure.model.FakeModelGateway.FaultMode.NONE,
                new cn.studykid.multiagent.ports.model.ModelResponse(
                        "这道题考查一元一次方程。请移项求解。",
                        java.util.Map.of(),
                        new cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage(
                                10, 20),
                        cn.studykid.multiagent.ports.model.ModelResponse.FinishReason.STOP));
    }

    /**
     * 审批策略：按风险等级判定「是否需要审批」。
     *
     * <p>⚠️ <b>fail-closed</b>：{@code READ} 不需审批，
     * 其余（含未知等级）一律需要审批。
     *
     * <p>⚠️ <b>P4-D08 R-3</b>：本 Bean 改为<b>读取 {@code agent.approval.required-by-risk}</b>，
     * 与 yml 声明对齐。原实现硬编码「非 READ 一律需审批」，
     * 与 yml 的 {@code WRITE_PROFILE: OPTIONAL} 矛盾——
     * 配置的 {@code OPTIONAL} 语义从未被任何代码读取，
     * 致使 {@code record_weakness} 工具即便修好注册也仍会被审批闸门拦死。
     *
     * <p>⚠️ <b>只能收紧不能放松</b>：每个等级的实际要求 =
     * {@code defaultApproval().maxWith(配置值)}——
     * 配置只能把要求<b>调严</b>，不能调松。
     * 例：某等级默认 REQUIRED，配置写 NOT_REQUIRED → 实际仍为 REQUIRED。
     */
    @Bean
    public RiskBasedApprovalPolicy riskBasedApprovalPolicy(
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.READ:NOT_REQUIRED}")
            String readRequirement,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.WRITE_PROFILE:OPTIONAL}")
            String writeProfileRequirement,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.WRITE_EXTERNAL:REQUIRED}")
            String writeExternalRequirement) {
        Map<ToolRiskLevel, ApprovalRequirement> byRisk = new java.util.EnumMap<>(
                ToolRiskLevel.class);
        byRisk.put(ToolRiskLevel.READ, parseRequirement(readRequirement, ToolRiskLevel.READ));
        byRisk.put(ToolRiskLevel.WRITE_PROFILE,
                parseRequirement(writeProfileRequirement, ToolRiskLevel.WRITE_PROFILE));
        byRisk.put(ToolRiskLevel.WRITE_EXTERNAL,
                parseRequirement(writeExternalRequirement, ToolRiskLevel.WRITE_EXTERNAL));
        log.info("装配审批策略: {}", byRisk);
        return new RiskBasedApprovalPolicy(byRisk);
    }

    /**
     * 解析审批要求（fail-closed）。
     *
     * <p>⚠️ 非法值不回落默认值，而是<b>按最严（REQUIRED）处理</b>——
     * 配置写错时放行有副作用工具 = Sev-0。
     */
    private static ApprovalRequirement parseRequirement(String raw, ToolRiskLevel level) {
        if (raw == null || raw.isBlank()) {
            return ApprovalRequirement.REQUIRED;
        }
        ApprovalRequirement parsed;
        try {
            parsed = ApprovalRequirement.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.error("审批要求配置非法，按最严处理: level={}, value={}", level, raw);
            return ApprovalRequirement.REQUIRED;
        }
        // ⚠️ 只能收紧不能放松：实际要求 = 默认要求 maxWith 配置值
        return level.defaultApproval().maxWith(parsed);
    }

    /**
     * 审批结论来源（P4-D01 / E-2）。
     *
     * <p>⚠️ <b>默认为空策略 = 恒 {@code FAIL_CLOSED}</b>：
     * 外部人类审批台未接入前，需要审批的工具一律被拒。
     * 这不是缺陷而是<b>设计内的安全状态</b>——
     * 一旦真实审批权威实现本接口并装配，有副作用工具即可被真实审批驱动。
     */
    @Bean
    public RiskBasedApprovalPolicy.PolicyEngineApprovalSource approvalSource() {
        return new RiskBasedApprovalPolicy.PolicyEngineApprovalSource();
    }

    /**
     * 工具调用闸门（D3–D9 的全部安全机制汇合点）。
     *
     * @param tools 本进程注册的工具
     */
    @Bean
    public ToolInvocationGate toolInvocationGate(
            List<ToolHandler> tools,
            AuthorizationService authorization,
            RiskBasedApprovalPolicy approvalPolicy,
            BudgetPort budget,
            RunToolLedgerPort ledger,
            ApprovalPort approvalPort,
            ToolInvocationGate.CancelGuard cancelGuard,
            RiskBasedApprovalPolicy.PolicyEngineApprovalSource approvalSource,
            BigDecimal reserveAmount,
            cn.studykid.multiagent.application.cap.CapExecutionFlow capExecutionFlow,
            cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlerProvider,
            cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation,
            java.time.Clock clock,
            cn.studykid.multiagent.application.orchestration.RunBudgetProbe runBudgetProbe) {
        log.info("装配工具闸门: 工具 {} 个，审批策略=按风险等级，审批来源=策略引擎，"
                        + "CAP 授权链路={}，多级委派={}，Run 级预算={}",
                tools.size(), capExecutionFlow.isEnabled(), delegation != null,
                runBudgetProbe != null);
        return new ToolInvocationGate(
                tools, authorization, approvalPolicy, budget, ledger, approvalPort,
                BudgetPolicy.requireReserveAmount(reserveAmount),
                cancelGuard, approvalSource, capExecutionFlow, mcpHandlerProvider, delegation,
                clock, runBudgetProbe);
    }

    /**
     * 子 Agent 模型配置装配（多级 Agent v1.1 · P2-MA-02）。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider
            childAgentModelConfigProvider(
            cn.studykid.multiagent.ports.persistence.AgentQueryPort agentQuery,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs) {
        return new cn.studykid.multiagent.worker.execute.WorkerChildAgentModelConfigProvider(
                agentQuery, closureResolver, toolDefs);
    }

    /**
     * 子 Agent 执行器（多级 Agent v1.1 · P2-MA-02）。
     *
     * <p>⚠️ <b>懒加载</b>：它依赖闸门，而闸门依赖委派执行器，委派执行器又依赖
     * 本端口 —— 直接构造会成环。{@code LazyChildAgentRunner} 把解析推迟到
     * 第一次真实委派，那时闸门已就绪。
     */
    @Bean
    public cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner workerChildAgentRunner(
            cn.studykid.multiagent.ports.model.ModelGateway modelGateway,
            ToolInvocationGate gate,
            cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider configs,
            cn.studykid.multiagent.ports.orchestration.CancellationProbe cancellation) {
        return new cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner(
                modelGateway, gate, configs,
                cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner.DEFAULT_MAX_CHILD_STEPS,
                cancellation);
    }

    /**
     * 取消探针（多级 Agent v1.1 · MA-T11）。
     *
     * <p>⚠️ <b>与闸门的取消守卫同源</b>：都接 {@code RunClaimPort.isCancelled} ——
     * 取消是 <b>Run 级</b>状态，父 Run 取消必须能传播到运行中的子 Agent；
     * 两处若读的不是同一个事实，就会出现「闸门以为没取消、子执行却停了」
     * 或反之的错位。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.CancellationProbe cancellationProbe(
            @NonNull RunClaimPort claimPort) {
        return claimPort::isCancelled;
    }

    /**
     * 子 Agent 执行端口（懒解析）。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.ChildAgentRunner childAgentRunner(
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner> delegate) {
        return new cn.studykid.multiagent.worker.execute.LazyChildAgentRunner(
                () -> delegate.getObject());
    }

    /**
     * 执行循环（FR-004）。
     */
    /**
     * Worker 执行循环（含事件写入，P4-D12 / G4）。
     *
     * <p>⚠️⚠️ <b>事件端口必须在这里真实注入</b>：
     * P4-D08 的 R-1 教训——「工具未注册为 Bean → 工具从不执行，
     * 而系统表面完全正常」；本片若只把端口留在执行循环里而**不注入**，
     * 事件就会**永远不写**（默认 {@code null} = 不写），
     * 进度页时间线依旧只有一条，而**没有任何报错**。
     * 故本片的连通性测试专门断言「事件端口**确实**被注入」。
     */
    @Bean
    public RunExecutionLoop runExecutionLoop(
            ModelGateway modelGateway,
            RunClaimPort claimPort,
            ToolInvocationGate gate,
            PromptGuard promptGuard,
            cn.studykid.multiagent.ports.persistence.RunEventPort eventPort,
            cn.studykid.multiagent.application.orchestration.InFlightDelegationAdopter adopter) {
        // ⚠️⚠️ **接管器必须在这里真实注入**（第九批 · MA-T12）：
        //    不注入时，Worker 接管后 RUNNING 的委派记录**永远无人能收敛**
        //    （新世代 token 不匹配、旧世代已被接管），
        //    真实原因（Worker 崩溃）会被掩盖成一次普通的子调用失败。
        return new RunExecutionLoop(modelGateway, claimPort, gate,
                Duration.ofSeconds(leaseSeconds), workerId, promptGuard, eventPort, adopter);
    }

    /**
     * MQ 消费者（P4-D01 断链修复点）。
     *
     * <p>⚠️ 这里把 {@code RunExecutionLoop} 适配为 {@code RunExecutor}：
     * 消费者只关心「执行结局 → MQ 处置」的映射。
     */
    @Bean
    public RunDispatchConsumer runDispatchConsumer(
            RunClaimPort claimPort,
            RunExecutionLoop loop,
            RunExecutionInputs inputs) {
        log.info("装配 Worker 消费者: workerId={}, 租约={}s（执行器已接入）",
                workerId, leaseSeconds);
        return new RunDispatchConsumer(claimPort, Duration.ofSeconds(leaseSeconds),
                (tenantId, task) -> {
                    RunExecutionLoop.ExecutionOutcome outcome = loop.execute(
                            tenantId, task, inputs.agentConfig(),
                            inputs.snapshotFor(task, tenantId), inputs.runInput(task));
                    return switch (outcome.kind()) {
                        case SUCCEEDED -> RunDispatchConsumer.Outcome.SUCCEEDED;
                        case FAILED -> RunDispatchConsumer.Outcome.FAILED;
                        case CANCELLED -> RunDispatchConsumer.Outcome.CANCELLED;
                        case RETRYABLE_FAILURE -> RunDispatchConsumer.Outcome.RETRYABLE_FAILURE;
                        // ⚠️⚠️ UNKNOWN 必须映射为「丢弃」——重投 = 重复副作用 = Sev-0
                        case UNKNOWN_RESULT -> RunDispatchConsumer.Outcome.UNKNOWN_RESULT;
                        case SKIPPED_STALE_GENERATION ->
                                RunDispatchConsumer.Outcome.STALE_GENERATION;
                    };
                });
    }

    /**
     * 执行输入供给（Agent 配置 + Run 快照）。
     *
     * <p>⚠️ <b>L-4 已知限制</b>：当前返回**占位**配置。
     * 真实实现须从 {@code run.input_payload} 与 {@code agent} 表读取
     * （快照不可变由 {@code forbid_snapshot_mutation} 触发器保证）。
     * 占位配置使链路<b>结构上完整</b>，但不代表真实业务参数已接通。
     */
    @Bean
    public RunExecutionInputs runExecutionInputs(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.ports.persistence.ConfigOverridePort configOverridePort) {
        // ⚠️ P4-D15：ConfigOverridePort 可选注入——缺失时本 Bean 与改动前完全等价，
        //    使配置表尚未迁移的环境也能启动（fail-open 于**装配**，
        //    但**收紧校验与审计**仍在写入路径 fail-closed）。
        EffectiveConfigResolver resolver =
                configOverridePort == null ? null : new EffectiveConfigResolver(configOverridePort);
        return new RunExecutionInputs(resolver, maxCostPerRun, maxLlmCalls, maxSteps,
                configMaps.toolRiskLevelMap(), configMaps.approvalRequiredByRisk());
    }

    /**
     * 执行输入供给。
     *
     * <p>⚠️ 当前为占位实现（P4-D01 §4 L-4）：
     * 真实实现须读 {@code run.input_payload} 与 Agent 配置表。
     */
    public static final class RunExecutionInputs {

        /**
         * P4-D15：租户配置覆盖解析器（可为 null——未装配时行为与改动前逐位相同）。
         *
         * <p>⚠️ <b>为什么允许为 null 而不直接依赖注入</b>：本类由
         * {@link #runExecutionInputs()} 无参构造；若强制依赖 {@code ConfigOverridePort}，
         * 则任何只关心 Worker 的上下文测试/装配都得先准备配置表。
         * 保持可空 + <b>缺失即回落 yml</b>，使本切片对既有行为零影响（D15-2）。
         */
        private final EffectiveConfigResolver effectiveResolver;

        // ⚠️ C8 接线（P4-D11）：快照必须来自 yml，不得硬编码。
        private final String maxCostPerRun;
        private final String maxLlmCalls;
        private final String maxSteps;
        private final Map<String, ToolRiskLevel> toolRiskLevelMap;
        private final Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk;

        RunExecutionInputs(EffectiveConfigResolver effectiveResolver,
                           String maxCostPerRun, String maxLlmCalls, String maxSteps,
                           Map<String, ToolRiskLevel> toolRiskLevelMap,
                           Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk) {
            this.effectiveResolver = effectiveResolver;
            this.maxCostPerRun = maxCostPerRun;
            this.maxLlmCalls = maxLlmCalls;
            this.maxSteps = maxSteps;
            this.toolRiskLevelMap = toolRiskLevelMap;
            this.approvalRequiredByRisk = approvalRequiredByRisk;
        }

        /** Agent 模型配置（占位）。 */
        public RunExecutionLoop.AgentModelConfig agentConfig() {
            return new RunExecutionLoop.AgentModelConfig(
                    "fake-model",
                    "你是初中数学辅导助手。请用初中生能懂的语言讲解，不要编造教材出处。",
                    0.7d, 2048, Duration.ofSeconds(30), java.util.Map.of());
        }

        /**
         * Run 输入（占位）。
         *
         * <p>⚠️ <b>L-4</b>：真实实现须读 {@code run.input_payload}
         *（Run 创建事务内落库，不可变）。
         */
        public java.util.Map<String, Object> runInput(
                RunClaimPort.LeasedTask task) {
            return java.util.Map.of("q", "解方程 x²-5x+6=0");
        }

        /**
         * Run 配置快照。
         *
         * <p>⚠️⚠️ <b>C8 接线（P4-D11 D11-1）</b>：此前此处返回<b>硬编码</b>快照
         * （成本上限 {@code 10.00} / 步数 {@code 20} / 调用 {@code 8}），
         * 而 yml 声明的 {@code agent.run.max-cost-per-run=2.00} <b>没有任何生产读取点</b>
         * —— 声明值比真实敞口<b>紧 5 倍</b>，运维读到的数字是假的。
         *
         * <p>⚠️ <b>fail-closed</b>：配置缺失/非法 → {@code ConfigResolutionException}
         * → <b>拒绝执行该 Run</b>，<b>不</b>退回硬编码默认值。
         * 退回默认值等于「悄悄用了一个没人审过的额度」——
         * 那正是本片要消除的分叉。
         *
         * <p>⚠️ <b>审批要求按「最严」解析</b>：本次 Run 的主工具未知
         * （{@code run.input_payload} 尚未接通，L-4），故按
         * {@code WRITE_EXTERNAL} 兜底 → 必然 {@code REQUIRED}——
         * 与改造前的硬编码行为一致，<b>不放松</b>任何门禁。
         */
        public RunConfigSnapshot snapshotFor(RunClaimPort.LeasedTask task) {
            return snapshotFor(task, null);
        }

        /**
         * P4-D15：叠加租户配置覆盖后生成快照。
         *
         * <p>⚠️ <b>为什么不把覆盖读进构造</b>：构造期注入的是 yml（启动期 fail-closed 守卫），
         * 而覆盖是<b>租户级、运行期可变</b>的。两者混在一起会把「启动期校验」
         * 退化成「运行期才发现配错」——原本 yml 配错 = 启动失败，是好的。
         *
         * <p>⚠️ <b>tenantId 为 null（无租户上下文）时直接用 yml</b>：
         * 保持与 P4-D15 之前逐位相同的行为，不让覆盖查询在无租户时抛错。
         *
         * @param tenantId 租户；为 null 时不叠加覆盖
         */
        public RunConfigSnapshot snapshotFor(RunClaimPort.LeasedTask task, String tenantId) {
            String cost = maxCostPerRun;
            String calls = maxLlmCalls;
            String steps = maxSteps;

            // ⚠️ P4-D15：DB 覆盖 ⊕ yml 默认。有覆盖才替换，无覆盖逐位沿用原值。
            if (effectiveResolver != null && tenantId != null) {
                var effective = effectiveResolver.resolve(tenantId, Map.of(
                        PlatformConfig.KEY_MAX_COST_PER_RUN, maxCostPerRun,
                        PlatformConfig.KEY_MAX_LLM_CALLS, maxLlmCalls,
                        PlatformConfig.KEY_MAX_STEPS, maxSteps));
                String c = effective.get(PlatformConfig.KEY_MAX_COST_PER_RUN);
                String l = effective.get(PlatformConfig.KEY_MAX_LLM_CALLS);
                String s = effective.get(PlatformConfig.KEY_MAX_STEPS);
                if (c != null) {
                    cost = c;
                }
                if (l != null) {
                    calls = l;
                }
                if (s != null) {
                    steps = s;
                }
            }

            return new RunConfigSnapshotFactory(java.time.Clock.systemUTC()).create(
                    task.runId(),
                    cost,
                    calls,
                    steps,
                    toolRiskLevelMap,
                    approvalRequiredByRisk,
                    // ⚠️ 主工具未知 → 未登记工具按 WRITE_EXTERNAL 兜底（fail-closed）
                    UNKNOWN_PRIMARY_TOOL);
        }

        /**
         * 「主工具未知」的占位工具名。
         *
         * <p>⚠️ 它<b>不在</b> yml 的 risk-level-map 中 → 按
         * {@code ToolRiskLevel.resolve} 规则落到 {@code WRITE_EXTERNAL}
         * → 审批要求 {@code REQUIRED}。这是<b>最严</b>方向，符合
         * 「判据只增不减」（RAG-D00 §3 原则 7 / P0-D03 红线 15）。
         */
        private static final String UNKNOWN_PRIMARY_TOOL = "__unknown_primary_tool__";
    }

    /**
     * 取消守卫 Bean（P3-D06 D-2）。
     *
     * <p>⚠️ 装配为 {@code RunClaimPort.isCancelled} —— D6 交付的取消检查
     * 在此才真正接入生产链路（此前闸门用的是默认 {@code NO_CANCEL} 桩）。
     */
    @Bean
    public ToolInvocationGate.CancelGuard cancelGuard(@NonNull RunClaimPort claimPort) {
        return claimPort::isCancelled;
    }
}
