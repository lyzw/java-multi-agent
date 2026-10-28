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
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner.ChildAgentResult;
import cn.studykid.multiagent.ports.orchestration.ResultContractProvider;
import cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort.TerminalWriteOutcome;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * ROUTER 模式的运行期委派判定（第十四批 · MA-T05 / MA-T15；B4 起为<b>两阶段决策</b>）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：此前 ROUTER 与 LEAF / SEQUENTIAL 的运行期行为
 * <b>完全相同</b> —— {@code routingPolicyRef} 是一个<b>纯悬空引用</b>：
 * 全仓没有任何地方真正持有这个被引用的对象，运行期也不读它。
 * 于是「配置看起来声明了路由策略」与「路由规则真的生效了」是两件毫不相干的事。
 *
 * <p><b>⚠️ 「全都拒」不能算通过</b>：本类同时钉住
 * 「规则命中 → 放行」「规则改判 → 执行规则的目标」「无规则命中 → 按 {@code no_match_action}」
 * 「策略不可用 → fail-closed」「兜底不越过拓扑」「非 ROUTER 模式不受影响」六侧。
 *
 * <p><b>B4 新增的四侧</b>（10 号设计 §6）：
 * {@code RULES_ONLY} 下模型候选<b>不参与判定</b>、{@code RULE_FIRST} 的
 * {@code on_conflict} 两向裁决、决策快照落库、快照<b>不含输入明文</b>。
 *
 * <p><b>夹具口径</b>（{@link CapFixture#routingPolicy}）：第 N 个目标对应规则
 * {@code in.topic == "case-N"}，所以本类用 {@code topic} 表达「这次输入该走到谁」。
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

    /** ROUTER 父 Agent：声明拓扑（三个子）+ 固定版本路由策略引用。 */
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
            // ⚠️ 终态 UPDATE 只改状态/原因/输出摘要三件事，
            //    决策快照是**插入时**就冻结的事实，不得被终态写清掉。
            rows.put(invocationId, new AgentInvocation(row.invocationId(), row.tenantId(),
                    row.runId(), row.parentInvocationId(), row.parentAgentId(),
                    row.parentAgentVersion(), row.targetAgentId(), row.targetAgentVersion(),
                    row.requestedTargetAgentId(),
                    status, row.depth(), row.traceId(), row.inputDigest(), outputDigest,
                    row.startedAt(), finishedAt, reasonCode, fencingToken,
                    row.routingDecision(), row.policyRef(), row.policyFingerprint()));
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

    private static CapRoutingPolicyDef policy(NoMatchAction noMatchAction, List<String> targets) {
        return CapFixture.routingPolicy(TENANT, POLICY_ID, 1, noMatchAction, targets,
                "fp-policy", Instant.EPOCH, null);
    }

    private static CapRoutingPolicyDef policy(NoMatchAction noMatchAction, List<String> targets,
                                              RoutingMode mode, ConflictPolicy onConflict) {
        return CapFixture.routingPolicy(TENANT, POLICY_ID, 1, noMatchAction, targets,
                "fp-policy", Instant.EPOCH, null, mode, onConflict);
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

    /**
     * 委派请求：模型候选 + {@code topic}（夹具规则的唯一判据）。
     *
     * @param target 模型给出的候选目标
     * @param topic  路由输入字段；{@code "case-N"} 命中夹具的第 N 条规则，其它值一条也不命中
     */
    private static DelegationExecutor.DelegationCommand command(String target, String topic) {
        return new DelegationExecutor.DelegationCommand(
                TENANT, RUN, null, null, null, 1, target, null,
                Map.of("question", "q", "topic", topic), "inv-1", RUN, 10_000L, 1L, 1);
    }

    private static DelegationExecutor.DelegationCommand command(String target) {
        return command(target, "case-1");
    }

    // ── 用例：判定顺序与 fail-closed ───────────────────────────────

    @Test
    @DisplayName("✅ ROUTER：规则命中候选自己 → 委派成功")
    void routerDelegatesAllowedTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD)))
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.lastTargetAgentId).isEqualTo(CHILD);
        assertThat(invocations.rows.get("inv-1").targetAgentId()).isEqualTo(CHILD);
        assertThat(invocations.rows.get("inv-1").requestedTargetAgentId())
                .as("未改判时「模型原始候选」= 实际生效目标")
                .isEqualTo(CHILD);
        assertThat(invocations.rows.get("inv-1").routedFallback())
                .as("未改判不得被记成降级")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ ROUTER（no_match_action=REJECT）：没有任何规则命中 → ROUTING_NO_MATCH_REJECTED，不执行子 Agent")
    void routerRejectsWhenNoRuleMatches() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // topic 不匹配夹具的任何一条规则（case-N）
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD)))
                .delegate(command(CHILD, "topic-nobody-wrote-a-rule-for"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("规则没覆盖这类输入 = 配置覆盖面缺口，不是「候选越出允许集」")
                .isEqualTo(ReasonCode.ROUTING_NO_MATCH_REJECTED.name());
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
    @DisplayName("⛔ ROUTER：已发布的策略文本此刻判不出来（配置漂移）→ fail-closed，绝不按候选放行")
    void routerFailsClosedWhenPolicyTextUnparseable() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // 造一条「库里能存、引擎判不出」的行：把 evaluation 换成文法之外的形状
        CapRoutingPolicyDef broken = new CapRoutingPolicyDef(TENANT, POLICY_ID, 1,
                RoutingMode.RULES_ONLY, CapFixture.ROUTING_INPUT_SCHEMA,
                "[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"child-agent\","
                        + "\"when\":{\"sql\":\"1=1\"}}]",
                null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp-broken", 1, Instant.EPOCH, Instant.EPOCH, null);

        var outcome = executor(fixture, invocations, runner, (tenantId, ref) -> broken)
                .delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("坏策略表现得像好策略 = 本仓库反复出现的「看起来生效、实际不生效」")
                .isEqualTo(ReasonCode.ROUTING_POLICY_UNAVAILABLE.name());
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("✅ ROUTER：候选越出委派拓扑 → 报「目标未声明」而非路由码")
    void routerReportsTopologyViolationBeforePolicyViolation() {
        // ⚠️ <b>判定顺序的证据</b>：① 拓扑准入在②③④ 之前。
        //    若把路由收窄判在拓扑之前，这条委派会被报成某个路由码，
        //    MA-T05 既有语义（越界试探 = DELEGATION_TARGET_NOT_DECLARED）就被改写了。
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD)))
                .delegate(command("not-declared-agent"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("⛔ 越出委派拓扑被报成路由问题：两个原因的排查方向完全不同")
                .isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED.name());
        assertThat(runner.calls.get()).isZero();
    }

    // ── 用例：B4 两阶段决策的四个模式分支 ───────────────────────────

    @Test
    @DisplayName("✅ RULES_ONLY：模型候选<b>不参与判定</b> —— 规则说给谁就给谁（改判）")
    void rulesOnlyIgnoresModelCandidate() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // 候选是 child-agent，但 topic=case-2 命中的规则指向 child-b
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B)))
                .delegate(command(CHILD, "case-2"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.lastTargetAgentId)
                .as("RULES_ONLY 下转发真正「按规则」：模型连目标选择权都没有")
                .isEqualTo(CHILD_B);
        assertThat(invocations.rows.get("inv-1").targetAgentId()).isEqualTo(CHILD_B);
        assertThat(invocations.rows.get("inv-1").requestedTargetAgentId())
                .as("改判同样要能看出来：候选与生效目标并存")
                .isEqualTo(CHILD);
        assertThat(snapshotOf(invocations))
                .contains("\"resolution\":\"RULE_MATCH\"")
                .as("RULES_ONLY 下引擎**不消费**模型候选（快照里 candidateFromModel 恒为 null）；"
                        + "候选本身仍由 requested_target_agent_id 列留痕，两件事不互相替代")
                .contains("\"candidateFromModel\":null")
                .contains("\"finalTarget\":\"" + CHILD_B + "\"");
    }

    @Test
    @DisplayName("✅ RULE_FIRST + RULE_WINS：规则与模型候选冲突 → <b>规则赢</b>，且冲突留痕")
    void ruleWinsConflictKeepsRuleTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B),
                        RoutingMode.RULE_FIRST, ConflictPolicy.RULE_WINS))
                .delegate(command(CHILD, "case-2"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.lastTargetAgentId)
                .as("RULE_WINS 不改判定：命中的规则就是答案")
                .isEqualTo(CHILD_B);
        assertThat(snapshotOf(invocations))
                .as("冲突必须留痕 —— 否则「规则与模型不一致」这件事在数据上不存在")
                .contains("\"conflict\":true")
                .contains("\"resolution\":\"RULE_MATCH\"");
    }

    @Test
    @DisplayName("✅ RULE_FIRST + CANDIDATE_WINS：规则与模型候选冲突 → 采纳<b>模型候选</b>")
    void candidateWinsAdoptsModelTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B),
                        RoutingMode.RULE_FIRST, ConflictPolicy.CANDIDATE_WINS))
                .delegate(command(CHILD, "case-2"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.lastTargetAgentId)
                .as("on_conflict=CANDIDATE_WINS 时采纳模型给的目标")
                .isEqualTo(CHILD);
        assertThat(snapshotOf(invocations))
                .contains("\"resolution\":\"SEMANTIC_MATCH\"")
                .contains("\"conflict\":true")
                .contains("\"finalTarget\":\"" + CHILD + "\"");
    }

    @Test
    @DisplayName("✅ RULE_FIRST：规则全部未命中 → 语义引擎采纳<b>策略点名的</b>候选（不是拒绝）")
    void ruleFirstFallsThroughToSemantic() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // child-b 在策略的规则目标里，但本次输入不匹配任何一条 case-N ⇒ 纯语义出口。
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B),
                        RoutingMode.RULE_FIRST, ConflictPolicy.RULE_WINS))
                .delegate(command(CHILD_B, "topic-no-rule-covers"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.lastTargetAgentId)
                .as("RULE_FIRST 的「无命中」正是语义引擎的入场条件，REJECT 只用于规则也判不动的场合")
                .isEqualTo(CHILD_B);
        assertThat(snapshotOf(invocations))
                .contains("\"resolution\":\"SEMANTIC_MATCH\"")
                .contains("\"matchedRuleId\":null");
    }

    @Test
    @DisplayName("⛔ MA-T05a：候选在<b>拓扑内</b>、却越出<b>策略点名的目标集</b> → 拒绝且零子执行")
    void ruleFirstRejectsCandidateOutsidePolicyWorkbook() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // child-c 是父声明的三个子之一（过得了阶段⑤ 准入），策略却只点名 child-agent/child-b。
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B),
                        RoutingMode.RULE_FIRST, ConflictPolicy.RULE_WINS))
                .delegate(command(CHILD_C, "topic-no-rule-covers"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("「策略可以比拓扑更窄」只在候选被拦时才成立；报成 NO_MATCH 会把「模型乱指」读成「规则没写全」")
                .isEqualTo(ReasonCode.ROUTING_TARGET_NOT_ALLOWED.name());
        assertThat(runner.calls.get()).as("被拒的委派不得触发子执行").isZero();
        assertThat(invocations.rows).as("被拒的委派不落调用记录").isEmpty();
    }

    // ── 用例：兜底与准入 ───────────────────────────────────────────

    @Test
    @DisplayName("✅ ROUTER + no_match_action=DEFAULT_TARGET：无规则命中 → 降级到 default_target")
    void routerFallsBackToDeclaredDefaultTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // 候选 child-agent 在**委派拓扑内**；topic 不命中任何规则 → 走兜底
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.DEFAULT_TARGET,
                        List.of(CHILD_B, CHILD_C)))
                .delegate(command(CHILD, "topic-no-rule-covers"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(runner.lastTargetAgentId)
                .as("兜底必须落到策略作者**指名道姓**写下的 default_target（列表首元）")
                .isEqualTo(CHILD_B);
        assertThat(invocations.rows.get("inv-1").targetAgentId())
                .as("委派记录必须写**实际生效**的目标，否则审计看到的是模型候选而非事实")
                .isEqualTo(CHILD_B);
        // ⚠️ 第二十三批 A3：降级发生过，就**必须**能从数据上看出来。
        //    只记生效目标的话，「这次是不是降级了、模型原本想选谁」就只剩日志 ——
        //    日志会轮转、会丢，事后无法复核。
        assertThat(invocations.rows.get("inv-1").requestedTargetAgentId())
                .as("模型给出的原始候选必须单独落库（降级的审计线索）")
                .isEqualTo(CHILD);
        assertThat(invocations.rows.get("inv-1").routedFallback())
                .as("原始候选 ≠ 生效目标 = 发生了路由降级")
                .isTrue();
        assertThat(snapshotOf(invocations)).contains("\"resolution\":\"DEFAULT_TARGET\"");
    }

    @Test
    @DisplayName("✅ 第二十三批 A3：「模型原始候选」与「实际生效目标」并存 —— 两者都不丢")
    void modelCandidateAndEffectiveTargetAreBothRecorded() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.DEFAULT_TARGET,
                        List.of(CHILD_B, CHILD_C)))
                .delegate(command(CHILD, "topic-no-rule-covers"));

        var row = invocations.rows.get("inv-1");
        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        // ⚠️ **成对断言**：「看得见降级」与「不该丢的事实」必须同时成立 ——
        //    只记候选 → 审计看到的是「并非事实」的委派；
        //    只记生效目标 → 降级这件事消失。
        assertThat(row.targetAgentId())
                .as("实际生效的目标（谁真的被调用了）—— 权威事实，不能被候选覆盖")
                .isEqualTo(CHILD_B);
        assertThat(row.requestedTargetAgentId())
                .as("模型原始候选（模型本来想要谁）—— 审计线索")
                .isEqualTo(CHILD);
    }

    @Test
    @DisplayName("✅ 第二十三批 A3：「未记录」不得被当成「没有降级」")
    void unrecordedCandidateIsNotAssertedAsNoFallback() {
        // ⚠️ 存量行（迁移前写入的）没有这一列 → NULL 的语义是「**当时未记录**」，
        //    不是「没有降级」。据此断言「这次没降级」就是**无中生有**。
        var row = new cn.studykid.multiagent.core.orchestration.AgentInvocation(
                "inv-x", TENANT, RUN, null, PARENT, 1, CHILD_B, 1,
                cn.studykid.multiagent.core.orchestration.AgentInvocationStatus.SUCCEEDED,
                1, "trace-x", null, null, Instant.EPOCH, Instant.EPOCH, null, 1L);

        assertThat(row.requestedTargetAgentId()).isNull();
        assertThat(row.routedFallback())
                .as("NULL 只意味着「无从判断」，不得据此断言「没降级」")
                .isFalse();
        assertThat(row.targetAgentId())
                .as("但「实际生效的目标」始终可读 —— 那一列从未缺过")
                .isEqualTo(CHILD_B);
    }

    @Test
    @DisplayName("⛔ ROUTER + DEFAULT_TARGET：兜底目标不在父声明拓扑内 → 拒绝（兜底不越过拓扑）")
    void routerFallbackStillRequiresDeclaredTopology() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.DEFAULT_TARGET,
                        List.of("not-declared")))
                .delegate(command(CHILD, "topic-no-rule-covers"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.FAILED);
        assertThat(outcome.reasonCode())
                .as("②③④ 选出的目标只是「建议」，⑤ 准入对兜底路径同样不打折")
                .isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED.name());
        assertThat(runner.calls.get()).isZero();
        assertThat(invocations.rows).isEmpty();
    }

    @Test
    @DisplayName("✅ 非 ROUTER 模式不受路由策略影响：策略能力未装配也照常委派，且不落决策快照")
    void nonRouterIsUnaffectedByRoutingPolicy() {
        // ⚠️ 这是「ROUTER 分支没有污染其它模式」的对照：
        //    若把策略判定写成了「所有模式都判」，本例会变红。
        CapFixture fixture = fixture(SEQUENTIAL_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        var outcome = executor(fixture, invocations, runner, null).delegate(command(CHILD));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(invocations.rows.get("inv-1").routingDecision())
                .as("没做路由决策 ≠ 决策失败：NULL 的语义是「本模式没有路由这一层」")
                .isNull();
    }

    // ── 用例：决策快照（B4 · §6.1）─────────────────────────────────

    @Test
    @DisplayName("✅ 决策快照与目标<b>同批落库</b>：policyRef / 指纹 / 输入摘要 / 轨迹")
    void decisionSnapshotIsPersistedWithTheTarget() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD, CHILD_B)))
                .delegate(command(CHILD, "case-2"));

        var row = invocations.rows.get("inv-1");
        assertThat(row.policyRef())
                .as("重放不变式的主键半边必须能直接查，不依赖 jsonb 里的键")
                .isEqualTo(POLICY_ID + "@1");
        assertThat(row.policyFingerprint()).isEqualTo("fp-policy");
        assertThat(row.routingDecision())
                .contains("\"policyRef\":\"" + POLICY_ID + "@1\"")
                .contains("\"policyFingerprint\":\"fp-policy\"")
                .contains("\"mode\":\"RULES_ONLY\"")
                .contains("\"matchedRuleId\":\"r-2\"")
                .contains("\"canonicalInputSha256\":\"")
                .contains("\"evaluationTrace\":[")
                .contains("\"ruleId\":\"r-1\"")
                .contains("\"outcome\":\"NO_MATCH\"");
    }

    @Test
    @DisplayName("⛔ 决策快照<b>不含路由输入明文</b>（零保留红线对本表同样成立）")
    void snapshotCarriesOnlyTheDigestOfInput() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RecordingInvocations invocations = new RecordingInvocations();
        RecordingRunner runner = new RecordingRunner();

        // 夹具里命中规则的那个 topic 值就是「业务输入」的代表：它必须只以摘要形式出现在快照里
        var outcome = executor(fixture, invocations, runner,
                (tenantId, ref) -> policy(NoMatchAction.REJECT, List.of(CHILD)))
                .delegate(command(CHILD, "case-1"));

        assertThat(outcome.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        String snapshot = invocations.rows.get("inv-1").routingDecision();
        assertThat(snapshot).contains("\"canonicalInputSha256\":\"");
        assertThat(snapshot)
                .as("把 in.* 字段抄进 jsonb = 为本表新开一条明文旁路，"
                        + "而重放不变式只需要 canonicalInputSha256")
                .doesNotContain("case-1")
                .doesNotContain("\"canonicalInput\"");
    }

    @Test
    @DisplayName("✅ 同输入重放：两次决策的 {@code canonicalInputSha256} 与目标都相同（§6.1 不变式）")
    void sameInputReplaysToSameTargetAndDigest() {
        CapFixture fixture = fixture(ROUTER_ORCHESTRATION);
        RoutingPolicyProvider provider =
                (tenantId, ref) -> policy(NoMatchAction.DEFAULT_TARGET, List.of(CHILD, CHILD_B));

        RecordingInvocations first = new RecordingInvocations();
        RecordingRunner firstRunner = new RecordingRunner();
        executor(fixture, first, firstRunner, provider).delegate(command(CHILD, "case-2"));

        RecordingInvocations second = new RecordingInvocations();
        RecordingRunner secondRunner = new RecordingRunner();
        // 同一份输入、同一份策略、同一时钟 —— 幂等键不同，所以是两次独立决策
        executor(fixture, second, secondRunner, provider)
                .delegate(new DelegationExecutor.DelegationCommand(
                        TENANT, RUN, null, null, null, 1, CHILD_B, null,
                        Map.of("question", "另一个问法", "topic", "case-2"),
                        "inv-2", RUN, 10_000L, 1L, 1));

        var a = first.rows.get("inv-1");
        var b = second.rows.get("inv-2");
        assertThat(a.routingDecision()).isNotNull();
        assertThat(b.routingDecision())
                .as("两次独立委派必须都留下各自的快照（快照不是只在首次存在的附件）")
                .isNotNull();
        assertThat(canonicalDigest(b.routingDecision()))
                .as("⚠️ 只有 topic 参与求值（question 不在 input_schema 里）→ 规范化输入相同")
                .isEqualTo(canonicalDigest(a.routingDecision()));
        assertThat(b.targetAgentId())
                .as("(policyRef, canonicalInputSha256) → finalTarget 必须唯一")
                .isEqualTo(a.targetAgentId());
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static @NonNull String snapshotOf(RecordingInvocations invocations) {
        String json = invocations.rows.get("inv-1").routingDecision();
        assertThat(json).as("ROUTER 委派必须落决策快照").isNotNull();
        return json;
    }

    /** 从快照文本里取 {@code canonicalInputSha256}（只取 64 位十六进制，不做 JSON 解析）。 */
    private static @NonNull String canonicalDigest(@NonNull String snapshotJson) {
        return snapshotJson.replaceAll(".*\"canonicalInputSha256\":\"([0-9a-f]{64}).*", "$1");
    }
}
