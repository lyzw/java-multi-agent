package cn.studykid.multiagent.worker.execute;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派工具 Schema 装配器测试（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类验证的是「装配器的边界」</b>：它被根路径与子路径<b>共用</b> ——
 * 两处各写一份必然漂移，而漂移的后果是
 * 「根能委派、子不能」（或反之），那正是「两套语义」本身。
 *
 * <p>⚠️ <b>fail-closed 是本类的重点</b>：读不到编排声明 / 声明非法 / 仓储异常
 * 时<b>一律不给</b>委派工具 —— 不知道有没有委派能力时，
 * 宁可不给，也不凭猜测给一把委派枪。
 */
class DelegationToolSchemaAssemblerTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "agent-1";
    private static final int VERSION = 1;

    private static final String SEQUENTIAL = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    /** 可编程的 Agent 定义仓储替身。 */
    private static final class ScriptedAgentDefs implements CapAgentDefPort {
        String orchestration = SEQUENTIAL;
        boolean present = true;
        RuntimeException failure;

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                    @NonNull String agentId,
                                                    int version) {
            if (failure != null) {
                throw failure;
            }
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new CapAgentDef(TENANT, AGENT, VERSION, "名称", "描述",
                    List.of(), List.of(), List.of(), List.of(), "{}", orchestration,
                    "fp", 1, Instant.EPOCH, Instant.EPOCH));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                          @NonNull String agentId) {
            return find(tenantId, agentId, VERSION);
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                    @NonNull String agentId) {
            return find(tenantId, agentId, VERSION);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            return List.of();
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 有委派能力 → 装配委派工具（模型必须看得见它）")
    void assemblesDelegationToolWhenCapable() {
        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(new ScriptedAgentDefs())
                        .assemble(TENANT, AGENT, VERSION);

        assertThat(schemas)
                .as("⚠️⚠️ 模型必须看得见委派工具 —— 否则运行期**物理上不可能**发起委派")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ LEAF → 不装配（无委派能力就别发委派枪）")
    void doesNotAssembleForLeaf() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.orchestration = "{}";

        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(defs).assemble(TENANT, AGENT, VERSION);

        assertThat(schemas)
                .as("⚠️ LEAF Agent 不得拿到委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ Agent 定义不存在 → 不装配（fail-closed：无从判断就不给）")
    void doesNotAssembleWhenAgentMissing() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.present = false;

        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(defs).assemble(TENANT, AGENT, VERSION);

        assertThat(schemas)
                .as("⚠️ 读不到 Agent 定义 → 不得凭空给委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 仓储读取异常 → 不装配且不抛（故障不得打断模型侧装配）")
    void doesNotAssembleWhenRepositoryFails() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.failure = new IllegalStateException("数据库不可用");

        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(defs).assemble(TENANT, AGENT, VERSION);

        assertThat(schemas)
                .as("⚠️ 仓储异常 → 不得给委派工具，也不得把异常抛给调用方")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 编排声明非法 → 不装配（闸门侧同样 fail-closed 拒绝委派）")
    void doesNotAssembleWhenOrchestrationInvalid() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.orchestration = "{\"mode\":\"NOT_A_MODE\"}";

        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(defs).assemble(TENANT, AGENT, VERSION);

        assertThat(schemas)
                .as("⚠️ 声明非法 → 不给委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }
}
