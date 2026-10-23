package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 闸门授权上下文测试（P4-D08 R-4）。
 *
 * <p><b>本类存在的唯一理由</b>：P4-D08 修复的 R-4 是一个
 * <b>「代码在、测试全绿、运行时工具永不被执行」</b>的缺陷——
 * {@code ToolInvocationGate.systemContext} 构造授权上下文时传了
 * <b>空角色集</b>（{@code Set.of()}），而授权实现按<b>角色</b>判定：
 *
 * <pre>
 * context.roles().stream()
 *         .map(ROLE_PERMISSIONS::get)
 *         .filter(Objects::nonNull)
 *         .anyMatch(actions -&gt; actions.contains(action));
 * </pre>
 *
 * <p>空角色集 → {@code anyMatch} 恒 {@code false} → 授权必然被拒 →
 * 闸门记「无权调用该工具」→ <b>工具永远不执行</b>。
 *
 * <p>⚠️ <b>为什么既有测试抓不到</b>：worker 模块的闸门测试用
 * {@code allowAll()} / {@code denyAll()} 授权替身，
 * <b>根本不检查上下文里带了什么角色</b>；
 * 而 {@code Roles.WORKER} 含 {@code TOOL_INVOKE} 是一条
 * <b>与本缺陷正交</b>的事实（角色定义正确，错的是<b>调用方没传</b>）。
 * 二者都绿，缺陷照样存在。
 *
 * <p>⚠️ <b>本类与 {@code ToolIdempotencyKeyTest} 的分工</b>：
 * 前者断言「键的内容」，本类断言「授权收到的上下文」——
 * <b>都是调用方责任</b>，类测试原理上覆盖不到。
 */
class ToolGateAuthorizationContextTest {

    private static final String TENANT = "t-a";

    // ── 捕获授权上下文的替身 ───────────────────────────────────────

    /**
     * 捕获闸门传入的授权上下文。
     *
     * <p>⚠️ 行为与真实实现一致：<b>按角色集合判定</b>，
     * 空角色集 → 拒绝（这正是 R-4 的判定机理）。
     */
    private static final class ContextCapturingAuthorization
            implements AuthorizationService {
        final List<TenantContext> seen = new ArrayList<>();

        @Override public boolean isAuthorized(TenantContext c, String a, String r) {
            seen.add(c);
            // ⚠️ 与 RoleBasedAuthorizationService 同机理：按角色集合判定
            return c.roles().contains(AuthorizationService.Roles.WORKER)
                    && AuthorizationService.Actions.TOOL_INVOKE.equals(a);
        }

        @Override public void requireAuthorized(TenantContext c, String a, String r) { }
        @Override public long currentAuthzVersion(String t, String p) { return 0L; }
        @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
    }

    /** 内存账本替身。 */
    private static final class SimpleLedger implements RunToolLedgerPort {
        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            return new Registration(UUID.randomUUID().toString(),
                    ToolOperationState.IN_FLIGHT, 0, 0L);
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
        @Override public Optional<Registration> findByIdempotencyKey(
                String tenantId, String idempotencyKey) {
            return Optional.empty();
        }
    }

    /** 恒批准的预算替身。 */
    private static final BudgetPort GRANT_BUDGET = new BudgetPort() {
        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }
        @Override public boolean settle(String tenantId, String reservationId,
                BigDecimal actualAmount, String modelId) { return true; }
        @Override public boolean release(String tenantId, String reservationId,
                String reason) { return true; }
        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.of(new Reservation("res-1", "run-1", operationId,
                    BigDecimal.ONE, null, "RESERVED", "CNY"));
        }
        @Override public Optional<AccountSnapshot> accountSnapshot(String tenantId,
                String currency) { return Optional.empty(); }
        @Override public java.util.List<OrphanedReservation> findOrphaned(
                String tenantId) { return List.of(); }
        @Override public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    };

    /** 恒「无审批」的审批端口替身。 */
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
                String operationId) { return Optional.empty(); }
    };

    /** 只读工具替身。 */
    private static final class ReadTool implements ToolHandler {
        @Override public String toolName() { return "get_student_profile"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c,
                Map<String, Object> a) { return ToolResult.of("ok"); }
    }

    // ── T-1：授权上下文带 WORKER 角色 ──────────────────────────────

    @Nested
    @DisplayName("T-1 闸门授权上下文携带 WORKER 角色")
    class AuthorizationContextCarriesWorkerRole {

        @Test
        @DisplayName("⚠️⚠️ 授权收到的上下文必须含 WORKER 角色（R-4 直接防线）")
        void authorizationSeesWorkerRole() {
            ContextCapturingAuthorization authz = new ContextCapturingAuthorization();
            ToolInvocationGate gate = new ToolInvocationGate(
                    List.of(new ReadTool()), authz,
                    handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                            .ApprovalDecision.notRequired(),
                    GRANT_BUDGET, new SimpleLedger(), NO_APPROVAL,
                    BigDecimal.ONE, (t, r) -> false,
                    (tenantId, handler) ->
                            cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                                    .ApprovalSource.Verdict.APPROVED);

            String runId = UUID.randomUUID().toString();
            gate.invokeAll(TENANT,
                    new RunClaimPort.LeasedTask(runId, 1L,
                            Instant.now().plusSeconds(60), 1),
                    Map.of("call-1", new ModelResponse.ToolCallRequest(
                            "get_student_profile",
                            Map.of("studentId", "stu_0123456789abcdef"), "call-1")));

            assertThat(authz.seen)
                    .as("⚠️⚠️ 闸门必须真的调用授权（红线 9：执行时重新授权）")
                    .isNotEmpty();
            TenantContext seen = authz.seen.get(0);
            assertThat(seen.roles())
                    .as("⚠️⚠️ 授权上下文必须携带 WORKER 角色——"
                            + "授权按角色判定（RoleBasedAuthorizationService），"
                            + "空角色集 → isAuthorized 恒 false → 工具永不被执行（R-4）")
                    .contains(AuthorizationService.Roles.WORKER);
        }

        @Test
        @DisplayName("⚠️⚠️ 工具因授权被拒时不得执行（R-4 的可观测后果）")
        void toolNotExecutedWhenAuthorizationDenied() {
            // ⚠️ 这个替身**永远拒绝**（模拟「角色集为空」时的真实后果）
            AuthorizationService denyAll = new AuthorizationService() {
                @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                    return false;
                }
                @Override public void requireAuthorized(TenantContext c, String a, String r) { }
                @Override public long currentAuthzVersion(String t, String p) { return 0L; }
                @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
            };
            ReadTool tool = new ReadTool();
            ToolInvocationGate gate = new ToolInvocationGate(
                    List.of(tool), denyAll,
                    handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                            .ApprovalDecision.notRequired(),
                    GRANT_BUDGET, new SimpleLedger(), NO_APPROVAL,
                    BigDecimal.ONE, (t, r) -> false,
                    (tenantId, handler) ->
                            cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                                    .ApprovalSource.Verdict.APPROVED);

            var result = gate.invokeAll(TENANT,
                    new RunClaimPort.LeasedTask(UUID.randomUUID().toString(), 1L,
                            Instant.now().plusSeconds(60), 1),
                    Map.of("call-1", new ModelResponse.ToolCallRequest(
                            "get_student_profile",
                            Map.of("studentId", "stu_0123456789abcdef"), "call-1")));

            assertThat(result.succeededCount())
                    .as("⚠️ 授权被拒时工具绝不能执行（无授权的副作用 = Sev-0）")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }
    }
}
