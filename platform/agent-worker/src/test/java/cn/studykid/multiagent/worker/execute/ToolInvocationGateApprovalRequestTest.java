package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * P0-2 回归测试：<b>需要审批的工具被拒时，审批台必须能看到待办</b>。
 *
 * <p><b>缺陷背景（P0-2）</b>：{@code ApprovalPort.grant()} 是唯一写 {@code approval} 表的入口，
 * 而它<b>只被审批权威调用</b> —— 生产代码零调用。
 * 于是 {@code approval} 表永远是空的，{@code ApprovalController} 的审批台
 * <b>永远返回空列表</b>，而需要审批的工具恒被拒（fail-closed）。
 * 「装好了门把手但通向墙壁的门」。
 *
 * <p><b>断言要点</b>：必须同时断言
 * <b>「工具仍被拒绝」</b>与<b>「待办已登记」</b>——
 * 只断言其一，另一半没做到时测试同样是绿的。
 */
class ToolInvocationGateApprovalRequestTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final String TOOL = "record_weakness";

    /** 有副作用的工具（需审批）。 */
    private static final class SideEffectTool implements ToolHandler {
        final AtomicIntegerHolder executes = new AtomicIntegerHolder();

        @Override public String toolName() { return TOOL; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.WRITE_EXTERNAL; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext context,
                                            Map<String, Object> arguments) {
            executes.value++;
            return ToolResult.of("ok");
        }
    }

    /** 极简计数器（避免额外依赖 AtomicInteger 的 import 冲突）。 */
    private static final class AtomicIntegerHolder {
        int value;
    }

    /** 记录型审批端口：记录 request/grant/consume 的调用。 */
    private static final class RecordingApprovalPort implements ApprovalPort {
        final List<String[]> requests = new ArrayList<>();
        int consumeCalls;

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
                    targetResource, requirement.name(), approverScope,
                    String.valueOf(expiresAt)});
            return Optional.of(UUID.randomUUID().toString());
        }

        @Override public ApprovalOutcome consume(String tenantId, String operationId,
                String argumentDigest, String targetResource, String runId) {
            consumeCalls++;
            return ApprovalOutcome.NOT_APPROVED;
        }

        @Override public void revoke(String tenantId, String operationId,
                String approverId, String reason) { }

        @Override public Optional<ApprovalView> findActive(String tenantId,
                String operationId) {
            return Optional.empty();
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

    private static ToolInvocationGate gate(ApprovalPort approvalPort,
                                            ToolHandler tool,
                                            ApprovalRequestSettings settings) {
        return new ToolInvocationGate(List.of(tool), allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL 需审批"),
                new GrantBudget(), new FreshLedger(), approvalPort,
                BigDecimal.valueOf(0.5), (t, r) -> false,
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.FAIL_CLOSED,
                null, null, null, java.time.Clock.systemUTC(), null, null, null, settings);
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    private static Map<String, ModelResponse.ToolCallRequest> call() {
        Map<String, ModelResponse.ToolCallRequest> calls = new java.util.LinkedHashMap<>();
        calls.put("c1", new ModelResponse.ToolCallRequest(TOOL,
                Map.of("studentId", "s-1", "knowledgePoint", "二次函数"), "c1"));
        return calls;
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 需审批工具被拒时：审批台必须出现待办（P0-2 的原症状）")
    void pendingApprovalIsRegistered() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool,
                new ApprovalRequestSettings("PARENT_OR_TEACHER", Duration.ofMinutes(15)))
                .invokeAll(TENANT, task(), call());

        assertThat(result.hasParked())
                .as("✅ M1-3 语义变更：未获批准 → **挂起等待人工审批**（PARK），"
                        + "而不是恒拒 —— 这正是 G2 的核心（恒拒 = HITL 形同虚设）")
                .isTrue();
        assertThat(result.rejectedTools())
                .as("挂起**不得**计入 rejected：否则一次 REQUIRED 工具会把整单判失败")
                .doesNotContain(TOOL);
        assertThat(tool.executes.value)
                .as("⚠️⚠️ 工具**绝不可执行**（fail-closed 的实质不变）：挂起发生在执行前")
                .isZero();

        assertThat(approvals.requests)
                .as("⚠️ 审批台必须能看到待办 —— 修复前 approval 表零写入，审批台永远空")
                .hasSize(1);
        String[] request = approvals.requests.get(0);
        assertThat(request[0]).isEqualTo(TENANT);
        assertThat(request[2])
                .as("参数摘要必须与调用参数绑定（审批后篡改参数将无法消费）")
                .startsWith("args_norm_v1:");
        assertThat(request[2])
                .as("⚠️ 合规红线：审批台只存**摘要**，绝不存参数原文"
                        + "（参数里含学生提交的解答原文）")
                .doesNotContain("二次函数")
                .doesNotContain("s-1");
        assertThat(request[3]).isEqualTo(TOOL);
        assertThat(request[4]).isEqualTo(ApprovalRequirement.REQUIRED.name());
        assertThat(request[5]).isEqualTo("PARENT_OR_TEACHER");
        assertThat(request[6])
                .as("必须带有效期 —— 否则待办永不过期")
                .isNotBlank();
    }

    @Test
    @DisplayName("✅ 明确拒绝（REJECTED）：不产生待办（没有待决状态）")
    void rejectedVerdictDoesNotRegisterPending() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        SideEffectTool tool = new SideEffectTool();

        ToolInvocationGate gate = new ToolInvocationGate(List.of(tool), allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL 需审批"),
                new GrantBudget(), new FreshLedger(), approvals,
                BigDecimal.valueOf(0.5), (t, r) -> false,
                (t, h) -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.REJECTED,
                null, null, null, java.time.Clock.systemUTC(), null, null, null,
                new ApprovalRequestSettings("PARENT_OR_TEACHER", Duration.ofMinutes(15)));

        var result = gate.invokeAll(TENANT, task(), call());

        assertThat(result.rejectedTools()).contains(TOOL);
        assertThat(approvals.requests)
                .as("明确拒绝不需要待办 —— 堆一堆待决记录只会淹没真正要处理的事")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 未装配登记参数：工具仍被拒绝（安全姿态不变），但不产生待办")
    void withoutSettingsToolIsStillRejected() {
        RecordingApprovalPort approvals = new RecordingApprovalPort();
        SideEffectTool tool = new SideEffectTool();

        var result = gate(approvals, tool, null).invokeAll(TENANT, task(), call());

        assertThat(result.rejectedTools())
                .as("⚠️ 登记不了待办 → 必须拒绝（而非挂起）："
                        + "挂起的前提是「人能在审批台看到并批准」，登记不了 = 没人能批 "
                        + "= Run 永久悬在 WAITING_APPROVAL")
                .contains(TOOL);
        assertThat(result.hasParked()).isFalse();
        assertThat(tool.executes.value).isZero();
        assertThat(approvals.requests).isEmpty();
    }
}
