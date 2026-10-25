package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 输出契约 / 路由策略的<b>独立生命周期迁移端点</b>测试
 * （多级 Agent v1.1 · 第二十五批 · C25-1 · A5）。
 *
 * <p>⚠️ <b>本类要证明的是「停用这条防线是真的存在」</b>：
 * 在第二十五批之前，输出契约与路由策略<b>只有</b> {@code published_at} 一个状态维度，
 * 于是：
 * <ul>
 *   <li>治理通道**无法停用**它们（生命周期迁移端点只接受 {@code agent} 类型）；</li>
 *   <li>于是「停用」这件事<b>在治理层根本不存在</b> ——
 *       被引用的契约 / 策略只能一直挂着；</li>
 *   <li>本批给这两类对象补上 {@code lifecycle_state} 并开放独立迁移通道，
 *       再用<b>发布期引用校验</b>把「停用」变成一道真防线。</li>
 * </ul>
 *
 * <p>⚠️ <b>「看得见」与「不该给的不给」必须成对</b>：
 * 每条「DEPRECATED 被拒」旁边都有一条「未停用的仍可引用」，
 * 否则「全都拒」也能让本类全绿，而那不是正确性。
 *
 * <p>⚠️ <b>停用只挡「新引用」，不追溯存量</b>（06 §3.2）：
 * 存量引用按固定版本继续过渡 —— 本类把这条边界也钉住。
 */
class ContractPolicyLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String TENANT = "tenant-cp-lifecycle";
    private static final String CONTRACT_ID = "answer-contract";
    private static final String POLICY_ID = "orders-router";
    private static final String REQUESTER = "requester-cp";
    private static final String APPROVER = "approver-cp";

    private CapFixture fixture;
    /** 生命周期状态表（键：type/tenant/object@version）。 */
    private Map<String, ConfigLifecycleState> states;
    /** 申请单表（键：requestId）。 */
    private Map<String, ConfigPublishRequest> requests;
    private long requestSeq;

    @BeforeEach
    void setUp() {
        fixture = new CapFixture();
        states = new LinkedHashMap<>();
        requests = new LinkedHashMap<>();
        requestSeq = 0L;
    }

    // ── 用例：独立生命周期迁移端点 ──────────────────────────────────

    @Test
    @DisplayName("✅ 输出契约可独立迁移 PUBLISHED → DEPRECATED（不必连着一个 Agent 一起发布）")
    void resultContractCanBeDeprecatedIndependently() {
        seedPublishedContract();
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("result_contract", CONTRACT_ID, 1,
                        "PUBLISHED", "DEPRECATED"), "停用输出契约", REQUESTER);
        assertThat(created.state()).isEqualTo(ConfigPublishRequest.State.PENDING);

        workflow.decide(TENANT, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        ConfigPublishRequest applied = workflow.apply(TENANT, created.requestId(), APPROVER);

        assertThat(applied.state()).isEqualTo(ConfigPublishRequest.State.APPLIED);
        assertThat(stateOf("result_contract", CONTRACT_ID, 1))
                .as("⛔ 独立迁移端点没有真的改状态：停用只是申请单上的一句话")
                .isEqualTo(ConfigLifecycleState.DEPRECATED);
    }

    @Test
    @DisplayName("✅ 路由策略可独立迁移 PUBLISHED → DEPRECATED")
    void routingPolicyCanBeDeprecatedIndependently() {
        seedPublishedPolicy();
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("routing_policy", POLICY_ID, 1,
                        "PUBLISHED", "DEPRECATED"), "停用路由策略", REQUESTER);
        workflow.decide(TENANT, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(TENANT, created.requestId(), APPROVER);

        assertThat(stateOf("routing_policy", POLICY_ID, 1))
                .as("⛔ 路由策略没有被独立停用")
                .isEqualTo(ConfigLifecycleState.DEPRECATED);
    }

    @Test
    @DisplayName("✅ 独立停用<b>不改内容</b>：内容指纹与 Schema 一字未动")
    void deprecationDoesNotChangeContent() {
        seedPublishedContract();
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("result_contract", CONTRACT_ID, 1,
                        "PUBLISHED", "DEPRECATED"), "停用输出契约", REQUESTER);
        workflow.decide(TENANT, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(TENANT, created.requestId(), APPROVER);

        assertThat(fixture.resultContracts.get(TENANT + "/" + CONTRACT_ID + "@1").schemaJson())
                .as("⛔ 停用改写了契约内容：不可变版本行被篡改")
                .isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    @DisplayName("⛔ 平台级对象类型仍不得经租户通道做生命周期迁移（闸门不得被放宽）")
    void platformObjectTypesAreStillRejected() {
        ConfigPublishWorkflowService workflow = workflow();

        for (String type : List.of("tool", "skill", "mcp_server")) {
            assertThatThrownBy(() -> workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                    new ConfigPublishPayload.LifecycleSpec(type, "some-object", 1,
                            "DRAFT", "VALIDATED"), "迁移平台对象", REQUESTER))
                    .as("⛔ 平台级对象类型「" + type + "」竟被租户通道接受了")
                    .isInstanceOf(ConfigPublishWorkflowService.ConfigPublishScopeNotAllowedException.class);
        }
        assertThat(requests)
                .as("⛔ 被拒的迁移仍落了申请单：闸门必须发生在落单之前")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 原状态不符（并发竞争）→ 冲突，不得静默覆盖")
    void staleExpectedStateIsRejected() {
        seedPublishedContract();
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("result_contract", CONTRACT_ID, 1,
                        "DRAFT", "VALIDATED"), "状态对不上的迁移", REQUESTER);
        workflow.decide(TENANT, created.requestId(), Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> workflow.apply(TENANT, created.requestId(), APPROVER))
                .as("⛔ 期望原状态不符却迁移成功：并发迁移被静默覆盖")
                .isInstanceOf(ConfigPublishWorkflowService.ConfigPublishBaselineConflictException.class);

        assertThat(stateOf("result_contract", CONTRACT_ID, 1))
                .as("⛔ 冲突后状态被改动了")
                .isEqualTo(ConfigLifecycleState.PUBLISHED);
    }

    @Test
    @DisplayName("⛔ 停用是单向的：DEPRECATED 是终态，不得迁回 PUBLISHED")
    void deprecationIsTerminal() {
        seedPublishedContract();
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("result_contract", CONTRACT_ID, 1,
                        "PUBLISHED", "DEPRECATED"), "停用输出契约", REQUESTER);
        workflow.decide(TENANT, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(TENANT, created.requestId(), APPROVER);

        ConfigPublishRequest revive = workflow.requestLifecycle(TENANT, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("result_contract", CONTRACT_ID, 1,
                        "DEPRECATED", "PUBLISHED"), "复活已停用的契约", REQUESTER);
        workflow.decide(TENANT, revive.requestId(), Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> workflow.apply(TENANT, revive.requestId(), APPROVER))
                .as("⛔ DEPRECATED 可迁出：停用形同虚设")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("非法的生命周期迁移");
    }

    // ── 用例：发布期引用防线 ────────────────────────────────────────

    @Test
    @DisplayName("⛔ 引用已停用的输出契约 → 拒绝发布（RESULT_CONTRACT_DEPRECATED）")
    void deprecatedResultContractCannotBeReferenced() {
        fixture.withContract(TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", true,
                ConfigLifecycleState.DEPRECATED);

        assertThatThrownBy(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"LEAF","resultContractRef":"answer-contract@1"}
                """)))
                .as("⛔ 已停用的契约竟能被新发布引用：停用不是防线，只是告示牌")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESULT_CONTRACT_DEPRECATED));
    }

    @Test
    @DisplayName("⛔ 引用已停用的路由策略 → 拒绝发布（ROUTING_POLICY_DEPRECATED）")
    void deprecatedRoutingPolicyCannotBeReferenced() {
        fixture.withRoutingPolicy(TENANT, POLICY_ID, 1, RoutingStrategy.EXPLICIT_TARGETS,
                List.of("child-agent"), true, ConfigLifecycleState.DEPRECATED);

        assertThatThrownBy(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"ROUTER","routingPolicyRef":"orders-router@1",
                 "childAgentRefs":[{"id":"child-agent","version":1}],
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """)))
                .as("⛔ 已停用的策略竟能被新发布引用")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ROUTING_POLICY_DEPRECATED));
    }

    @Test
    @DisplayName("✅ 未停用的契约 / 策略仍可被引用（成对断言：防线不得变成「全都拒」）")
    void activeContractAndPolicyRemainReferenceable() {
        fixture.withContract(TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", true,
                        ConfigLifecycleState.PUBLISHED)
                .withRoutingPolicy(TENANT, POLICY_ID, 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("child-agent"), true, ConfigLifecycleState.PUBLISHED)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        assertThatCode(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"LEAF","resultContractRef":"answer-contract@1"}
                """))).doesNotThrowAnyException();

        assertThatCode(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"ROUTER","routingPolicyRef":"orders-router@1",
                 "childAgentRefs":[{"id":"child-agent","version":1}],
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("✅ 「未记录生命周期状态」≠「已停用」（存量行口径不变）")
    void missingLifecycleStateIsNotDeprecation() {
        fixture.withContract(TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", true);

        assertThat(fixture.resultContracts.get(TENANT + "/" + CONTRACT_ID + "@1").isDeprecated())
                .as("⛔ 「未记录状态」被读成了「已停用」：存量行会被误伤")
                .isFalse();

        assertThatCode(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"LEAF","resultContractRef":"answer-contract@1"}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("✅ 「未发布」与「已停用」是两个不同原因码（排查方向不同）")
    void unpublishedAndDeprecatedCarryDistinctReasonCodes() {
        fixture.withContract(TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", false,
                ConfigLifecycleState.DRAFT);

        assertThatThrownBy(() -> validator().validate(TENANT, leafDraft("""
                {"mode":"LEAF","resultContractRef":"answer-contract@1"}
                """)))
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESULT_CONTRACT_NOT_PUBLISHED));
        assertThat(ReasonCode.RESULT_CONTRACT_NOT_PUBLISHED)
                .as("⛔ 「未发布」与「已停用」共用了同一个原因码：排查方向会被带偏")
                .isNotEqualTo(ReasonCode.RESULT_CONTRACT_DEPRECATED);
        assertThat(ReasonCode.ROUTING_POLICY_NOT_PUBLISHED)
                .isNotEqualTo(ReasonCode.ROUTING_POLICY_DEPRECATED);
    }

    // ── 装配 ────────────────────────────────────────────────────────

    private void seedPublishedContract() {
        fixture.withContract(TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", true,
                ConfigLifecycleState.PUBLISHED);
        states.put(key("result_contract", CONTRACT_ID, 1), ConfigLifecycleState.PUBLISHED);
    }

    private void seedPublishedPolicy() {
        fixture.withRoutingPolicy(TENANT, POLICY_ID, 1, RoutingStrategy.EXPLICIT_TARGETS,
                List.of("child-agent"), true, ConfigLifecycleState.PUBLISHED);
        states.put(key("routing_policy", POLICY_ID, 1), ConfigLifecycleState.PUBLISHED);
    }

    private ConfigLifecycleState stateOf(String type, String objectId, int version) {
        return states.get(key(type, objectId, version));
    }

    private static String key(String type, String objectId, int version) {
        return type + "/" + objectId + "@" + version;
    }

    private AgentPublishClosureValidator validator() {
        return new AgentPublishClosureValidator(fixture.agentDefs(), fixture.resultContracts(),
                fixture.routingPolicies());
    }

    private static CapAgentDef leafDraft(String orchestration) {
        return new CapAgentDef(TENANT, "parent-agent", 2, "parent-agent", "desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-parent", 1, Instant.EPOCH, null);
    }

    private ConfigPublishWorkflowService workflow() {
        ConfigPublishService publisher = new ConfigPublishService(
                new ConfigPublishTransaction() {
                    @Override
                    public <T> T execute(String tenantId, Action<T> action) {
                        return action.run();
                    }
                },
                new ConfigPointerPort() {
                    @Override
                    public Optional<ConfigPointer> current(ConfigRelease.Scope scope,
                                                           String tenantId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                                 Long expectedPublishSeq, long newReleaseId,
                                                 long newPublishSeq) {
                        return true;
                    }
                },
                new ConfigReleasePort() {
                    @Override
                    public ConfigRelease save(ConfigRelease release) {
                        return release;
                    }

                    @Override
                    public Optional<ConfigRelease> findByReleaseId(String tenantId, long releaseId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean markRolledBack(String tenantId, long releaseId) {
                        return false;
                    }

                    @Override
                    public Optional<ConfigRelease> findByPublishSeq(String tenantId, long seq) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<ConfigRelease> findLatestPublished(ConfigRelease.Scope scope,
                                                                       String tenantId) {
                        return Optional.empty();
                    }

                    @Override
                    public Page<ConfigRelease> listReleases(String tenantId, String cursor,
                                                           int limit) {
                        return Page.of(List.of(), null);
                    }
                },
                fixture.toolDefs(), fixture.skillDefs(), fixture.serverDefs(), fixture.agentDefs(),
                new DecisionAuditPort() {
                    @Override
                    public void append(DecisionAuditEntry entry) {
                    }

                    @Override
                    public List<DecisionAuditEntry> findByInvocation(String tenantId, String id) {
                        return List.of();
                    }

                    @Override
                    public List<DecisionAuditEntry> findByRun(String tenantId, String runId) {
                        return List.of();
                    }

                    @Override
                    public cn.studykid.multiagent.core.cap.DecisionAuditCoverage coverage(
                            String tenantId) {
                        return new cn.studykid.multiagent.core.cap.DecisionAuditCoverage(0, 0, 0, 0, 0);
                    }

                    @Override
                    public List<DecisionAuditEntry> findRecentDenies(String tenantId,
                                                                     Instant since, int limit) {
                        return List.of();
                    }
                },
                new ConfigValidationGate(), CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS, validator(), null,
                fixture.resultContracts(), fixture.routingPolicies());

        return new ConfigPublishWorkflowService(
                requestPort(), publisher,
                new ConfigLifecycleService(lifecyclePort(), new ConfigLifecycleStateMachine()),
                fixture.agentDefs(),
                new ConfigPointerPort() {
                    @Override
                    public Optional<ConfigPointer> current(ConfigRelease.Scope scope,
                                                           String tenantId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                                 Long expectedPublishSeq, long newReleaseId,
                                                 long newPublishSeq) {
                        return true;
                    }
                },
                new ConfigReleasePort() {
                    @Override
                    public ConfigRelease save(ConfigRelease release) {
                        return release;
                    }

                    @Override
                    public Optional<ConfigRelease> findByReleaseId(String tenantId, long releaseId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean markRolledBack(String tenantId, long releaseId) {
                        return false;
                    }

                    @Override
                    public Optional<ConfigRelease> findByPublishSeq(String tenantId, long seq) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<ConfigRelease> findLatestPublished(ConfigRelease.Scope scope,
                                                                       String tenantId) {
                        return Optional.empty();
                    }

                    @Override
                    public Page<ConfigRelease> listReleases(String tenantId, String cursor,
                                                           int limit) {
                        return Page.of(List.of(), null);
                    }
                },
                fixture.resultContracts(), fixture.routingPolicies(), CLOCK);
    }

    /** 内存生命周期端口（CAS 语义与 DB 实现同口径）。 */
    private ConfigLifecyclePort lifecyclePort() {
        return new ConfigLifecyclePort() {
            @Override
            public boolean transition(String type, String tenantId, String objectId, int version,
                                      ConfigLifecycleState expectedFrom,
                                      ConfigLifecycleState to) {
                String k = key(type, objectId, version);
                if (states.get(k) != expectedFrom) {
                    return false;
                }
                states.put(k, to);
                return true;
            }

            @Override
            public Optional<ConfigLifecycleState> stateOf(String type, String tenantId,
                                                           String objectId, int version) {
                return Optional.ofNullable(states.get(key(type, objectId, version)));
            }
        };
    }

    /** 内存申请单端口（三步状态机与 DB 实现同口径）。 */
    private ConfigPublishRequestPort requestPort() {
        return new ConfigPublishRequestPort() {
            @Override
            public ConfigPublishRequest insert(String tenantId,
                                               ConfigPublishRequest.Operation operation,
                                               String payload, String reason,
                                               String requesterId) {
                String id = "req-" + (++requestSeq);
                ConfigPublishRequest row = new ConfigPublishRequest(id, tenantId, operation,
                        payload, reason, requesterId, ConfigPublishRequest.State.PENDING,
                        null, null, CLOCK.instant(), null, null, null);
                requests.put(id, row);
                return row;
            }

            @Override
            public Optional<ConfigPublishRequest> find(String tenantId, String requestId) {
                return Optional.ofNullable(requests.get(requestId));
            }

            @Override
            public Page<ConfigPublishRequest> list(String tenantId,
                                                   ConfigPublishRequest.State state,
                                                   String cursor, int limit) {
                List<ConfigPublishRequest> items = new ArrayList<>(requests.values());
                return Page.of(items, null);
            }

            @Override
            public ConfigPublishRequest decide(String tenantId, String requestId,
                                               Decision decision, String note,
                                               String approverId) {
                ConfigPublishRequest row = requests.get(requestId);
                if (row == null) {
                    throw new NotFoundException(requestId);
                }
                if (row.state() != ConfigPublishRequest.State.PENDING) {
                    throw new StateConflictException("状态不是 PENDING");
                }
                if (row.requesterId().equals(approverId)) {
                    throw new SelfApprovalException();
                }
                ConfigPublishRequest next = new ConfigPublishRequest(row.requestId(),
                        row.tenantId(), row.operation(), row.payload(), row.reason(),
                        row.requesterId(),
                        decision == Decision.APPROVE ? ConfigPublishRequest.State.APPROVED
                                : ConfigPublishRequest.State.REJECTED,
                        approverId, note, row.requestedAt(), CLOCK.instant(), null, null);
                requests.put(requestId, next);
                return next;
            }

            @Override
            public ConfigPublishRequest markApplied(String tenantId, String requestId,
                                                    String actorId, Instant appliedAt) {
                ConfigPublishRequest row = requests.get(requestId);
                if (row == null) {
                    throw new NotFoundException(requestId);
                }
                if (row.state() != ConfigPublishRequest.State.APPROVED) {
                    throw new StateConflictException("状态不是 APPROVED");
                }
                ConfigPublishRequest next = new ConfigPublishRequest(row.requestId(),
                        row.tenantId(), row.operation(), row.payload(), row.reason(),
                        row.requesterId(), ConfigPublishRequest.State.APPLIED, row.approverId(),
                        row.decisionNote(), row.requestedAt(), row.decidedAt(), appliedAt, null);
                requests.put(requestId, next);
                return next;
            }

            @Override
            public ConfigPublishRequest markFailed(String tenantId, String requestId,
                                                   String reason) {
                ConfigPublishRequest row = requests.get(requestId);
                if (row == null) {
                    throw new NotFoundException(requestId);
                }
                ConfigPublishRequest next = new ConfigPublishRequest(row.requestId(),
                        row.tenantId(), row.operation(), row.payload(), row.reason(),
                        row.requesterId(), ConfigPublishRequest.State.FAILED, row.approverId(),
                        row.decisionNote(), row.requestedAt(), row.decidedAt(), null, reason);
                requests.put(requestId, next);
                return next;
            }
        };
    }
}
