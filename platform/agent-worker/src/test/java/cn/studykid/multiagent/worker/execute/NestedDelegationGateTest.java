package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import cn.studykid.multiagent.application.cap.CapExecutionFlow;
import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.cap.DecisionAuditService;
import cn.studykid.multiagent.application.orchestration.DelegationExecutor;
import cn.studykid.multiagent.application.orchestration.DelegationGuard;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.orchestration.DelegationContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner.ChildAgentRequest;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner.ChildAgentResult;
import cn.studykid.multiagent.ports.orchestration.ResultContractProvider;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort.TerminalWriteOutcome;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 嵌套委派的闸门接线测试（多级 Agent v1.1 · 第十五批 · C15-1）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：此前闸门构造委派命令时，
 * <b>父身份、深度、父调用标识恒为「根 Agent / 1 / null」</b> ——
 * 于是<b>子 Agent 发起的委派被按根 Agent 的编排声明判定</b>。
 * 后果是三条真实缺陷：
 * <ol>
 *   <li><b>越权路径</b>：子 Agent 能借根 Agent 的声明，把工作交给
 *       「根声明过、而自己没声明」（甚至自己根本没有委派能力）的 Agent；</li>
 *   <li><b>深度上限失效</b>：深度恒为 1 → 任意深度的嵌套委派都被当成深度 2 判定；</li>
 *   <li><b>幂等键撞号</b>：invocationId 派生的父调用标识恒为 null
 *       → 不同子 Agent 的同名 tool call 派生出同一个 id → 后者被当成幂等重放，
 *       <b>第二次委派根本没发生</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>「全都拒」不能算通过</b>：本类同时钉住
 * 「按当前 Agent 放行」「按当前 Agent 拒绝」「深度正确」「深度超限被拒」
 * 「父子链完整」「不同父不撞号」「上下文不一致即拒绝」两侧。
 *
 * <p>⚠️ <b>每条用例都必须是「修复前红、修复后绿」</b>：为此刻意让
 * {@link #ROOT} 也声明了 {@link #SHARED} ——
 * 这样「幂等撞号」那条用例在修复前<b>不会被拓扑检查挡住</b>，
 * 失败原因精确落在幂等分支上，而不是被别的判定顺手拦掉。
 */
class NestedDelegationGateTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String ROOT = "root-agent";
    /** 只声明了 SHARED 的子 Agent（有自己的委派能力）。 */
    private static final String SUB_A = "sub-a";
    /** 完全没有委派能力（LEAF）的子 Agent。 */
    private static final String SUB_B = "sub-b";
    /** 另一个只声明了 SHARED 的子 Agent（用于幂等撞号用例）。 */
    private static final String SUB_C = "sub-c";
    /** 委派深度上限为 2 的子 Agent（用于深度超限用例）。 */
    private static final String SUB_D = "sub-d";
    /** 根声明过、但子 Agent 都没声明的目标。 */
    private static final String ROOT_ONLY = "root-only-target";
    /** 根与子 Agent 都声明过的目标。 */
    private static final String SHARED = "shared-target";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    /**
     * 根的编排。
     *
     * <p>⚠️ <b>根也声明了 {@link #SHARED}</b>：这是为了让「幂等撞号」用例
     * 在修复前<b>真的走到幂等分支</b>（而不是先被拓扑检查拒掉），
     * 使该用例成为一条精确的反证。
     */
    private static final String ROOT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"sub-a","version":1},
                                                   {"id":"sub-b","version":1},
                                                   {"id":"sub-c","version":1},
                                                   {"id":"sub-d","version":1},
                                                   {"id":"root-only-target","version":1},
                                                   {"id":"shared-target","version":1}],
             "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** SUB_A 的编排：只声明了 SHARED。 */
    private static final String SUB_A_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"shared-target","version":1}],
             "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** SUB_C 的编排：与 SUB_A 同形（用于不同父的撞号用例）。 */
    private static final String SUB_C_ORCHESTRATION = SUB_A_ORCHESTRATION;

    /** SUB_D 的编排：委派深度上限只有 2（自身深度 2 → 再跳一层即 3 > 2）。 */
    private static final String SUB_D_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"shared-target","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":8,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** SUB_B 是叶子：没有任何子声明。 */
    private static final String SUB_B_ORCHESTRATION = "{}";

    // ── 替身 ──────────────────────────────────────────────────────

    /** 恒放行的授权替身。 */
    private static final AuthorizationService ALLOW_ALL = new AuthorizationService() {
        @Override
        public boolean isAuthorized(TenantContext context, String action, String resourceId) {
            return true;
        }

        @Override
        public long currentAuthzVersion(String tenantId, String principalId) {
            return 1L;
        }

        @Override
        public @NonNull Set<String> rolesOf(TenantContext context) {
            return context.roles();
        }
    };

    /** 恒批准的预算替身（委派分支不触达预算，但构造器要求非空）。 */
    private static final BudgetPort GRANT_BUDGET = new BudgetPort() {
        @Override
        public ReserveOutcome reserve(String tenantId, String runId,
                                      String operationId, BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }

        @Override
        public boolean settle(String tenantId, String reservationId,
                              BigDecimal actualAmount, String modelId) {
            return true;
        }

        @Override
        public boolean release(String tenantId, String reservationId, String reason) {
            return true;
        }

        @Override
        public Optional<Reservation> findByOperation(String tenantId, String operationId) {
            return Optional.empty();
        }

        @Override
        public Optional<AccountSnapshot> accountSnapshot(String tenantId, String currency) {
            return Optional.empty();
        }

        @Override
        public List<OrphanedReservation> findOrphaned(String tenantId) {
            return List.of();
        }

        @Override
        public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    };

    /** 恒「无审批」的审批端口替身。 */
    private static final ApprovalPort NO_APPROVAL = new ApprovalPort() {
        @Override
        public ApprovalOutcome consume(String tenantId, String operationId,
                                       String argumentDigest, String targetResource,
                                       String runId) {
            return ApprovalOutcome.NO_APPROVAL;
        }

        @Override
        public String grant(String tenantId, String operationId, String argumentDigest,
                            String targetResource,
                            cn.studykid.multiagent.core.config.ApprovalRequirement requirement,
                            String approverScope, Instant expiresAt, String approverId) {
            throw new UnsupportedOperationException("本替身不支持授予");
        }

        @Override
        public void revoke(String tenantId, String operationId, String approverId,
                           String reason) {
            throw new UnsupportedOperationException("本替身不支持撤销");
        }

        @Override
        public Optional<ApprovalView> findActive(String tenantId, String operationId) {
            return Optional.empty();
        }
    };

    /** 恒「本次新建」的账本替身（委派分支不触达账本）。 */
    private static final RunToolLedgerPort FRESH_LEDGER = new RunToolLedgerPort() {
        @Override
        public Registration register(String tenantId, String runId, String toolName,
                                     String riskLevel, String idempotencyKey,
                                     String requestDigest, Map<String, Object> arguments,
                                     long fencingToken) {
            return new Registration("op-1", cn.studykid.multiagent.core.run.ToolOperationState
                    .IN_FLIGHT, 0, 0L, true);
        }

        @Override
        public void recordResult(String tenantId, String operationId,
                                 cn.studykid.multiagent.core.run.ToolOperationState result,
                                 long fencingToken, String errorClass,
                                 String errorMessage) {
            // 不涉及
        }

        @Override
        public void reconcile(String tenantId, String operationId,
                              cn.studykid.multiagent.core.run.ToolOperationState reconciled,
                              String source) {
            // 不涉及
        }

        @Override
        public void manualDispose(String tenantId, String operationId, String disposedBy,
                                  String note) {
            // 不涉及
        }

        @Override
        public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return List.of();
        }

        @Override
        public Optional<Registration> findByIdempotencyKey(String tenantId,
                                                           String idempotencyKey) {
            return Optional.empty();
        }
    };

    /** 多 Agent 的定义行仓储。 */
    private static final class InMemoryAgentDefs implements CapAgentDefPort {
        private final Map<String, CapAgentDef> rows = new LinkedHashMap<>();

        InMemoryAgentDefs(CapAgentDef... defs) {
            for (CapAgentDef def : defs) {
                rows.put(key(def.tenantId(), def.agentId(), def.version()), def);
            }
        }

        private static String key(String tenantId, String agentId, int version) {
            return tenantId + "/" + agentId + "@" + version;
        }

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            String k = key(def.tenantId(), def.agentId(), def.version());
            if (rows.containsKey(k)) {
                return false;
            }
            rows.put(k, def);
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                   @NonNull String agentId, int version) {
            return Optional.ofNullable(rows.get(key(tenantId, agentId, version)));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                         @NonNull String agentId) {
            return rows.values().stream()
                    .filter(d -> tenantId.equals(d.tenantId()))
                    .filter(d -> agentId.equals(d.agentId()))
                    .max((x, y) -> Integer.compare(x.version(), y.version()));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                   @NonNull String agentId) {
            return findLatest(tenantId, agentId).filter(CapAgentDef::isPublished);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            return List.copyOf(rows.values());
        }
    }

    /** 记录每次委派的父与深度的调用记录仓储。 */
    private static final class InMemoryInvocations implements AgentInvocationPort {
        final Map<String, AgentInvocation> rows = new LinkedHashMap<>();

        @Override
        public boolean insert(@NonNull AgentInvocation invocation) {
            return rows.putIfAbsent(invocation.invocationId(), invocation) == null;
        }

        @Override
        public @NonNull Optional<AgentInvocation> find(@NonNull String tenantId,
                                                       @NonNull String invocationId) {
            return Optional.ofNullable(rows.get(invocationId));
        }

        @Override
        public @NonNull List<AgentInvocation> listByRun(@NonNull String tenantId,
                                                        @NonNull String runId) {
            return rows.values().stream().filter(r -> runId.equals(r.runId())).toList();
        }

        @Override
        public int adoptInFlight(@NonNull String tenantId, @NonNull String runId,
                                 long newFencingToken) {
            return 0;
        }

        @Override
        public @NonNull TerminalWriteOutcome markTerminal(@NonNull String tenantId,
                                                          @NonNull String invocationId,
                                                          @NonNull AgentInvocationStatus status,
                                                          String reasonCode, String outputDigest,
                                                          Instant finishedAt, long fencingToken) {
            AgentInvocation row = rows.get(invocationId);
            if (row == null) {
                return TerminalWriteOutcome.NOT_FOUND;
            }
            if (row.fencingToken() != fencingToken) {
                return TerminalWriteOutcome.TOKEN_EXPIRED;
            }
            if (row.status().isTerminal()) {
                return TerminalWriteOutcome.ALREADY_TERMINAL;
            }
            rows.put(invocationId, new AgentInvocation(row.invocationId(), row.tenantId(),
                    row.runId(), row.parentInvocationId(), row.parentAgentId(),
                    row.parentAgentVersion(), row.targetAgentId(), row.targetAgentVersion(),
                    status, row.depth(), row.traceId(), row.inputDigest(), outputDigest,
                    row.startedAt(), finishedAt, reasonCode, row.fencingToken()));
            return TerminalWriteOutcome.WRITTEN;
        }
    }

    /** 记录调用次数与**实际收到的目标**的子执行器。 */
    private static final class CountingRunner implements ChildAgentRunner {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> targets = new ArrayList<>();

        @Override
        public @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request) {
            calls.incrementAndGet();
            targets.add(request.targetAgentId());
            return ChildAgentResult.succeeded("{\"answer\":\"ok\"}");
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapAgentDef def(String agentId, String orchestration) {
        return new CapAgentDef(TENANT, agentId, 1, agentId, agentId,
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-" + agentId, 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static DelegationExecutor executor(CountingRunner runner,
                                               InMemoryInvocations invocations) {
        InMemoryAgentDefs agentDefs = new InMemoryAgentDefs(
                def(ROOT, ROOT_ORCHESTRATION),
                def(SUB_A, SUB_A_ORCHESTRATION),
                def(SUB_B, SUB_B_ORCHESTRATION),
                def(SUB_C, SUB_C_ORCHESTRATION),
                def(SUB_D, SUB_D_ORCHESTRATION),
                def(ROOT_ONLY, "{}"),
                def(SHARED, "{}"));
        AuthzVersionPort authzVersions = new AuthzVersionPort() {
            @Override
            public @NonNull Optional<AuthzVersion> find(String tenantId, String principalId) {
                return Optional.of(new AuthzVersion(tenantId, principalId, 1L, Instant.EPOCH));
            }

            @Override
            public @NonNull AuthzVersion require(String tenantId, String principalId) {
                return new AuthzVersion(tenantId, principalId, 1L, Instant.EPOCH);
            }

            @Override
            public @NonNull AuthzVersion initialize(String tenantId, String principalId) {
                return new AuthzVersion(tenantId, principalId, 1L, Instant.EPOCH);
            }

            @Override
            public long bump(String tenantId, String principalId) {
                return 2L;
            }

            @Override
            public cn.studykid.multiagent.core.monitoring.AuthzWatermark
                    watermark(String tenantId) {
                throw new UnsupportedOperationException();
            }
        };
        CapabilitySnapshotPort snapshots = new CapabilitySnapshotPort() {
            @Override
            public boolean save(@NonNull CapabilitySnapshot snapshot) {
                return true;
            }

            @Override
            public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                              @NonNull String runId) {
                return Optional.of(new CapabilitySnapshot(RUN, TENANT, ROOT, "{}", 1, 1L,
                        "cap_fp_v1:root", 1L, Instant.EPOCH));
            }
        };
        ClosureResolver resolver = new ClosureResolver() {
            @Override
            public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                                      @NonNull String agentId,
                                                      int agentVersion) {
                return EffectiveClosure.empty(agentId, agentVersion);
            }

            @Override
            public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                            @NonNull String agentId) {
                return EffectiveClosure.empty(agentId, 1);
            }
        };
        DelegationGuard guard = new DelegationGuard(agentDefs, ALLOW_ALL, authzVersions,
                (tenantId, runId) -> true);
        ResultContractProvider noContract = (tenantId, contractRef) -> null;
        return new DelegationExecutor(guard, agentDefs, resolver, snapshots, authzVersions,
                invocations, runner, noContract, CLOCK);
    }

    private static CapExecutionFlow enabledCapFlow() {
        return new CapExecutionFlow(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ONE,
                CapExecutionFlow.DEFAULT_PRINCIPAL_ID, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    private static ToolInvocationGate gate(DelegationExecutor delegation) {
        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        return new ToolInvocationGate(
                // ⚠️ 故意不注册任何 ToolHandler：委派必须**不需要**普通工具处理器
                List.<ToolHandler>of(),
                ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                GRANT_BUDGET, FRESH_LEDGER, NO_APPROVAL,
                BigDecimal.ONE, (t, r) -> false, approveAll,
                enabledCapFlow(), null, delegation, CLOCK);
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.MAX, 1);
    }

    private static Map<String, ModelResponse.ToolCallRequest> delegationCall(
            String callId, String target) {
        return Map.of(callId, new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", target, "input", Map.of("question", "q")),
                callId));
    }

    /** 子 Agent 的委派上下文（深度 2，父调用标识 = 该子 Agent 自己的调用 id）。 */
    private static DelegationContext subContext(String agentId, String invocationId) {
        return new DelegationContext(agentId, 1, 2, invocationId);
    }

    private static ExecutionScope scopeOf(String agentId) {
        return new ExecutionScope(agentId, 1, "fp-" + agentId);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 嵌套委派按**当前 Agent** 判定：子 Agent 不得借根的声明越权委派")
    void nestedDelegationCannotBorrowRootDeclaration() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        // ⚠️ ROOT_ONLY 在**根**的 childAgentRefs 里，但 SUB_A（当前 Agent）没声明它。
        //    修复前闸门把父恒传 null → 父按根解析 → 根声明了它 → **放行并执行子 Agent**。
        var result = gate.invokeAll(TENANT, task(),
                delegationCall("call-1", ROOT_ONLY),
                scopeOf(SUB_A), subContext(SUB_A, "inv-sub-a"));

        assertThat(result.succeededTools())
                .as("⛔ 子 Agent 借根的声明越权委派成功：委派拓扑必须来自当前 Agent 自己的声明")
                .isEmpty();
        assertThat(runner.calls.get())
                .as("⛔ 越权委派触发了子执行")
                .isZero();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .as("拒绝原因必须是「目标未在当前 Agent 的声明内」")
                .contains(ReasonCode.DELEGATION_TARGET_NOT_DECLARED.name());
    }

    @Test
    @DisplayName("⛔ 嵌套委派：当前 Agent 是叶子（无委派能力）→ 拒绝（不得借根的声明放行）")
    void nestedDelegationFromLeafIsRejected() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        var result = gate.invokeAll(TENANT, task(),
                delegationCall("call-1", ROOT_ONLY),
                scopeOf(SUB_B), subContext(SUB_B, "inv-sub-b"));

        assertThat(runner.calls.get()).isZero();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .as("叶子 Agent 的委派必须报「不允许委派」")
                .contains(ReasonCode.DELEGATION_NOT_ALLOWED.name());
    }

    @Test
    @DisplayName("✅ 嵌套委派：当前 Agent 声明了该目标 → 放行并按当前 Agent 执行")
    void nestedDelegationUsesCurrentAgentDeclaration() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        var result = gate.invokeAll(TENANT, task(),
                delegationCall("call-1", SHARED),
                scopeOf(SUB_A), subContext(SUB_A, "inv-sub-a"));

        assertThat(result.succeededTools())
                .as("⛔ 当前 Agent 自己声明的目标被拒：修复过头会让嵌套委派完全不可用")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.targets).containsExactly(SHARED);
    }

    @Test
    @DisplayName("✅ 嵌套委派：深度与父子链按当前 Agent 推导（深度 3、父调用 = 子 Agent 自己的调用）")
    void nestedDelegationCarriesDepthAndParentLink() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        gate.invokeAll(TENANT, task(), delegationCall("call-1", SHARED),
                scopeOf(SUB_A), subContext(SUB_A, "inv-sub-a"));

        assertThat(invocations.rows).hasSize(1);
        AgentInvocation record = invocations.rows.values().iterator().next();
        assertThat(record.parentAgentId())
                .as("父必须是当前执行中的 Agent，而不是根 Agent")
                .isEqualTo(SUB_A);
        assertThat(record.parentAgentVersion()).isEqualTo(1);
        assertThat(record.parentInvocationId())
                .as("父调用标识必须指向子 Agent 自己的那次委派，否则父子链断裂、无法追溯")
                .isEqualTo("inv-sub-a");
        assertThat(record.depth())
                .as("嵌套调用的深度 = 当前 Agent 深度 2 + 1 = 3")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("⛔ 嵌套委派：按**当前 Agent** 的深度上限判定（不是根的上限）")
    void nestedDelegationRespectsCurrentAgentDepthLimit() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        // ⚠️ SUB_D 自身深度 2、它声明的 maxDelegationDepth = 2 →
        //    再跳一层（深度 3）必须**被 SUB_D 自己的上限**拒绝。
        //    修复前父深度恒为 1 → 深度恒算成 2 → 2 ≤ 2 通过 → 超深委派放行。
        var result = gate.invokeAll(TENANT, task(),
                delegationCall("call-1", SHARED),
                scopeOf(SUB_D), subContext(SUB_D, "inv-sub-d"));

        assertThat(runner.calls.get())
                .as("⛔ 超过当前 Agent 自己声明的深度上限仍启动了子执行")
                .isZero();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .as("拒绝原因必须是「深度超限」")
                .contains(ReasonCode.DELEGATION_DEPTH_EXCEEDED.name());
    }

    @Test
    @DisplayName("✅ 不同父的同名 tool call → invocationId 不撞（否则第二次委派被当成幂等重放）")
    void differentParentsDoNotCollideOnInvocationId() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        // ⚠️ 两个不同的子 Agent 各自发起「call-1」的委派，目标都在各自声明内
        //    （且根也声明了它，保证修复前不会被拓扑检查顺手拦掉）：
        //    修复前 invocationId 的派生材料恒不含父调用标识 → 两次算出**同一个** id
        //    → 第二次被幂等分支吞掉，**第二次委派根本没发生**。
        gate.invokeAll(TENANT, task(), delegationCall("call-1", SHARED),
                scopeOf(SUB_A), subContext(SUB_A, "inv-sub-a"));
        gate.invokeAll(TENANT, task(), delegationCall("call-1", SHARED),
                scopeOf(SUB_C), subContext(SUB_C, "inv-sub-c"));

        assertThat(runner.calls.get())
                .as("⛔ 第二次委派被当成幂等重放：不同父的委派必须各自成立")
                .isEqualTo(2);
        assertThat(invocations.rows).hasSize(2);
    }

    @Test
    @DisplayName("⛔ 执行作用域与委派上下文不一致 → 拒绝委派（上下文串了，不猜哪个对）")
    void mismatchedScopeAndContextRejectDelegation() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        var result = gate.invokeAll(TENANT, task(), delegationCall("call-1", SHARED),
                scopeOf(SUB_A), subContext(SUB_B, "inv-sub-b"));

        assertThat(runner.calls.get())
                .as("⛔ 上下文不一致却照样委派：等于在越权路径上替调用方猜答案")
                .isZero();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .contains(ReasonCode.ORCHESTRATION_INVALID.name());
    }

    @Test
    @DisplayName("✅ 根路径不受影响：根 Agent 的委派仍按 Run 快照解析父")
    void rootPathStillResolvesParentFromSnapshot() {
        CountingRunner runner = new CountingRunner();
        InMemoryInvocations invocations = new InMemoryInvocations();
        ToolInvocationGate gate = gate(executor(runner, invocations));

        var result = gate.invokeAll(TENANT, task(), delegationCall("call-1", SUB_A));

        assertThat(result.succeededTools())
                .as("⛔ 根路径被改坏：根 Agent 的委派必须照常按 Run 快照解析父")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        AgentInvocation record = invocations.rows.values().iterator().next();
        assertThat(record.parentAgentId()).isEqualTo(ROOT);
        assertThat(record.parentInvocationId()).isNull();
        assertThat(record.depth()).isEqualTo(2);
    }
}
