package cn.studykid.multiagent.bootstrap.config;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根路径的委派工具 Schema 装配测试（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类验证的是「根→子这一跳也有触发点」</b>：
 * 委派工具此前<b>从未进入任何模型的工具表</b> → 运行期模型
 * <b>物理上不可能</b>发起委派（守卫、预算、账本、契约全都齐备，却没有触发点）。
 *
 * <p>⚠️ <b>根路径必须按 Run 装配</b>：根 Agent 的身份来自
 * <b>Run 能力快照</b> —— 同一租户的不同 Run 可能由不同根 Agent 执行，
 * 按全局装配会让 A 的 Run 拿到 B 的委派目标。
 *
 * <p>⚠️ <b>第二十一批：本类已按「根路径读真实定义」重写</b>。
 * 此前本类断言根 Agent 的模型标识是 {@code fake-model} 占位
 * —— 那正是第二十一批要消除的缺陷（根 Agent 没有任何普通工具可用）。
 * 现在断言的是<b>真实语义</b>：读真实定义、读不到即拒绝执行。
 */
class RootDelegationToolSchemaWiringTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final String ROOT_AGENT = "root-agent";
    private static final int ROOT_VERSION = 3;

    private static final String SEQUENTIAL = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** 根 Agent 的真实定义（含模型标识与提示词）。 */
    private static final String ROOT_DEFINITION = """
            {"modelRef":"model-root","systemPrompt":"你是根 Agent","temperature":0.3,
             "maxOutputTokens":512,"timeoutMs":15000}
            """;

    /** 可编程的 Agent 定义仓储替身。 */
    private static final class ScriptedAgentDefs implements CapAgentDefPort {
        String orchestration = SEQUENTIAL;
        String agentId = ROOT_AGENT;

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                    @NonNull String agentId,
                                                    int version) {
            if (!TENANT.equals(tenantId) || !this.agentId.equals(agentId)) {
                return Optional.empty();
            }
            return Optional.of(new CapAgentDef(TENANT, agentId, version, "根", "描述",
                    List.of(), List.of(), List.of(), List.of(), "{}", orchestration,
                    "fp", 1, Instant.EPOCH, Instant.EPOCH));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                          @NonNull String agentId) {
            return find(tenantId, agentId, ROOT_VERSION);
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                    @NonNull String agentId) {
            return find(tenantId, agentId, ROOT_VERSION);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            return List.of();
        }
    }

    /** 可编程的 Agent 版本查询替身（读模型配置定义）。 */
    private static final class ScriptedAgentQuery implements AgentQueryPort {
        String agentId = ROOT_AGENT;
        int version = ROOT_VERSION;
        String definition = ROOT_DEFINITION;
        boolean present = true;

        @Override
        public @NonNull Page<AgentItem> listAgents(
                @NonNull String tenantId, String cursor, int limit) {
            return Page.of(List.of(), null);
        }

        @Override
        public AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId) {
            return null;
        }

        @Override
        public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                            @NonNull String agentId) {
            if (!present || !this.agentId.equals(agentId)) {
                return List.of();
            }
            return List.of(new AgentVersionItem("v-" + version, version, definition,
                    Instant.EPOCH, Instant.EPOCH));
        }
    }

    /** 可编程的能力快照仓储替身。 */
    private static final class ScriptedSnapshots implements CapabilitySnapshotPort {
        String agentId = ROOT_AGENT;
        int agentVersion = ROOT_VERSION;
        boolean present = true;

        @Override
        public boolean save(@NonNull CapabilitySnapshot snapshot) {
            return true;
        }

        @Override
        public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                          @NonNull String runId) {
            if (!present) {
                return Optional.empty();
            }
            // ⚠️ agentVersion 必须取快照自己的值 ——
            //    它就是「这个 Run 绑定的是哪个 release」的权威事实，
            //    装配必须按它精确取那一版（不得漂移到当前已发布版本）。
            return Optional.of(new CapabilitySnapshot(runId, tenantId, agentId, "{}",
                    agentVersion, 1L, "cap_fp_v1:root", 1L, Instant.EPOCH));
        }
    }

    private static WorkerExecutionConfig.RunExecutionInputs inputs(
            ScriptedAgentDefs defs, ScriptedSnapshots snapshots, ScriptedAgentQuery query) {
        return new WorkerExecutionConfig.RunExecutionInputs(
                null, "2.00", "12", "20",
                java.util.Map.of(), java.util.Map.of(),
                new cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler(defs),
                snapshots,
                new cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider(
                        snapshots,
                        new cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler(
                                query,
                                new cn.studykid.multiagent.application.cap.ClosureResolver() {
                                    @Override
                                    public cn.studykid.multiagent.core.cap.EffectiveClosure
                                            resolve(@NonNull String t, @NonNull String a, int v) {
                                        return cn.studykid.multiagent.core.cap.EffectiveClosure
                                                .empty(a, v);
                                    }

                                    @Override
                                    public cn.studykid.multiagent.core.cap.EffectiveClosure
                                            resolveCurrent(@NonNull String t, @NonNull String a) {
                                        return cn.studykid.multiagent.core.cap.EffectiveClosure
                                                .empty(a, 1);
                                    }
                                },
                                new cn.studykid.multiagent.ports.persistence.CapToolDefPort() {
                                    @Override
                                    public boolean insert(@NonNull CapToolDef def) {
                                        return true;
                                    }

                                    @Override
                                    public @NonNull Optional<CapToolDef>
                                            find(@NonNull String toolId, int version) {
                                        return Optional.empty();
                                    }

                                    @Override
                                    public @NonNull Optional<CapToolDef>
                                            findLatestPublished(@NonNull String toolId) {
                                        return Optional.empty();
                                    }

                                    @Override
                                    public @NonNull Optional<CapToolDef>
                                            findByMcpBinding(@NonNull String mcpServerId,
                                                              @NonNull String mcpToolName) {
                                        return Optional.empty();
                                    }

                                    @Override
                                    public @NonNull List<CapToolDef> listAll() {
                                        return List.of();
                                    }
                                }),
                        new cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler(defs)));
    }

    private static WorkerExecutionConfig.RunExecutionInputs inputs(
            ScriptedAgentDefs defs, ScriptedSnapshots snapshots) {
        return inputs(defs, snapshots, new ScriptedAgentQuery());
    }

    @Test
    @DisplayName("⚠️⚠️ 根 Agent 有委派能力 → 根路径也看得见委派工具（根→子有触发点）")
    void rootPathSeesDelegationTool() {
        var config = inputs(new ScriptedAgentDefs(), new ScriptedSnapshots())
                .agentConfig(TENANT, RUN);

        assertThat(config).as("⚠️ 根路径必须读得到真实定义").isPresent();
        assertThat(config.get().toolSchemas())
                .as("⚠️⚠️ 根路径必须也有触发点 —— 否则「根→子」这一跳根本发不出去，"
                        + "多级 Agent 在生产上不可用")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⚠️⚠️ 委派工具按「该 Run 的根 Agent」装配（不串号）")
    void delegationToolFollowsRunRootAgent() {
        // ⚠️ 同一租户的另一个 Run 由另一个根 Agent 执行 ——
        //    若按全局装配，A 的 Run 会拿到 B 的委派目标。
        ScriptedSnapshots snapshots = new ScriptedSnapshots();
        snapshots.agentId = "other-root";
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.agentId = "other-root";
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.agentId = "other-root";

        var config = inputs(defs, snapshots, query).agentConfig(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().toolSchemas())
                .as("⚠️ 委派工具必须跟着该 Run 的根 Agent 走")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 根 Agent 是 LEAF → 不装配委派工具（无委派能力就别发枪）")
    void rootLeafGetsNoDelegationTool() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.orchestration = "{}";

        var config = inputs(defs, new ScriptedSnapshots()).agentConfig(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().toolSchemas())
                .as("⚠️ LEAF 根 Agent 不得拿到委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 读不到 Run 能力快照 → 拒绝执行（fail-closed，不得回落占位模型）")
    void missingSnapshotRejectsExecution() {
        ScriptedSnapshots snapshots = new ScriptedSnapshots();
        snapshots.present = false;

        var config = inputs(new ScriptedAgentDefs(), snapshots).agentConfig(TENANT, RUN);

        assertThat(config)
                .as("⚠️⚠️ 不知道根 Agent 是谁 → **不得**拿占位模型跑完整个 Run")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 根 Agent 定义缺少模型标识 → 拒绝执行（不猜模型）")
    void missingModelIdentifierRejectsExecution() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = "{\"systemPrompt\":\"没有模型标识\"}";

        var config = inputs(new ScriptedAgentDefs(), new ScriptedSnapshots(), query)
                .agentConfig(TENANT, RUN);

        assertThat(config)
                .as("⚠️⚠️ 取不到模型标识 → **不得**随便挑一个默认模型")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 根 Agent 的模型配置来自真实定义（不再是 fake-model 占位）")
    void rootModelConfigComesFromRealDefinition() {
        // ⚠️ 第二十一批的核心断言：此前根 Agent 的模型标识恒为 "fake-model"，
        //    提示词恒为硬编码文案，普通工具表恒为空 ——
        //    也就是**根 Agent 实际上没有任何普通工具可用**。
        var config = inputs(new ScriptedAgentDefs(), new ScriptedSnapshots())
                .agentConfig(TENANT, RUN);

        assertThat(config).isPresent();
        assertThat(config.get().modelId())
                .as("⚠️⚠️ 根 Agent 的模型标识必须来自它自己的定义")
                .isEqualTo("model-root");
        assertThat(config.get().systemPrompt())
                .as("⚠️ 提示词必须来自它自己的定义")
                .isEqualTo("你是根 Agent");
    }

    @Test
    @DisplayName("⚠️ 未装配根 Agent 配置提供器 → 拒绝执行（保持既有行为，零影响）")
    void missingProviderRejectsExecution() {
        var inputs = new WorkerExecutionConfig.RunExecutionInputs(
                null, "2.00", "12", "20", java.util.Map.of(), java.util.Map.of());

        var config = inputs.agentConfig(TENANT, RUN);

        assertThat(config)
                .as("⚠️ 未装配 → 拒绝执行（装配层 fail-open，运行期 fail-closed）")
                .isEmpty();
    }
}
