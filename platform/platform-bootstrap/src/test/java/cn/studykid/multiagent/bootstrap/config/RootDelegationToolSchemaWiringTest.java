package cn.studykid.multiagent.bootstrap.config;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

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
            return Optional.of(new CapabilitySnapshot(runId, tenantId, agentId, "{}", 1,
                    1L, "cap_fp_v1:root", 1L, Instant.EPOCH));
        }
    }

    private static WorkerExecutionConfig.RunExecutionInputs inputs(
            ScriptedAgentDefs defs, ScriptedSnapshots snapshots) {
        return new WorkerExecutionConfig.RunExecutionInputs(
                null, "2.00", "12", "20",
                java.util.Map.of(), java.util.Map.of(),
                new cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler(defs),
                snapshots);
    }

    @Test
    @DisplayName("⚠️⚠️ 根 Agent 有委派能力 → 根路径也看得见委派工具（根→子有触发点）")
    void rootPathSeesDelegationTool() {
        var config = inputs(new ScriptedAgentDefs(), new ScriptedSnapshots())
                .agentConfig(TENANT, RUN);

        assertThat(config.toolSchemas())
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

        var config = inputs(defs, snapshots).agentConfig(TENANT, RUN);

        assertThat(config.toolSchemas())
                .as("⚠️ 委派工具必须跟着该 Run 的根 Agent 走")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 根 Agent 是 LEAF → 不装配委派工具（无委派能力就别发枪）")
    void rootLeafGetsNoDelegationTool() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.orchestration = "{}";

        var config = inputs(defs, new ScriptedSnapshots()).agentConfig(TENANT, RUN);

        assertThat(config.toolSchemas())
                .as("⚠️ LEAF 根 Agent 不得拿到委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 读不到 Run 能力快照 → 不装配委派工具（fail-closed）")
    void missingSnapshotYieldsNoDelegationTool() {
        ScriptedSnapshots snapshots = new ScriptedSnapshots();
        snapshots.present = false;

        var config = inputs(new ScriptedAgentDefs(), snapshots).agentConfig(TENANT, RUN);

        assertThat(config.toolSchemas())
                .as("⚠️ 不知道根 Agent 是谁 → 不得凭空给委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⚠️ 未装配委派工具装配器 → 不装配（保持既有行为，零影响）")
    void missingAssemblerYieldsNoDelegationTool() {
        var inputs = new WorkerExecutionConfig.RunExecutionInputs(
                null, "2.00", "12", "20", java.util.Map.of(), java.util.Map.of());

        var config = inputs.agentConfig(TENANT, RUN);

        assertThat(config.toolSchemas())
                .as("⚠️ 未装配 → 不给委派工具（装配层 fail-open，行为不变）")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⚠️ 既有占位字段不得被本批改动（模型标识/提示词/普通工具表）")
    void placeholderFieldsRemainUnchanged() {
        // ⚠️ 本批只补**委派工具**这一项触发点，
        //    根 Agent 的模型标识/提示词/**普通工具表**仍是 L-4 占位 ——
        //    顺手改它们等于把「占位」说成「已接通」，那是虚报实现状态。
        var config = inputs(new ScriptedAgentDefs(), new ScriptedSnapshots())
                .agentConfig(TENANT, RUN);

        assertThat(config.modelId()).isEqualTo("fake-model");
        assertThat(config.toolSchemas())
                .as("⚠️ 普通工具表仍是空占位（L-4），本批不得顺手动它")
                .containsOnlyKeys(DelegationToolSchema.TOOL_NAME);
    }
}
