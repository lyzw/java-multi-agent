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

import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.orchestration.DelegationContext;
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
 * 多级 Agent 的工具幂等键分域测试（第十七批 · MA-T 系列的副作用面）。
 *
 * <p><b>本类存在的唯一理由</b>：修复前工具幂等键的形状是
 * {@code run_id/tool_name/call_id} —— <b>不含「当前执行中的 Agent」</b>。
 * 而账本命中重复键时<b>复用既有 operation、不再次执行工具</b>，
 * 于是：
 *
 * <ul>
 *   <li>父 Agent 与子 Agent 调用<b>同名工具 + 同名 model call id</b>
 *       （模型通常都叫 {@code call_1}）→ 算出同一个键 →
 *       后者被当成前者的幂等重放 → <b>那次工具调用根本没发生</b>，
 *       且模型拿到的还是<b>另一个 Agent 的结果</b>；</li>
 *   <li>两个<b>兄弟</b>子 Agent 同理 —— 第二个子 Agent 的调用被第一个的记录吞掉。</li>
 * </ul>
 *
 * <p>⚠️ <b>这个缺陷在单 Agent 时代不可见</b>：那时「谁在调用」只有一个答案，
 * 键里没有它也不影响什么。多级递归把它变成了生产事故面。
 *
 * <p>⚠️ <b>为什么不能只断言「键里含 agent 字样」</b>：
 * 那种断言在「所有调用都被塞进同一个键」时同样会绿。
 * 本类的断言一律是<b>成对</b>的：不同的 Agent 必须产生<b>不同</b>的键，
 * 同一个 Agent 的重复投递必须产生<b>同一个</b>键 —— 两头都钉住才算数。
 *
 * <p>⚠️ 本类走<b>既有执行链路</b>（未装配 CAP 执行流）：
 * 幂等键是两条链路<b>共用</b>的账本键，只在一条链路上分域等于没修。
 * CAP 链路的分域由 {@code CapExecutionFlowTest} 的对应用例覆盖。
 */
class NestedToolIdempotencyTest {

    private static final String TENANT = "t-nested-idem";
    private static final String TOOL = "get_student_profile";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 记录传入账本的幂等键（并模拟「同键复用既有 operation」的账本语义）。 */
    private static final class KeyCapturingLedger implements RunToolLedgerPort {
        final List<String> capturedKeys = new ArrayList<>();
        final Map<String, String> operationsByKey = new LinkedHashMap<>();

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            capturedKeys.add(idempotencyKey);
            String opId = UUID.randomUUID().toString();
            operationsByKey.putIfAbsent(idempotencyKey, opId);
            boolean created = !operationsByKey.get(idempotencyKey).equals(opId);
            return new Registration(operationsByKey.get(idempotencyKey),
                    ToolOperationState.IN_FLIGHT, 0, 0L, created);
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
                    : Optional.of(new Registration(opId, ToolOperationState.IN_FLIGHT, 0, 0L, false));
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

    /** 恒「无审批」的审批端口替身（工具风险等级为 READ → 不会被调用）。 */
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
        @Override public String toolName() { return TOOL; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c, Map<String, Object> a) {
            return ToolResult.of("ok");
        }
    }

    /** 名为 record_weakness 的工具替身（业务事实幂等口径）。 */
    private static final class RecordWeaknessTool implements ToolHandler {
        @Override public String toolName() { return "record_weakness"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext c, Map<String, Object> a) {
            return ToolResult.of("ok");
        }
    }

    /** 构造闸门（既有执行链路：未装配 CAP 执行流）。 */
    private static ToolInvocationGate gate(KeyCapturingLedger ledger,
            List<ToolHandler> handlers) {
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

    /** 构造子 Agent 的执行作用域。 */
    private static ExecutionScope scope(String agentId, int version) {
        return new ExecutionScope(agentId, version, "fp-" + agentId + "-" + version);
    }

    /** 构造一次同名工具 + 同名 model call id 的调用。 */
    private static Map<String, ModelResponse.ToolCallRequest> sameToolSameCallId() {
        return Map.of("call-1", new ModelResponse.ToolCallRequest(TOOL,
                Map.of("studentId", "stu_0123456789abcdef"), "call-1"));
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("父子 / 兄弟 Agent 不得互相吞掉调用")
    class AgentDomainIsolation {

        @Test
        @DisplayName("⚠️⚠️ 父 Agent 与子 Agent 同名工具 + 同名 call id → 不同键")
        void parentAndChildProduceDifferentKeys() {
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            // 父 Agent 在**根路径**执行（无作用域），子 Agent 在**作用域**下执行。
            gate.invokeAll(TENANT, task(runId), sameToolSameCallId());
            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-child", 1),
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));

            assertThat(ledger.capturedKeys)
                    .as("⚠️⚠️ 必须产生两个键")
                    .hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 父子撞键 = 子 Agent 那次调用被当成父的幂等重放 → "
                            + "工具根本没执行，模型还拿到了父的结果")
                    .isNotEqualTo(ledger.capturedKeys.get(1));
            assertThat(ledger.capturedKeys.get(1))
                    .as("✅ 子 Agent 的键必须含自己的身份（agentId@version）")
                    .contains("agent-child@1");
        }

