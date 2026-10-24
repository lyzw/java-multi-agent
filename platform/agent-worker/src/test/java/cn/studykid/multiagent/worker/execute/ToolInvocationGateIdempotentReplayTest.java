package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 工具闸门 · 幂等重放不得重复执行（反证 · Sev-0 防重副作用）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复前
 * {@code ToolInvocationGate} 在账本登记后<b>从不检查
 * {@code registration.state()}</b>，
 * 于是幂等键命中既有操作时流程径直走向执行 →
 * <b>同一逻辑操作产生两次副作用</b>（重复发放 / 重复写入 = Sev-0）。
 * 紧接着 {@code recordResult(SUCCEEDED)} 因状态已非 {@code IN_FLIGHT} 抛
 * {@code IllegalStateTransitionException}，
 * 而 catch 块里的兜底 {@code recordResult(UNKNOWN)} 又抛一次 ——
 * <b>异常从 catch 块内部逃出闸门</b>，
 * Run 被标记 FAILED 且账本说 SUCCEEDED（两个事实互相矛盾），
 * 同时<b>没有任何对账记录</b>（兜底记账本身就是失败的那一次）。
 *
 * <p><b>⚠️ 为什么必须区分「新建」与「命中既有」</b>：
 * 既有操作的状态<b>可能仍是 IN_FLIGHT</b>（上次调用崩在中途，
 * 或另一 worker 正在执行）——只看状态会把「本次新建」误判成「命中既有」，
 * 从而连正常调用也执行不了。故端口契约新增
 * {@code Registration.createdByThisCall}。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>G-1：命中既有且已成功 → <b>工具不得再被执行</b>；</li>
 *   <li>G-2：命中既有且仍在途（IN_FLIGHT）→ <b>工具不得再被执行</b>；</li>
 *   <li>G-3：命中既有且结果未知（UNKNOWN）→ <b>工具不得再被执行</b>；</li>
 *   <li>G-4：本次新建 → 工具<b>必须</b>执行（防止修过头，把正常调用也堵死）；</li>
 *   <li>G-5：catch 路径里的兜底记账再失败 → <b>异常不得逃出闸门</b>。</li>
 * </ul>
 */
class ToolInvocationGateIdempotentReplayTest {

    private static final String TENANT = "t-a";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 恒放行的授权替身。 */
    private static final AuthorizationService ALLOW_ALL = new AuthorizationService() {
        @Override public boolean isAuthorized(TenantContext c, String a, String r) {
            return true;
        }
        @Override public void requireAuthorized(TenantContext c, String a, String r) { }
        @Override public long currentAuthzVersion(String t, String p) { return 0L; }
        @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
    };

