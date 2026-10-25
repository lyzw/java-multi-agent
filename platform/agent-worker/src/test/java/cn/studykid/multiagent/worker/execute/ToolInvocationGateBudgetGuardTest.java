package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * 工具闸门 · 预算预留异常的 fail-closed 纪律。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复前 {@code ToolInvocationGate}
 * 在「预算预留已生效、但 {@code findByOperation} 查不到预留记录」时
 * 直接返回 {@code null}，而调用方把 {@code null} 一律当成
 * 「已记录拒绝」直接 {@code continue} —— 结果是：
 * <ul>
 *   <li>工具<b>没有执行</b>，账本行却永远停在 {@code IN_FLIGHT}；</li>
 *   <li><b>没有</b>拒绝原因、<b>没有</b>拒绝记录，模型也收不到任何反馈；</li>
 *   <li>这次调用<b>从系统里彻底消失</b>：不可见、不可对账、
 *       也无人知道它曾经发生过。</li>
 * </ul>
 *
 * <p><b>本类的判据</b>（G-6）：
 * <ul>
 *   <li>G-6-a：预留记录不可见时，工具<b>不得执行</b>
 *       （没有 reservationId 就无法结算/释放，执行了必然留下无人认领的成本）；</li>
 *   <li>G-6-b：该次调用<b>必须留痕</b> —— 拒绝原因可查，不得静默丢弃；</li>
 *   <li>G-6-c：账本必须记 <b>FAILED</b>（工具根本没发出 = 确定失败，
 *       不是结果未知），不得停在 {@code IN_FLIGHT} 变成幽灵操作。</li>
 * </ul>
 */
class ToolInvocationGateBudgetGuardTest {

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

    /**
     * 「预留成功但查不到预留记录」的预算替身。
     *
     * <p>⚠️ 模拟预留层写后读不一致：{@code reserve} 返回 RESERVED，
     * 但 {@code findByOperation} 查不到 —— 闸门拿不到 reservationId。
     */
    private static final BudgetPort RESERVED_BUT_INVISIBLE = new BudgetPort() {
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
            return Optional.empty();
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

    private static ToolInvocationGate gate(RunToolLedgerPort ledger,
                                           BudgetPort budget,
                                           List<ToolHandler> handlers) {
        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        return new ToolInvocationGate(handlers, ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                budget, ledger, NO_APPROVAL,
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

    // ── G-6：预算预留异常必须 fail-closed 且留痕 ─────────────────────

    @Test
    @DisplayName("⛔ 反证 G-6：预算预留已生效但查不到记录 → 工具不得执行、调用不得静默消失")
    void rejectsAndRecordsWhenReservationRecordIsInvisible() {
        RecordingLedger ledger = new RecordingLedger();
        CountingTool tool = new CountingTool();

        var result = gate(ledger, RESERVED_BUT_INVISIBLE, List.of(tool))
                .invokeAll(TENANT, task("run-1"), oneCall("call-1"));

        assertThat(tool.executions.get())
                .as("⚠️ 拿不到 reservationId 就无法结算/释放，执行了必然留下无人认领的成本")
                .isZero();
        assertThat(result.rejectedTools())
                .as("⚠️⚠️ 该次调用必须留痕 —— 修复前它从系统里彻底消失，"
                        + "既不可见也不可对账")
                .containsExactly("record_weakness");
        assertThat(result.rejectionReasonOf("record_weakness"))
                .as("拒绝原因必须可查（08 §4「非笼统 DENY 占比 100%」）")
                .get()
                .asString()
                .contains("预算");
        assertThat(ledger.recorded)
                .as("⚠️ 账本必须记 FAILED（工具根本没发出 = 确定失败），"
                        + "不得停在 IN_FLIGHT 变成幽灵操作")
                .containsExactly(ToolOperationState.FAILED);
    }
}
