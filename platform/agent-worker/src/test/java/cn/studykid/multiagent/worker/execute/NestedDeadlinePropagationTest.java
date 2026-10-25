package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.orchestration.DelegationContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider;
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
 * 根 Run 业务截止时间在<b>嵌套路径</b>上的贯穿测试（多级 Agent v1.1 · 第十五批 · C15-2）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：此前子执行构造闸门任务时恒传
 * {@code deadlineAt = null}（= 不限时）→
 * 子 Agent 再发起<b>嵌套委派</b>时，闸门算出的剩余时间是 {@link Long#MAX_VALUE} →
 * <b>根 Run 的剩余时间在嵌套路径上彻底失效</b>。
 *
 * <p>后果是「全调用树共享超时上限」被架空：
 * <pre>
 *   根 Run 剩余 5s → 根委派子 Agent（预算 5s，✅ 正确）
 *      └─ 子 Agent 自己声明 timeoutMs = 30s
 *         └─ 子 Agent 再委派孙 Agent → 预算 = min(无限, 30s) = 30s  ← ❌ 根的 5s 被丢掉
 * </pre>
 * 也就是说：只要多套一层，根 Run 的业务截止时间就不再有任何强制力。
 *
 * <p>⚠️ <b>本类走的是完整的两跳链路</b>（根 → 子 → 孙），
 * 且子/孙都由真实的 {@link WorkerChildAgentRunner} 执行 ——
 * 只在闸门层断言「用了 task.deadlineAt」是不够的：
 * 真正要证明的是<b>根的截止时刻能穿过子执行、抵达孙执行</b>。
 */
class NestedDeadlinePropagationTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String ROOT = "root-agent";
    private static final String MID = "mid-agent";
    private static final String LEAF = "leaf-agent";

    /** 固定的「当前时刻」——时间判定必须是确定事实，不靠 sleep 去撞。 */
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    /** 根 Run 的业务截止时刻：NOW + 5s。 */
    private static final Instant ROOT_DEADLINE = NOW.plusSeconds(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 根只声明了 MID。 */
    private static final String ROOT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"mid-agent","version":1}],
             "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** MID 声明了 LEAF，且**自己声明了 30s 的链路超时**（比根剩余长得多）。 */
    private static final String MID_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"leaf-agent","version":1}],
             "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000,
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

    /**
     * 记录每一跳子执行请求的替身执行器。
     *
     * <p>⚠️ 它<b>延迟委派</b>给真实执行器：委派执行器必须先于闸门构造，
     * 而闸门又要先于子执行器构造 —— 用延迟委派打断这个环。
     */
    private static final class RecordingRunner implements ChildAgentRunner {
        final List<ChildAgentRequest> requests = new ArrayList<>();
        ChildAgentRunner delegate;

        @Override
        public @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request) {
            requests.add(request);
            if (delegate == null) {
                throw new IllegalStateException("替身执行器尚未装配真实执行器");
            }
            return delegate.run(request);
        }
    }

    /** 按次序返回响应的模型网关替身。 */
    private static final class ScriptedGateway implements ModelGateway {
        final java.util.Queue<ModelResponse> sequence = new java.util.ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        /** 最近一次模型调用的超时（用于断言预算封顶）。 */
        Duration lastTimeout;

        @Override
        public @NonNull ModelResponse complete(@NonNull ModelRequest request) {
            calls.incrementAndGet();
            lastTimeout = request.timeout();
            ModelResponse next = sequence.poll();
            // ⚠️ 队列空时返回默认答复而不是抛异常：
            //    有些用例只关心**第一次**模型调用的预算，不需要后续轮次。
            //    调用次序由各用例自己断言次数。
            return next == null
                    ? plainResponse("done")
                    : next;
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private static CapAgentDef def(String agentId, String orchestration) {
        return new CapAgentDef(TENANT, agentId, 1, agentId, agentId,
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-" + agentId, 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static AuthzVersionPort authzVersions() {
        return new AuthzVersionPort() {
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
    }

    private static CapExecutionFlow enabledCapFlow() {
        return new CapExecutionFlow(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ONE,
                CapExecutionFlow.DEFAULT_PRINCIPAL_ID, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    /** 子 Agent 的模型配置（按 agentId 区分系统提示）。 */
    private static ChildAgentModelConfigProvider configs() {
        return (tenantId, agentId, agentVersion) -> Optional.of(
                new ChildAgentModelConfigProvider.ChildModelConfig(
                        "model-" + agentId, "你是 " + agentId, 0.2, 512,
                        Duration.ofSeconds(30), Map.of()));
    }

    private static ModelResponse delegationResponse(String callId, String target) {
        return new ModelResponse("", Map.of(callId, new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", target, "input", Map.of("question", "q")), callId)),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.TOOL_CALLS);
    }

    private static ModelResponse plainResponse(String content) {
        return new ModelResponse(content, Map.of(),
                new ModelResponse.ModelUsage(10, 5), ModelResponse.FinishReason.STOP);
    }

    private static Map<String, ModelResponse.ToolCallRequest> delegationCall(
            String callId, String target) {
        return Map.of(callId, new ModelResponse.ToolCallRequest(
                DelegationExecutor.DELEGATION_TOOL_NAME,
                Map.of("targetAgentId", target, "input", Map.of("question", "q")),
                callId));
    }

    /** 根 Run 的任务：带业务截止时刻。 */
    private static RunClaimPort.LeasedTask rootTask() {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.MAX, 1, ROOT_DEADLINE);
    }

    /** 装配产物：闸门 + 记录型执行器（闸门与执行器互相持有，成对返回）。 */
    private record Fixture(@NonNull ToolInvocationGate gate,
                           @NonNull RecordingRunner recorder) {
    }

    /** 装配「根 → 子 → 孙」两跳链路。 */
    private static Fixture assemble(ScriptedGateway gateway) {
        return assemble(gateway, CLOCK);
    }

    /**
     * 装配「根 → 子 → 孙」两跳链路。
     *
     * @param gateway    模型网关替身
     * @param childClock <b>子执行</b>使用的墙钟 —— 与闸门的时钟可以不同：
     *                   用来构造「委派获批时根还有时间、但子执行真正启动时根已过期」
     *                   这个竞态窗口
     */
    private static Fixture assemble(ScriptedGateway gateway, Clock childClock) {
        InMemoryAgentDefs agentDefs = new InMemoryAgentDefs(
                def(ROOT, ROOT_ORCHESTRATION),
                def(MID, MID_ORCHESTRATION),
                def(LEAF, "{}"));
        AuthzVersionPort authz = authzVersions();
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
        DelegationGuard guard = new DelegationGuard(agentDefs, ALLOW_ALL, authz,
                (tenantId, runId) -> true);
        ResultContractProvider noContract = (tenantId, contractRef) -> null;
        RecordingRunner recorder = new RecordingRunner();
        DelegationExecutor executor = new DelegationExecutor(guard, agentDefs, resolver,
                snapshots, authz, new InMemoryInvocations(), recorder, noContract, CLOCK);

        cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource approveAll = (tenantId, handler) ->
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict.APPROVED;
        ToolInvocationGate gate = new ToolInvocationGate(
                List.<ToolHandler>of(),
                ALLOW_ALL,
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy
                        .ApprovalDecision.notRequired(),
                GRANT_BUDGET, FRESH_LEDGER, NO_APPROVAL,
                BigDecimal.ONE, (t, r) -> false, approveAll,
                enabledCapFlow(), null, executor, CLOCK);

        // ⚠️ 真实子执行器用**同一个闸门**：子 Agent 的嵌套委派必须再走一次闸门，
        //    否则测的就是「一次性链路」而不是「嵌套路径」。
        recorder.delegate = new WorkerChildAgentRunner(gateway, gate, configs(), 4, null,
                System::nanoTime, childClock);
        return new Fixture(gate, recorder);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 根 Run 的业务截止时刻逐跳下传（根 → 子 → 孙 都拿到同一个绝对时刻）")
    void rootDeadlineIsPropagatedToEveryHop() {
        ScriptedGateway gateway = new ScriptedGateway();
        // 调用次序：MID 第 1 轮（发起嵌套委派）→ LEAF 第 1 轮 → MID 第 2 轮
        gateway.sequence.add(delegationResponse("call-mid", LEAF));
        gateway.sequence.add(plainResponse("leaf done"));
        gateway.sequence.add(plainResponse("mid done"));
        Fixture fixture = assemble(gateway);

        var result = fixture.gate().invokeAll(TENANT, rootTask(),
                delegationCall("call-root", MID));
        RecordingRunner recorder = fixture.recorder();

        assertThat(result.succeededTools())
                .as("⛔ 两跳链路没跑通：根委派与嵌套委派都必须成功")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(recorder.requests)
                .as("应当恰好发生两次子执行（子 → 孙）")
                .hasSize(2);
        assertThat(recorder.requests.get(0).targetAgentId()).isEqualTo(MID);
        assertThat(recorder.requests.get(1).targetAgentId()).isEqualTo(LEAF);
        assertThat(recorder.requests.get(0).rootDeadlineAt())
                .as("⛔ 子执行没拿到根 Run 的业务截止时刻")
                .isEqualTo(ROOT_DEADLINE);
        assertThat(recorder.requests.get(1).rootDeadlineAt())
                .as("⛔ 孙执行没拿到根 Run 的业务截止时刻：根截止时刻必须逐跳下传")
                .isEqualTo(ROOT_DEADLINE);
        assertThat(gateway.calls.get())
                .as("调用次序：MID 第 1 轮 → LEAF 第 1 轮 → MID 第 2 轮")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("⛔ 嵌套委派的预算必须被**根 Run 的剩余时间**封顶（不是被本级声明重置）")
    void nestedBudgetIsCappedByRootRemainingTime() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.sequence.add(delegationResponse("call-mid", LEAF));
        gateway.sequence.add(plainResponse("leaf done"));
        gateway.sequence.add(plainResponse("mid done"));
        Fixture fixture = assemble(gateway);

        fixture.gate().invokeAll(TENANT, rootTask(), delegationCall("call-root", MID));
        RecordingRunner recorder = fixture.recorder();

        assertThat(recorder.requests.get(1).timeoutMs())
                .as("⛔ 孙执行的预算被重置成 MID 自己声明的 30s："
                        + "根 Run 只剩 5s 时，嵌套路径不得拿到比根更长的预算")
                .isEqualTo(5_000L);
        assertThat(recorder.requests.get(0).timeoutMs())
                .as("子执行的预算本来就该是根剩余（5s）")
                .isEqualTo(5_000L);
    }

    @Test
    @DisplayName("⛔ 根 Run 的业务截止时刻已过 → 子执行不启动（不得明知会超时还要先花钱）")
    void expiredRootDeadlinePreventsChildExecution() {
        ScriptedGateway gateway = new ScriptedGateway();
        Fixture fixture = assemble(gateway);

        // 根 Run 的截止时刻已过（NOW - 1s）
        RunClaimPort.LeasedTask expired = new RunClaimPort.LeasedTask(
                RUN, 1L, Instant.MAX, 1, NOW.minusSeconds(1));

        var result = fixture.gate().invokeAll(TENANT, expired,
                delegationCall("call-root", MID));

        assertThat(result.rejectedTools())
                .as("⛔ 根 Run 已过截止时刻却仍发起了委派")
                .containsExactly(DelegationExecutor.DELEGATION_TOOL_NAME);
        assertThat(fixture.recorder().requests)
                .as("⛔ 已过截止时刻仍启动了子执行")
                .isEmpty();
        assertThat(gateway.calls.get()).isZero();
    }

    @Test
    @DisplayName("⛔ 委派获批后、子执行启动前根 Run 已过期 → 子执行不启动（竞态窗口）")
    void childExecutionRefusesToStartAfterRootDeadlinePassed() {
        ScriptedGateway gateway = new ScriptedGateway();
        // ⚠️ 闸门时钟 = NOW（根还剩 5s → 委派获批）；
        //    子执行墙钟 = NOW + 10s（**子执行真正启动时**根已过期 5s）。
        //    这个竞态窗口只能靠「子执行自己比对绝对截止时刻」拦住 ——
        //    父给的相对预算（5s）在这一刻仍然是正的。
        Fixture fixture = assemble(gateway, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));

        fixture.gate().invokeAll(TENANT, rootTask(), delegationCall("call-root", MID));

        assertThat(gateway.calls.get())
                .as("⛔ 根 Run 已过期，子执行仍发起了模型调用（明知会超时还要先花钱）")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 子执行的墙钟预算被**根 Run 的真实剩余**封顶（不是委派获批那一刻的快照）")
    void childWallClockIsCappedByActualRootRemaining() {
        ScriptedGateway gateway = new ScriptedGateway();
        // ⚠️ 子执行启动时根只剩 2s（NOW + 3s 启动，根截止 NOW + 5s），
        //    而委派获批时给的相对预算是 5s —— 子执行必须按**实际剩余**收敛。
        Fixture fixture = assemble(gateway, Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));

        fixture.gate().invokeAll(TENANT, rootTask(), delegationCall("call-root", MID));

        assertThat(gateway.calls.get()).isEqualTo(1);
        var modelTimeout = gateway.lastTimeout;
        assertThat(modelTimeout)
                .as("⛔ 单次模型调用仍按委派获批时的 5s 预算执行："
                        + "根 Run 实际只剩 2s 时不得再花 5s")
                .isLessThanOrEqualTo(Duration.ofMillis(2_000L));
        assertThat(modelTimeout)
                .as("封顶后也不得为 0（否则模型连一次都发不出去）")
                .isGreaterThan(Duration.ofMillis(1_900L));
    }
}
