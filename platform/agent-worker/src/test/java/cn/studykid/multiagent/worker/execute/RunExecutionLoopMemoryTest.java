package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.memory.MemoryConsolidator;
import cn.studykid.multiagent.application.memory.MemoryRecallService;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.memory.MemoryEntry;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.MemoryPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P1 记忆接线（{@code RunExecutionLoop} ↔ {@code MemoryRecallService} / {@code MemoryConsolidator}）。
 *
 * <p><b>⚠️ 本测试存在的唯一理由</b>：P4-D08 R-1 的教训是
 * 「机制正确、测试全绿、但生产里从未接线」。
 * {@code MemoryRecallServiceTest} 证明"召回逻辑写对了"，
 * {@code MemoryConsolidatorTest} 证明"整合逻辑写对了"——
 * 但两者都<b>不能</b>证明 {@code RunExecutionLoop} 真的调了它们。
 * 本类专门补这一段。
 *
 * <p>⚠️ <b>而且要断言"进入了模型请求"，不是"被调用过"</b>：
 * 召回到了却被拼在 systemPrompt 之外、或只在第一轮拼、
 * 或因为某个 {@code if} 分支写反了而从未进入请求 ——
 * 这些在"方法被调用"的断言下全部是绿的。
 */
@DisplayName("P1 记忆接线（执行循环层）")
class RunExecutionLoopMemoryTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = "11111111-2222-3333-4444-555555555555";
    private static final String STU = "stu_0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelCallObserverChain NOOP_OBSERVERS =
            new ModelCallObserverChain(List.of());

    private static ObservableModelGateway observe(ModelGateway gw) {
        return new ObservableModelGateway(gw, NOOP_OBSERVERS);
    }

    private static RunConfigSnapshot snapshot(int maxSteps) {
        return new RunConfigSnapshot(RUN_ID, new BigDecimal("2.00"),
                12, maxSteps, ApprovalRequirement.NOT_REQUIRED, NOW);
    }

    private static RunExecutionLoop.AgentModelConfig agentConfig() {
        return new RunExecutionLoop.AgentModelConfig(
                "test-model", "你是初中数学辅导助手", 0.7d, 1024,
                Duration.ofSeconds(5), Map.of());
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, NOW.plusSeconds(30), 1);
    }

    /** 记录每次模型请求的 systemPrompt（用于断言记忆真的进了请求）。 */
    private static final class RecordingGateway implements ModelGateway {
        final List<String> systemPrompts = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<ModelResponse> queued = new ArrayList<>();

        RecordingGateway(ModelResponse... responses) {
            queued.addAll(List.of(responses));
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            systemPrompts.add(request.systemPrompt());
            int i = calls.getAndIncrement();
            return i < queued.size() ? queued.get(i) : queued.get(queued.size() - 1);
        }
    }

    private static ModelResponse text(String content) {
        return new ModelResponse(content, Map.of(),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.STOP);
    }

    /** 记忆端口桩（非 final：个别用例需覆写以模拟失败）。 */
    private static class StubMemoryPort implements MemoryPort {
        List<MemoryEntry> entries = List.of();
        int consolidateCalls;
        String consolidatedStudent;
        RuntimeException recallFailure;

        @Override
        public List<MemoryEntry> recall(String tenantId, String studentId, int k, int maxTokens) {
            if (recallFailure != null) {
                throw recallFailure;
            }
            return entries;
        }

        @Override
        public int consolidate(String tenantId, String studentId, String runId) {
            consolidateCalls++;
            consolidatedStudent = studentId;
            return 1;
        }

        @Override
        public int forget(String tenantId, String studentId) {
            return 0;
        }

        @Override
        public int countMemories(String tenantId, String studentId) {
            return entries.size();
        }
    }

    private static final class StubQueryPort implements ResultQueryPort {
        String state = "SUCCEEDED";
        List<ResultQueryPort.Weakness> weaknesses =
                List.of(new ResultQueryPort.Weakness("一元二次方程", "概念不清", null));

        @Override
        public RunResult findResult(String tenantId, String runId) {
            return new RunResult(runId, state, NOW, null, "讲解",
                    weaknesses, List.of(),
                    new Reconciliation(false, List.of()));
        }
    }

    // ── 依赖桩（签名与本仓库既有替身对齐，避免"桩与端口漂移"） ──────

    private static final class FakeClaimPort implements RunClaimPort {
        boolean commitSucceeds = true;
        String committedState;

        @Override public List<LeasedTask> claimTasks(String t, String w, Duration d, int l) {
            return List.of();
        }
        @Override public Optional<LeasedTask> claimSpecificRun(String t, String r, String w,
                                                               Duration d) {
            return Optional.empty();
        }
        @Override public boolean renewLease(String t, String r, String w, long f, Duration d) {
            return true;
        }
        @Override public boolean releaseLease(String t, String r, String w, long f) {
            return true;
        }
        @Override public boolean commitResult(String t, String r, long f, String s, String p) {
            committedState = s;
            return commitSucceeds;
        }
        @Override public boolean commitResult(String t, String r, long f, String s, String p,
                                              String ec) {
            return commitResult(t, r, f, s, p);
        }
        @Override public boolean isCurrentGeneration(String t, String r, long f) {
            return true;
        }
        @Override public boolean requestCancel(String t, String r) {
            return true;
        }
        @Override public boolean isCancelled(String t, String r) {
            return false;
        }
    }

    /**
     * 内存预算替身。
     *
     * <p>⚠️ <b>必须真的记住预留记录</b>：闸门在预留成功后会
     * {@code findByOperation} 回查，查不到就把这次调用
     * <b>fail-closed 拒绝</b>（「预算预留已生效但查不到预留记录」）。
     * 一个"永远返回 RESERVED 但什么都不记"的桩会让所有工具调用被拒 ——
     * 症状是"工具从不执行"，与 P4-D08 R-1 一模一样。
     */
    private static final class AlwaysBudget implements BudgetPort {
        private final Map<String, Reservation> byOperation = new LinkedHashMap<>();

        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, BigDecimal amount) {
            String key = operationId == null ? "anon" : operationId;
            if (byOperation.containsKey(key)) {
                return ReserveOutcome.REPLAYED;
            }
            byOperation.put(key, new Reservation(java.util.UUID.randomUUID().toString(),
                    runId, operationId, amount, BigDecimal.ZERO, States.RESERVED, "CNY"));
            return ReserveOutcome.RESERVED;
        }
        @Override public boolean settle(String tenantId, String reservationId,
                BigDecimal actualAmount, String modelId) {
            return true;
        }
        @Override public boolean release(String tenantId, String reservationId, String reason) {
            return true;
        }
        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.ofNullable(byOperation.get(
                    operationId == null ? "anon" : operationId));
        }
        @Override public List<OrphanedReservation> findOrphaned(String tenantId) {
            return List.of();
        }
        @Override public Optional<AccountSnapshot> accountSnapshot(String tenantId,
                String currency) {
            return Optional.of(new AccountSnapshot("a", currency,
                    BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        @Override public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    }

    /**
     * 内存账本（与本仓库既有替身同形状）。
     *
     * <p>⚠️ 必须<b>真的实现状态机</b>：闸门按 {@code ToolOperationState} 分支处置
     * （UNKNOWN 不结算不释放），"永远成功"的桩会让这些分支从未被执行。
     */
    private static final class InMemoryLedger implements RunToolLedgerPort {
        final Map<String, cn.studykid.multiagent.core.run.ToolOperationState> states =
                new LinkedHashMap<>();
        final Map<String, String> byKey = new LinkedHashMap<>();

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            String existing = byKey.get(idempotencyKey);
            if (existing != null) {
                return new Registration(existing, states.get(existing), 1, 0L, false);
            }
            String opId = java.util.UUID.randomUUID().toString();
            byKey.put(idempotencyKey, opId);
            states.put(opId, cn.studykid.multiagent.core.run.ToolOperationState.IN_FLIGHT);
            return new Registration(opId,
                    cn.studykid.multiagent.core.run.ToolOperationState.IN_FLIGHT, 0, 0L, true);
        }

        @Override public void recordResult(String tenantId, String operationId,
                cn.studykid.multiagent.core.run.ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) {
            states.put(operationId, result);
        }

        @Override public void reconcile(String tenantId, String operationId,
                cn.studykid.multiagent.core.run.ToolOperationState reconciled, String source) {
            states.put(operationId, reconciled);
        }

        @Override public void manualDispose(String tenantId, String operationId,
                String disposedBy, String note) { }

        @Override public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return List.of();
        }

        @Override public Optional<Registration> findByIdempotencyKey(
                String tenantId, String idempotencyKey) {
            String opId = byKey.get(idempotencyKey);
            return opId == null ? Optional.empty()
                    : Optional.of(new Registration(opId, states.get(opId), 1, 0L, false));
        }
    }

    private static final AuthorizationService ALLOW_ALL = new AuthorizationService() {
        @Override public boolean isAuthorized(TenantContext c, String a, String r) {
            return true;
        }
        @Override public long currentAuthzVersion(String t, String p) {
            return 0L;
        }
        @Override public Set<String> rolesOf(TenantContext c) {
            return c.roles();
        }
    };

    private static ToolInvocationGate gate() {
        return new ToolInvocationGate(List.of(), ALLOW_ALL,
                h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                new AlwaysBudget(), new InMemoryLedger());
    }

    /** 完整生产形状的循环：含记忆两件套。 */
    private static RunExecutionLoop loopWithMemory(ModelGateway gw, RunClaimPort port,
                                                   MemoryPort memoryPort,
                                                   ResultQueryPort queryPort,
                                                   int contextTokenBudget) {
        PromptGuard guard = new PromptGuard();
        MemoryRecallService recall = new MemoryRecallService(
                memoryPort, guard, 5, 800, 0.40, CLOCK);
        MemoryConsolidator consolidator = new MemoryConsolidator(memoryPort, queryPort);
        return new RunExecutionLoop(observe(gw), port, gate(), Duration.ofSeconds(30), "w-1",
                guard, null, null, contextTokenBudget, null, null, null, null, null,
                null, null, null, recall, consolidator);
    }

    private static MemoryEntry strongEntry() {
        return new MemoryEntry(STU, "一元二次方程", "概念不清", 0.65, 3,
                NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(2)), "run-prev");
    }

    private static Map<String, Object> inputWithStudent() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("question", "解方程 x^2-5x+6=0");
        input.put("studentId", STU);
        return input;
    }

    @Nested
    @DisplayName("召回：记忆必须真的进入模型请求")
    class RecallIntoRequest {

        @Test
        @DisplayName("✅ 有记忆 → systemPrompt 含围栏记忆块")
        void memoryBlockReachesModelRequest() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.entries = List.of(strongEntry());
            RecordingGateway gw = new RecordingGateway(text("x=2 或 x=3"));

            loopWithMemory(gw, new FakeClaimPort(), mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5), inputWithStudent());

            assertThat(gw.systemPrompts).hasSize(1);
            String prompt = gw.systemPrompts.get(0);
            assertThat(prompt)
                    .as("⚠️ 记忆必须出现在**模型实际收到的** systemPrompt 里")
                    .contains(PromptGuard.MEMORY_OPEN)
                    .contains("一元二次方程")
                    .contains("概念不清")
                    .contains(PromptGuard.MEMORY_CLOSE)
                    .contains(PromptGuard.MEMORY_PREAMBLE);
        }

        @Test
        @DisplayName("✅ 多轮：记忆在每一轮的 systemPrompt 里都还在")
        void memoryBlockPersistsAcrossRounds() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.entries = List.of(strongEntry());
            // 第一轮发起工具调用，第二轮才给终答
            RecordingGateway gw = new RecordingGateway(
                    new ModelResponse("", Map.of("c1", new ModelResponse.ToolCallRequest(
                            "noop_tool", Map.of(), "c1")),
                            new ModelResponse.ModelUsage(10, 5),
                            ModelResponse.FinishReason.TOOL_CALLS),
                    text("x=2 或 x=3"));

            ToolHandler noop = new ToolHandler() {
                @Override public String toolName() { return "noop_tool"; }
                @Override public String inputSchema() { return "{}"; }
                @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
                @Override public void validate(Map<String, Object> arguments) { }
                @Override public ToolResult execute(ToolExecutionContext ctx,
                                                    Map<String, Object> a) {
                    return ToolResult.of("ok");
                }
            };
            PromptGuard guard = new PromptGuard();
            MemoryRecallService recall = new MemoryRecallService(
                    mem, guard, 5, 800, 0.40, CLOCK);
            RunExecutionLoop loop = new RunExecutionLoop(observe(gw), new FakeClaimPort(),
                    new ToolInvocationGate(List.of(noop), ALLOW_ALL,
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            new AlwaysBudget(), new InMemoryLedger()),
                    Duration.ofSeconds(30), "w-1", guard, null, null, 16000,
                    null, null, null, null, null, null, null, null,
                    recall, new MemoryConsolidator(mem, new StubQueryPort()));

            // ⚠️ 工具必须声明在 Agent 的 toolSchemas 里，否则模型调用会被闸门
            //    以「未声明的工具」拒绝，循环就走不到第二轮 ——
            //    那样测试会退化成"只有一轮"，而断言"每一轮都有记忆"就永远为真。
            RunExecutionLoop.AgentModelConfig withTools = new RunExecutionLoop.AgentModelConfig(
                    "test-model", "你是初中数学辅导助手", 0.7d, 1024,
                    Duration.ofSeconds(5), Map.of("noop_tool", Map.of(
                            "name", "noop_tool", "parameters", Map.of("type", "object"))));

            loop.execute(TENANT, task(), withTools, snapshot(5), inputWithStudent());

            assertThat(gw.systemPrompts).hasSizeGreaterThan(1);
            for (String prompt : gw.systemPrompts) {
                assertThat(prompt)
                        .as("⚠️ 记忆必须在每一轮都在（「只有第一轮有记忆」是典型回归）")
                        .contains(PromptGuard.MEMORY_OPEN);
            }
        }

        @Test
        @DisplayName("✅ 无 studentId → 不召回，systemPrompt 无记忆块（且不报错）")
        void noStudentIdMeansNoRecall() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.entries = List.of(strongEntry());
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            var outcome = loopWithMemory(gw, new FakeClaimPort(), mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5),
                            Map.of("question", "解方程"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(gw.systemPrompts.get(0))
                    .doesNotContain(PromptGuard.MEMORY_OPEN);
        }

        @Test
        @DisplayName("⚠️ 非法 studentId → 当作没有学生，不召回也不整合")
        void invalidStudentIdIsIgnored() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.entries = List.of(strongEntry());
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            var outcome = loopWithMemory(gw, new FakeClaimPort(), mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5),
                            Map.of("question", "解方程", "studentId", "stu_TOO_SHORT"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(gw.systemPrompts.get(0)).doesNotContain(PromptGuard.MEMORY_OPEN);
            assertThat(mem.consolidateCalls)
                    .as("非法 studentId 不得触发整合（否则会给一个不存在的键写记忆）")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️⚠️ 召回抛异常 → Run 仍然成功（记忆是增强，不是合规义务）")
        void recallFailureDoesNotFailRun() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.recallFailure = new IllegalStateException("库连不上");
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            var outcome = loopWithMemory(gw, new FakeClaimPort(), mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5), inputWithStudent());

            assertThat(outcome.kind())
                    .as("⚠️ 记忆不可用绝不能把 Run 打成 FAILED")
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(gw.systemPrompts.get(0)).doesNotContain(PromptGuard.MEMORY_OPEN);
        }
    }

    @Nested
    @DisplayName("预算：记忆占用必须从裁剪预算里扣掉")
    class TrimBudget {

        @Test
        @DisplayName("⚠️⚠️ 记忆占满预算 → 裁剪预算被夹紧到下限，Run 不得因预算为 0 而失败")
        void memoryNeverStarvesTrimBudget() {
            StubMemoryPort mem = new StubMemoryPort();
            // 造一条渲染后远超预算的记忆
            mem.entries = List.of(strongEntry());
            RecordingGateway gw = new RecordingGateway(text("x=2"));
            PromptGuard guard = new PromptGuard();
            MemoryRecallService recall = new MemoryRecallService(mem, guard, 5, 4000, 0.40, CLOCK);

            // ⚠️ 上下文预算刚好等于记忆上限：不夹紧的话 trimBudget <= 0 →
            //    ContextBudgetTrimmer.trim 抛 IllegalArgumentException →
            //    每一个 Run 都在第一步失败。
            RunExecutionLoop loop = new RunExecutionLoop(observe(gw), new FakeClaimPort(),
                    gate(), Duration.ofSeconds(30), "w-1", guard, null, null,
                    4000, null, null, null, null, null, null, null, null,
                    recall, new MemoryConsolidator(mem, new StubQueryPort()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    inputWithStudent());

            assertThat(outcome.kind())
                    .as("⚠️ 记忆把预算吃光时，必须夹紧而不是让 trim 抛异常")
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
        }
    }

    @Nested
    @DisplayName("整合：只在成功提交之后")
    class Consolidation {

        @Test
        @DisplayName("✅ SUCCEEDED → 整合被调用，且学生标识正确")
        void succeededRunConsolidates() {
            StubMemoryPort mem = new StubMemoryPort();
            FakeClaimPort port = new FakeClaimPort();
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            var outcome = loopWithMemory(gw, port, mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5), inputWithStudent());

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(mem.consolidateCalls).isEqualTo(1);
            assertThat(mem.consolidatedStudent).isEqualTo(STU);
        }

        @Test
        @DisplayName("⚠️⚠️ 提交失败（世代失效）→ 不整合（不沉淀一个没有成功结果的 Run）")
        void staleGenerationDoesNotConsolidate() {
            StubMemoryPort mem = new StubMemoryPort();
            FakeClaimPort port = new FakeClaimPort();
            port.commitSucceeds = false;
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            loopWithMemory(gw, port, mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5), inputWithStudent());

            assertThat(mem.consolidateCalls)
                    .as("⚠️ 提交失败 = 白干了，其证据不得进入长期记忆")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️ 整合抛异常 → 已提交的 SUCCEEDED 不被改写")
        void consolidationFailureDoesNotChangeCommittedState() {
            StubMemoryPort mem = new StubMemoryPort() {
                @Override
                public int consolidate(String tenantId, String studentId, String runId) {
                    throw new IllegalStateException("整合失败（模拟）");
                }
            };
            FakeClaimPort port = new FakeClaimPort();
            RecordingGateway gw = new RecordingGateway(text("x=2"));

            var outcome = loopWithMemory(gw, port, mem, new StubQueryPort(), 16000)
                    .execute(TENANT, task(), agentConfig(), snapshot(5), inputWithStudent());

            assertThat(outcome.kind())
                    .as("⚠️ Run 已提交成功，整合失败不得改状态（那是伪造结果）")
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(port.committedState).isEqualTo("SUCCEEDED");
        }
    }

    @Nested
    @DisplayName("零装配回滚位：不注入记忆时行为与改动前一致")
    class NullDeps {

        @Test
        @DisplayName("✅ 记忆两件套为 null → Run 正常完成，systemPrompt 无记忆块")
        void nullMemoryDepsKeepOldBehaviour() {
            RecordingGateway gw = new RecordingGateway(text("x=2"));
            RunExecutionLoop loop = new RunExecutionLoop(observe(gw), new FakeClaimPort(),
                    gate(), Duration.ofSeconds(30), "w-1");

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    inputWithStudent());

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(gw.systemPrompts.get(0)).doesNotContain(PromptGuard.MEMORY_OPEN);
        }
    }
}