    /** 恒批准的预算替身。 */
    private static final BudgetPort GRANT_BUDGET = new BudgetPort() {
        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }
        @Override public boolean settle(String tenantId, String reservationId,
                BigDecimal actualAmount, String modelId) {
            return true;
        }
        @Override public boolean release(String tenantId, String reservationId,
                String reason) {
            return true;
        }
        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.of(new Reservation("res-1", "run-1", operationId,
                    BigDecimal.ONE, null, "RESERVED", "CNY"));
        }
        @Override public Optional<AccountSnapshot> accountSnapshot(String tenantId,
                String currency) {
            return Optional.empty();
        }
        @Override public java.util.List<OrphanedReservation> findOrphaned(
                String tenantId) {
            return java.util.List.of();
        }
        @Override public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    };

    /** 恒「无审批」的审批端口替身（工具风险等级 READ → 不需审批）。 */
    private static final ApprovalPort NO_APPROVAL = new ApprovalPort() {
        @Override public ApprovalOutcome consume(String tenantId, String operationId,
                String argumentDigest, String targetResource, String runId) {
            return ApprovalOutcome.NO_APPROVAL;
        }
        @Override public String grant(String tenantId, String operationId,
                String argumentDigest, String targetResource,
                cn.studykid.multiagent.core.config.ApprovalRequirement requirement,
                String approverScope, Instant expiresAt, String approverId) {
            throw new UnsupportedOperationException("本替身不支持授予");
        }
        @Override public void revoke(String tenantId, String operationId,
                String approverId, String reason) {
            throw new UnsupportedOperationException("本替身不支持撤销");
        }
        @Override public Optional<ApprovalView> findActive(String tenantId,
                String operationId) {
            return Optional.empty();
        }
    };

    /**
     * 可编程的账本替身。
     *
     * @param existingState 命中既有操作时返回的状态；{@code null} 表示本次新建
     * @param recordThrows  recordResult 是否抛异常（模拟兜底记账再失败）
     */
    private static final class ScriptedLedger implements RunToolLedgerPort {
        private final ToolOperationState existingState;
        private final boolean recordThrows;
        final AtomicInteger recordCalls = new AtomicInteger();

        ScriptedLedger(ToolOperationState existingState, boolean recordThrows) {
            this.existingState = existingState;
            this.recordThrows = recordThrows;
        }

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            if (existingState != null) {
                return new Registration(UUID.randomUUID().toString(), existingState, 1, 0L, false);
            }
            return new Registration(UUID.randomUUID().toString(),
                    ToolOperationState.IN_FLIGHT, 0, 0L, true);
        }

        @Override public void recordResult(String tenantId, String operationId,
                ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) {
            recordCalls.incrementAndGet();
            if (recordThrows) {
                throw new IllegalStateException("模拟账本落账失败");
            }
        }

        @Override public void reconcile(String tenantId, String operationId,
                ToolOperationState reconciled, String source) { }
        @Override public void manualDispose(String tenantId, String operationId,
                String disposedBy, String note) { }
        @Override public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return List.of();
        }
        @Override public Optional<Registration> findByIdempotencyKey(
                String tenantId, String idempotencyKey) {
            return Optional.empty();
        }
    }

    /** 计数工具替身：只记录被调用了几次。 */
    private static final class CountingTool implements ToolHandler {
        final AtomicInteger executions = new AtomicInteger();
        private final boolean throwsOnExecute;

        CountingTool(boolean throwsOnExecute) {
            this.throwsOnExecute = throwsOnExecute;
        }

        @Override public String toolName() { return "record_weakness"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c, Map<String, Object> a) {
            executions.incrementAndGet();
            if (throwsOnExecute) {
                throw new IllegalStateException("模拟工具执行失败");
            }
            return ToolResult.of("ok");
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static ToolInvocationGate gate(RunToolLedgerPort ledger, List<ToolHandler> handlers) {
        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        return new ToolInvocationGate(handlers, ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                GRANT_BUDGET, ledger, NO_APPROVAL,
                BigDecimal.ONE, (t, r) -> false, approveAll);
    }

    private static RunClaimPort.LeasedTask task(String runId) {
        return new RunClaimPort.LeasedTask(runId, 1L,
                Instant.now().plusSeconds(60), 1);
    }

    private static Map<String, ModelResponse.ToolCallRequest> oneCall(String callId) {
        return Map.of(callId, new ModelResponse.ToolCallRequest("record_weakness",
                Map.of("studentId", "stu_0123456789abcdef",
                        "knowledgePoint", "一元二次方程"),
                callId));
    }

    // ── G-1 ～ G-3：命中既有操作不得重复执行 ────────────────────────

    @Test
    @DisplayName("⛔ 反证 G-1：幂等键命中已成功的既有操作时，工具不得再被执行（重复副作用 = Sev-0）")
    void doesNotReExecuteWhenExistingOperationSucceeded() {
        ScriptedLedger ledger = new ScriptedLedger(ToolOperationState.SUCCEEDED, false);
        CountingTool tool = new CountingTool(false);

        gate(ledger, List.of(tool)).invokeAll(TENANT, task(UUID.randomUUID().toString()),
                oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("⚠️⚠️ 上一次已成功，再次执行 = 同一逻辑操作产生两次副作用 = Sev-0")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 反证 G-2：幂等键命中仍在途（IN_FLIGHT）的既有操作时，工具不得再被执行")
    void doesNotReExecuteWhenExistingOperationInFlight() {
        ScriptedLedger ledger = new ScriptedLedger(ToolOperationState.IN_FLIGHT, false);
        CountingTool tool = new CountingTool(false);

        gate(ledger, List.of(tool)).invokeAll(TENANT, task(UUID.randomUUID().toString()),
                oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("⚠️⚠️ 既有操作仍在途（上次崩在中途 / 另一 worker 正在执行）→ 再执行即重复副作用")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 反证 G-3：幂等键命中结果未知（UNKNOWN）的既有操作时，工具不得再被执行")
    void doesNotReExecuteWhenExistingOperationUnknown() {
        ScriptedLedger ledger = new ScriptedLedger(ToolOperationState.UNKNOWN, false);
        CountingTool tool = new CountingTool(false);

        gate(ledger, List.of(tool)).invokeAll(TENANT, task(UUID.randomUUID().toString()),
                oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("⚠️⚠️ UNKNOWN 意味着「可能已产生副作用」——盲目重试 = Sev-0")
                .isZero();
    }

    // ── G-4：正常新建必须仍能执行（防止修过头）──────────────────────

    @Test
    @DisplayName("✅ 反证 G-4：本次新建的操作必须正常执行（闸门不得把正常调用也堵死）")
    void executesWhenOperationIsNewlyCreated() {
        ScriptedLedger ledger = new ScriptedLedger(null, false);
        CountingTool tool = new CountingTool(false);

        gate(ledger, List.of(tool)).invokeAll(TENANT, task(UUID.randomUUID().toString()),
                oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("✅ 正常调用必须照常执行")
                .isEqualTo(1);
    }

    // ── G-5：catch 路径的兜底记账再失败，异常不得逃出闸门 ───────────

    @Test
    @DisplayName("⛔ 反证 G-5：兜底记账再失败时异常不得逃出闸门（修复前从 catch 块内部抛出）")
    void doesNotPropagateWhenFallbackLedgerWriteAlsoFails() {
        // ⚠️ 工具抛异常 → 进入 catch 分支 → 兜底 recordResult 也抛
        //    （修复前：这次异常从 catch 块内部逃出闸门 → Run 被误报 FAILED）
        ScriptedLedger ledger = new ScriptedLedger(null, true);
        CountingTool tool = new CountingTool(true);

        ToolInvocationGate gate = gate(ledger, List.of(tool));

        assertThatCode(() -> gate.invokeAll(TENANT, task(UUID.randomUUID().toString()),
                oneCall("call-1")))
                .as("⚠️⚠️ 异常处理路径里的记账失败只能记日志，"
                        + "不得再抛一次把 Run 打成 FAILED")
                .doesNotThrowAnyException();
    }
}
