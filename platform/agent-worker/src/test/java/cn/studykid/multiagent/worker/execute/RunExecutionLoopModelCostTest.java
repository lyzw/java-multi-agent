package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.ModelPricingPolicy;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ModelPricing;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelGateway;
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
 * P0-3b 回归测试：<b>模型调用的 token 与真实成本必须入账</b>。
 *
 * <p><b>缺陷背景</b>：{@code usage_record} 建好了、列也齐了
 * （{@code input_tokens} / {@code output_tokens} / {@code cost_amount}），
 * 但<b>没有任何写入方</b>写 token 与真实成本 ——
 * 唯一一条 INSERT 只写「工具调用次数 × ¥0.50」的固定代理值，
 * 且 {@code model_id} 列存的是<b>工具名</b>。
 * 于是「这个 Run 用了多少 token、花了多少钱」在账上<b>不存在</b>，
 * 成本类指标（COST-1/2/3）算出来的是错的。
 *
 * <p><b>断言要点</b>：不能只断言「调用成功」——
 * 修复前调用同样成功。真正要锁死的是
 * <b>「真实 token 与真实成本进了计量行」</b>与<b>「成本上限真的拦得住」</b>。
 */
class RunExecutionLoopModelCostTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();

    private static final ModelCallObserverChain NOOP_OBSERVERS =
            new ModelCallObserverChain(java.util.List.of());

    /** 一次模型调用的计量记录。 */
    private record Usage(String modelId, long inputTokens, long outputTokens,
                         BigDecimal cost, String reservationId) {
    }

    /**
     * 记录型预算替身：把预留、结算、计量全部记下来供断言。
     *
     * <p>⚠️ <b>必须实现 token 版 settle</b>：
     * 端口的默认实现会丢弃 token —— 若测试替身也用默认实现，
     * 「token 有没有落库」就永远测不出来，测试会变成摆设。
     */
    private static final class RecordingBudget implements BudgetPort {
        final Map<String, Reservation> byOperation = new LinkedHashMap<>();
        final List<Usage> usages = new ArrayList<>();
        final List<String> releases = new ArrayList<>();
        int reserveCalls;
        boolean grant = true;

        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, BigDecimal amount) {
            reserveCalls++;
            if (!grant) {
                return ReserveOutcome.INSUFFICIENT;
            }
            String key = operationId == null ? "anon-" + reserveCalls : operationId;
            Reservation existing = byOperation.get(key);
            if (existing != null) {
                return ReserveOutcome.REPLAYED;
            }
            String rid = UUID.randomUUID().toString();
            byOperation.put(key, new Reservation(rid, runId, operationId, amount,
                    BigDecimal.ZERO, States.RESERVED, "CNY"));
            return ReserveOutcome.RESERVED;
        }

        @Override public boolean settle(String tenantId, String reservationId,
                BigDecimal actualAmount, String modelId) {
            return true;
        }

        /** ⚠️ 带 token 的结算：生产实现写 token 列，测试替身必须同形。 */
        @Override public boolean settle(String tenantId, String reservationId,
                BigDecimal actualAmount, String modelId,
                long inputTokens, long outputTokens) {
            usages.add(new Usage(modelId, inputTokens, outputTokens, actualAmount, reservationId));
            return true;
        }

        @Override public boolean recordModelUsage(String tenantId, String runId,
                String modelId, long inputTokens, long outputTokens,
                BigDecimal costAmount, String modelCallId) {
            usages.add(new Usage(modelId, inputTokens, outputTokens, costAmount, null));
            return true;
        }

        @Override public boolean release(String tenantId, String reservationId, String reason) {
            releases.add(reservationId);
            return true;
        }

        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.ofNullable(byOperation.get(operationId));
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

    private static RunExecutionLoop loop(ModelGateway gw, RunClaimPort claim,
                                         BudgetPort budget, ModelPricingPolicy pricing) {
        ToolInvocationGate gate = new ToolInvocationGate(List.of(), allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                budget, new FreshLedger());
        return new RunExecutionLoop(new ObservableModelGateway(gw, NOOP_OBSERVERS),
                claim, gate, Duration.ofSeconds(30), "w-1",
                new cn.studykid.multiagent.application.prompt.PromptGuard(), null, null,
                ContextBudgetTrimmer.DEFAULT_CONTEXT_TOKEN_BUDGET, null, null, null,
                new ExecutionObserverChain(java.util.List.of()), null, budget, pricing);
    }

    private static RunConfigSnapshot snapshot(BigDecimal maxCost) {
        return new RunConfigSnapshot(RUN_ID, maxCost, 12, 20,
                ApprovalRequirement.NOT_REQUIRED, Instant.now());
    }

    private static RunExecutionLoop.AgentModelConfig agentConfig(String modelId) {
        return new RunExecutionLoop.AgentModelConfig(
                modelId, "你是初中数学辅导助手", 0.7d, 1024, Duration.ofSeconds(5), Map.of());
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    /** 单价：每千 token 输入 1 元、输出 2 元。 */
    private static ModelPricingPolicy pricing() {
        return new ModelPricingPolicy(Map.of(
                "test-model", new ModelPricing(new BigDecimal("1"), new BigDecimal("2"))));
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 模型调用的真实 token 与真实成本进计量行（不再是固定代理值）")
    void modelUsageIsRecordedWithRealTokensAndCost() {
        RecordingBudget budget = new RecordingBudget();
        ModelGateway gw = request -> new ModelResponse("答案是 42", Map.of(),
                new ModelResponse.ModelUsage(1200, 300),
                ModelResponse.FinishReason.STOP);

        loop(gw, new RecordingClaimPort(), budget, pricing())
                .execute(TENANT, task(), agentConfig("test-model"),
                        snapshot(new BigDecimal("2.00")), Map.of("q", "一元二次方程"));

        assertThat(budget.usages)
                .as("模型调用必须产生一条计量记录（修复前 usage_record 只有工具行的代理值）")
                .hasSize(1);
        Usage usage = budget.usages.get(0);
        assertThat(usage.modelId())
                .as("model_id 必须是真实模型标识（修复前存的是工具名）")
                .isEqualTo("test-model");
        assertThat(usage.inputTokens()).isEqualTo(1200);
        assertThat(usage.outputTokens()).isEqualTo(300);
        // 1200/1000×1 + 300/1000×2 = 1.2 + 0.6 = 1.8
        assertThat(usage.cost())
                .as("成本必须按「真实 token × 单价」算，不是固定 ¥0.50")
                .isEqualByComparingTo(new BigDecimal("1.800000"));
    }

    @Test
    @DisplayName("⚠️ 单 Run 成本上限真的拦得住（maxCostPerRun 此前只用于配置展示）")
    void maxCostPerRunIsEnforced() {
        RecordingBudget budget = new RecordingBudget();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gw = request -> {
            calls.incrementAndGet();
            // 每轮都带工具调用 → 循环持续；每轮成本 1.8 元
            return new ModelResponse("", Map.of("noop",
                            new ModelResponse.ToolCallRequest("noop", Map.of(), "c")),
                    new ModelResponse.ModelUsage(1200, 300),
                    ModelResponse.FinishReason.TOOL_CALLS);
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        // 上限 2.00 元 → 第一次调用就超限（1.8 < 2.0，第二轮才超，但第一轮已记 1.8）
        // 用 1.00 元上限：第一次调用 1.8 元即超限。
        var outcome = loop(gw, claim, budget, pricing())
                .execute(TENANT, task(), agentConfig("test-model"),
                        snapshot(new BigDecimal("1.00")), Map.of("q", "一元二次方程"));

        assertThat(outcome.kind())
                .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.FAILED);
        assertThat(claim.committedErrorCode)
                .as("必须写结构化错误码 MAX_COST，供终止原因归因")
                .isEqualTo("MAX_COST");
        assertThat(calls.get())
                .as("成本超限必须**在发起下一次模型调用之前**停住")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 模型未配置单价：token 仍如实入账，成本按 0 记（缺配置必须可见）")
    void unpricedModelStillRecordsTokens() {
        RecordingBudget budget = new RecordingBudget();
        ModelGateway gw = request -> new ModelResponse("ok", Map.of(),
                new ModelResponse.ModelUsage(500, 100), ModelResponse.FinishReason.STOP);

        loop(gw, new RecordingClaimPort(), budget, new ModelPricingPolicy(Map.of()))
                .execute(TENANT, task(), agentConfig("unpriced-model"),
                        snapshot(new BigDecimal("2.00")), Map.of("q", "x"));

        assertThat(budget.usages)
                .as("未配价 ≠ 不记账：token 是事实，必须记")
                .hasSize(1);
        assertThat(budget.usages.get(0).inputTokens()).isEqualTo(500);
        assertThat(budget.usages.get(0).outputTokens()).isEqualTo(100);
        assertThat(budget.usages.get(0).cost())
                .as("没有价格就没有金额 —— 按 0 记，且必须配了单价表才非零")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(budget.reserveCalls)
                .as("未配价时不做预留（金额为 0 的预留会被 DB 拒绝）")
                .isZero();
    }

    @Test
    @DisplayName("⚠️ 预算预留被拒 → fail-closed：不得「钱不够也照跑」")
    void budgetRejectionFailsClosed() {
        RecordingBudget budget = new RecordingBudget();
        budget.grant = false;
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gw = request -> {
            calls.incrementAndGet();
            return new ModelResponse("ok", Map.of(), new ModelResponse.ModelUsage(1, 1),
                    ModelResponse.FinishReason.STOP);
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        var outcome = loop(gw, claim, budget, pricing())
                .execute(TENANT, task(), agentConfig("test-model"),
                        snapshot(new BigDecimal("2.00")), Map.of("q", "x"));

        assertThat(outcome.kind())
                .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.FAILED);
        assertThat(claim.committedErrorCode).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(calls.get())
                .as("预留被拒时不得发起模型调用")
                .isZero();
    }

    @Test
    @DisplayName("⚠️ 模型调用失败：预留必须释放（否则额度凭空少一笔）")
    void failedModelCallReleasesReservation() {
        RecordingBudget budget = new RecordingBudget();
        ModelGateway gw = request -> {
            throw new ModelGateway.ModelGatewayException(
                    ModelGateway.ModelErrorKind.PROVIDER_ERROR, "模拟上游失败");
        };
        RecordingClaimPort claim = new RecordingClaimPort();

        loop(gw, claim, budget, pricing())
                .execute(TENANT, task(), agentConfig("test-model"),
                        snapshot(new BigDecimal("2.00")), Map.of("q", "x"));

        assertThat(budget.releases)
                .as("调用失败时预留必须释放 —— 否则这笔额度永远占着")
                .hasSize(1);
    }
}
