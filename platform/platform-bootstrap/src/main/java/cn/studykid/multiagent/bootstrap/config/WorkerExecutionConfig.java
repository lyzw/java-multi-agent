package cn.studykid.multiagent.bootstrap.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.application.config.BudgetPolicy;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
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
     */
    @Bean
    public RiskBasedApprovalPolicy riskBasedApprovalPolicy() {
        return new RiskBasedApprovalPolicy();
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
            BigDecimal reserveAmount) {
        log.info("装配工具闸门: 工具 {} 个，审批策略=按风险等级，审批来源=策略引擎",
                tools.size());
        return new ToolInvocationGate(
                tools, authorization, approvalPolicy, budget, ledger, approvalPort,
                BudgetPolicy.requireReserveAmount(reserveAmount),
                cancelGuard, approvalSource);
    }

    /**
     * 执行循环（FR-004）。
     */
    @Bean
    public RunExecutionLoop runExecutionLoop(
            ModelGateway modelGateway,
            RunClaimPort claimPort,
            ToolInvocationGate gate,
            PromptGuard promptGuard) {
        return new RunExecutionLoop(modelGateway, claimPort, gate,
                Duration.ofSeconds(leaseSeconds), workerId, promptGuard);
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
                            inputs.snapshotFor(task), inputs.runInput(task));
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
    public RunExecutionInputs runExecutionInputs() {
        return new RunExecutionInputs();
    }

    /**
     * 执行输入供给。
     *
     * <p>⚠️ 当前为占位实现（P4-D01 §4 L-4）：
     * 真实实现须读 {@code run.input_payload} 与 Agent 配置表。
     */
    public static final class RunExecutionInputs {

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

        /** Run 配置快照（占位）。 */
        public RunConfigSnapshot snapshotFor(RunClaimPort.LeasedTask task) {
            return new RunConfigSnapshot(
                    task.runId(), new BigDecimal("10.00"), 20, 8,
                    cn.studykid.multiagent.core.config.ApprovalRequirement.REQUIRED,
                    java.time.Instant.now());
        }
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
