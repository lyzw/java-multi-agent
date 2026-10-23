package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
 * 闸门幂等键测试（P4-D08 R-2）。
 *
 * <p><b>本类存在的唯一理由</b>：P4-D08 修复的 R-2 是一个
 * <b>「代码在、测试全绿、运行时跨 Run 撞键」</b>的缺陷——
 * {@code ToolInvocationGate.buildIdempotencyKey} 把 runId 硬编码为
 * {@code "pending-run"}，而 {@code tool_operation} 的唯一约束是
 * {@code UNIQUE (tenant_id, idempotency_key)}（<b>租户级，不含 run 维度</b>），
 * 且账本命中重复键时<b>复用既有 operation</b>（不新建）。
 *
 * <p>后果：同租户下不同 Run 的同名工具 + 同 callId 撞键 →
 * 新 Run 的调用被记到旧 Run 的 operation 上 →
 * <b>对账链路错乱、副作用语义破裂（重复副作用 = Sev-0）</b>。
 *
 * <p>⚠️ <b>为什么 {@code IdempotencyProtocolTest} 抓不到</b>：
 * 它测的是 {@code IdempotencyKey} <b>类本身</b>的构造规则，
 * 而 R-2 的缺陷在<b>闸门是否把真实 runId 传进去</b>——
 * 这是<b>调用方</b>的责任，类测试<b>原理上</b>无法覆盖。
 * 这正是本项目「行为测试 ≠ 连通性测试」的又一实例：
 * <b>被测单元正确，调用方传错参数，全绿照旧</b>。
 *
 * <p>⚠️ 本类只断言「键的内容」，不断言执行结果——
 * 执行路径的断言在 {@code RunExecutionLoopTest} 中。
 */
class ToolIdempotencyKeyTest {

    private static final String TENANT = "t-a";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 记录传入账本的幂等键。 */
    private static final class KeyCapturingLedger implements RunToolLedgerPort {
        final List<String> capturedKeys = new java.util.ArrayList<>();
        final Map<String, String> operationsByKey = new LinkedHashMap<>();

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            capturedKeys.add(idempotencyKey);
            String opId = UUID.randomUUID().toString();
            operationsByKey.put(idempotencyKey, opId);
            return new Registration(opId, ToolOperationState.IN_FLIGHT, 0, 0L);
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
            String opId = operationsByKey.get(idempotencyKey);
            return opId == null ? Optional.empty()
                    : Optional.of(new Registration(opId, ToolOperationState.IN_FLIGHT, 0, 0L));
        }
    }

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

    /**
     * 恒「无审批」的审批端口替身。
     *
     * <p>⚠️ 本测试的工具替身风险等级一律 READ → 审批策略判「不需审批」，
     * 故此端口<b>不会被调用</b>；实现它只为满足构造器契约。
     */
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

    /** 只读工具替身（不需审批）。 */
    private static final class ReadTool implements ToolHandler {
        @Override public String toolName() { return "get_student_profile"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c,
                Map<String, Object> a) {
            return ToolResult.of("ok");
        }
    }

    /** 构造闸门。 */
    private static ToolInvocationGate gate(KeyCapturingLedger ledger,
            List<ToolHandler> handlers) {
        // ⚠️ 审批结论来源给「恒批准」：本测试只测幂等键内容，
        //    工具替身风险等级为 READ → 策略判「不需审批」→ 该来源不会被调用。
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

    /** 构造一个已领取任务。 */
    private static RunClaimPort.LeasedTask task(String runId) {
        return new RunClaimPort.LeasedTask(runId, 1L,
                Instant.now().plusSeconds(60), 1);
    }

    // ── T-1：record_weakness 的键含真实 runId ───────────────────────

    @Nested
    @DisplayName("T-1 闸门传入的幂等键含真实 runId")
    class KeyCarriesRunId {

        @Test
        @DisplayName("⚠️⚠️ record_weakness 键含真实 runId（修复前是 \"pending-run\"）")
        void recordWeaknessKeyContainsRealRunId() {
            String runId = UUID.randomUUID().toString();
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger,
                    List.of(new RecordWeaknessLikeTool()));

            gate.invokeAll(TENANT, task(runId), Map.of(
                    "call-1", new ModelResponse.ToolCallRequest("record_weakness",
                            Map.of("studentId", "stu_0123456789abcdef",
                                    "knowledgePoint", "一元二次方程"),
                            "call-1")));

            assertThat(ledger.capturedKeys)
                    .as("⚠️⚠️ 闸门必须把真实 runId 写进幂等键")
                    .hasSize(1);
            String key = ledger.capturedKeys.get(0);
            assertThat(key)
                    .as("⚠️⚠️ 键必须含真实 runId——"
                            + "tool_operation 的 UNIQUE(tenant_id, idempotency_key) 是租户级，"
                            + "缺 run 维度会让跨 Run 撞键并复用旧 operation")
                    .contains(runId);
            assertThat(key)
                    .as("⚠️⚠️ 绝不能出现占位符（修复前为 \"pending-run\"）")
                    .doesNotContain("pending-run");
            assertThat(key)
                    .as("✅ 命名空间须为 tool.record_weakness（对账服务按此前缀解析）")
                    .startsWith("tool.record_weakness:");
        }

        @Test
        @DisplayName("⚠️⚠️ 跨 Run 不撞键（同 student + 同 knowledgePoint）")
        void differentRunsProduceDifferentKeys() {
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger,
                    List.of(new RecordWeaknessLikeTool()));

            String runA = UUID.randomUUID().toString();
            String runB = UUID.randomUUID().toString();
            Map<String, Object> args = Map.of(
                    "studentId", "stu_0123456789abcdef",
                    "knowledgePoint", "一元二次方程");

            gate.invokeAll(TENANT, task(runA), Map.of(
                    "call-1", new ModelResponse.ToolCallRequest("record_weakness", args, "call-1")));
            gate.invokeAll(TENANT, task(runB), Map.of(
                    "call-1", new ModelResponse.ToolCallRequest("record_weakness", args, "call-1")));

            assertThat(ledger.capturedKeys)
                    .as("⚠️⚠️ 跨 Run 必须产生两个不同的键")
                    .hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 跨 Run 撞键 = 新 Run 的调用被记到旧 Run 的 operation 上 = Sev-0")
                    .isNotEqualTo(ledger.capturedKeys.get(1));
        }

        @Test
        @DisplayName("✅ 其他工具的键含 runId 且命名空间为 tool.invoke")
        void otherToolKeyContainsRunId() {
            String runId = UUID.randomUUID().toString();
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));

            gate.invokeAll(TENANT, task(runId), Map.of(
                    "call-7", new ModelResponse.ToolCallRequest("get_student_profile",
                            Map.of("studentId", "stu_0123456789abcdef"), "call-7")));

            assertThat(ledger.capturedKeys).hasSize(1);
            String key = ledger.capturedKeys.get(0);
            assertThat(key)
                    .as("⚠️⚠️ 其他工具的键同样必须含 runId"
                            + "（同工具名 + 同 callId 跨 Run 会撞键）")
                    .contains(runId);
            assertThat(key)
                    .as("✅ 命名空间须为 tool.invoke（不得复用 run.create）")
                    .startsWith("tool.invoke:");
        }
    }

    /** 名为 record_weakness 的只读工具替身（避免审批分支干扰，只测键）。 */
    private static final class RecordWeaknessLikeTool implements ToolHandler {
        @Override public String toolName() { return "record_weakness"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c,
                Map<String, Object> a) {
            return ToolResult.of("ok");
        }
    }
}
