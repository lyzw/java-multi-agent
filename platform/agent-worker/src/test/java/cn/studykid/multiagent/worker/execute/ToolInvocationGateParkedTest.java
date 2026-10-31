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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.approval.ApprovalRequestSettings;
import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * G1/G2 回归测试：工具审批的「挂起（PARK）」语义（M0-1 / M1-3）。
 *
 * <p><b>背景</b>：修复前，幂等键命中<b>已登记待批</b>（PENDING）的操作时，
 * 闸门把 PENDING 与 IN_FLIGHT 合并判成「结果未知」——
 * 于是挂起恢复后会被自己判成 UNKNOWN，永久转入人工对账，
 * 而审批台上的「批准」永远不可能生效。
 *
 * <p><b>断言要点</b>（每条都可被反证）：
 * <ol>
 *   <li>需审批工具在无批准时 → <b>挂起</b>（parked），<b>不是</b>拒绝、
 *       <b>不是</b>未知；</li>
 *   <li>挂起时工具<b>未执行</b>（无副作用）、审批待办<b>已登记</b>（审批台可见）；</li>
 *   <li>挂起<b>不计入</b> rejected 集合（否则一次 REQUIRED 工具会把整单判失败）；</li>
 *   <li>幂等键命中 PENDING 既有操作 → 同样是 parked，不是 unknown。</li>
 * </ol>
 */
class ToolInvocationGateParkedTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final String TOOL = "record_weakness";

    /** 有副作用的工具（需审批）。 */
    private static final class SideEffectTool implements ToolHandler {
        int executes;

        @Override public String toolName() { return TOOL; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.WRITE_EXTERNAL; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext context,
                                            Map<String, Object> arguments) {
            executes++;
            return ToolResult.of("ok");
        }
    }

    /** 记录型审批端口。 */
    private static final class RecordingApprovalPort implements ApprovalPort {
        final List<String[]> requests = new ArrayList<>();
        ApprovalPort.ApprovalOutcome consumeOutcome = ApprovalPort.ApprovalOutcome.NOT_APPROVED;

        @Override public String grant(String tenantId, String operationId,
                String argumentDigest, String targetResource,
                ApprovalRequirement requirement, String approverScope,
                Instant expiresAt, String approverId) {
            throw new UnsupportedOperationException("本用例不授予审批");
        }

        @Override public Optional<String> request(String tenantId, String operationId,
                String argumentDigest, String targetResource,
                ApprovalRequirement requirement, String approverScope,
                Instant expiresAt) {
            requests.add(new String[]{tenantId, operationId, argumentDigest,
                    targetResource, requirement.name(), approverScope});
            return Optional.of(UUID.randomUUID().toString());
        }

        @Override public ApprovalPort.ApprovalOutcome consume(String tenantId, String operationId,
                String argumentDigest, String targetResource, String runId) {
            return consumeOutcome;
        }

        @Override public void revoke(String tenantId, String operationId,
                String approverId, String reason) { }

        @Override public Optional<ApprovalView> findActive(String tenantId,
                String operationId) {
            return Optional.empty();
        }
    }

    /** 账本：可配置「幂等键命中既有操作」时的状态。 */
    private static final class StubLedger implements RunToolLedgerPort {
        ToolOperationState existingState = ToolOperationState.IN_FLIGHT;
        String existingOperationId = UUID.randomUUID().toString();
        /**
         * ⚠️ 是否「本次新建」。
         *
         * <p>闸门用 {@code createdByThisCall()} 区分「新建」与「命中既有操作」——
         * 只看状态会把「本次新建」误判成「命中既有」。
         * 故测试必须**显式**控制它，不能由状态推导。
         */
        boolean createdByThisCall = true;

        @Override public Registration register(String tenantId, String runId, String toolName,
                String riskLevel, String idempotencyKey, String requestDigest,
                Map<String, Object> arguments, long fencingToken) {
            return new Registration(existingOperationId, existingState, 1, fencingToken,
                    createdByThisCall);
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

    private static final class GrantBudget implements BudgetPort {
        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, BigDecimal amount) {
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
            return Optional.of(new Reservation(UUID.randomUUID().toString(), RUN_ID, operationId,
                    BigDecimal.ONE, BigDecimal.ZERO, States.RESERVED, "CNY"));
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

    private static AuthorizationService allowAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                return true;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    private static ToolInvocationGate gate(ApprovalPort approvalPort, ToolHandler tool,
                                            StubLedger ledger,
                                            RiskBasedApprovalPolicy.ApprovalSource source) {
        return new ToolInvocationGate(List.of(tool), allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL 需审批"),
                new GrantBudget(), ledger, approvalPort,
                BigDecimal.valueOf(0.5), (t, r) -> false,
                source, null, null, null, java.time.Clock.systemUTC(), null, null, null,
                new ApprovalRequestSettings("PARENT_OR_TEACHER", Duration.ofMinutes(15)));
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    private static Map<String, ModelResponse.ToolCallRequest> call() {
        Map<String, ModelResponse.ToolCallRequest> calls = new LinkedHashMap<>();
        calls.put("c1", new ModelResponse.ToolCallRequest(TOOL,
                Map.of("studentId", "s-1", "knowledgePoint", "二次函数"), "c1"));
        return calls;
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ M1-3：无批准时 → 挂起（parked），不是拒绝、不是未知、工具未执行")
    void requiredToolParksInsteadOfRejecting() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool, new StubLedger(),
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.hasParked())
                .as("需审批工具在无批准时必须**挂起**（PARK），交给人批")
                .isTrue();
        assertThat(result.parkedTools())
                .as("挂起必须带 operationId —— 审批台与恢复流程都靠它定位")
                .containsKey(TOOL);
        assertThat(result.rejectedTools())
                .as("⚠️ 挂起**不得**计入 rejected：否则一次 REQUIRED 工具会把整单判失败")
                .doesNotContain(TOOL);
        assertThat(result.unknownResultOccurred())
                .as("⚠️ 挂起**不得**判成结果未知：否则恢复后永久转人工对账")
                .isFalse();
        assertThat(tool.executes)
                .as("挂起发生在执行前 → 工具未执行、无副作用")
                .isZero();
        assertThat(approvals.requests)
                .as("挂起必须同时登记待办 —— 否则审批台看不到任何东西")
                .hasSize(1);
    }

    @Test
    @DisplayName("✅ M0-1：幂等键命中 PENDING 既有操作且仍未批准 → 挂起，不是结果未知")
    void idempotencyHitOnPendingIsParkedNotUnknown() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        // ⚠️ 尚未获批（消费不命中）→ 应挂起等待人批
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.NOT_APPROVED;
        SideEffectTool tool = new SideEffectTool();
        StubLedger ledger = new StubLedger();
        // ⚠️ 复现「上一次已登记待批（PENDING）、本次重入」的场景
        ledger.existingState = ToolOperationState.PENDING;
        ledger.createdByThisCall = false;

        var result = gate(approvals, tool, ledger,
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.unknownResultOccurred())
                .as("⚠️ 修复前 PENDING 与 IN_FLIGHT 被合并判成 unknown —— "
                        + "挂起恢复后会被自己判成 UNKNOWN，永久转人工对账")
                .isFalse();
        assertThat(result.unknownOperationId()).isNull();
        assertThat(result.hasParked()).isTrue();
        assertThat(tool.executes)
                .as("挂起发生在执行前 → 工具未执行、无副作用")
                .isZero();
    }

    @Test
    @DisplayName("✅ M0-1：幂等键命中 PENDING 且已批准 → 直接执行（这就是 RESUME 语义）")
    void idempotencyHitOnPendingWithApprovalExecutes() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.CONSUMED;
        SideEffectTool tool = new SideEffectTool();
        StubLedger ledger = new StubLedger();
        ledger.existingState = ToolOperationState.PENDING;
        ledger.createdByThisCall = false;

        var result = gate(approvals, tool, ledger,
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.hasParked())
                .as("已批准 → 不该再挂起（挂起会让 Run 永远醒不过来）")
                .isFalse();
        assertThat(result.succeededTools()).contains(TOOL);
        assertThat(tool.executes)
                .as("恢复路径必须真正执行工具 —— 这正是「人批了要生效」的含义")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ IN_FLIGHT 仍判结果未知（语义未被放宽）")
    void idempotencyHitOnInFlightStaysUnknown() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.CONSUMED;
        SideEffectTool tool = new SideEffectTool();
        StubLedger ledger = new StubLedger();
        ledger.existingState = ToolOperationState.IN_FLIGHT;
        ledger.createdByThisCall = false;

        var result = gate(approvals, tool, ledger,
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.unknownResultOccurred())
                .as("IN_FLIGHT = 可能崩在中途或另一 worker 正在执行 → 重复执行 = 重复副作用")
                .isTrue();
        assertThat(tool.executes).isZero();
    }

    @Test
    @DisplayName("✅ 人已拒绝（consume=REJECTED）→ 直接拒绝，不得再登记待办")
    void rejectedApprovalDoesNotRepark() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.REJECTED;
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool, new StubLedger(),
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.rejectedTools()).contains(TOOL);
        assertThat(result.hasParked())
                .as("⚠️ 人已拒绝却再次挂起 = 死循环（拒绝→重登记→再挂起→再拒…）")
                .isFalse();
        assertThat(approvals.requests)
                .as("⚠️ 已拒绝是终态结论，不得重新登记待办（否则待办永远批不完）")
                .isEmpty();
        assertThat(tool.executes).isZero();
    }

    @Test
    @DisplayName("✅ 审批已过期（consume=EXPIRED）→ 直接拒绝，不得复活")
    void expiredApprovalDoesNotRepark() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.EXPIRED;
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool, new StubLedger(),
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.rejectedTools()).contains(TOOL);
        assertThat(result.hasParked())
                .as("⚠️ 过期审批不得重新登记（否则过期决定被当成「可重新申请」）")
                .isFalse();
        assertThat(approvals.requests).isEmpty();
    }

    @Test
    @DisplayName("✅ 审批来源明确批准（APPROVED）→ 直接执行，不挂起")
    void sourceApprovedExecutesWithoutParking() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        approvals.consumeOutcome = ApprovalPort.ApprovalOutcome.NOT_APPROVED;
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool, new StubLedger(),
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED)
                .invokeAll(TENANT, task(), call());

        assertThat(result.hasParked()).isFalse();
        assertThat(result.succeededTools()).contains(TOOL);
        assertThat(tool.executes).isEqualTo(1);
        assertThat(approvals.requests)
                .as("来源已批准 → 不该再登记待办（会把待办台淹在无效待办里）")
                .isEmpty();
    }
}