        @Test
        @DisplayName("⚠️⚠️ 两个兄弟子 Agent 同名工具 + 同名 call id → 不同键")
        void siblingAgentsProduceDifferentKeys() {
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-left", 1),
                    new DelegationContext("agent-left", 1, 2, "inv-left-1"));
            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-right", 1),
                    new DelegationContext("agent-right", 1, 2, "inv-right-1"));

            assertThat(ledger.capturedKeys).hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 兄弟撞键 = 第二个子 Agent 的调用被第一个的记录吞掉")
                    .isNotEqualTo(ledger.capturedKeys.get(1));
        }

        @Test
        @DisplayName("⚠️⚠️ 同一 agentId 的不同 release → 不同键")
        void differentReleasesProduceDifferentKeys() {
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-child", 1),
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));
            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-child", 2),
                    new DelegationContext("agent-child", 2, 2, "inv-child-2"));

            assertThat(ledger.capturedKeys).hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 不同 release 是不同的执行主体（闭包/工具集可能不同），"
                            + "键不区分版本会让新 release 继承旧 release 的幂等记录")
                    .isNotEqualTo(ledger.capturedKeys.get(1));
        }
    }

    @Nested
    @DisplayName("幂等语义不得被修复破坏")
    class IdempotencyPreserved {

        @Test
        @DisplayName("✅ 同一 Agent 重复投递 → 同一个键（不得变成重复副作用）")
        void sameAgentRepeatDeliveryKeepsSameKey() {
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-child", 1),
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));
            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    scope("agent-child", 1),
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));

            assertThat(ledger.capturedKeys)
                    .as("⚠️⚠️ 同一 Agent 的同一次调用重投必须是同一个键 —— "
                            + "键若每次都变，幂等就退化成「每次都执行一遍」")
                    .hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .isEqualTo(ledger.capturedKeys.get(1));
        }

        @Test
        @DisplayName("✅ 根路径（无作用域）的键与历史逐字符一致")
        void rootPathKeyShapeUnchanged() {
            // ⚠️ 在途的 tool_operation 行是按旧键写的：
            //    单 Agent 路径若改了键形状，那些行再也匹配不上 →
            //    重放会被当成新调用 → **重复副作用**。
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            gate.invokeAll(TENANT, task(runId), sameToolSameCallId());

            assertThat(ledger.capturedKeys).hasSize(1);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 单 Agent 路径的键必须与历史完全一致")
                    .isEqualTo("tool.invoke:" + runId + "/" + TOOL + "/call-1");
        }

        @Test
        @DisplayName("✅ record_weakness 不按 Agent 分域（业务事实全局唯一）")
        void recordWeaknessIgnoresAgentDomain() {
            // ⚠️ record_weakness 记的是「某学生的某知识点」这条**业务事实**，
            //    全局唯一：父 Agent 与子 Agent 记同一条，后写的那次被幂等拦下是**正确**的。
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new RecordWeaknessTool()));
            String runId = UUID.randomUUID().toString();
            Map<String, Object> args = Map.of(
                    "studentId", "stu_0123456789abcdef",
                    "knowledgePoint", "一元二次方程");

            gate.invokeAll(TENANT, task(runId), Map.of(
                    "call-1", new ModelResponse.ToolCallRequest("record_weakness", args, "call-1")));
            gate.invokeAll(TENANT, task(runId), Map.of(
                    "call-1", new ModelResponse.ToolCallRequest("record_weakness", args, "call-1")),
                    scope("agent-child", 1),
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));

            assertThat(ledger.capturedKeys).hasSize(2);
            assertThat(ledger.capturedKeys.get(0))
                    .as("✅ record_weakness 是业务事实，父子记同一条应被幂等拦下")
                    .isEqualTo(ledger.capturedKeys.get(1));
            assertThat(ledger.capturedKeys.get(0))
                    .as("✅ 命名空间须为 tool.record_weakness（对账服务按此前缀解析）")
                    .startsWith("tool.record_weakness:");
        }
    }

    @Nested
    @DisplayName("委派上下文单独存在时也必须分域")
    class DelegationContextOnly {

        @Test
        @DisplayName("⚠️ 只有委派上下文（无执行作用域）→ 键仍含 Agent 维度")
        void delegationContextAloneStillDomainsTheKey() {
            // ⚠️ 执行作用域与委派上下文是**两件事**：
            //    前者回答「按哪个 release 授权」，后者回答「当前 Agent 是谁」。
            //    若只看作用域，委派上下文这条线索就被丢掉了 ——
            //    而「当前 Agent 是谁」正是幂等键要分域的那件事。
            KeyCapturingLedger ledger = new KeyCapturingLedger();
            ToolInvocationGate gate = gate(ledger, List.of(new ReadTool()));
            String runId = UUID.randomUUID().toString();

            gate.invokeAll(TENANT, task(runId), sameToolSameCallId(),
                    null,
                    new DelegationContext("agent-child", 1, 2, "inv-child-1"));

            assertThat(ledger.capturedKeys).hasSize(1);
            assertThat(ledger.capturedKeys.get(0))
                    .as("⚠️⚠️ 键必须按「当前执行中的 Agent」分域")
                    .contains("agent-child@1");
        }
    }
}
