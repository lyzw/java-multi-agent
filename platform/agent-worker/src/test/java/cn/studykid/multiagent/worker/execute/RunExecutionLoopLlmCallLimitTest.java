package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ExecutionObserverChain;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P0-3a 回归测试：<b>模型调用次数上限必须真的被执行</b>。
 *
 * <p><b>缺陷背景</b>：{@code RunConfigSnapshot.maxLlmCalls} 与
 * {@code allowsLlmCall()} 一直存在、也进了配置快照，但生产代码<b>零调用</b> ——
 * 「单 Run 最多 12 次模型调用」只是一句写在配置里的承诺。
 * 真正生效的只有 {@code maxSteps}（轮次上限），
 * 于是模型调用数可以是承诺上限的近 2 倍（maxSteps=20 vs maxLlmCalls=12）。
 *
 * <p><b>为什么这个用例不能只断言「Run 失败了」</b>：
 * 工具被拒（TOOL_REJECTED）、超时（TIMEOUT）、模型报错（MODEL_*）
 * 都会让 Run 失败 —— 只断言失败态，等于这个测试在缺陷修复前
 * 也可能是绿的。真正要锁死的是
 * <b>「模型被调用的次数恰好等于上限」</b>与<b>「结构化错误码是 MAX_LLM_CALLS」</b>。
 */
class RunExecutionLoopLlmCallLimitTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();

    private static final ModelCallObserverChain NOOP_OBSERVERS =
            new ModelCallObserverChain(java.util.List.of());

    // ── 替身 ────────────────────────────────────────────────────────

    /** 永远返回工具调用的模型 —— 迫使执行循环一直转下去。 */
    private static ModelResponse toolCallResponse(String toolName) {
        return new ModelResponse("", Map.of(toolName,
                new ModelResponse.ToolCallRequest(toolName, Map.of(), "c1")),
                new ModelResponse.ModelUsage(10, 5),
                ModelResponse.FinishReason.TOOL_CALLS);
    }

    /** 永远成功的工具：让循环「工具调用成功 → 继续下一轮」。 */
    private static final class OkTool implements ToolHandler {
        @Override public String toolName() { return "ok_tool"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext context,
                                            Map<String, Object> arguments) {
            return ToolResult.of("ok");
        }
    }

    /** 记录型账本：每次登记都新建操作行（不复用既有行）。 */
    private static final class FreshLedger implements RunToolLedgerPort {
        @Override public Registration register(String tenantId, String runId, String toolName,
                String riskLevel, String idempotencyKey, String requestDigest,
                Map<String, Object> arguments, long fencingToken) {
            return new Registration(UUID.randomUUID().toString(), ToolOperationState.IN_FLIGHT,
                    1, fencingToken, true);
        }
        @Override public void recordResult(String tenantId, String operationId,
                ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) { }
        @Override public void reconcile(String tenantId, String operationId,
                ToolOperationState reconciled, String source) { }
        @Override public void manualDispose(String tenantId, String operationId,
                String disposedBy, String note) { }
        @Override public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return List.of();
        }
        @Override public Optional<Registration> findByIdempotencyKey(String tenantId,
                String idempotencyKey) {
            return Optional.empty();
        }
    }

    /** 额度充足的预算替身。 */
    private static final class GrantBudget implements BudgetPort {
        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, java.math.BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }
        @Override public boolean settle(String tenantId, String reservationId,
                java.math.BigDecimal actualAmount, String modelId) {
            return true;
        }
        @Override public boolean release(String tenantId, String reservationId, String reason) {
            return true;
        }
        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.of(new Reservation(UUID.randomUUID().toString(), RUN_ID, operationId,
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, States.RESERVED, "CNY"));
        }
        @Override public List<OrphanedReservation> findOrphaned(String tenantId) {
            return List.of();
        }
        @Override public Optional<AccountSnapshot> accountSnapshot(String tenantId,
                String currency) {
            return Optional.of(new AccountSnapshot("a", currency,
                    java.math.BigDecimal.valueOf(100), java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO));
        }
        @Override public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    }

    private static AuthorizationService allowAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                return true;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    private static final class RecordingClaimPort implements RunClaimPort {
        String committedState;
        String committedErrorCode;
        int commitCount;

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
            commitCount++;
            committedState = s;
            return true;
        }
        @Override public boolean commitResult(String t, String r, long f, String s, String p,
                String ec) {
            committedErrorCode = ec;
            return commitResult(t, r, f, s, p);
        }
        @Override public boolean isCurrentGeneration(String t, String r, long f) {
            return true;
        }
        @Override public boolean requestCancel(String t, String r) { return true; }
        @Override public boolean isCancelled(String t, String r) { return false; }
    }

    private static RunExecutionLoop loop(ModelGateway gw, RunClaimPort claim) {
        ToolInvocationGate gate = new ToolInvocationGate(List.of(new OkTool()), allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                new GrantBudget(), new FreshLedger());
        return new RunExecutionLoop(new ObservableModelGateway(gw, NOOP_OBSERVERS),
                claim, gate, Duration.ofSeconds(30), "w-1",
                new cn.studykid.multiagent.application.prompt.PromptGuard(), null, null,
                ContextBudgetTrimmer.DEFAULT_CONTEXT_TOKEN_BUDGET, null, null, null,
                new ExecutionObserverChain(java.util.List.of()), null);
    }

    private static RunConfigSnapshot snapshot(int maxSteps, int maxLlmCalls) {
        return new RunConfigSnapshot(RUN_ID, new java.math.BigDecimal("2.00"),
                maxLlmCalls, maxSteps, ApprovalRequirement.NOT_REQUIRED, Instant.now());
    }

    private static RunExecutionLoop.AgentModelConfig agentConfig() {
        return new RunExecutionLoop.AgentModelConfig(
                "test-model", "你是初中数学辅导助手", 0.7d, 1024,
                Duration.ofSeconds(5), Map.of());
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 模型调用次数达上限即停：恰好调用 N 次，Run 收敛为 FAILED/MAX_LLM_CALLS")
    void llmCallLimitIsEnforced() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gw = request -> {
            modelCalls.incrementAndGet();
            return toolCallResponse("ok_tool");
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        var outcome = loop(gw, claim).execute(TENANT, task(), agentConfig(),
                snapshot(20, 2), Map.of("q", "一元二次方程"));

        assertThat(outcome.kind())
                .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.FAILED);
        assertThat(modelCalls.get())
                .as("模型调用次数必须恰好等于上限（maxLlmCalls=2）——"
                        + "多了说明闸门没生效，少了说明闸门提前收紧")
                .isEqualTo(2);
        assertThat(claim.committedState).isEqualTo("FAILED");
        assertThat(claim.committedErrorCode)
                .as("必须写结构化错误码 MAX_LLM_CALLS，供终止原因归因")
                .isEqualTo("MAX_LLM_CALLS");
    }

    @Test
    @DisplayName("✅ 模型调用次数未超上限：不被误伤，Run 正常收敛")
    void withinLimitIsNotBlocked() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gw = request -> {
            modelCalls.incrementAndGet();
            // 第二轮就交终答 —— 全程 2 次模型调用，低于上限 5
            if (modelCalls.get() >= 2) {
                return new ModelResponse("x=2 或 x=3", Map.of(),
                        new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.STOP);
            }
            return toolCallResponse("ok_tool");
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        var outcome = loop(gw, claim).execute(TENANT, task(), agentConfig(),
                snapshot(20, 5), Map.of("q", "一元二次方程"));

        assertThat(outcome.kind())
                .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(claim.committedErrorCode)
                .as("未超限不得写 MAX_LLM_CALLS")
                .isNull();
    }

    @Test
    @DisplayName("⚠️ 上限为 1 时：第一次模型调用后即收敛，不发起第二次")
    void limitOfOneStopsAfterFirstCall() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gw = request -> {
            modelCalls.incrementAndGet();
            return toolCallResponse("ok_tool");
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        loop(gw, claim).execute(TENANT, task(), agentConfig(),
                snapshot(20, 1), Map.of("q", "一元二次方程"));

        assertThat(modelCalls.get()).isEqualTo(1);
        assertThat(claim.committedErrorCode).isEqualTo("MAX_LLM_CALLS");
    }
}
