package cn.studykid.multiagent.worker.execute;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException.Reason;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyInput;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyResult;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;
import cn.studykid.multiagent.ports.orchestration.CancellationProbe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子 Agent 执行器测试（多级 Agent v1.1 · P2-MA-02 · MA-T09 / MA-T11 / MA-T13）。
 *
 * <p>覆盖子执行的三个控制面行为：
 * <ol>
 *   <li><b>取消</b>：父 Run 取消后子执行必须停下，且不得再调模型（MA-T11）；</li>
 *   <li><b>超时</b>：本级声明的 {@code timeoutMs} 必须被<b>实施</b>，
 *       而不只是参与一次判定（MA-T09）；</li>
 *   <li><b>剩余预算封顶</b>：单次模型调用的超时不得超过剩余预算。</li>
 * </ol>
 *
 * <p>⚠️ 时间判定用<b>注入的时钟源</b>，不用「睡一会儿」撞时间窗 ——
 * 那类测试要么慢，要么不稳定。
 */
class WorkerChildAgentRunnerTest {

    /**
     * 无观测者的观测链：测试不需要观测副作用，仅用于满足 WorkerChildAgentRunner
     * 构造器对 {@link ObservableModelGateway}（ModelGateway 的装饰器）的类型要求。
     */
    private static final ModelCallObserverChain NOOP_OBSERVERS =
            new ModelCallObserverChain(java.util.List.of());

    /** 把 ModelGateway 桩包成 ObservableModelGateway，满足构造器类型要求。 */
    private static ObservableModelGateway observe(ModelGateway gw) {
        return new ObservableModelGateway(gw, NOOP_OBSERVERS);
    }

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";

    // ── 替身 ──────────────────────────────────────────────────────

    /** 可编程的模型网关替身（支持<b>按次序</b>返回多个响应）。 */
    private static final class ScriptedGateway implements ModelGateway {
        final AtomicInteger calls = new AtomicInteger();
        ModelResponse response = new ModelResponse("done", Map.of(),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.STOP);
        /** 按次序返回的响应队列（为空时用 {@link #response}）。 */
        final java.util.Queue<ModelResponse> sequence = new java.util.ArrayDeque<>();
        /** 历次请求（用于断言回灌内容）。 */
        final java.util.List<ModelRequest> requests = new java.util.ArrayList<>();
        ModelRequest lastRequest;

        @Override
        public @NonNull ModelResponse complete(@NonNull ModelRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            requests.add(request);
            ModelResponse next = sequence.poll();
            return next == null ? response : next;
        }
    }

    /** 恒定返回固定配置的子 Agent 模型配置源。 */
    private static ChildAgentModelConfigProvider configs(Duration timeout) {
        return (tenantId, agentId, agentVersion) -> Optional.of(
                new ChildAgentModelConfigProvider.ChildModelConfig(
                        "model-1", "你是子 Agent", 0.2, 512, timeout, Map.of()));
    }

    /** 空闸门（子执行本类用例不触发工具调用）。 */
    private static ToolInvocationGate emptyGate() {
        return gateWith(List.of());
    }

