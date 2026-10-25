package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 路由策略接入发布审批流的测试
 * （多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
 *
 * <p>⚠️ <b>本类要证明的是「路由策略的治理闭环」</b>：
 * 此前 {@code routingPolicyRef} 是<b>悬空引用</b> —— 全仓没有任何地方真正持有
 * 这个被引用的对象：发布期不校验它是否存在，运行期不读它。
 * 结果是「配置看起来声明了路由策略，实际路由行为与普通多级委派毫无差别」。
 *
 * <p>⚠️ <b>正向与反向必须成对</b>：每个「拒绝发布」旁边都有一个「同批次引用可以发布」，
 * 否则「全都拒」也能让本类全绿，而那不是正确性。
 */
class RoutingPolicyPublishFlowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String TENANT = "t-routing-publish";
    private static final String POLICY_ID = "orders-router";
    private static final String CHILD_ID = "child-a";

    private World world;

    @BeforeEach
    void setUp() {
        world = new World();
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapRoutingPolicyDef policy(int version, RoutingStrategy strategy,
                                              List<String> targets, Instant publishedAt) {
        return new CapRoutingPolicyDef(TENANT, POLICY_ID, version, strategy, targets,
                CapObjectFingerprint.of(new CapRoutingPolicyDef(TENANT, POLICY_ID, version,
                        strategy, targets, "pending", 1, CLOCK.instant(), null)),
                1, CLOCK.instant(), publishedAt);
    }

    private static CapAgentDef agent(String agentId, String orchestration) {
        return new CapAgentDef(TENANT, agentId, 1, agentId, agentId + " desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(), "{}",
                orchestration,
                CapObjectFingerprint.of(new CapAgentDef(TENANT, agentId, 1, agentId,
                        agentId + " desc", List.of("TEACHER"), List.of(), List.of(), List.of(),
                        "{}", orchestration, "pending", 1, CLOCK.instant(), null)),
                1, CLOCK.instant(), null);
    }

    /** ROUTER 父 Agent 的编排声明（声明拓扑 = childAgentRefs）。 */
    private static String routerOrchestration(String policyRef, String childId) {
        return "{\"mode\":\"ROUTER\",\"routingPolicyRef\":\"" + policyRef
                + "\",\"childAgentRefs\":[{\"id\":\"" + childId + "\",\"version\":1}],\"maxDelegationDepth\":3,\"maxChildInvocations\":8,\"timeoutMs\":30000}";
    }

    private ConfigPublishService service(boolean withPolicies, boolean withValidator) {
        AgentPublishClosureValidator validator = withValidator
                ? new AgentPublishClosureValidator(world.agentDefs, world.contracts,
                        world.routingPolicies)
                : null;
        return new ConfigPublishService(
                world.tx, world.pointers, world.releases,
                world.toolDefs, world.skillDefs, world.serverDefs, world.agentDefs,
                world.audit, new ConfigValidationGate(), CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS, validator, world.childRefs,
                world.contracts, withPolicies ? world.routingPolicies : null);
    }

    private ConfigPublishCommand commandWith(List<CapAgentDef> agents,
                                             List<CapRoutingPolicyDef> policies) {
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, TENANT, null,
                "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), agents, null, List.of(), policies);
    }

    /** 一次完整的「子 Agent + ROUTER 父 Agent」发布集合。 */
    private List<CapAgentDef> routerAndChild(String policyRef) {
        return List.of(agent(CHILD_ID, "{}"),
                agent("router-1", routerOrchestration(policyRef, CHILD_ID)));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 策略随同一次发布落库：已发布 + 服务端内容指纹 + 进发布头清单")
    void policyIsPublishedWithinTheSameRelease() {
        ConfigPublishService service = service(true, true);

        service.publish(commandWith(routerAndChild(POLICY_ID + "@1"),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null))));

        CapRoutingPolicyDef stored = world.routingPolicies
                .find(TENANT, POLICY_ID, 1).orElseThrow();
        assertThat(stored.isPublished())
                .as("⛔ 策略未被标记为已发布：ROUTER 引用的将是一个「不生效的事实」")
                .isTrue();
        assertThat(stored.configFingerprint())
                .as("⛔ 策略内容指纹不是服务端算的：路由规则变了但指纹没变将无法察觉")
                .isEqualTo(CapObjectFingerprint.of(new CapRoutingPolicyDef(TENANT, POLICY_ID, 1,
                        RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), "pending",
                        1, CLOCK.instant(), null)));
        assertThat(world.releaseRows.get(0).objectVersions())
                .as("⛔ 策略未进发布头清单：回滚与追溯无法确定「当时生效的是哪套路由规则」")
                .contains("routing_policy")
                .contains(POLICY_ID);
    }

    @Test
    @DisplayName("✅ 同一次发布里登记策略并被 ROUTER Agent 引用 → 发布通过")
    void routerMayReferencePolicyPublishedInTheSameRelease() {
        ConfigPublishService service = service(true, true);

        service.publish(commandWith(routerAndChild(POLICY_ID + "@1"),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null))));

        assertThat(world.agents).containsKey(TENANT + "/router-1@1");
    }

    @Test
    @DisplayName("⛔ 引用不存在的策略 → 拒绝发布（悬空引用必须在发布期就被挡住）")
    void missingPolicyBlocksPublish() {
        ConfigPublishService service = service(true, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                routerAndChild(POLICY_ID + "@9"), List.of())))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));

        assertThat(world.agents)
                .as("⛔ 引用不存在策略的 Agent 仍被写入：配置错误被推迟到线上才暴露")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 引用未发布的策略（草稿）→ 拒绝发布（先引用再发布是绕过发布流程的后门）")
    void draftPolicyBlocksPublish() {
        ConfigPublishService service = service(true, true);
        // ⚠️ 草稿策略只能来自「上一次登记、尚未发布」：
        //    本次发布事务里的策略一律会被标记为已发布（withPublished），
        //    所以必须**绕过发布事务**直接写进仓储，才造得出草稿状态。
        world.routingPolicies.insert(
                policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null));

        assertThatThrownBy(() -> service.publish(commandWith(
                routerAndChild(POLICY_ID + "@1"), List.of())))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ROUTING_POLICY_NOT_PUBLISHED));
    }

    @Test
    @DisplayName("⛔ 策略允许的目标不在父声明的 childAgentRefs 内 → 拒绝发布（拓扑与策略必须两层收窄）")
    void policyTargetOutsideDeclaredTopologyBlocksPublish() {
        ConfigPublishService service = service(true, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                routerAndChild(POLICY_ID + "@1"),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("never-declared"), null)))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID));

        assertThat(world.agents)
                .as("⛔ 策略与拓扑不一致的 Agent 仍被写入")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 非 ROUTER 模式声明 routingPolicyRef → 拒绝发布（策略对其它模式毫无作用）")
    void nonRouterDeclaringPolicyBlocksPublish() {
        ConfigPublishService service = service(true, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                List.of(agent("leaf-agent",
                        "{\"mode\":\"LEAF\",\"routingPolicyRef\":\"" + POLICY_ID + "@1\"}")),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null)))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID));
    }

    @Test
    @DisplayName("⛔ 策略仓储未装配却带策略发布 → 拒绝（不得「发布成功但策略没落库」）")
    void missingPolicyPortBlocksPublish() {
        ConfigPublishService service = service(false, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                routerAndChild(POLICY_ID + "@1"),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null)))))
                .isInstanceOf(ConfigPublishRejectedException.class);

        assertThat(world.routingPolicies.find(TENANT, POLICY_ID, 1))                .as("⛔ 策略仓储未装配却落了策略行")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 策略校验器未装配却声明了策略 → 拒绝（不得因「没接上校验」就放行）")
    void missingPolicyValidationBlocksPublish() {
        ConfigPublishService service = service(true, false);

        assertThatThrownBy(() -> service.publish(commandWith(
                routerAndChild(POLICY_ID + "@1"),
                List.of(policy(1, RoutingStrategy.EXPLICIT_TARGETS, List.of(CHILD_ID), null)))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID));
    }

    // ── 内存世界 ──────────────────────────────────────────────────

    private static final class World {
        final Map<String, CapAgentDef> agents = new LinkedHashMap<>();
        final Map<String, CapRoutingPolicyDef> policyRows = new LinkedHashMap<>();
        final List<ConfigRelease> releaseRows = new ArrayList<>();
        long seq;

        /**
         * 撤销栈：每次写入登记一条撤销动作。
         *
         * <p>⚠️ <b>事务是真的会撤销写入的</b>：没有它，「发布失败 → Agent 行不得残留」
         * 这条断言永远绿 —— 那是最坏的失效形态。
         */
        final java.util.Deque<Runnable> undo = new java.util.LinkedList<>();

        final ConfigPublishTransaction tx = new ConfigPublishTransaction() {
            @Override
            public <T> T execute(String tenantId, Action<T> action) {
                int mark = undo.size();
                try {
                    T result = action.run();
                    undo.clear();
                    return result;
                } catch (RuntimeException e) {
                    while (undo.size() > mark) {
                        undo.pop().run();
                    }
                    throw e;
                }
            }
        };

        final ConfigPointerPort pointers = new ConfigPointerPort() {
            @Override
            public Optional<ConfigPointer> current(ConfigRelease.Scope scope, String tenantId) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                         Long expectedPublishSeq, long newReleaseId,
                                         long newPublishSeq) {
                return true;
            }
        };

        final ConfigReleasePort releases = new ConfigReleasePort() {
            @Override
            public ConfigRelease save(ConfigRelease release) {
                long seq = ++World.this.seq;
                ConfigRelease saved = new ConfigRelease(releaseRows.size() + 1L, seq,
                        release.scope(), release.tenantId(), release.objectVersions(),
                        release.aggregateFingerprint(), release.status(), release.requesterId(),
                        release.approverId(), release.effectiveAt(), release.createdAt());
                releaseRows.add(saved);
                return saved;
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
            public Page<ConfigRelease> listReleases(String tenantId, String cursor, int limit) {
                return Page.of(List.of(), null);
            }
        };

        final DecisionAuditPort audit = new DecisionAuditPort() {
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
            public cn.studykid.multiagent.core.cap.DecisionAuditCoverage coverage(String tenantId) {
                return new cn.studykid.multiagent.core.cap.DecisionAuditCoverage(0, 0, 0, 0, 0);
            }

            @Override
            public List<DecisionAuditEntry> findRecentDenies(String tenantId, Instant since,
                                                             int limit) {
                return List.of();
            }
        };

        /**
         * 父子关系投影端口（评审前置决策 4：存储）。
         *
         * <p>⚠️ <b>决策 4 之后它不再是可选的</b>：发布带子 Agent 引用的 Agent 时，
         * 投影未装配即**拒绝发布**（fail-closed）——
         * 关系边落不了库，「谁依赖谁」就查不出来，
         * 影响分析与删除守卫会因此放行「被引用对象被抽走」的事故。
         * 故本类必须装配它（此前这里是 {@code null}）。
         */
        final List<CapAgentChildRef> childRefRows = new ArrayList<>();

        final CapAgentChildRefPort childRefs = new CapAgentChildRefPort() {
            @Override
            public boolean insert(CapAgentChildRef ref) {
                boolean exists = childRefRows.stream().anyMatch(r ->
                        r.parentAgentId().equals(ref.parentAgentId())
                                && r.parentReleaseVersion() == ref.parentReleaseVersion()
                                && r.childAgentId().equals(ref.childAgentId())
                                && r.childReleaseVersion() == ref.childReleaseVersion());
                if (exists) {
                    return false;
                }
                childRefRows.add(ref);
                return true;
            }

            @Override
            public List<CapAgentChildRef> listByParentRelease(String tenantId, String parentAgentId,
                                                               int parentReleaseVersion) {
                return childRefRows.stream()
                        .filter(r -> r.parentAgentId().equals(parentAgentId))
                        .filter(r -> r.parentReleaseVersion() == parentReleaseVersion)
                        .toList();
            }

            @Override
            public List<CapAgentChildRef> listByParentAgent(String tenantId, String parentAgentId) {
                return childRefRows.stream()
                        .filter(r -> r.parentAgentId().equals(parentAgentId))
                        .toList();
            }

            @Override
            public List<CapAgentChildRef> listByChildAgent(String tenantId, String childAgentId) {
                return childRefRows.stream()
                        .filter(r -> r.childAgentId().equals(childAgentId))
                        .toList();
            }

            @Override
            public List<CapAgentChildRef> listByTenant(String tenantId) {
                return List.copyOf(childRefRows);
            }
        };

        final CapToolDefPort toolDefs = new CapToolDefPort() {
            @Override
            public boolean insert(CapToolDef def) {
                return true;
            }

            @Override
            public Optional<CapToolDef> find(String toolId, int version) {
                return Optional.empty();
            }

            @Override
            public Optional<CapToolDef> findLatestPublished(String toolId) {
                return Optional.empty();
            }

            @Override
            public Optional<CapToolDef> findByMcpBinding(String serverId, String toolName) {
                return Optional.empty();
            }

            @Override
            public List<CapToolDef> listAll() {
                return List.of();
            }
        };

        final CapSkillDefPort skillDefs = new CapSkillDefPort() {
            @Override
            public boolean insert(CapSkillDef def) {
                return true;
            }

            @Override
            public Optional<CapSkillDef> find(String skillId, int version) {
                return Optional.empty();
            }

            @Override
            public Optional<CapSkillDef> findLatestPublished(String skillId) {
                return Optional.empty();
            }

            @Override
            public List<CapSkillDef> listAll() {
                return List.of();
            }
        };

        final CapMcpServerDefPort serverDefs = new CapMcpServerDefPort() {
            @Override
            public boolean insert(CapMcpServerDef def) {
                return true;
            }

            @Override
            public Optional<CapMcpServerDef> find(String id, int version) {
                return Optional.empty();
            }

            @Override
            public Optional<CapMcpServerDef> findLatestPublished(String id) {
                return Optional.empty();
            }

            @Override
            public List<CapMcpServerDef> listAll() {
                return List.of();
            }
        };

        final CapAgentDefPort agentDefs = new CapAgentDefPort() {
            @Override
            public boolean insert(CapAgentDef def) {
                String key = def.tenantId() + "/" + def.agentId() + "@" + def.version();
                if (agents.containsKey(key)) {
                    return false;
                }
                agents.put(key, def);
                undo.push(() -> agents.remove(key));
                return true;
            }

            @Override
            public Optional<CapAgentDef> find(String tenantId, String agentId, int version) {
                return Optional.ofNullable(agents.get(tenantId + "/" + agentId + "@" + version));
            }

            @Override
            public Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
                return agents.values().stream()
                        .filter(a -> tenantId.equals(a.tenantId()))
                        .filter(a -> agentId.equals(a.agentId()))
                        .max((x, y) -> Integer.compare(x.version(), y.version()));
            }

            @Override
            public Optional<CapAgentDef> findLatestPublished(String tenantId, String agentId) {
                return findLatest(tenantId, agentId).filter(CapAgentDef::isPublished);
            }

            @Override
            public List<CapAgentDef> listLatestPerAgent(String tenantId) {
                return List.copyOf(agents.values());
            }
        };

        final CapResultContractPort contracts = new CapResultContractPort() {
            @Override
            public boolean insert(CapResultContractDef def) {
                return true;
            }

            @Override
            public Optional<CapResultContractDef> find(String tenantId, String contractId,
                                                        int version) {
                return Optional.empty();
            }

            @Override
            public Optional<CapResultContractDef> findLatestPublished(String tenantId,
                                                                      String contractId) {
                return Optional.empty();
            }

            @Override
            public Optional<CapResultContractDef> findLatest(String tenantId, String contractId) {
                return Optional.empty();
            }
        };

        final CapRoutingPolicyPort routingPolicies = new CapRoutingPolicyPort() {
            @Override
            public boolean insert(CapRoutingPolicyDef def) {
                String key = def.tenantId() + "/" + def.policyId() + "@" + def.version();
                if (policyRows.containsKey(key)) {
                    return false;
                }
                policyRows.put(key, def);
                undo.push(() -> policyRows.remove(key));
                return true;
            }

            @Override
            public Optional<CapRoutingPolicyDef> find(String tenantId, String policyId,
                                                       int version) {
                return Optional.ofNullable(
                        policyRows.get(tenantId + "/" + policyId + "@" + version));
            }

            @Override
            public Optional<CapRoutingPolicyDef> findLatestPublished(String tenantId,
                                                                       String policyId) {
                return policyRows.values().stream()
                        .filter(p -> tenantId.equals(p.tenantId()))
                        .filter(p -> policyId.equals(p.policyId()))
                        .filter(CapRoutingPolicyDef::isPublished)
                        .max((x, y) -> Integer.compare(x.version(), y.version()));
            }

            @Override
            public Optional<CapRoutingPolicyDef> findLatest(String tenantId, String policyId) {
                return policyRows.values().stream()
                        .filter(p -> tenantId.equals(p.tenantId()))
                        .filter(p -> policyId.equals(p.policyId()))
                        .max((x, y) -> Integer.compare(x.version(), y.version()));
            }
        };
    }
}
