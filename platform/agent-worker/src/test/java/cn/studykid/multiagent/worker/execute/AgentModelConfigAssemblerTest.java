package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共用装配器测试（多级 Agent v1.1 · 第二十一批 · FR-036）。
 *
 * <p>⚠️ <b>本类的存在理由</b>：根 Agent 与子 Agent 的模型配置此前是<b>两套实现</b>
 * —— 子路径读真实定义，根路径走硬编码占位（{@code fake-model} + 空工具表）。
 * 现在两者共用 {@link AgentModelConfigAssembler}，
 * 本类钉死这份<b>唯一定义</b>的行为。
 */
class AgentModelConfigAssemblerTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "agent-1";

    /** 可编程的 Agent 版本查询替身。 */
    private static final class ScriptedAgentQuery implements AgentQueryPort {
        String definition = "{\"modelRef\":\"model-1\"}";
        int version = 1;
        boolean throwOnList = false;

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
            if (throwOnList) {
                throw new IllegalStateException("仓储故障");
            }
            return List.of(new AgentVersionItem("v-" + version, version, definition,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        }
    }

    /** 闭包替身：可指定该 Agent 自己的工具绑定。 */
    private static final class ScriptedClosureResolver implements ClosureResolver {
        List<EffectiveClosure.ToolBinding> tools = List.of();

        @Override
        public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                                 @NonNull String agentId,
                                                 int agentVersion) {
            return new EffectiveClosure(agentId, agentVersion, List.of(), tools,
                    List.of());
        }

        @Override
        public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                        @NonNull String agentId) {
            return resolve(tenantId, agentId, 1);
        }
    }

    /** 工具定义替身。 */
    private static final class ScriptedToolDefs implements CapToolDefPort {
        @Override
        public boolean insert(@NonNull CapToolDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
            return Optional.of(new CapToolDef(toolId, version, "工具",
                    cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                    cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                    null, null, "{}", "{}", "fp", 1,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH));
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

    private static AgentModelConfigAssembler assembler(ScriptedAgentQuery query,
                                                        ScriptedClosureResolver closure) {
        return new AgentModelConfigAssembler(query, closure, new ScriptedToolDefs());
    }

    @Test
    @DisplayName("✅ 按定义装配模型标识与提示词（根与子共用这一份）")
    void assemblesModelConfigFromDefinition() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = """
                {"modelRef":"model-1","systemPrompt":"你是辅导助手",
                 "temperature":0.5,"maxOutputTokens":777,"timeoutMs":9000}
                """;

        var settings = assembler(query, new ScriptedClosureResolver())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("model-1");
        assertThat(settings.get().systemPrompt()).isEqualTo("你是辅导助手");
        assertThat(settings.get().temperature()).isEqualTo(0.5);
        assertThat(settings.get().maxTokens()).isEqualTo(777);
        assertThat(settings.get().timeout()).isEqualTo(Duration.ofMillis(9000));
    }

    @Test
    @DisplayName("✅ 提示词兼容契约形态（instructions.inlineInstructions）")
    void supportsContractShapedPrompt() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = """
                {"modelRef":"model-1","instructions":{"inlineInstructions":"契约形态提示词"}}
                """;

        var settings = assembler(query, new ScriptedClosureResolver())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().systemPrompt()).isEqualTo("契约形态提示词");
    }

    @Test
    @DisplayName("⛔ 定义缺少模型标识 → 拒绝装配（不猜模型）")
    void missingModelIdentifierIsRejected() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = "{\"systemPrompt\":\"没有模型标识\"}";

        assertThat(assembler(query, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️⚠️ 取不到模型标识 → **不得**随便挑一个默认模型")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 定义不是合法 JSON → 拒绝装配（fail-closed）")
    void malformedDefinitionIsRejected() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.definition = "{这不是 JSON";

        assertThat(assembler(query, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️ 配置解析不了 → 不得凭空猜一个配置")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 读不到指定版本 → 拒绝装配（不漂移到其它版本）")
    void missingVersionIsRejected() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.version = 1;

        assertThat(assembler(query, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 9))
                .as("⚠️⚠️ 固定 release 取不到 → 不得回落到别的版本（MA-T08）")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 仓储异常 → 拒绝装配（不吞异常当成功）")
    void repositoryFailureIsRejected() {
        ScriptedAgentQuery query = new ScriptedAgentQuery();
        query.throwOnList = true;

        assertThat(assembler(query, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️ 读失败 → 不得当成「没有配置」之外的成功")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 工具表来自该 Agent 自己的闭包（不多不少）")
    void toolSchemasComeFromOwnClosure() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));

        var settings = assembler(new ScriptedAgentQuery(), closure).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().toolSchemas())
                .as("⚠️ 模型能看到的工具 = 它被授权的工具")
                .containsOnlyKeys("tool-a");
    }

    @Test
    @DisplayName("⚠️ 闭包解析失败 → 工具表为空但配置仍可用（不放大成全瘫）")
    void closureFailureYieldsEmptyToolTable() {
        ClosureResolver broken = new ClosureResolver() {
            @Override
            public @NonNull EffectiveClosure resolve(@NonNull String t, @NonNull String a, int v) {
                throw new IllegalStateException("闭包解析失败");
            }

            @Override
            public @NonNull EffectiveClosure resolveCurrent(@NonNull String t, @NonNull String a) {
                throw new IllegalStateException("闭包解析失败");
            }
        };

        var settings = new AgentModelConfigAssembler(
                new ScriptedAgentQuery(), broken, new ScriptedToolDefs())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings)
                .as("⚠️ 闭包坏掉只是「模型看不到那些工具」，不该让整个 Agent 全瘫")
                .isPresent();
        assertThat(settings.get().toolSchemas()).isEmpty();
    }
}
