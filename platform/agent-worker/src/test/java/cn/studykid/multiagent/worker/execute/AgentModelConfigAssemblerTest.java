package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共用装配器测试（多级 Agent v1.1 · 第二十一批 · FR-036 · 2026-11-09 权威源定 A）。
 *
 * <p>⚠️ <b>本类的存在理由</b>：根 Agent 与子 Agent 的模型配置此前是<b>两套实现</b>
 * —— 子路径读真实定义，根路径走硬编码占位（{@code fake-model} + 空工具表）。
 * 现在两者共用 {@link AgentModelConfigAssembler}，
 * 本类钉死这份<b>唯一定义</b>的行为。
 *
 * <p>⚠️ <b>配置真源是 {@code cap_agent_def}</b>（2026-11-09）：
 * 本类的替身因此实现 {@link CapAgentDefPort} 而<b>不是</b>旧的
 * {@code AgentQueryPort} —— 旧表 {@code agent_version.definition} 降级为归档，
 * 运行期不再读它。
 */
class AgentModelConfigAssemblerTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "agent-1";

    /** 可编程的 Agent 定义仓储替身（权威真源）。 */
    private static final class ScriptedAgentDefs implements CapAgentDefPort {
        String modelRef = "model-1";
        String instructions = "{\"inlineInstructions\":\"你是辅导助手\"}";
        String generationParams = "{}";
        int version = 1;
        boolean throwOnFind = false;
        boolean present = true;

        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                    @NonNull String agentId,
                                                    int version) {
            if (throwOnFind) {
                throw new IllegalStateException("仓储故障");
            }
            if (!present || version != this.version) {
                return Optional.empty();
            }
            return Optional.of(new CapAgentDef(
                    tenantId, agentId, version,
                    "agent-1", "描述", List.of("student"), List.of(), List.of(), List.of(),
                    "{}", "{}", instructions, modelRef, generationParams,
                    "fp", 1, java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                          @NonNull String agentId) {
            return find(tenantId, agentId, this.version);
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                     @NonNull String agentId) {
            return find(tenantId, agentId, this.version);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            return List.of();
        }

        @Override
        public List<CapAgentDef> listVersionsGlobal(@NonNull String agentId) {
            return List.of();
        }
}

    /** 闭包替身：可指定该 Agent 自己的工具绑定与 Skill 绑定（含两级描述）。 */
    private static final class ScriptedClosureResolver implements ClosureResolver {
        List<EffectiveClosure.ToolBinding> tools = List.of();
        List<EffectiveClosure.SkillBinding> skills = List.of();

        @Override
        public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                                 @NonNull String agentId,
                                                 int agentVersion) {
            return new EffectiveClosure(agentId, agentVersion, skills, tools,
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
        /** 夹具的输入 Schema（默认最小，各用例按需覆盖）。 */
        String inputSchema = "{}";
        /** 工具自己的描述（契约 description）；{@code null} = 未声明。 */
        String description;

        @Override
        public boolean insert(@NonNull CapToolDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
            return Optional.of(new CapToolDef(toolId, version, "工具",
                    cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                    cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                    null, null, null, inputSchema, "{}", description, "fp", 1,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        }

        @Override
        public @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId) {
            return Optional.empty();
        }

        @Override
        public @NonNull Optional<CapToolDef> findLatest(@NonNull String toolId) {
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

        @Override
        public List<CapToolDef> listVersions(@NonNull String toolId) {
            return List.of();
        }
}

    private static AgentModelConfigAssembler assembler(ScriptedAgentDefs defs,
                                                        ScriptedClosureResolver closure) {
        return new AgentModelConfigAssembler(defs, closure, new ScriptedToolDefs());
    }

    @Test
    @DisplayName("✅ 按权威定义装配模型标识与提示词（根与子共用这一份）")
    void assemblesModelConfigFromDefinition() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.instructions = "{\"inlineInstructions\":\"你是辅导助手\"}";
        defs.generationParams = "{\"temperature\":0.5,\"maxOutputTokens\":777,\"timeoutMs\":9000}";

        var settings = assembler(defs, new ScriptedClosureResolver())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("model-1");
        assertThat(settings.get().systemPrompt()).isEqualTo("你是辅导助手");
        assertThat(settings.get().temperature()).isEqualTo(0.5);
        assertThat(settings.get().maxTokens()).isEqualTo(777);
        assertThat(settings.get().timeout()).isEqualTo(Duration.ofMillis(9000));
    }

    @Test
    @DisplayName("✅ 提示词读契约形态（instructions.inlineInstructions）")
    void supportsContractShapedPrompt() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.instructions = "{\"inlineInstructions\":\"契约形态提示词\"}";

        var settings = assembler(defs, new ScriptedClosureResolver())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().systemPrompt()).isEqualTo("契约形态提示词");
    }

    @Test
    @DisplayName("⛔ 提示词声明含 systemPromptRef → 拒绝装配（V1 未实现引用式提示词库，不静默忽略）")
    void systemPromptRefIsRejected() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.instructions = "{\"systemPromptRef\":\"prompt-x\",\"systemPromptVersion\":\"3\"}";

        assertThat(assembler(defs, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️ V1 没有提示词库：静默忽略引用会让运维以为「提示词已生效」")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 定义缺少模型标识 → 拒绝装配（不猜模型）")
    void missingModelIdentifierIsRejected() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.modelRef = null;

        assertThat(assembler(defs, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️⚠️ 取不到模型标识 → **不得**随便挑一个默认模型")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 提示词声明不是合法 JSON → 拒绝装配（fail-closed）")
    void malformedInstructionsIsRejected() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.instructions = "{这不是 JSON";

        assertThat(assembler(defs, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
                .as("⚠️ 配置解析不了 → 不得凭空猜一个配置")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 读不到指定版本 → 拒绝装配（不漂移到其它版本）")
    void missingVersionIsRejected() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.version = 1;

        assertThat(assembler(defs, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 9))
                .as("⚠️⚠️ 固定 release 取不到 → 不得回落到别的版本（MA-T08）")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 仓储异常 → 拒绝装配（不吞异常当成功）")
    void repositoryFailureIsRejected() {
        ScriptedAgentDefs defs = new ScriptedAgentDefs();
        defs.throwOnFind = true;

        assertThat(assembler(defs, new ScriptedClosureResolver()).assemble(TENANT, AGENT, 1))
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

        var settings = assembler(new ScriptedAgentDefs(), closure).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().toolSchemas())
                .as("⚠️ 模型能看到的工具 = 它被授权的工具")
                .containsOnlyKeys("tool-a");
    }

    /**
     * 跨模块契约测试：登记表的 {@code inputSchema} 必须是 {@code Map}。
     *
     * <p><b>为什么单独立一条</b>：2026-10-28 接真实模型端到端实测时，
     * 装配侧把 Schema 存成了 {@code JsonNode}，而模型网关按 {@code Map} 取值，
     * 于是<b>每个工具的参数定义都被静默替换成空 Schema</b>。
     * 症状是「模型照提示词喊出工具名、却交不出参数」，报错却只有一句
     * 「缺少必填参数」—— 现场离真因隔了三个模块。上面那些用例只断言
     * {@code containsOnlyKeys("tool-a")} 与 {@code description}，
     * 对这个形态漂移<b>完全无感</b>，所以一直没抓到。
     */
    @Test
    @DisplayName("⛔ 工具登记的 inputSchema 必须是 Map（网关按 Map 取值，JsonNode = 参数全丢）")
    void toolSchemaValueIsMapNotJsonNode() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));
        ScriptedToolDefs toolDefs = new ScriptedToolDefs();
        toolDefs.inputSchema = """
                {"type":"object","required":["studentId"],
                 "properties":{"studentId":{"type":"string"}}}
                """;

        var settings = new AgentModelConfigAssembler(new ScriptedAgentDefs(), closure, toolDefs)
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema =
                (Map<String, Object>) settings.get().toolSchemas().get("tool-a");
        assertThat(toolSchema.get("inputSchema"))
                .as("⛔ 不是 Map → 网关 instanceof Map 判假 → 参数表发空")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) toolSchema.get("inputSchema");
        assertThat(inputSchema)
                .as("参数定义必须原样送达，不能只剩一个空对象")
                .containsEntry("required", List.of("studentId"))
                .containsKey("properties");
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
                new ScriptedAgentDefs(), broken, new ScriptedToolDefs())
                .assemble(TENANT, AGENT, 1);

        assertThat(settings)
                .as("⚠️ 闭包坏掉只是「模型看不到那些工具」，不该让整个 Agent 全瘫")
                .isPresent();
        assertThat(settings.get().toolSchemas()).isEmpty();
    }

    @Test
    @DisplayName("✅ 三级描述落地：工具自己的 description 优先，其次 1级 purpose；2级 usage + 3级 content 进系统提示")
    void threeLevelDescriptionFlowsIntoToolsAndPrompt() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.skills = List.of(new EffectiveClosure.SkillBinding(
                "skill-a", 1,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp-skill-a", List.of("tool-a"), "[]",
                new cn.studykid.multiagent.core.cap.SkillDescription("只读查询学生画像",
                        List.of(new cn.studykid.multiagent.core.cap.SkillDescription.SkillUsage(
                                "用户提供了学生标识",
                                "调用 tool-a；id 取自用户原文，不得凭上下文补全")),
                        "适用于学生查询场景，参数必须来自对话中用户明确给出的标识，不得从历史上下文推测。")));
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));

        var settings = assembler(new ScriptedAgentDefs(), closure).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        // 1级：模型在「挑工具」时首先看到的是所属 Skill 的 purpose（选型信号）
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema =
                (Map<String, Object>) settings.get().toolSchemas().get("tool-a");
        assertThat(toolSchema.get("description"))
                .as("1级 purpose = 工具的模型可见选型信号（不再只给工具名）")
                .isEqualTo("只读查询学生画像");
        // 2级 + 3级：Skill 被命中后指令注入系统提示（when → how + content）
        String prompt = settings.get().systemPrompt();
        assertThat(prompt)
                .startsWith("你是辅导助手")
                .contains("技能使用指引")
                .contains("当 用户提供了学生标识")
                .contains("调用 tool-a；id 取自用户原文，不得凭上下文补全")
                .contains("详细描述：适用于学生查询场景，参数必须来自对话中用户明确给出的标识");
    }

    @Test
    @DisplayName("✅ 工具自己的 description 优先于 Skill 的 purpose（2026-11-09）")
    void toolOwnDescriptionWinsOverPurpose() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.skills = List.of(new EffectiveClosure.SkillBinding(
                "skill-a", 1,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp-skill-a", List.of("tool-a"), "[]",
                new cn.studykid.multiagent.core.cap.SkillDescription("只读查询学生画像",
                        List.of(), "")));
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));
        ScriptedToolDefs toolDefs = new ScriptedToolDefs();
        toolDefs.description = "按学生编码读取学生档案（只读）";

        var settings = new AgentModelConfigAssembler(new ScriptedAgentDefs(), closure, toolDefs)
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema =
                (Map<String, Object>) settings.get().toolSchemas().get("tool-a");
        assertThat(toolSchema.get("description"))
                .as("⚠️ 工具自己的描述描述的是「这个工具做什么」，比 Skill 的 purpose 更精确")
                .isEqualTo("按学生编码读取学生档案（只读）");
    }

    @Test
    @DisplayName("✅ 没有 2/3 级内容的 Skill → 提示词一字不改（向后兼容存量行）")
    void skillsWithoutUsageAndContentLeavePromptUntouched() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.skills = List.of(new EffectiveClosure.SkillBinding(
                "skill-a", 1,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp-skill-a", List.of("tool-a"), "[]",
                new cn.studykid.multiagent.core.cap.SkillDescription(
                        "只读查询学生画像", List.of(), "")));
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));

        var settings = assembler(new ScriptedAgentDefs(), closure).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().systemPrompt()).isEqualTo("你是辅导助手");
        // 1级照样进工具 description
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema =
                (Map<String, Object>) settings.get().toolSchemas().get("tool-a");
        assertThat(toolSchema.get("description")).isEqualTo("只读查询学生画像");
    }

    @Test
    @DisplayName("✅ Skill 无两级描述（存量行）→ 工具描述回退工具名（旧行为）")
    void legacySkillFallsBackToToolName() {
        ScriptedClosureResolver closure = new ScriptedClosureResolver();
        closure.skills = List.of(new EffectiveClosure.SkillBinding(
                "skill-a", 1,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp-skill-a", List.of("tool-a"), "[]"));
        closure.tools = List.of(new EffectiveClosure.ToolBinding(
                "tool-a", 1, cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                cn.studykid.multiagent.core.cap.CapabilityRiskLevel.READ,
                "fp", "{}", null, null));

        var settings = assembler(new ScriptedAgentDefs(), closure)
                .assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema =
                (Map<String, Object>) settings.get().toolSchemas().get("tool-a");
        assertThat(toolSchema.get("description")).isEqualTo("工具");
    }
}
