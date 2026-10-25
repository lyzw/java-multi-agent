package cn.studykid.multiagent.worker.execute;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子 Agent 模型配置的**工具表装配**测试（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类验证的是「模型看得见什么」</b>：委派工具在修复前
 * <b>从未进入任何模型的工具表</b> → 运行期模型<b>物理上不可能</b>发起委派。
 * 多级 Agent 的守卫、预算、账本、契约全都齐备，却<b>没有触发点</b>。
 *
 * <p>⚠️ <b>委派不是普通工具</b>：它<b>不进</b> Skill→Tool 能力闭包
 * （进了闭包就等于给每个 Agent 都发一把委派枪），
 * 因此闭包解析<b>永远给不出</b>它 —— 必须<b>显式装配</b>。
 */
class WorkerChildAgentModelConfigProviderTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "child-agent";
    private static final int VERSION = 1;

    private static final String SEQUENTIAL_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"grand-child","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    // ── 替身 ──────────────────────────────────────────────────────

    /** 内存版 Agent 版本查询（只提供模型配置定义）。 */
    private static final class InMemoryAgentQuery implements AgentQueryPort {
        @Override
        public @NonNull Page<AgentItem> listAgents(
                @NonNull String tenantId, @Nullable String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable AgentItem findAgent(@NonNull String tenantId,
                                             @NonNull String agentId) {
            return null;
        }

        @Override
        public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                            @NonNull String agentId) {
            return List.of(new AgentVersionItem("v1", VERSION,
                    "{\"modelRef\":\"model-1\",\"systemPrompt\":\"你是子 Agent\"}",
                    Instant.EPOCH, Instant.EPOCH));
        }
    }

    /** 内存版 Agent 定义仓储（编排声明的真源）。 */
    private static final class InMemoryAgentDefs implements CapAgentDefPort {
        private final String orchestration;

        InMemoryAgentDefs(String orchestration) {
            this.orchestration = orchestration;
        }

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                    @NonNull String agentId,
                                                    int version) {
            if (!TENANT.equals(tenantId) || !AGENT.equals(agentId) || version != VERSION) {
                return Optional.empty();
            }
            return Optional.of(new CapAgentDef(TENANT, AGENT, VERSION, "子 Agent", "描述",
                    List.of(), List.of(), List.of(), List.of(), "{}", orchestration,
                    "fp-child", 1, Instant.EPOCH, Instant.EPOCH));
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
            return List.of(find(tenantId, AGENT, VERSION).orElseThrow());
        }
    }

    /** 空闭包（工具表里除委派工具外什么都没有）。 */
    private static final class EmptyClosureResolver implements ClosureResolver {
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

    /** 空工具定义仓储。 */
    private static final class NoToolDefs implements CapToolDefPort {
        @Override
        public boolean insert(@NonNull CapToolDef tool) {
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
                                                                @NonNull String operation) {
            return Optional.empty();
        }

        @Override
        public List<CapToolDef> listByMcpServer(String mcpServerId) {
            // ⚠️ 夹具里没有 MCP 绑定关系 —— 显式返回空，
            //    而不是留一个默认实现（默认返回空 = 撒谎「这个 Server 下没有 Tool」）。
            return List.of();
        }

        @Override

        public @NonNull List<CapToolDef> listAll() {
            return List.of();
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private static WorkerChildAgentModelConfigProvider provider(String orchestration) {
        return new WorkerChildAgentModelConfigProvider(
                new InMemoryAgentQuery(), new EmptyClosureResolver(), new NoToolDefs(),
                new DelegationToolSchemaAssembler(new InMemoryAgentDefs(orchestration)));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 有委派能力的子 Agent → 模型看得见委派工具（这是多级的触发点）")
    void delegationToolIsVisibleToModel() {
        var config = provider(SEQUENTIAL_ORCHESTRATION)
                .configFor(TENANT, AGENT, VERSION);

        assertThat(config).isPresent();
        assertThat(config.orElseThrow().toolSchemas())
                .as("⚠️⚠️ 模型必须看得见委派工具 —— 否则运行期**物理上不可能**发起委派："
                        + "守卫、预算、账本、契约全都齐备，却没有触发点")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⚠️ 目标枚举 = 该 Agent 自己声明的子 Agent（不给模型越界试探的机会）")
    void delegationToolEnumeratesOwnDeclaredTargets() {
        var config = provider(SEQUENTIAL_ORCHESTRATION)
                .configFor(TENANT, AGENT, VERSION).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) config.toolSchemas().get(
                DelegationToolSchema.TOOL_NAME);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) inputSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) properties.get("targetAgentId");

        assertThat(target.get("enum"))
                .as("⚠️ 目标只能来自该 Agent 自己的编排声明")
                .isEqualTo(List.of("grand-child"));
    }

    @Test
    @DisplayName("⛔ LEAF 子 Agent → 不给委派工具（无委派能力就别发委派枪）")
    void leafAgentGetsNoDelegationTool() {
        // ⚠️ 编排声明缺省即 LEAF（MA-T01）——
        //    给没有委派能力的 Agent 发委派工具，
        //    等于让模型反复尝试必然被拒的调用（白烧模型调用）。
        var config = provider("{}").configFor(TENANT, AGENT, VERSION);

        assertThat(config).isPresent();
        assertThat(config.orElseThrow().toolSchemas())
                .as("⚠️ LEAF Agent 不得拿到委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 未装配编排声明来源 → 不给委派工具（fail-closed：无从判断就不给）")
    void missingAgentDefsYieldsNoDelegationTool() {
        // ⚠️ 读不到编排声明就不知道有没有委派能力，
        //    此时**宁可不给** —— 多给了等于让模型去试探不该碰的边界。
        var provider = new WorkerChildAgentModelConfigProvider(
                new InMemoryAgentQuery(), new EmptyClosureResolver(), new NoToolDefs());

        var config = provider.configFor(TENANT, AGENT, VERSION);

        assertThat(config).isPresent();
        assertThat(config.orElseThrow().toolSchemas())
                .as("⚠️ 无编排声明来源 → 不得凭空给委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ 编排声明非法 → 不给委派工具，但普通工具照常可用（不整体打死）")
    void invalidOrchestrationYieldsNoDelegationTool() {
        // ⚠️ 声明非法时闸门会 fail-closed 拒绝委派（配置缺陷本应被发布期拦住），
        //    但**模型侧不必因此整个不可用** —— 该 Agent 的普通工具照常工作。
        var config = provider("{\"mode\":\"BOGUS_MODE\"}")
                .configFor(TENANT, AGENT, VERSION);

        assertThat(config).isPresent();
        assertThat(config.orElseThrow().toolSchemas())
                .as("⚠️ 声明非法 → 不给委派工具")
                .doesNotContainKey(DelegationToolSchema.TOOL_NAME);
    }
}
