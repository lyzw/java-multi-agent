package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P0-1a 回归测试：<b>能力闭包必须在强制层生效</b>。
 *
 * <p><b>缺陷背景（P0-1）</b>：闭包校验只存在于 CAP 授权链路，
 * 而生产默认走<b>既有链路</b>（{@code invokeAllLegacy}）——
 * 该方法全文件 grep {@code closure} 零命中。
 * 于是「能力闭包」只在<b>呈现层</b>生效（模型看不到越权工具），
 * <b>强制层没有这道校验</b>：任何已注册工具都可被任意 Agent 调用。
 *
 * <p><b>断言要点</b>：不能只断言「被拒绝了」——
 * 必须断言<b>拒绝的是「不在闭包内的那个工具」</b>，
 * 且<b>闭包内的工具照常执行</b>（否则「一律拒绝」也能让这个测试变绿，
 * 而那等于把平台改成不可用）。
 */
class ToolInvocationGateClosureGuardTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final String AGENT_ID = "math-tutor";

    /** 闭包里声明的工具。 */
    private static final String DECLARED = "get_student_profile";
    /** 未在闭包里声明、但**已注册**的工具（P0-1 的越权面）。 */
    private static final String UNDECLARED = "record_weakness";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 记录执行次数的工具替身。 */
    private static final class CountingTool implements ToolHandler {
        private final String name;
        final AtomicInteger executes = new AtomicInteger();

        CountingTool(String name) {
            this.name = name;
        }

        @Override public String toolName() { return name; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext context,
                                            Map<String, Object> arguments) {
            executes.incrementAndGet();
            return ToolResult.of("ok");
        }
    }

    /** 只声明 {@link #DECLARED} 的闭包解析器。 */
    private static final class SingleToolClosureResolver implements ClosureResolver {
        @Override public EffectiveClosure resolve(String tenantId, String agentId, int version) {
            return closure(agentId, version);
        }

        @Override public EffectiveClosure resolveCurrent(String tenantId, String agentId) {
            return closure(agentId, 1);
        }

        private static EffectiveClosure closure(String agentId, int version) {
            return new EffectiveClosure(agentId, version,
                    List.of(new EffectiveClosure.SkillBinding("skill-profile-view", 1,
                            CapabilityRiskLevel.READ, "fp", List.of(DECLARED), "[]", null)),
                    List.of(new EffectiveClosure.ToolBinding(DECLARED, 1, CapToolKind.NATIVE,
                            CapabilityRiskLevel.READ, "fp", "[]", null, null)),
                    List.of(), List.of(),
                    cn.studykid.multiagent.core.orchestration.OrchestrationBinding.leaf());
        }
    }

    /** 永远解析不出闭包的解析器。 */
    private static final class BrokenClosureResolver implements ClosureResolver {
        @Override public EffectiveClosure resolve(String tenantId, String agentId, int version) {
            throw new IllegalStateException("能力目录为空");
        }

        @Override public EffectiveClosure resolveCurrent(String tenantId, String agentId) {
            throw new IllegalStateException("能力目录为空");
        }
    }

    /** 能力快照端口：有快照时给出 agentId。 */
    private static final class StubSnapshots implements CapabilitySnapshotPort {
        private final boolean present;

        StubSnapshots(boolean present) {
            this.present = present;
        }

        @Override public boolean save(CapabilitySnapshot snapshot) {
            return true;
        }

        @Override public Optional<CapabilitySnapshot> find(String tenantId, String runId) {
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new CapabilitySnapshot(RUN_ID, tenantId, AGENT_ID,
                    "closure-fp", 1, 0L, "config-fp", 1L, Instant.EPOCH));
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

    private static ToolInvocationGate gate(CapabilityClosureGuard guard,
                                            List<ToolHandler> tools) {
        return new ToolInvocationGate(tools, allowAll(),
                h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                new GrantBudget(), new FreshLedger(), null,
                BigDecimal.valueOf(0.5), (t, r) -> false,
                (t, h) -> cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.FAIL_CLOSED,
                null, null, null, java.time.Clock.systemUTC(), null, null, guard);
    }

    private static Map<String, ModelResponse.ToolCallRequest> call(String toolName) {
        Map<String, ModelResponse.ToolCallRequest> calls = new java.util.LinkedHashMap<>();
        calls.put("c1", new ModelResponse.ToolCallRequest(toolName, Map.of(), "c1"));
        return calls;
    }

    private static cn.studykid.multiagent.ports.persistence.RunClaimPort.LeasedTask task() {
        return new cn.studykid.multiagent.ports.persistence.RunClaimPort.LeasedTask(
                RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 越权工具（已注册但不在闭包内）被拒绝；闭包内的工具照常执行")
    void undeclaredToolIsRejected() {
        CountingTool declared = new CountingTool(DECLARED);
        CountingTool undeclared = new CountingTool(UNDECLARED);
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new SingleToolClosureResolver(), new StubSnapshots(true),
                CapabilityClosureGuard.Mode.ENFORCE);

        var result = gate(guard, List.of(declared, undeclared))
                .invokeAll(TENANT, task(), call(UNDECLARED));

        assertThat(result.rejectedTools())
                .as("未在闭包内声明的工具必须被拒绝（P0-1 的洞就在这里）")
                .contains(UNDECLARED);
        assertThat(undeclared.executes.get())
                .as("越权工具绝不能被执行")
                .isZero();
        assertThat(declared.executes.get())
                .as("闭包内的工具不受影响（否则「一律拒绝」也能让测试变绿）")
                .isZero();
    }

    @Test
    @DisplayName("✅ 闭包内的工具：正常执行，不被闭包闸门误伤")
    void declaredToolStillExecutes() {
        CountingTool declared = new CountingTool(DECLARED);
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new SingleToolClosureResolver(), new StubSnapshots(true),
                CapabilityClosureGuard.Mode.ENFORCE);

        var result = gate(guard, List.of(declared))
                .invokeAll(TENANT, task(), call(DECLARED));

        assertThat(result.rejectedTools()).isEmpty();
        assertThat(declared.executes.get())
                .as("闭包内的工具必须照常执行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ 闭包解析不出来（能力目录未装载）→ 强制模式下 fail-closed")
    void unresolvableClosureFailsClosed() {
        CountingTool declared = new CountingTool(DECLARED);
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new BrokenClosureResolver(), new StubSnapshots(true),
                CapabilityClosureGuard.Mode.ENFORCE);

        var result = gate(guard, List.of(declared))
                .invokeAll(TENANT, task(), call(DECLARED));

        assertThat(result.rejectedTools())
                .as("无法证明已声明 ≠ 已声明（fail-closed）")
                .contains(DECLARED);
        assertThat(declared.executes.get()).isZero();
    }

    @Test
    @DisplayName("⚠️ 没有能力快照（定位不到 Agent）→ 强制模式下 fail-closed")
    void missingSnapshotFailsClosed() {
        CountingTool declared = new CountingTool(DECLARED);
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new SingleToolClosureResolver(), new StubSnapshots(false),
                CapabilityClosureGuard.Mode.ENFORCE);

        var result = gate(guard, List.of(declared))
                .invokeAll(TENANT, task(), call(DECLARED));

        assertThat(result.rejectedTools()).contains(DECLARED);
        assertThat(declared.executes.get()).isZero();
    }

    @Test
    @DisplayName("⚠️ WARN 模式：只告警不拒绝（过渡期），但执行仍发生")
    void warnModeAllowsButLogs() {
        CountingTool undeclared = new CountingTool(UNDECLARED);
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new SingleToolClosureResolver(), new StubSnapshots(true),
                CapabilityClosureGuard.Mode.WARN);

        var result = gate(guard, List.of(undeclared))
                .invokeAll(TENANT, task(), call(UNDECLARED));

        assertThat(result.rejectedTools())
                .as("WARN 模式不拒绝（能力目录未装载时的过渡期）")
                .isEmpty();
        assertThat(undeclared.executes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ 子 Agent 路径：按子 Agent 自己的固定 release 解析闭包")
    void childAgentUsesItsOwnClosure() {
        CountingTool undeclared = new CountingTool(UNDECLARED);
        // ⚠️ 子 Agent 的闭包**不含**该工具 → 必须被拒；
        //    若错误地沿用根 Agent 的闭包，判定结果会相反。
        CapabilityClosureGuard guard = new CapabilityClosureGuard(
                new ChildClosureResolver(), new StubSnapshots(true),
                CapabilityClosureGuard.Mode.ENFORCE);

        var result = gate(guard, List.of(undeclared)).invokeAll(TENANT, task(),
                call(UNDECLARED), new ExecutionScope("child-agent", 3, "fp"), null);

        assertThat(result.rejectedTools()).contains(UNDECLARED);
        assertThat(undeclared.executes.get()).isZero();
    }

    /** 子 Agent 闭包解析器：固定版本 3，闭包内<b>不含</b>目标工具。 */
    private static final class ChildClosureResolver implements ClosureResolver {
        @Override public EffectiveClosure resolve(String tenantId, String agentId, int version) {
            return new EffectiveClosure(agentId, version,
                    List.of(new EffectiveClosure.SkillBinding("skill-other", 1,
                            CapabilityRiskLevel.READ, "fp", List.of("other_tool"), "[]", null)),
                    List.of(new EffectiveClosure.ToolBinding("other_tool", 1, CapToolKind.NATIVE,
                            CapabilityRiskLevel.READ, "fp", "[]", null, null)),
                    List.of(), List.of(),
                    cn.studykid.multiagent.core.orchestration.OrchestrationBinding.leaf());
        }

        @Override public EffectiveClosure resolveCurrent(String tenantId, String agentId) {
            throw new IllegalStateException("子 Agent 路径不得按「当前已发布版本」解析");
        }
    }
}
