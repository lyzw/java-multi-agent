package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

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

import cn.studykid.multiagent.application.orchestration.DelegationExecutor;
import cn.studykid.multiagent.application.orchestration.DelegationGuard;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner.ChildAgentResult;
import cn.studykid.multiagent.ports.orchestration.ResultContractProvider;
import cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort.TerminalWriteOutcome;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * ROUTER 模式的运行期委派判定（多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：此前 ROUTER 与 LEAF / SEQUENTIAL 的运行期行为
 * <b>完全相同</b> —— {@code routingPolicyRef} 是一个<b>纯悬空引用</b>：
 * 全仓没有任何地方真正持有这个被引用的对象，运行期也不读它。
 * 于是「配置看起来声明了路由策略」与「路由规则真的生效了」是两件毫不相干的事。
 *
 * <p><b>⚠️ 「全都拒」不能算通过</b>：本类同时钉住
 * 「策略允许 → 放行」「策略收窄 → 拒绝」「策略不可用 → fail-closed」
 * 「降级 → 执行实际目标」「非 ROUTER 模式不受影响」两侧。
 */
class RouterDelegationTest {

    private static final String TENANT = "tenant-router";
    private static final String RUN = "run-router";
    private static final String PARENT = "router-agent";
    private static final String CHILD = "child-agent";
    private static final String CHILD_B = "child-b";
    private static final String CHILD_C = "child-c";
    private static final String POLICY_ID = "orders-router";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    /** ROUTER 父 Agent：声明拓扑（两个子）+ 固定版本路由策略引用。 */
    private static final String ROUTER_ORCHESTRATION = """
            {"mode":"ROUTER","routingPolicyRef":"orders-router@1",
             "childAgentRefs":[{"id":"child-agent","version":1},
                               {"id":"child-b","version":1},
                               {"id":"child-c","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** SEQUENTIAL 父 Agent：对照组 —— 不受路由策略影响。 */
    private static final String SEQUENTIAL_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    // ── 夹具 ──────────────────────────────────────────────────────

    private static class RecordingInvocations implements AgentInvocationPort {
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
                    row.startedAt(), finishedAt, reasonCode, fencingToken));
            return TerminalWriteOutcome.WRITTEN;
        }
    }

    /** 记录调用次数与**实际收到的目标**的子执行器。 */
    private static final class RecordingRunner implements ChildAgentRunner {
        final AtomicInteger calls = new AtomicInteger();
        String lastTargetAgentId;
        ChildAgentResult result = ChildAgentResult.succeeded("{\"answer\":\"ok\"}");

        @Override
        public @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request) {
            calls.incrementAndGet();
            lastTargetAgentId = request.targetAgentId();
            return result;
        }
    }

    /** 恒定拒绝的契约提供端口（本类不测契约）。 */
    private static final ResultContractProvider NO_CONTRACT = (tenantId, contractRef) -> null;

    private static CapFixture fixture(String parentOrchestration) {
        return new CapFixture()
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, PARENT, 1, List.of(), "{}", parentOrchestration)
                .withAgent(TENANT, CHILD, 1, List.of("skill-a"), "{}", "{}")
                .withAgent(TENANT, CHILD_B, 1, List.of("skill-a"), "{}", "{}")
                .withAgent(TENANT, CHILD_C, 1, List.of("skill-a"), "{}", "{}")
                .withAuthzVersion(TENANT, "worker", 1L)
                .withSnapshot(new CapabilitySnapshot(RUN, TENANT, PARENT, "{}", 1, 1L,
                        "cap_fp_v1:parent", 1L, Instant.EPOCH));
    }

    private static CapRoutingPolicyDef policy(RoutingStrategy strategy, List<String> targets) {
        return new CapRoutingPolicyDef(TENANT, POLICY_ID, 1, strategy, targets,
                "fp-policy", 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static DelegationExecutor executor(CapFixture fixture,
                                               AgentInvocationPort invocations,
                                               ChildAgentRunner runner,
                                               RoutingPolicyProvider routingPolicies) {
        DelegationGuard guard = new DelegationGuard(
                fixture.agentDefs(),
                new AuthorizationService() {
                    @Override
                    public boolean isAuthorized(TenantContext context, String action,
                                                String resourceId) {
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
                },
                fixture.authzVersions(),
                (tenantId, runId) -> true);

        PermissionResolver resolver = new PermissionResolver(
                fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());

        return new DelegationExecutor(guard, fixture.agentDefs(), resolver,
                fixture.snapshots(), fixture.authzVersions(), invocations, runner,
                NO_CONTRACT, routingPolicies, CLOCK);
    }

    private static DelegationExecutor.DelegationCommand command(String target) {
        return new DelegationExecutor.DelegationCommand(
                TENANT, RUN, null, null, null, 1, target, null,
                Map.of("question", "q"), "inv-1", RUN, 10_000L, 1L, 1);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ ROUTER：候选落在策略允许集合内 → 委派成功")
    void routerDelegatesAllowedTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD)))
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.lastTargetAgentId).isEqualTo(CHILD);
        assertThat(invocations.rows.get("inv-1").targetAgentId()).isEqualTo(CHILD);
    }

    @Test
    @DisplayName("⛔ ROUTER：候选不在策略允许集合内（EXPLICIT_TARGETS）→ 拒绝且不执行子 Agent")
    void routerRejectsTargetOutsidePolicy() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD)))
                .delegate(command(CHILD_B));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .isEqualTo(ReasonCode.ROUTING_TARGET_NOT_ALLOWED.name());
        assertThat(runner.calls.get()).as("被拒的委派不得触发子执行").isZero();
        assertThat(invocations.rows).as("被拒的委派不落调用记录").isEmpty();
    }

    @Test
    @DisplayName("⛔ ROUTER：策略取不到 → fail-closed（ROUTING_POLICY_UNAVAILABLE），绝不按声明随便选")
    void routerFailsClosedWhenPolicyUnavailable() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner, (tenantId, ref) -> null)
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .isEqualTo(ReasonCode.ROUTING_POLICY_UNAVAILABLE.name());
        assertThat(runner.calls.get())
                .as("⛔ 策略不可用却照样委派：路由规则写了等于没写")
                .isZero();
        assertThat(invocations.rows).isEmpty();
    }

    @Test
    @DisplayName("⛔ ROUTER：路由策略能力未装配 → fail-closed（不得降级成「随便选一个子 Agent」）")
    void routerFailsClosedWhenProviderNotWired() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner, null).delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .isEqualTo(ReasonCode.ROUTING_POLICY_UNAVAILABLE.name());
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("⛔ ROUTER：策略读取抛异常 → fail-closed（读失败 ≠ 「没有策略」）")
    void routerFailsClosedWhenProviderThrows() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner, (tenantId, ref) -> {
            throw new IllegalStateException("策略表读不到");
        }).delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .isEqualTo(ReasonCode.ROUTING_POLICY_UNAVAILABLE.name());
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("✅ ROUTER：候选越出委派拓扑 → 报「目标未声明」而非「越出路由允许集」")
    void routerReportsTopologyViolationBeforePolicyViolation() {
        // ⚠️ <b>判定顺序的证据</b>：策略允许集是拓扑的子集（发布期强制），
        //    所以「越出拓扑」的候选必然也「不在策略允许集内」。
        //    若把路由收窄判在拓扑之前，这条委派会被报成 ROUTING_TARGET_NOT_ALLOWED，
        //    MA-T05 既有语义（越界试探 = DELEGATION_TARGET_NOT_DECLARED）就被改写了。
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD)))
                .delegate(command("not-declared-agent"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("⛔ 越出委派拓扑被报成「越出路由允许集」：两个原因的排查方向完全不同")
                .isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED.name());
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("✅ ROUTER + ORDERED_FALLBACK：候选未命中 → 按声明顺序降级到第一个目标")
    void routerFallsBackInDeclaredOrder() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // ⚠️ 候选 child-agent 在**委派拓扑内**、但**不在策略允许集内** ——
        //    这才触发降级（拓扑检查在路由收窄之前，见执行器的判定顺序）。
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(RoutingStrategy.ORDERED_FALLBACK,
                        List.of(CHILD_B, CHILD_C)))
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.lastTargetAgentId)
                .as("⚠️ 降级必须落到策略声明顺序里的第一个目标")
                .isEqualTo(CHILD_B);
        assertThat(invocations.rows.get("inv-1").targetAgentId())
                .as("委派记录必须写**实际生效**的目标，否则审计看到的是模型候选而非事实")
                .isEqualTo(CHILD_B);
    }

    @Test
    @DisplayName("⛔ ROUTER + ORDERED_FALLBACK：降级目标不在父声明拓扑内 → 拒绝（降级不越过拓扑）")
    void routerFallbackStillRequiresDeclaredTopology() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(RoutingStrategy.ORDERED_FALLBACK,
                        List.of("not-declared")))
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED.name());
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("✅ 非 ROUTER 模式不受路由策略影响：策略能力未装配也照常委派")
    void nonRouterIsUnaffectedByRoutingPolicy() {
        // ⚠️ 这是「ROUTER 分支没有污染其它模式」的对照：
        //    若把策略判定写成了「所有模式都判」，本例会变红。
        CapFixture fixture = fixture(SEQUENTIAL_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner, null).delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
    }
}
