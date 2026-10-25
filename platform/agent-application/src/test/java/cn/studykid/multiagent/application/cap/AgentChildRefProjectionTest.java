package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 父子 Agent 关系投影的发布期语义（评审前置决策 4：{@code cap_agent_child_ref} <b>存储</b>）。
 *
 * <p>⚠️ <b>决策 4 之前，这个投影是「可选的」</b>：
 * {@code ConfigPublishService.projectChildRefs} 里写着
 * {@code childRefs == null → 直接 return}（静默跳过）。
 * 后果不是「少一张表的���据」，而是<b>关系真相缺失</b>：
 * 「谁依赖我」这个问题，影响分析与删除守卫都靠这张表回答；
 * 表是空的就等于「没人依赖我」，
 * 于是「被引用对象被抽走」的事故一路畅通，
 * 而运维看到的是「发布成功」。
 *
 * <p>⚠️ <b>本类钉住的是判据的边界</b>，不只是「要拒绝」：
 * 拒绝对不对，取决于判的是「<b>有没有边</b>」还是「<b>仓储装没装配</b>」——
 * 前者该拒（带子引用却落不了库），
 * 后者不该拒（纯 LEAF Agent 的发布与投影无关，
 * 不该因为一个用不到的组件缺失就把人拦在门外）。
 * 只写「拒绝」这一条而不写「不该拒绝」那条，
 * 就会有人把实现改成「仓储没装配就全拒」——
 * 那会让投影能力成为整个平台级 Agent 发布的隐形前置依赖。
 */
class AgentChildRefProjectionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String TENANT = "t-child-ref-projection";
    private static final String PARENT = "parent-agent";
    private static final String CHILD_A = "child-a";
    private static final String CHILD_B = "child-b";

    /** 父 Agent 的编排：声明两个子（第二个 optional）。 */
    private static final String PARENT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1},
                                                   {"id":"child-b","version":1,"required":false}],
             "maxDelegationDepth":2,"maxChildInvocations":4,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 投影未装配 + 发布了带子引用的 Agent → 拒绝发布（决策 4 的 fail-closed）")
    void projectionNotWiredRejectsPublishWithChildren() {
        World world = new World();
        // ⚠️ 刻意**不装配**投影端口
        ConfigPublishService service = service(world, null);

        assertThatThrownBy(() -> service.publish(commandWith(PARENT_ORCHESTRATION)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .as("⚠️ 拒绝原因码必须是 ORCHESTRATION_INVALID —— "
                                + "「关系投影缺失」是编排声明层面的问题")
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID))
                .hasMessageContaining("父子关系投影未装配");

        assertThat(world.agents)
                .as("⛔ 拒绝后仍留下了 Agent 行：发布失败却写了一半（半发布）")
                .isEmpty();
        assertThat(world.childRefRows).isEmpty();
    }

    @Test
    @DisplayName("✅ 投影未装配 + 发布的是 LEAF Agent → **不**拒绝（不该过度拦截）")
    void projectionNotWiredStillAllowsLeafPublish() {
        World world = new World();
        ConfigPublishService service = service(world, null);

        assertThatCode(() -> service.publish(commandWith("{}")))
                .as("⚠️ 纯 LEAF Agent 的发布与关系投影无关 —— "
                        + "投影能力缺失不得成为整个平台级发布的隐形前置依赖")
                .doesNotThrowAnyException();

        assertThat(world.agents).containsKey(TENANT + "/" + PARENT + "@1");
    }

    @Test
    @DisplayName("✅ 投影装配 → 边按 ordinal 落库，required 保真")
    void projectionWritesEdgesInDeclaredOrder() {
        World world = new World();
        ConfigPublishService service = service(world, world.childRefs);

        service.publish(commandWith(PARENT_ORCHESTRATION));

        assertThat(world.childRefRows)
                .as("⛔ 关系边没落库：「谁依赖谁」查不出来，删除守卫会放行抽走被引用对象")
                .hasSize(2);
        assertThat(world.childRefRows.get(0).childAgentId()).isEqualTo(CHILD_A);
        assertThat(world.childRefRows.get(0).ordinal())
                .as("⛔ 声明次序失真：父 Agent 的声明顺序是配置事实")
                .isZero();
        assertThat(world.childRefRows.get(0).required()).isTrue();
        assertThat(world.childRefRows.get(1).childAgentId()).isEqualTo(CHILD_B);
        assertThat(world.childRefRows.get(1).ordinal()).isEqualTo(1);
        assertThat(world.childRefRows.get(1).required())
                .as("⛔ required 标记丢失：optional 子失败不该拖垮父 Agent")
                .isFalse();
        assertThat(world.childRefRows.get(0).parentReleaseVersion()).isEqualTo(1);
        assertThat(world.childRefRows.get(0).childReleaseVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 同一父 release 重复投影 → 幂等（不产生第二条边）")
    void repeatedProjectionIsIdempotent() {
        World world = new World();
        ConfigPublishService service = service(world, world.childRefs);

        service.publish(commandWith(PARENT_ORCHESTRATION));
        service.publish(commandWith(PARENT_ORCHESTRATION));

        assertThat(world.childRefRows)
                .as("⛔ 重复投影产生了重复边："
                        + "边集合出现重复会让「子调用次数预算」被重复计入")
                .hasSize(2);
    }

    @Test
    @DisplayName("✅ 出向 / 入向 / 全量三种查询口径各自正确")
    void relationQueriesAnswerOutboundInboundAndWholeTenant() {
        World world = new World();
        ConfigPublishService service = service(world, world.childRefs);
        service.publish(commandWith(PARENT_ORCHESTRATION));

        assertThat(world.childRefs.listByParentAgent(TENANT, PARENT)).hasSize(2);
        assertThat(world.childRefs.listByParentAgent(TENANT, "nobody")).isEmpty();
        assertThat(world.childRefs.listByChildAgent(TENANT, CHILD_A))
                .as("⛔ 反查不到入向边：删除守卫问的正是「谁依赖我」")
                .hasSize(1);
        assertThat(world.childRefs.listByChildAgent(TENANT, CHILD_A).get(0).parentAgentId())
                .isEqualTo(PARENT);
        assertThat(world.childRefs.listByChildAgent(TENANT, "nobody")).isEmpty();
        assertThat(world.childRefs.listByTenant(TENANT)).hasSize(2);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static ConfigPublishService service(World world, CapAgentChildRefPort childRefs) {
        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                world.agentDefs, null, null);
        return new ConfigPublishService(
                world.tx, world.pointers, world.releases,
                world.toolDefs, world.skillDefs, world.serverDefs, world.agentDefs,
                world.audit, new ConfigValidationGate(), CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS, validator, childRefs,
                null, null);
    }

    private static ConfigPublishCommand commandWith(String orchestration) {
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, TENANT, null,
                "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), List.of(agent(PARENT, orchestration)),
                null, List.of(), List.of());
    }

    /** 待发布的 Agent（未发布 —— 它正是本次要发布的东西）。 */
    private static CapAgentDef agent(String agentId, String orchestration) {
        return new CapAgentDef(TENANT, agentId, 1, agentId, agentId + " desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(), "{}",
                orchestration, "fp-" + agentId, 1, CLOCK.instant(), null);
    }

    /** 已发布的子 Agent（委派目标必须固定到已发布 release）。 */
    private static CapAgentDef publishedChild(String agentId) {
        return new CapAgentDef(TENANT, agentId, 1, agentId, agentId + " desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(), "{}",
                "{}", "fp-" + agentId, 1, CLOCK.instant(), CLOCK.instant());
    }

    /**
     * 可回滚的内存世界。
     *
     * <p>⚠️ <b>事务是真的会撤销写入的</b>：失败即撤销本次事务内的全部写入。
     * 若替身只「记一笔日志」，「拒绝后不留半成品」的断言就永远绿。
     */
    private static final class World {
        final Map<String, CapAgentDef> agents = new LinkedHashMap<>();
        final List<CapAgentChildRef> childRefRows = new ArrayList<>();
        final java.util.Deque<Runnable> undo = new LinkedList<>();
        long seq;

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
                long publishSeq = ++World.this.seq;
                ConfigRelease saved = new ConfigRelease(releaseRowsSize() + 1L, publishSeq,
                        release.scope(), release.tenantId(), release.objectVersions(),
                        release.aggregateFingerprint(), release.status(), release.requesterId(),
                        release.approverId(), release.effectiveAt(), release.createdAt());
                return saved;
            }

            private int releaseRowsSize() {
                return 0;
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
                CapAgentDef found = agents.get(tenantId + "/" + agentId + "@" + version);
                if (found != null) {
                    return Optional.of(found);
                }
                // ⚠️ 已发布的子 Agent 是**夹具**（它们不在本次发布集合里）
                if (CHILD_A.equals(agentId) || CHILD_B.equals(agentId)) {
                    return Optional.of(publishedChild(agentId));
                }
                return Optional.empty();
            }

            @Override
            public Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
                return find(tenantId, agentId, 1);
            }

            @Override
            public Optional<CapAgentDef> findLatestPublished(String tenantId, String agentId) {
                return findLatest(tenantId, agentId);
            }

            @Override
            public List<CapAgentDef> listLatestPerAgent(String tenantId) {
                return List.copyOf(agents.values());
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
            public List<CapToolDef> listByMcpServer(String mcpServerId) {
                // ⚠️ 夹具里没有「按 MCP Server 反查」的数据 —— 显式返回空，
                //    而不是留一个默认实现（默认返回空 = 撒谎「这个 Server 下没有 Tool」）。
                return List.of();
            }

            public List<CapToolDef> listAll() {
                return List.of();
            }
        };

        final CapSkillDefPort skillDefs = new CapSkillDefPort() {
            @Override
            public boolean insert(cn.studykid.multiagent.core.cap.CapSkillDef def) {
                return true;
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapSkillDef> find(String skillId,
                                                                                int version) {
                return Optional.empty();
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapSkillDef> findLatestPublished(
                    String skillId) {
                return Optional.empty();
            }

            @Override
            public List<cn.studykid.multiagent.core.cap.CapSkillDef> listAll() {
                return List.of();
            }
        };

        final CapMcpServerDefPort serverDefs = new CapMcpServerDefPort() {
            @Override
            public boolean insert(CapMcpServerDef def) {
                return true;
            }

            @Override
            public Optional<CapMcpServerDef> find(String serverId, int version) {
                return Optional.empty();
            }

            @Override
            public Optional<CapMcpServerDef> findLatestPublished(String serverId) {
                return Optional.empty();
            }

            @Override
            public List<CapMcpServerDef> listAll() {
                return List.of();
            }
        };

        final DecisionAuditPort audit = new DecisionAuditPort() {
            @Override
            public void append(DecisionAuditEntry entry) {
                // 审计写入不进撤销栈：审计是「发生过」的记录，不该随事务回滚消失
            }

            @Override
            public List<DecisionAuditEntry> findByInvocation(String tenantId, String invocationId) {
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
    }
}
