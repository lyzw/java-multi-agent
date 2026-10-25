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
import cn.studykid.multiagent.application.orchestration.RunBudgetProbe;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
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
import cn.studykid.multiagent.ports.persistence.RunBudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 委派的<b>预算计费</b>测试（多级 Agent v1.1 · 第十六批 · MA-T17）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：此前闸门的委派分支<b>直接绕过</b>
 * 「Run 级金额判定 + 预算预留」—— 一次委派会启动子 Agent、子 Agent 会调模型
 * （<b>真实成本</b>），却<b>不占任何 Run 预算额度</b>。
 *
 * <p>后果：「全调用树共享预算上限」（MA-T17 / NFR-012）对委派**完全失效** ——
 * {@code maxChildInvocations} 是<b>每父 Agent</b> 的（8 × 8 × 8 = 512 次子调用），
 * Run 额度本是唯一的 Run 级兜底，而它恰好对委派不管用。
 *
 * <p>⚠️ <b>「全都拒」与「全都放行」都能让用例绿</b>：本类同时钉住
 * 「额度够 → 委派成立且<b>占额</b>」「额度不足 → <b>零子执行</b>」
 * 「被拒 → <b>释放</b>」「执行失败 → <b>结算</b>」「UNKNOWN → <b>既不结算也不释放</b>」两侧。
 */
class DelegationBudgetChargeTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String ROOT = "root-agent";
    private static final String CHILD = "child-agent";
    private static final String UNDECLARED = "other-agent";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    /** 每次调用预留的默认金额（闸门的既有口径）。 */
    private static final BigDecimal RESERVE_AMOUNT = BigDecimal.ONE;

    private static final String ROOT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    // ── 替身 ──────────────────────────────────────────────────────

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

    /**
     * <b>可观测</b>的预算替身：记录每次预留 / 结算 / 释放。
     *
     * <p>⚠️ 它模拟真实实现的关键性质：{@code reserve} 成功后
     * {@code findByOperation} <b>一定查得到</b>（同事务同库）——
     * 查不到才是异常（写后读不一致），闸门对那种情况 fail-closed。
     */
    private static final class RecordingBudget implements BudgetPort {
        final List<String> reserves = new ArrayList<>();
        final List<String> settles = new ArrayList<>();
        final List<String> releases = new ArrayList<>();
        /** {@code reserve} 的返回（可切换成被拒场景）。 */
        ReserveOutcome outcome = ReserveOutcome.RESERVED;

        @Override
        public ReserveOutcome reserve(String tenantId, String runId, String operationId,
                                      BigDecimal amount) {
            reserves.add(operationId);
            if (!outcome.granted()) {
                return outcome;
            }
            return ReserveOutcome.RESERVED;
        }

        @Override
        public boolean settle(String tenantId, String reservationId,
                              BigDecimal actualAmount, String modelId) {
            settles.add(reservationId);
            return true;
        }

        @Override
        public boolean release(String tenantId, String reservationId, String reason) {
            releases.add(reservationId);
            return true;
        }

        @Override
        public Optional<Reservation> findByOperation(String tenantId, String operationId) {
            return Optional.of(new Reservation("res-" + operationId, RUN, operationId,
                    RESERVE_AMOUNT, null, "RESERVED", "CNY"));
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
    }

    /** Run 级额度替身（额度可配置；{@code null} = 未声明额度 = 不额外限制）。 */
    private static final class StubRunBudgetPort implements RunBudgetPort {
        BigDecimal budgetAmount = BigDecimal.valueOf(100);

        @Override
        public @NonNull Optional<RunBudget> find(@NonNull String tenantId,
                                                 @NonNull String runId) {
            if (budgetAmount == null) {
                return Optional.empty();
            }
            return Optional.of(new RunBudget(runId, budgetAmount, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
    }

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

    private static final class InMemoryAgentDefs implements CapAgentDefPort {
        private final Map<String, CapAgentDef> rows = new LinkedHashMap<>();

        InMemoryAgentDefs(CapAgentDef... defs) {
            for (CapAgentDef def : defs) {
                rows.put(def.tenantId() + "/" + def.agentId() + "@" + def.version(), def);
            }
        }

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            String key = def.tenantId() + "/" + def.agentId() + "@" + def.version();
            if (rows.containsKey(key)) {
                return false;
            }
            rows.put(key, def);
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                   @NonNull String agentId, int version) {
            return Optional.ofNullable(rows.get(tenantId + "/" + agentId + "@" + version));
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
            rows.put(invocationId, new AgentInvocation(row.invocationId(), row.tenantId(),
                    row.runId(), row.parentInvocationId(), row.parentAgentId(),
                    row.parentAgentVersion(), row.targetAgentId(), row.targetAgentVersion(),
                    status, row.depth(), row.traceId(), row.inputDigest(), outputDigest,
                    row.startedAt(), finishedAt, reasonCode, row.fencingToken()));
            return TerminalWriteOutcome.WRITTEN;
        }
    }

    /** 计数型子执行器（结果可配置）。 */
    private static final class CountingRunner implements ChildAgentRunner {
        final AtomicInteger calls = new AtomicInteger();
        ChildAgentResult result = ChildAgentResult.succeeded("{\"answer\":\"ok\"}");

        @Override
        public @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request) {
            calls.incrementAndGet();
            return result;
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private static CapAgentDef rootDef() {
        return new CapAgentDef(TENANT, ROOT, 1, ROOT, "根 Agent",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", ROOT_ORCHESTRATION, "fp-root", 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static CapExecutionFlow enabledCapFlow() {
        return new CapExecutionFlow(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ONE,
                CapExecutionFlow.DEFAULT_PRINCIPAL_ID, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    private static ToolInvocationGate gate(CountingRunner runner,
                                            RecordingBudget budget,
                                            StubRunBudgetPort runBudgets) {
        InMemoryAgentDefs agentDefs = new InMemoryAgentDefs(rootDef());
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
        DelegationExecutor executor = new DelegationExecutor(guard, agentDefs, resolver,
                snapshots, authzVersions, new InMemoryInvocations(), runner, noContract, CLOCK);

        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        return new ToolInvocationGate(
                List.<ToolHandler>of(),
                ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                budget, FRESH_LEDGER, NO_APPROVAL,
                RESERVE_AMOUNT, (t, r) -> false, approveAll,
                enabledCapFlow(), null, executor, CLOCK,
                new RunBudgetProbe(runBudgets));
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

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 委派**消耗** Run 预算（预留 + 结算，不再是免费动作）")
    void delegationConsumesRunBudget() {
        CountingRunner runner = new CountingRunner();
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();

        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.succeededTools()).containsExactly(
                DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(budget.reserves)
                .as("⛔ 委派没有预留任何额度：一次委派会启动子 Agent（真实成本），"
                        + "不占额等于「全调用树共享预算上限」对委派完全失效")
                .hasSize(1);
        assertThat(budget.settles)
                .as("⛔ 委派成功后额度未结算：成本已发生却把额度留着，等于白占")
                .hasSize(1);
        assertThat(budget.releases)
                .as("⛔ 委派成功却释放了额度：成本已发生却把额度退回，等于白送")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ Run 级额度不足以容纳本次委派 → 拒绝且**零子执行**")
    void insufficientRunBudgetBlocksDelegation() {
        CountingRunner runner = new CountingRunner();
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();
        // ⚠️ 额度 0.5 元 < 本次预留 1 元 → 拒绝
        runBudgets.budgetAmount = new BigDecimal("0.5");

        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.rejectedTools()).containsExactly(
                DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get())
                .as("⛔ 额度不足却启动了子执行：明知会被预算拒绝还要先花钱")
                .isZero();
        assertThat(budget.reserves)
                .as("⛔ 额度不足却仍发起了预留")
                .isEmpty();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .as("拒绝原因必须指向 Run 级额度，而不是笼统的「失败」")
                .contains("Run 级预算不足");
    }

    @Test
    @DisplayName("⛔ 租户级预留被拒（额度不足 / 未开通账户）→ 拒绝且**零子执行**")
    void tenantBudgetRejectionBlocksDelegation() {
        CountingRunner runner = new CountingRunner();
        RecordingBudget budget = new RecordingBudget();
        budget.outcome = BudgetPort.ReserveOutcome.INSUFFICIENT;
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();

        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.rejectedTools()).containsExactly(
                DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isZero();
        assertThat(result.rejectionReasons().get(DelegationExecutor.DELEGATION_TOOL_NAME))
                .as("拒绝原因必须可区分「额度不足」与「未开通账户」")
                .contains("预算不足");
    }

    @Test
    @DisplayName("✅ 委派被拒（子执行从未启动）→ **释放**预留（零成本，额度原样退回）")
    void rejectedDelegationReleasesQuota() {
        CountingRunner runner = new CountingRunner();
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();

        // 目标未在父的声明内 → 委派被拒 → 子执行从未启动
        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", UNDECLARED));

        assertThat(result.rejectedTools()).containsExactly(
                DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isZero();
        assertThat(budget.releases)
                .as("⛔ 委派被拒（零成本）却不释放额度：额度被凭空占用")
                .hasSize(1);
        assertThat(budget.settles)
                .as("⛔ 委派被拒却结算了额度：子 Agent 从未运行，没有成本可结算")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 委派执行失败（子执行已启动）→ **结算**而非释放（成本已发生）")
    void failedDelegationSettlesQuota() {
        CountingRunner runner = new CountingRunner();
        runner.result = ChildAgentResult.failed(ReasonCode.TOOL_EXECUTION_FAILED.name(), "boom");
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();

        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.failedTools()).containsExactly(
                DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(budget.settles)
                .as("⛔ 子执行已启动却释放了额度：子 Agent 跑过了，模型调用的钱已经花出去")
                .hasSize(1);
        assertThat(budget.releases)
                .as("⛔ 子执行已启动却释放了额度：等于把已花掉的成本白送回去")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 委派结果 UNKNOWN → 预留**既不结算也不释放**（红线：成本可能已发生）")
    void unknownDelegationHoldsQuota() {
        CountingRunner runner = new CountingRunner();
        runner.result = new ChildAgentResult(AgentInvocationStatus.UNKNOWN, "",
                ReasonCode.EXEC_UNKNOWN.name());
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();

        var result = gate(runner, budget, runBudgets)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.unknownResultOccurred()).isTrue();
        assertThat(budget.settles)
                .as("⛔ UNKNOWN 却结算了：成本是否发生未知，认定「已花」是过度断言")
                .isEmpty();
        assertThat(budget.releases)
                .as("⛔ UNKNOWN 却释放了：释放等于白送额度（R-007 红线）")
                .isEmpty();
        assertThat(budget.reserves)
                .as("预留必须仍然挂着（RESERVED）待对账")
                .hasSize(1);
    }

    @Test
    @DisplayName("✅ 同一委派的重复投递 → 预留按同一幂等键复用（不重复扣减）")
    void repeatedDelegationReusesSameQuota() {
        CountingRunner runner = new CountingRunner();
        RecordingBudget budget = new RecordingBudget();
        StubRunBudgetPort runBudgets = new StubRunBudgetPort();
        ToolInvocationGate gate = gate(runner, budget, runBudgets);

        gate.invokeAll(TENANT, task(), delegationCall("call-1", CHILD));
        gate.invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(budget.reserves)
                .as("两次委派必须用**同一个**幂等键预留（否则重复投递会重复扣额度）")
                .hasSize(2)
                .allSatisfy(operationId ->
                        assertThat(operationId).isEqualTo(budget.reserves.get(0)));
    }
}
