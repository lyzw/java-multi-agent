package cn.studykid.multiagent.worker.execute;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根 Agent 模型配置提供器测试（多级 Agent v1.1 · 第二十一批 · FR-036）。
 *
 * <p>⚠️ <b>本类验证的是「根 Agent 不再走占位」</b>：
 * 修复前根 Agent 的模型标识恒为 {@code fake-model}、提示词恒为硬编码文案、
 * <b>普通工具表恒为空</b> —— 也就是根 Agent <b>没有任何普通工具可用</b>，
 * 只能委派、不能自己干活。
 *
 * <p>⚠️ <b>「看得见」与「不该给的」必须成对断言</b>：
 * 只断言「能读到配置」的话，「全部 Agent 都给同一份配置」也能让用例绿；
 * 只断言「读不到就拒绝」的话，「永远读不到」也能让用例绿。
 */
class RootAgentModelConfigProviderTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String ROOT_AGENT = "root-agent";
    private static final int ROOT_VERSION = 3;

    private static final String SEQUENTIAL = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    private static final class ScriptedSnapshots implements CapabilitySnapshotPort {
        boolean present = true;
        boolean throwOnFind = false;
        String agentId = ROOT_AGENT;
        int agentVersion = ROOT_VERSION;

        @Override
        public boolean save(@NonNull CapabilitySnapshot snapshot) {
            return true;
        }

        @Override
        public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                          @NonNull String runId) {
            if (throwOnFind) {
                throw new IllegalStateException("快照仓储故障");
            }
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new CapabilitySnapshot(runId, tenantId, agentId, "{}",
                    agentVersion, 1L, "fp", 1L, Instant.EPOCH));
        }
    }

    private static final class ScriptedAgentQuery implements AgentQueryPort {
        String definition = "{\"modelRef\":\"model-root\",\"systemPrompt\":\"根提示词\"}";

        @Override
        public @NonNull Page<AgentItem> listAgents(@NonNull String tenantId,
                                                   String cursor, int limit) {
            return Page.of(List.of(), null);
        }

        @Override
        public AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId) {
            return null;
        }

        @Override
        public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                            @NonNull String agentId) {
            return List.of(new AgentVersionItem("v", ROOT_VERSION, definition,
                    Instant.EPOCH, Instant.EPOCH));
        }
    }

    private static final class ScriptedClosureResolver implements ClosureResolver {
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
    }

    private static final class ScriptedToolDefs implements CapToolDefPort {
        @Override
        public boolean insert(@NonNull CapToolDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
            return Optional.empty();
        }

        @Override
        public @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId) {
            return Optional.empty();
        }

        @Override
        public @NonNull Optional<CapToolDef> findByMcpBinding(@NonNull String mcpServerId,
                                                              @NonNull String mcpToolName) {
            return Optional.empty();
        }

        @Override
        public @NonNull List<CapToolDef> listAll() {
            return List.of();
        }
    }

    /** 委派工具装配器替身（按编排声明给不给委派工具）。 */
    private static final class ScriptedDelegationAssembler extends DelegationToolSchemaAssembler {
        private final String orchestration;

        ScriptedDelegationAssembler(String orchestration) {
            super(null);
            this.orchestration = orchestration;
        }

        @Override
        public @NonNull Map<String, Object> assemble(@NonNull String tenantId,
                                                     @NonNull String agentId,
                                                     int agentVersion) {
            return DelegationToolSchema
                    .forOrchestration(AgentOrchestrationParser.parse(orchestration))
                    .map(DelegationToolSchema::render)
                    .orElseGet(Map::of);
        }
    }

    private static RootAgentModelConfigProvider provider(ScriptedSnapshots snapshots,
                                                          ScriptedAgentQuery query,
                                                          String orchestration) {
        return new RootAgentModelConfigProvider(
                snapshots,
                new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                        new ScriptedToolDefs()),
                new ScriptedDelegationAssembler(orchestration));
    }

    @Test
    @DisplayName("✅ 根 Agent 按快照里的固定 release 读真实定义")
    void readsRealDefinitionByFixedRelease() {
        var config = provider(new ScriptedSnapshots(), new ScriptedAgentQuery(), SEQUENTIAL)
                .configFor(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().agentId()).isEqualTo(ROOT_AGENT);
        assertThat(config.get().agentVersion()).isEqualTo(ROOT_VERSION);
        assertThat(config.get().modelId())
                .as("⚠️⚠️ 根 Agent 的模型标识必须来自它自己的定义，不能是占位")
                .isEqualTo("model-root");
        assertThat(config.get().systemPrompt()).isEqualTo("根提示词");
    }

    @Test
    @DisplayName("✅ 根 Agent 有委派能力 → 看得见委派工具（与子路径同一份语义）")
    void seesDelegationTool() {
        var config = provider(new ScriptedSnapshots(), new ScriptedAgentQuery(), SEQUENTIAL)
                .configFor(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().toolSchemas())
                .as("⚠️⚠️ 根→子这一跳必须有触发点")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 根 Agent 是 LEAF → 不给委派工具（不该给的不给）")
    void leafGetsNoDelegationTool() {
        var config = provider(new ScriptedSnapshots(), new ScriptedAgentQuery(), "{}")
                .configFor(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().toolSchemas())
                .as("⚠️ 没有委派能力就别发枪")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 读不到能力快照 → 拒绝执行（不得回落占位模型）")
    void missingSnapshotIsRejected() {
        ScriptedSnapshots snapshots = new ScriptedSnapshots();
        snapshots.present = false;

        var config = provider(snapshots, new ScriptedAgentQuery(), SEQUENTIAL)
                .configFor(TENANT, RUN);

        assertThat(config)
                .as("⚠️⚠️ 不知道根 Agent 是谁 → 不得拿占位模型跑完整个 Run")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 快照读取异常 → 拒绝执行（不得吞异常当成功）")
    void snapshotFailureIsRejected() {
        ScriptedSnapshots snapshots = new ScriptedSnapshots();
        snapshots.throwOnFind = true;

        var config = provider(snapshots, new ScriptedAgentQuery(), SEQUENTIAL)
                .configFor(TENANT, RUN);

        assertThat(config).isEmpty();
    }

    @Test
    @DisplayName("⛔ 定义缺少模型标识 → 拒绝执行（不猜模型）")
    void missingModelIdentifierIsRejected() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = "{\"systemPrompt\":\"没有模型标识\"}";

        var config = provider(new ScriptedSnapshots(), query, SEQUENTIAL)
                .configFor(TENANT, RUN);

        assertThat(config).isEmpty();
    }

    @Test
    @DisplayName("⛔ 未装配委派工具装配器 → 不给委派工具，但配置仍可用")
    void missingDelegationAssemblerStillYieldsConfig() {
        var config = new RootAgentModelConfigProvider(
                new ScriptedSnapshots(),
                new AgentModelConfigAssembler(new ScriptedAgentQuery(),
                        new ScriptedClosureResolver(), new ScriptedToolDefs()),
                null)
                .configFor(TENANT, RUN);

        assertThat(config).as("⚠️ 委派工具装配器缺席只该少一个工具，不该让 Agent 全瘫").isPresent();
        assertThat(config.get().toolSchemas())
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }
}
