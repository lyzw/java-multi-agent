package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
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
 * 工具闸门 · Run 级预算（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：不可妥协约束之一是
 * 「<b>全调用树共享预算上限，不能通过嵌套重置额度</b>」。
 * 此前系统只有<b>租户级</b>预算账户 —— 也就是说一个 Run 可以把租户余额烧光，
 * 而「这一棵调用树最多花多少」没有任何独立口径。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>R-1：Run 级额度用尽 → 工具<b>不得执行</b>（零副作用）；</li>
 *   <li>R-2：该次调用必须<b>留痕</b>（拒绝原因可查），不得静默丢弃；</li>
 *   <li>R-3：账本必须记 <b>FAILED</b>（工具根本没发出 = 确定失败），不得停在
 *       {@code IN_FLIGHT} 变成幽灵操作；</li>
 *   <li>R-4：额度<b>未声明</b> → 不额外限制（≠「余额为 0」）。</li>
 * </ul>
 */
class ToolInvocationGateRunBudgetTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";

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

    /** 恒批准的租户级预算替身（租户级额度充足）。 */
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

    /** 记录落账状态的账本替身。 */
    private static final class RecordingLedger implements RunToolLedgerPort {
        final java.util.List<ToolOperationState> recorded = new java.util.ArrayList<>();

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            return new Registration("op-1", ToolOperationState.IN_FLIGHT, 0, 0L, true);
        }

        @Override public void recordResult(String tenantId, String operationId,
                ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) {
            recorded.add(result);
        }

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

    /** 计数工具替身：只记录被调用了几次。 */
    private static final class CountingTool implements ToolHandler {
        final AtomicInteger executions = new AtomicInteger();

        @Override public String toolName() { return "record_weakness"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c, Map<String, Object> a) {
            executions.incrementAndGet();
            return ToolResult.of("ok");
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** Run 级额度已用尽的探针（额度 10，已用 6+4 = 10）。 */
    private static cn.studykid.multiagent.application.orchestration.RunBudgetProbe exhaustedRunBudget() {
        return new cn.studykid.multiagent.application.orchestration.RunBudgetProbe(
                (tenantId, runId) -> Optional.of(
                        new cn.studykid.multiagent.ports.persistence.RunBudgetPort.RunBudget(
                                runId, new BigDecimal("10"), new BigDecimal("6"),
                                new BigDecimal("4"))));
    }

    /** Run 级额度未声明的探针（= 不额外限制）。 */
    private static cn.studykid.multiagent.application.orchestration.RunBudgetProbe undeclaredRunBudget() {
        return new cn.studykid.multiagent.application.orchestration.RunBudgetProbe(
                (tenantId, runId) -> Optional.empty());
    }

    private static ToolInvocationGate gate(RunToolLedgerPort ledger,
                                           ToolHandler tool,
                                           cn.studykid.multiagent.application.orchestration
                                                   .RunBudgetProbe runBudget) {
        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        return new ToolInvocationGate(
                List.of(tool), ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                GRANT_BUDGET, ledger, NO_APPROVAL,
                BigDecimal.ONE, (t, r) -> false, approveAll,
                null, null, null, java.time.Clock.systemUTC(), runBudget);
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.now().plusSeconds(60), 1);
    }

    private static Map<String, ModelResponse.ToolCallRequest> oneCall(String callId) {
        return Map.of(callId, new ModelResponse.ToolCallRequest("record_weakness",
                Map.of("studentId", "stu_0123456789abcdef",
                        "knowledgePoint", "一元二次方程"),
                callId));
    }

    // ── R-1 ～ R-4 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ Run 级额度用尽 → 工具不得执行、调用必须留痕、账本记 FAILED（MA-T17）")
    void exhaustedRunBudgetBlocksToolExecution() {
        RecordingLedger ledger = new RecordingLedger();
        CountingTool tool = new CountingTool();

        var result = gate(ledger, tool, exhaustedRunBudget())
                .invokeAll(TENANT, task(), oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("⚠️⚠️ Run 额度用尽 → 绝不能再产生副作用（否则「共享预算上限」形同虚设）")
                .isZero();
        assertThat(result.rejectedTools())
                .as("⚠️ 该次调用必须留痕（不得静默丢弃）")
                .containsExactly("record_weakness");
        assertThat(result.rejectionReasonOf("record_weakness"))
                .get()
                .asString()
                .contains("Run 级预算");
        assertThat(ledger.recorded)
                .as("⚠️ 账本必须记 FAILED（工具根本没发出 = 确定失败），"
                        + "不得停在 IN_FLIGHT 变成幽灵操作")
                .containsExactly(ToolOperationState.FAILED);
    }

    @Test
    @DisplayName("✅ Run 级额度未声明 → 不额外限制（≠「余额为 0」）")
    void undeclaredRunBudgetDoesNotBlockToolExecution() {
        RecordingLedger ledger = new RecordingLedger();
        CountingTool tool = new CountingTool();

        var result = gate(ledger, tool, undeclaredRunBudget())
                .invokeAll(TENANT, task(), oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("未声明额度 = 没配预算，不得把工具全拦掉")
                .isEqualTo(1);
        assertThat(result.succeededTools()).containsExactly("record_weakness");
    }
}
