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
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 输出契约接入发布审批流的测试（多级 Agent v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>本类要证明的是「契约的治理闭环」</b>：
 * 输出契约曾经只有一个<b>独立登记端口</b>，既不在发布事务里、
 * 也没有服务端内容指纹、更没有治理通道入口 ——
 * 结果是「Agent 可以引用一个从未生效的 Schema，而发布看起来完全成功」。
 *
 * <p>⚠️ <b>正向与反向必须成对</b>：每个「拒绝发布」旁边都有一个「同批次引用可以发布」，
 * 否则「全都拒」也能让本类全绿，而那不是正确性。
 */
class ResultContractPublishFlowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String TENANT = "t-contract-publish";
    private static final String CONTRACT_ID = "answer-contract";
    private static final String SCHEMA = """
            {"type":"object","properties":{"answer":{"type":"string"}}}
            """;

    private World world;

    @BeforeEach
    void setUp() {
        world = new World();
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapResultContractDef contract(int version, String schema) {
        return new CapResultContractDef(TENANT, CONTRACT_ID, version, schema,
                CapObjectFingerprint.of(new CapResultContractDef(TENANT, CONTRACT_ID, version,
                        schema, "pending", 1, CLOCK.instant(), null)),
                1, CLOCK.instant(), null);
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

    private ConfigPublishService service(boolean withContracts, boolean withValidator) {
        AgentPublishClosureValidator validator = withValidator
                ? new AgentPublishClosureValidator(world.agentDefs, world.contracts)
                : null;
        return new ConfigPublishService(
                world.tx, world.pointers, world.releases,
                world.toolDefs, world.skillDefs, world.serverDefs, world.agentDefs,
                world.audit, new ConfigValidationGate(), CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS, validator, null,
                withContracts ? world.contracts : null);
    }

    private ConfigPublishCommand commandWith(List<CapAgentDef> agents,
                                             List<CapResultContractDef> contracts) {
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, TENANT, null,
                "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), agents, null, contracts);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 契约随同一次发布落库：已发布 + 服务端内容指纹 + 进发布头清单")
    void contractIsPublishedWithinTheSameRelease() {
        ConfigPublishService service = service(true, true);

        service.publish(commandWith(List.of(agent("leaf-agent", "{}")),
                List.of(contract(1, SCHEMA))));

        CapResultContractDef stored = world.contracts.find(TENANT, CONTRACT_ID, 1).orElseThrow();
        assertThat(stored.isPublished())
                .as("⛔ 契约未被标记为已发布：Agent 引用的将是一个「不生效的事实」")
                .isTrue();
        assertThat(stored.configFingerprint())
                .as("⛔ 契约内容指纹不是服务端算的：内容变了但指纹没变将无法察觉")
                .isEqualTo(CapObjectFingerprint.of(
                        new CapResultContractDef(TENANT, CONTRACT_ID, 1, SCHEMA, "pending",
                                1, CLOCK.instant(), null)));
        assertThat(world.releaseRows.get(0).objectVersions())
                .as("⛔ 契约未进发布头清单：回滚与追溯无法确定「当时生效的是哪份 Schema」")
                .contains("result_contract")
                .contains(CONTRACT_ID);
    }

    @Test
    @DisplayName("✅ 同一次发布里登记契约并引用它 → 发布通过")
    void agentMayReferenceContractPublishedInTheSameRelease() {
        ConfigPublishService service = service(true, true);

        service.publish(commandWith(
                List.of(agent("leaf-agent", "{\"mode\":\"LEAF\",\"resultContractRef\":\""
                        + CONTRACT_ID + "@1\"}")),
                List.of(contract(1, SCHEMA))));

        assertThat(world.agents).containsKey(TENANT + "/leaf-agent@1");
    }

    @Test
    @DisplayName("⛔ 引用不存在的契约 → 拒绝发布（不得让 Agent 生效后才在运行期炸）")
    void missingContractBlocksPublish() {
        ConfigPublishService service = service(true, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                List.of(agent("leaf-agent", "{\"mode\":\"LEAF\",\"resultContractRef\":\""
                        + CONTRACT_ID + "@9\"}")),
                List.of())))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));

        assertThat(world.agents)
                .as("⛔ 引用不存在契约的 Agent 仍被写入：配置错误被推迟到线上才暴露")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ LEAF Agent 声明不存在的契约 → 同样拒绝发布（不得因「没有子 Agent」而跳过校验）")
    void leafAgentDeclaringContractIsStillValidated() {
        ConfigPublishService service = service(true, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                List.of(agent("leaf-agent", "{\"mode\":\"LEAF\",\"resultContractRef\":\""
                        + CONTRACT_ID + "@7\"}")),
                List.of())))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    @Test
    @DisplayName("⛔ 契约仓储未装配却带契约发布 → 拒绝（不得「发布成功但契约没落库」）")
    void missingContractPortBlocksPublish() {
        ConfigPublishService service = service(false, true);

        assertThatThrownBy(() -> service.publish(commandWith(
                List.of(agent("leaf-agent", "{}")),
                List.of(contract(1, SCHEMA)))))
                .isInstanceOf(ConfigPublishRejectedException.class);

        assertThat(world.contracts.find(TENANT, CONTRACT_ID, 1))
                .as("⛔ 契约仓储未装配却落了契约行")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 契约内容变化 → 内容指纹必变（否则「内容变了但指纹没变」不可察）")
    void contractContentChangeChangesFingerprint() {
        String before = CapObjectFingerprint.of(new CapResultContractDef(
                TENANT, CONTRACT_ID, 1, SCHEMA, "pending", 1, CLOCK.instant(), null));
        String after = CapObjectFingerprint.of(new CapResultContractDef(
                TENANT, CONTRACT_ID, 1, "{\"type\":\"object\"}", "pending", 1,
                CLOCK.instant(), null));

        assertThat(after)
                .as("⛔ Schema 变了却指纹不变：子输出校验口径被悄悄换掉")
                .isNotEqualTo(before);
    }

    @Test
    @DisplayName("✅ Schema 键序变化 → 指纹不变（规范化：内容未变不得被读成契约变了）")
    void contractKeyOrderDoesNotChangeFingerprint() {
        String a = CapObjectFingerprint.of(new CapResultContractDef(
                TENANT, CONTRACT_ID, 1,
                "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}",
                "pending", 1, CLOCK.instant(), null));
        String b = CapObjectFingerprint.of(new CapResultContractDef(
                TENANT, CONTRACT_ID, 1,
                "{\"properties\":{\"answer\":{\"type\":\"string\"}},\"type\":\"object\"}",
                "pending", 1, CLOCK.instant(), null));

        assertThat(b)
                .as("⛔ Schema 只是键序不同却被读成「契约变了」：每次重新登记都会失效下游")
                .isEqualTo(a);
    }

    // ── 内存世界 ──────────────────────────────────────────────────

    private static final class World {
        final Map<String, CapAgentDef> agents = new LinkedHashMap<>();
        final Map<String, CapResultContractDef> contractRows = new LinkedHashMap<>();
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
                String key = def.tenantId() + "/" + def.contractId() + "@" + def.version();
                if (contractRows.containsKey(key)) {
                    return false;
                }
                contractRows.put(key, def);
                undo.push(() -> contractRows.remove(key));
                return true;
            }

            @Override
            public Optional<CapResultContractDef> find(String tenantId, String contractId,
                                                        int version) {
                return Optional.ofNullable(
                        contractRows.get(tenantId + "/" + contractId + "@" + version));
            }

            @Override
            public Optional<CapResultContractDef> findLatestPublished(String tenantId,
                                                                      String contractId) {
                return contractRows.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> contractId.equals(c.contractId()))
                        .filter(CapResultContractDef::isPublished)
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }

            @Override
            public Optional<CapResultContractDef> findLatest(String tenantId, String contractId) {
                return contractRows.values().stream()
                        .filter(c -> tenantId.equals(c.tenantId()))
                        .filter(c -> contractId.equals(c.contractId()))
                        .max((a, b) -> Integer.compare(a.version(), b.version()));
            }
        };
    }
}
