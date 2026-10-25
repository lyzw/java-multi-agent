package cn.studykid.multiagent.worker.execute;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
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
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门委派分支测试（多级 Agent v1.1 · P2-MA-02 · MA-T05 / MA-T10 / MA-T11 / MA-T13）。
 *
 * <p><b>本类只验证闸门这一层的接线</b>：委派请求是否被识别为委派（而不是普通工具）、
 * 参数是否被校验、被拒时是否<b>完全不产生子执行</b>、结果状态如何映射到四字段结论。
 * 委派本身的判定逻辑（目标声明 / 幂等 / 契约）在
 * {@code DelegationExecutorTest} 中单独覆盖。
 *
 * <p>⚠️ <b>委派不是普通工具</b>：闸门里<b>没有</b>注册任何 {@code ToolHandler}，
 * 委派仍能跑通 —— 证明委派<b>不进</b> Skill→Tool 能力闭包，
 * 但仍经由「唯一执行入口」执行（ADR-011 防绕过）。
 */
class DelegationGateBranchTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String PARENT = "parent-agent";
    private static final String CHILD = "child-agent";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static final String PARENT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    // ── 替身 ──────────────────────────────────────────────────────

    /** 恒放行的授权替身。 */
    private static final AuthorizationService ALLOW_ALL = new AuthorizationService() {
        @Override
        public boolean isAuthorized(TenantContext c, String action, String r) {
            return true;
        }

        @Override
        public long currentAuthzVersion(String t, String p) {
            return 1L;
        }

        @Override
        public @NonNull Set<String> rolesOf(TenantContext c) {
            return c.roles();
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

    /** 内存版 Agent 定义仓储。 */
    private static final class InMemoryAgentDefs implements CapAgentDefPort {
        private final CapAgentDef parent;

        InMemoryAgentDefs(CapAgentDef parent) {
            this.parent = parent;
        }

        @Override
        public boolean insert(CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(String tenantId, String agentId,
                                                    int version) {
            return TENANT.equals(tenantId) && PARENT.equals(agentId) && version == 1
                    ? Optional.of(parent)
                    : Optional.empty();
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
            return find(tenantId, agentId, 1);
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(String tenantId,
                                                                    String agentId) {
            return find(tenantId, agentId, 1);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(String tenantId) {
            return List.of(parent);
        }
    }

    /** 内存版委派记录仓储。 */
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
            return rows.values().stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public int adoptInFlight(@NonNull String tenantId, @NonNull String runId,
                                 long newFencingToken) {
            int affected = 0;
            for (Map.Entry<String, AgentInvocation> entry : rows.entrySet()) {
                AgentInvocation row = entry.getValue();
                if (row.runId().equals(runId)
                        && !row.status().isTerminal()
                        && row.fencingToken() < newFencingToken) {
                    entry.setValue(new AgentInvocation(row.invocationId(), row.tenantId(),
                            row.runId(), row.parentInvocationId(), row.parentAgentId(),
                            row.parentAgentVersion(), row.targetAgentId(),
                            row.targetAgentVersion(), row.status(), row.depth(), row.traceId(),
                            row.inputDigest(), row.outputDigest(),
                            row.startedAt(), row.finishedAt(), row.reasonCode(),
                            newFencingToken));
                    affected++;
                }
            }
            return affected;
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

    /** 计数型子执行器。 */
    private static final class CountingRunner implements ChildAgentRunner {
        final AtomicInteger calls = new AtomicInteger();
        ChildAgentResult result =
                ChildAgentResult.succeeded("{\"answer\":\"ok\"}");
        /** 最近一次子执行实际收到的墙钟预算（毫秒）。 */
        long lastTimeoutMs = -1L;

        @Override
        public @NonNull ChildAgentResult run(
                @NonNull ChildAgentRequest request) {
            calls.incrementAndGet();
            lastTimeoutMs = request.timeoutMs();
            return result;
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private static CapAgentDef parentDef(String orchestration) {
        return new CapAgentDef(TENANT, PARENT, 1, PARENT, "父 Agent",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-parent", 1, Instant.EPOCH, Instant.EPOCH);
    }

    /** 声明「失败后继续跑后续子调用」的父编排。 */
    private static final String PARENT_ORCHESTRATION_CONTINUE = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"CONTINUE_ON_ERROR"}
            """;

    private static DelegationExecutor executor(CountingRunner runner) {
        return executor(runner, PARENT_ORCHESTRATION);
    }

    private static DelegationExecutor executor(CountingRunner runner, String parentOrchestration) {
        InMemoryAgentDefs agentDefs = new InMemoryAgentDefs(parentDef(parentOrchestration));
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
                return Optional.of(new CapabilitySnapshot(RUN, TENANT, PARENT, "{}", 1, 1L,
                        "cap_fp_v1:parent", 1L, Instant.EPOCH));
            }
        };
        // ⚠️ 子闭包解析替身：本类只验证闸门接线，闭包解析的正确性由
        //    PermissionResolverMultiLevelTest 覆盖。
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
                new InMemoryInvocations(), runner, noContract, CLOCK);
    }

    /**
     * CAP 链路编排器（仅用于把闸门切到 CAP 分支）。
     *
     * <p>⚠️ 委派分支<b>不触达</b> CAP 链路的任何依赖，因此这里用空占位；
     * 一旦分支误用 {@code capFlow.invoke}，测试会以 NPE 失败而非静默通过。
     */
    private static CapExecutionFlow enabledCapFlow() {
        return new CapExecutionFlow(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ONE,
                CapExecutionFlow.DEFAULT_PRINCIPAL_ID, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    private static ToolInvocationGate gate(DelegationExecutor delegation,
                                            boolean cancelled) {
        return gate(delegation, cancelled, Clock.systemUTC());
    }

    /**
     * 带指定时钟的闸门。
     *
     * <p>⚠️ 时钟必须可注入：「根 Run 剩余时间已耗尽 → 拒绝新增子调用」
     * 这条分支只有在<b>确定的时间点</b>上才能被稳定断言。
     */
    private static ToolInvocationGate gate(DelegationExecutor delegation,
                                            boolean cancelled,
                                            Clock clock) {
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
                BigDecimal.ONE, (t, r) -> cancelled, approveAll,
                enabledCapFlow(), null, delegation, clock);
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.MAX, 1);
    }

    /** 带业务截止时间的任务（{@code deadlineAt} = 根 Run 该结束的时刻）。 */
    private static RunClaimPort.LeasedTask taskWithDeadline(Instant deadlineAt) {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.MAX, 1, deadlineAt);
    }

    private static Map<String, ModelResponse.ToolCallRequest> delegationCall(
            String callId, String target) {
        return Map.of(callId, new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", target, "input", Map.of("question", "q")),
                callId));
    }

    /** 两个委派调用（用于验证失败策略对后续委派的影响）。 */
    private static Map<String, ModelResponse.ToolCallRequest> twoDelegationCalls() {
        Map<String, ModelResponse.ToolCallRequest> calls = new LinkedHashMap<>();
        calls.put("call-1", new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", CHILD, "input", Map.of("question", "q")), "call-1"));
        calls.put("call-2", new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", CHILD, "input", Map.of("question", "q2")), "call-2"));
        return calls;
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("委派请求 → 走委派分支并成功（无需注册普通工具处理器）")
    void delegationSucceeds() {
        CountingRunner runner = new CountingRunner();

        var result = gate(executor(runner), false)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.succeededTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).as("合法委派必须真的执行子 Agent").isEqualTo(1);
        assertThat(result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME))
                .get()
                .satisfies(d -> assertThat(d.decision().isAllowed()).isTrue());
    }

    // ── Run 级截止时间贯通（第六批 · FR-024 · MA-T09）───────────────

    @Test
    @DisplayName("⚠️ 根 Run 只剩极短时间（但仍为正）→ 不得误判为超时（只 ≤ 0 才拒绝）")
    void nearlyExpiredRootBudgetIsNotMistakenForTimeout() {
        // ⚠️ 边界口径：只剩 100 毫秒也是**有效**预算 —— 拒绝阈值是「≤ 0」。
        //    若把「即将到期」也当超时，合法的短任务会被批量误杀。
        CountingRunner runner = new CountingRunner();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var task = taskWithDeadline(now.plusMillis(100));

        var result = gate(executor(runner), false, Clock.fixed(now, ZoneOffset.UTC))
                .invokeAll(TENANT, task, delegationCall("call-1", CHILD));

        assertThat(result.succeededTools())
                .as("⚠️ 剩余时间仍为正 → 委派必须放行")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.lastTimeoutMs)
                .as("子执行预算 = 剩余的 100ms（仍被根剩余封顶）")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("⛔ 根 Run 已过截止时刻 → 在新增子调用前拒绝（不得启动子执行）")
    void pastDeadlineBlocksNewDelegation() {
        CountingRunner runner = new CountingRunner();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var task = taskWithDeadline(now.minusSeconds(1));

        var result = gate(executor(runner), false, Clock.fixed(now, ZoneOffset.UTC))
                .invokeAll(TENANT, task, delegationCall("call-1", CHILD));

        assertThat(result.rejectedTools())
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get())
                .as("⚠️ 已过截止时刻 → 零子执行")
                .isZero();
    }

    @Test
    @DisplayName("✅ 根 Run 的真实剩余时间封顶子执行预算（不再传上界 Long.MAX_VALUE）")
    void rootRemainingTimeCapsChildBudget() {
        CountingRunner runner = new CountingRunner();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var task = taskWithDeadline(now.plusSeconds(5));

        var result = gate(executor(runner), false, Clock.fixed(now, ZoneOffset.UTC))
                .invokeAll(TENANT, task, delegationCall("call-1", CHILD));

        assertThat(result.succeededTools())
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.lastTimeoutMs)
                .as("⚠️ 子执行预算必须是 min(根剩余 5s, 本级声明 30s) = 5s；"
                        + "传上界就等于「全调用树共享超时上限」形同虚设")
                .isEqualTo(5_000L);
    }

    @Test
    @DisplayName("目标未在父的声明内 → 拒绝且不产生任何子执行（MA-T05）")
    void undeclaredTargetIsRejectedWithoutChildExecution() {
        CountingRunner runner = new CountingRunner();

        var result = gate(executor(runner), false)
                .invokeAll(TENANT, task(), delegationCall("call-1", "other-agent"));

        assertThat(result.rejectedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).as("被拒的委派不得触发子执行").isZero();
        assertThat(result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME))
                .get()
                .satisfies(d -> assertThat(d.decision().reasonCode())
                        .isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED));
    }

    @Test
    @DisplayName("委派参数非法（缺 targetAgentId）→ 拒绝且不产生子执行")
    void invalidArgumentsAreRejectedWithoutChildExecution() {
        CountingRunner runner = new CountingRunner();
        Map<String, ModelResponse.ToolCallRequest> calls = Map.of("call-1",
                new ModelResponse.ToolCallRequest(DelegationExecutor.DELEGATION_TOOL_NAME,
                        Map.of("input", Map.of("question", "q")), "call-1"));

        var result = gate(executor(runner), false).invokeAll(TENANT, task(), calls);

        assertThat(result.rejectedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).isZero();
        assertThat(result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME))
                .get()
                .satisfies(d -> assertThat(d.decision().reasonCode())
                        .isEqualTo(ReasonCode.PARAM_SCHEMA_FAIL));
    }

    @Test
    @DisplayName("Run 已取消 → 拒绝发起委派且不产生子执行（MA-T11）")
    void cancelledRunDoesNotStartDelegation() {
        CountingRunner runner = new CountingRunner();

        var result = gate(executor(runner), true)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.rejectedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(runner.calls.get()).as("取消后不得发起任何子执行").isZero();
        assertThat(result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME))
                .get()
                .satisfies(d -> assertThat(d.decision().reasonCode())
                        .isEqualTo(ReasonCode.RUN_CANCELLED));
    }

    @Test
    @DisplayName("子执行结果不确定 → 记为 UNKNOWN（须转对账，不得当普通失败 · MA-T13）")
    void unknownChildResultIsRecordedAsUnknown() {
        CountingRunner runner = new CountingRunner();
        runner.result = new ChildAgentResult(
                AgentInvocationStatus.UNKNOWN, "", ReasonCode.EXEC_UNKNOWN.name());

        var result = gate(executor(runner), false)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.unknownResultOccurred()).isTrue();
        assertThat(result.unknownToolName()).isEqualTo(DelegationExecutor.DELEGATION_TOOL_NAME);
    }

    @Test
    @DisplayName("未装配委派执行器 → 委派按「未登记的工具」被拒（不得静默放行）")
    void delegationIsRejectedWhenExecutorIsMissing() {
        var result = gate(null, false)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.rejectedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME))
                .get()
                .satisfies(d -> assertThat(d.decision().reasonCode())
                        .isEqualTo(ReasonCode.TOOL_NOT_DECLARED));
    }

    @Test
    @DisplayName("子执行已启动但失败 → 记为执行失败（授权 ALLOW + 执行 DENY），不得说成越权被拒")
    void childExecutionFailureIsNotRecordedAsAuthorizationDenial() {
        CountingRunner runner = new CountingRunner();
        runner.result = ChildAgentResult.failed(ReasonCode.TOOL_EXECUTION_FAILED.name(), "boom");

        var result = gate(executor(runner), false)
                .invokeAll(TENANT, task(), delegationCall("call-1", CHILD));

        assertThat(result.failedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(result.rejectedTools()).as("执行失败不得记成被拒").isEmpty();
        var decision = result.decisionOf(DelegationExecutor.DELEGATION_TOOL_NAME).orElseThrow();
        assertThat(decision.decision().authorization())
                .as("子执行已启动 ⇒ 授权阶段已通过")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(decision.decision().execution())
                .as("执行确实发生了（结果失败，不是没执行）")
                .isEqualTo(AuthorizationDecision.Execution.ALLOW);
        assertThat(decision.decision().status())
                .isEqualTo(AuthorizationDecision.Status.FAILED);
        assertThat(decision.decision().reasonCode()).isEqualTo(ReasonCode.TOOL_EXECUTION_FAILED);
    }

    @Test
    @DisplayName("失败策略 FAIL_FAST → 本轮后续委派不再发起（FR-026 · 07 第 6 步）")
    void failFastStopsSubsequentDelegations() {
        CountingRunner runner = new CountingRunner();
        runner.result = ChildAgentResult.failed(ReasonCode.TOOL_EXECUTION_FAILED.name(), "boom");

        var result = gate(executor(runner), false)
                .invokeAll(TENANT, task(), twoDelegationCalls());

        assertThat(runner.calls.get())
                .as("FAIL_FAST 下后续委派不得再启动子 Agent")
                .isEqualTo(1);
        assertThat(result.failedTools()).containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(result.rejectedTools())
                .as("被跳过的后续委派要记成被拒，而不是悄悄消失")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
    }

    @Test
    @DisplayName("失败策略 CONTINUE_ON_ERROR → 后续委派继续发起（FR-026 · 07 第 6 步）")
    void continueOnErrorKeepsRunningSubsequentDelegations() {
        CountingRunner runner = new CountingRunner();
        runner.result = ChildAgentResult.failed(ReasonCode.TOOL_EXECUTION_FAILED.name(), "boom");

        var result = gate(executor(runner, PARENT_ORCHESTRATION_CONTINUE), false)
                .invokeAll(TENANT, task(), twoDelegationCalls());

        assertThat(runner.calls.get())
                .as("CONTINUE_ON_ERROR 下后续委派必须继续")
                .isEqualTo(2);
        assertThat(result.failedTools()).hasSize(2);
    }
}