    /** 带工具处理器的闸门（用于验证工具结果回灌）。 */
    private static ToolInvocationGate gateWith(
            @NonNull List<cn.studykid.multiagent.ports.tool.ToolHandler> handlers) {
        return new ToolInvocationGate(
                handlers,
                new cn.studykid.multiagent.ports.security.AuthorizationService() {
                    @Override
                    public boolean isAuthorized(
                            cn.studykid.multiagent.core.tenant.TenantContext c,
                            String a, String r) {
                        return true;
                    }

                    @Override
                    public long currentAuthzVersion(String t, String p) {
                        return 1L;
                    }

                    @Override
                    public @NonNull Set<String> rolesOf(
                            cn.studykid.multiagent.core.tenant.TenantContext c) {
                        return c.roles();
                    }
                },
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                new cn.studykid.multiagent.ports.persistence.BudgetPort() {
                    @Override
                    public ReserveOutcome reserve(String t, String r, String o, BigDecimal a) {
                        return ReserveOutcome.RESERVED;
                    }

                    @Override
                    public boolean settle(String t, String r, BigDecimal a, String m) {
                        return true;
                    }

                    @Override
                    public boolean release(String t, String r, String reason) {
                        return true;
                    }

                    @Override
                    public Optional<Reservation> findByOperation(String t, String o) {
                        // ⚠️ 预留成功后**必须能查到预留记录**：闸门靠它拿 reservationId
                        //    去结算；查不到即 fail-closed 拒绝执行（不得静默丢弃该次调用）
                        return Optional.of(new Reservation("res-1", RUN, o,
                                BigDecimal.ONE, BigDecimal.ZERO, "RESERVED", "CNY"));
                    }

                    @Override
                    public Optional<AccountSnapshot> accountSnapshot(String t, String c) {
                        return Optional.empty();
                    }

                    @Override
                    public List<OrphanedReservation> findOrphaned(String t) {
                        return List.of();
                    }

                    @Override
                    public boolean isConsistent(String t, String c) {
                        return true;
                    }
                },
                new cn.studykid.multiagent.ports.persistence.RunToolLedgerPort() {
                    @Override
                    public Registration register(String t, String r, String toolName,
                                                 String riskLevel, String idempotencyKey,
                                                 String requestDigest,
                                                 Map<String, Object> arguments,
                                                 long fencingToken) {
                        return new Registration("op-1",
                                cn.studykid.multiagent.core.run.ToolOperationState.IN_FLIGHT,
                                0, 0L, true);
                    }

                    @Override
                    public void recordResult(String t, String o,
                                             cn.studykid.multiagent.core.run.ToolOperationState
                                                     result,
                                             long fencingToken, String errorClass,
                                             String errorMessage) {
                        // 不涉及
                    }

                    @Override
                    public void reconcile(String t, String o,
                                          cn.studykid.multiagent.core.run.ToolOperationState
                                                  reconciled,
                                          String source) {
                        // 不涉及
                    }

                    @Override
                    public void manualDispose(String t, String o, String by, String note) {
                        // 不涉及
                    }

                    @Override
                    public List<UnknownOperation> findUnknownOperations(String t) {
                        return List.of();
                    }

                    @Override
                    public Optional<Registration> findByIdempotencyKey(String t, String key) {
                        return Optional.empty();
                    }
                },
                new cn.studykid.multiagent.ports.persistence.ApprovalPort() {
                    @Override
                    public ApprovalOutcome consume(String t, String o, String d, String target,
                                                   String r) {
                        return ApprovalOutcome.NO_APPROVAL;
                    }

                    @Override
                    public String grant(String t, String o, String d, String target,
                                        cn.studykid.multiagent.core.config.ApprovalRequirement
                                                requirement,
                                        String scope, Instant expiresAt, String approverId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void revoke(String t, String o, String approverId, String reason) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<ApprovalView> findActive(String t, String o) {
                        return Optional.empty();
                    }
                },
                BigDecimal.ONE, (t, r) -> false,
                (t, handler) -> cn.studykid.multiagent.application.approval
                        .RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED);
    }

    private static ChildAgentRunner.ChildAgentRequest request(long timeoutMs) {
        return new ChildAgentRunner.ChildAgentRequest(
                TENANT, RUN, "inv-1", "trace-1", "parent-agent", 1,
                "child-agent", 1, Map.<String, Object>of("question", "q"), 2,
                new ExecutionScope("child-agent", 1, "cap_fp_v1:child"), timeoutMs, 1L, 1);
    }

    private static ChildAgentRunner.ChildAgentRequest anchoredRequest(String marker) {
        return new ChildAgentRunner.ChildAgentRequest(
                TENANT, RUN, "inv-1", "trace-1", "parent-agent", 1,
                "child-agent", 1, Map.<String, Object>of("question", marker), 2,
                new ExecutionScope("child-agent", 1, "cap_fp_v1:child"), 30_000L, 1L, 1);
    }

    // ── M7-b 统一请求装配替身 ──────────────────────────────────────

    private static final class FakeAssemblyPort implements RequestAssemblyPort {
        final List<RequestAssemblyInput> inputs = new java.util.ArrayList<>();
        RequestAssemblyResult forced;
        RuntimeException failure;

        @Override
        public RequestAssemblyResult assemble(RequestAssemblyInput input) {
            inputs.add(input);
            if (failure != null) {
                throw failure;
            }
            return forced == null
                    ? RequestAssemblyResult.passthrough(input.legacyRequest())
                    : forced;
        }
    }

    private static WorkerChildAgentRunner runner(ModelGateway gateway,
                                                 RequestAssemblyPort port) {
        return new WorkerChildAgentRunner(observe(gateway), emptyGate(),
                configs(Duration.ofSeconds(30)), 4, null, System::nanoTime,
                java.time.Clock.systemUTC(), 200, port);
    }

    private static WorkerChildAgentRunner runner(
            ModelGateway gateway, RequestAssemblyPort port,
            List<cn.studykid.multiagent.ports.tool.ToolHandler> tools) {
        return new WorkerChildAgentRunner(observe(gateway), gateWith(tools),
                configs(Duration.ofSeconds(30)), 4, null, System::nanoTime,
                java.time.Clock.systemUTC(), 200, port);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Run 已取消 → 子执行停下并返回 CANCELLED（不得再调模型 · MA-T11）")
    void cancelledRunStopsChildExecution() {
        ScriptedGateway gateway = new ScriptedGateway();
        CancellationProbe cancelled = (tenantId, runId) -> true;

        var result = new WorkerChildAgentRunner(observe(gateway), emptyGate(),
                configs(Duration.ofSeconds(30)), 4, cancelled, System::nanoTime)
                .run(request(30_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.CANCELLED);
        assertThat(result.reasonCode()).isEqualTo("RUN_CANCELLED");
        assertThat(gateway.calls.get()).as("取消后不得再发起模型调用").isZero();
    }

    @Test
    @DisplayName("取消探针读不到状态 → 按未取消继续（不得因一次抖动把 Run 停掉）")
    void cancellationProbeFailureDoesNotStopExecution() {
        ScriptedGateway gateway = new ScriptedGateway();
        CancellationProbe broken = (tenantId, runId) -> {
            throw new IllegalStateException("模拟取消状态读取失败");
        };

        var result = new WorkerChildAgentRunner(observe(gateway), emptyGate(),
                configs(Duration.ofSeconds(30)), 4, broken, System::nanoTime)
                .run(request(30_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(gateway.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("时间预算耗尽 → 返回 TIMED_OUT 且不发起模型调用（MA-T09）")
    void exhaustedBudgetTimesOut() {
        ScriptedGateway gateway = new ScriptedGateway();
        // ⚠️ 时钟直接跳到截止时间之后：超时是**确定**事实，不靠 sleep 去撞
        AtomicLong clock = new AtomicLong(0L);
        LongSupplier jumpingClock = () -> {
            clock.set(clock.get() + 60_000_000_000L);
            return clock.get();
        };

        var result = new WorkerChildAgentRunner(observe(gateway), emptyGate(),
                configs(Duration.ofSeconds(30)), 4, null, jumpingClock)
                .run(request(1_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.TIMED_OUT);
        assertThat(result.reasonCode()).isEqualTo("DELEGATION_TIMEOUT");
        assertThat(gateway.calls.get()).as("预算耗尽后不得再发起模型调用").isZero();
    }

    @Test
    @DisplayName("单次模型调用的超时不得超过剩余预算（预算不能被一次调用吃光）")
    void modelCallTimeoutIsCappedByRemainingBudget() {
        ScriptedGateway gateway = new ScriptedGateway();
        AtomicLong clock = new AtomicLong(0L);
        // ⚠️ 每读一次时钟推进 100ms：剩余预算 = 1s - 0.1s = 0.9s
        LongSupplier tickingClock = () -> clock.addAndGet(100_000_000L);

        new WorkerChildAgentRunner(observe(gateway), emptyGate(),
                configs(Duration.ofSeconds(60)), 4, null, tickingClock)
                .run(request(1_000L));

        assertThat(gateway.lastRequest).isNotNull();
        assertThat(gateway.lastRequest.timeout())
                .as("模型调用超时必须被剩余预算封顶")
                .isLessThanOrEqualTo(Duration.ofMillis(900));
        assertThat(gateway.lastRequest.timeout())
                .as("封顶后也不得为 0（否则模型连一次都发不出去）")
                .isPositive();
    }

    @Test
    @DisplayName("子 Agent 模型配置取不到 → 确定失败（不是 UNKNOWN）")
    void missingModelConfigIsDeterministicFailure() {
        ScriptedGateway gateway = new ScriptedGateway();
        ChildAgentModelConfigProvider none = (t, a, v) -> Optional.empty();

        var result = new WorkerChildAgentRunner(observe(gateway), emptyGate(), none, 4, null,
                System::nanoTime).run(request(30_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("RESOURCE_UNKNOWN");
        assertThat(gateway.calls.get()).isZero();
    }

    @Test
    @DisplayName("工具结果回灌进下一轮上下文（模型必须看到自己刚调了什么）")
    void toolResultIsFedBackIntoNextRound() {
        ScriptedGateway gateway = new ScriptedGateway();
        // 第 1 轮：模型请求调用工具；第 2 轮：给出最终答案
        gateway.sequence.add(new ModelResponse("", Map.of("call-1",
                new ModelResponse.ToolCallRequest("record_weakness",
                        Map.of("studentId", "stu_1"), "call-1")),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.TOOL_CALLS));
        gateway.sequence.add(new ModelResponse("已记录", Map.of(),
                new ModelResponse.ModelUsage(20, 5), ModelResponse.FinishReason.STOP));

        var result = new WorkerChildAgentRunner(observe(gateway), gateWith(List.of(new StubTool())),
                configs(Duration.ofSeconds(30)), 4, null, System::nanoTime)
                .run(request(30_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(gateway.calls.get()).as("应当有第二轮（看到工具结果后继续推理）").isEqualTo(2);

        var secondRound = gateway.requests.get(1);
        var messages = secondRound.effectiveMessages();
        assertThat(messages).as("工具结果必须出现在下一轮上下文里").hasSizeGreaterThan(1);

        var toolMessages = messages.stream()
                .filter(m -> m.role() == ModelMessage.Role.TOOL)
                .toList();
        assertThat(toolMessages).as("必须有一条工具结果消息").hasSize(1);
        assertThat(toolMessages.get(0).toolName()).isEqualTo("record_weakness");
        assertThat(toolMessages.get(0).content())
                .as("工具输出必须被回灌")
                .contains("薄弱点：二次函数")
                .as("工具输出是不可信数据，必须显式标记")
                .contains("不可信数据");
    }

    @Test
    @DisplayName("工具输出超长 → 回灌时被截断且留痕（防止顶爆上下文）")
    void oversizedToolOutputIsTruncated() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.sequence.add(new ModelResponse("", Map.of("call-1",
                new ModelResponse.ToolCallRequest("record_weakness",
                        Map.of("studentId", "stu_1"), "call-1")),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.TOOL_CALLS));
        gateway.sequence.add(new ModelResponse("已记录", Map.of(),
                new ModelResponse.ModelUsage(20, 5), ModelResponse.FinishReason.STOP));

        var result = new WorkerChildAgentRunner(observe(gateway), gateWith(List.of(new BigOutputTool())),
                configs(Duration.ofSeconds(30)), 4, null, System::nanoTime)
                .run(request(30_000L));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        var toolMessage = gateway.requests.get(1).effectiveMessages().stream()
                .filter(m -> m.role() == ModelMessage.Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertThat(toolMessage.content())
                .as("截断必须留痕，否则模型会以为工具只返回了这些")
                .contains("输出已截断")
                .contains("原始长度");
    }

    @Test
    @DisplayName("✅ 子路径同样按 token 预算裁剪（与根路径共用同一份实现与口径）")
    void childContextIsAlsoTrimmedByTokenBudget() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.sequence.add(new ModelResponse("", Map.of("call-1",
                new ModelResponse.ToolCallRequest("record_weakness",
                        Map.of("studentId", "stu_1"), "call-1")),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.TOOL_CALLS));
        gateway.sequence.add(new ModelResponse("已记录", Map.of(),
                new ModelResponse.ModelUsage(20, 5), ModelResponse.FinishReason.STOP));

        // ⚠️ 预算极小：子路径的上下文必然超预算。
        //    若子路径没接裁剪（父子两套语义），这里的断言会红。
        new WorkerChildAgentRunner(observe(gateway), gateWith(List.of(new BigOutputTool())),
                configs(Duration.ofSeconds(30)), 4, null, System::nanoTime,
                java.time.Clock.systemUTC(), 200)
                .run(request(30_000L));

        assertThat(gateway.requests).hasSizeGreaterThanOrEqualTo(2);
        ModelRequest second = gateway.requests.get(1);
        assertThat(ContextBudgetTrimmer.estimateTokens(second.effectiveMessages()))
                .as("⛔ 子路径的上下文仍然超预算：父子两套语义（根裁了子不裁）")
                .isLessThanOrEqualTo(200);
        assertThat(second.systemPrompt())
                .as("⛔ 子路径没有裁剪留痕：模型会以为前面那些工具**根本没调用过**，"
                        + "从而重复调用 → 重复副作用")
                .contains("裁剪");
    }

    /** 返回固定文本的工具替身。 */
    private static class StubTool
            implements cn.studykid.multiagent.ports.tool.ToolHandler {
        @Override
        public String toolName() {
            return "record_weakness";
        }

        @Override
        public String inputSchema() {
            return "{}";
        }

        @Override
        public cn.studykid.multiagent.core.config.ToolRiskLevel riskLevel() {
            return cn.studykid.multiagent.core.config.ToolRiskLevel.READ;
        }

        @Override
        public void validate(Map<String, Object> arguments) {
            // 不涉及
        }

        @Override
        public ToolResult execute(ToolExecutionContext c, Map<String, Object> arguments) {
            return ToolResult.of("学生 stu_1 薄弱点：二次函数");
        }
    }

    /** 返回超长文本的工具替身（验证截断）。 */
    private static final class BigOutputTool extends StubTool {
        @Override
        public ToolResult execute(ToolExecutionContext c, Map<String, Object> arguments) {
            return ToolResult.of("x".repeat(20_000));
        }
    }

    // ── M7-b 子 Agent 统一请求装配接线 ────────────────────────────

    @Test
    @DisplayName("✅ L4 关闭态：发的是 legacy 基线同一引用；首轮锚只发一遍、不继承父 checkpoint")
    void childClosedStateIsLegacyByIdentityAndHasNoParentCheckpoint() {
        ScriptedGateway gateway = new ScriptedGateway();
        FakeAssemblyPort assembly = new FakeAssemblyPort();

        var result = runner(gateway, assembly).run(anchoredRequest("child-anchor-marker"));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(gateway.requests).hasSize(1);
        assertThat(assembly.inputs).hasSize(1);
        assertThat(gateway.lastRequest)
                .as("⛔ L4 关闭态没有返回 legacy 同一引用")
                .isSameAs(assembly.inputs.get(0).legacyRequest());

        RequestAssemblyInput input = assembly.inputs.get(0);
        assertThat(input.tenantId()).isEqualTo(TENANT);
        assertThat(input.scopeKey()).isEqualTo("run:" + RUN);
        assertThat(input.agentId()).isEqualTo("child-agent");
        assertThat(input.agentVersion()).isEqualTo(1);
        assertThat(input.modelId()).isEqualTo("model-1");
        assertThat(input.stableSystemPrompt()).isEqualTo("你是子 Agent");
        assertThat(input.firstUserTurn()).contains("child-anchor-marker");
        assertThat(input.currentInput()).as("委派输入已是首轮锚，不得再发一遍").isEmpty();
        assertThat(input.historyMessages()).isEmpty();
        assertThat(input.memoryBlock()).isEmpty();
        assertThat(input.ragBlocks()).isEmpty();
        assertThat(input.maxOutputTokens()).isEqualTo(512);
        assertThat(input.fencingToken()).isEqualTo(1L);
        assertThat(input.upToSeq()).as("子 Agent 绝不继承父 checkpoint").isEqualTo(-1L);
        assertThat(input.carryOverStateJson()).isNull();
        assertThat(input.priorCompactions()).isZero();
    }

    @Test
    @DisplayName("✅ L4 开启态：唯一请求来自端口；每轮都不继承 checkpoint，首轮锚不重复")
    void childAssembledRequestIsOnlyRequestAndNeverInheritsCheckpoint() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.sequence.add(new ModelResponse("", Map.of("call-1",
                        new ModelResponse.ToolCallRequest("record_weakness",
                                Map.of(), "call-1")),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.TOOL_CALLS));
        gateway.sequence.add(new ModelResponse("已记录", Map.of(),
                new ModelResponse.ModelUsage(20, 5), ModelResponse.FinishReason.STOP));
        ModelRequest assembled = new ModelRequest("model-1", "子 Agent 统一装配", "",
                0.2d, 512, Duration.ofSeconds(30), Map.of(),
                List.of(ModelMessage.user("子 Agent 装配消息")));
        FakeAssemblyPort assembly = new FakeAssemblyPort();
        assembly.forced = new RequestAssemblyResult(assembled, true, 1, false, 100, 60, 1, null);

        var result = runner(gateway, assembly, List.of(new StubTool()))
                .run(anchoredRequest("child-anchor-marker"));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests)
                .as("⛔ 子 Agent 又二次拼了请求")
                .allSatisfy(r -> assertThat(r).isSameAs(assembled));
        assertThat(assembly.inputs).hasSize(2);
        assertThat(assembly.inputs).allSatisfy(in -> {
            assertThat(in.upToSeq()).as("⛔ 子路径继承或累计了 checkpoint 游标")
                    .isEqualTo(-1L);
            assertThat(in.carryOverStateJson()).isNull();
            assertThat(in.priorCompactions()).isZero();
            assertThat(in.firstUserTurn()).contains("child-anchor-marker");
            assertThat(in.currentInput()).isEmpty();
        });

        RequestAssemblyInput second = assembly.inputs.get(1);
        assertThat(second.historyMessages())
                .as("⛔ 工具结果回灌后没有进入子路径装配输入").isNotEmpty();
        assertThat(second.historyMessages()).allSatisfy(
                m -> assertThat(m.content()).as("⛔ 首轮锚在子路径历史里重复出现 [D27]")
                        .doesNotContain("child-anchor-marker"));
        assertThat(second.historyMessages()).anySatisfy(
                m -> assertThat(m.role()).isEqualTo(ModelMessage.Role.TOOL));
    }

    @Test
    @DisplayName("⛔ L4 装配被拒 → FAILED + 稳定原因码，绝不静默回退 legacy 调模型")
    void childAssemblyRejectionFailsClosed() {
        ScriptedGateway gateway = new ScriptedGateway();
        FakeAssemblyPort assembly = new FakeAssemblyPort();
        assembly.failure = new RequestAssemblyException(
                Reason.CONTEXT_POLICY_INVALID, "模拟策略非法");

        var result = runner(gateway, assembly).run(anchoredRequest("child-anchor-marker"));

        assertThat(result.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("CONTEXT_POLICY_INVALID");
        assertThat(gateway.calls.get()).as("⛔ 被拒后仍然发了模型请求（静默回退）").isZero();
    }
}
