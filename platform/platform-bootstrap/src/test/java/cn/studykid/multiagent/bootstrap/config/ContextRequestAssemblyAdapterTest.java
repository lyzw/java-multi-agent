package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyInput;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyResult;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * M7-b 组合根 adapter 行为测试（ADR-013 D38 / G1 A.5 升级证据）。
 *
 * <p>⚠️ <b>本类从统一入口进，不测内部私有方法</b>：断言「送模型的唯一请求」、关闭态
 * 同一引用、冻结策略读取、能力精确匹配与 fail-closed 稳定码。
 *
 * <p>⚠️ <b>不引用被测实现自己的常量</b>：围栏标记与原因码用字面量冻结，
 * 避免「改常量＝改测试」的自证循环（铁律 #6）。
 */
class ContextRequestAssemblyAdapterTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String AGENT = "agent-m7b";
    private static final int VERSION = 7;
    private static final String MODEL = "model-m7b";
    private static final String STABLE = "你是错题归因助手，必须遵守以下约束";
    private static final String MEMORY = "偏好：先讲思路再给答案";
    private static final String RAG = "命中：概念混淆的定义";
    private static final String FIRST = "原始任务：解方程 2x+3=7";

    private final StubAgentDefs defs = new StubAgentDefs();
    private final StubCatalog catalog = new StubCatalog();

    @Test
    @DisplayName("✅ 总开关关闭：返回 legacy 同一引用，零策略读取 / 零能力读取 / 零行为数据")
    void disabledShortCircuitsWithZeroInteractions() {
        RequestAssemblyPort adapter = adapter(false, "{}");
        ModelRequest legacy = legacy();
        defs.failOnRead = true;
        catalog.failOnRead = true;

        RequestAssemblyResult out = adapter.assemble(input(legacy, List.of(), ""));

        assertThat(out.request()).isSameAs(legacy);
        assertThat(out.assembled()).isFalse();
        assertThat(out.tier()).isZero();
        assertThat(out.beforeTokens()).isZero();
        assertThat(out.afterTokens()).isZero();
        assertThat(out.compactionCount()).isZero();
        assertThat(out.checkpointDelta()).isNull();
        assertThat(defs.reads).as("⛔ 关闭态读了冻结策略").isZero();
        assertThat(catalog.reads).as("⛔ 关闭态读了模型能力").isZero();
    }

    @Test
    @DisplayName("✅ 开启态 W0：稳定前缀逐字保留，memory/RAG 走围栏 USER 块，6 个非上下文字段逐字复制")
    void enabledSwapsOnlyContextBearingFields() {
        RequestAssemblyPort adapter = adapter(true, "{}");
        ModelRequest legacy = legacy();

        RequestAssemblyResult out = adapter.assemble(input(legacy,
                List.of(ModelMessage.assistant("历史助手"), ModelMessage.user("历史用户")),
                MEMORY, RAG));

        assertThat(out.assembled()).as("⛔ 未装配：测试可能根本没触发压缩链路").isTrue();
        ModelRequest req = out.request();
        assertThat(req).isNotSameAs(legacy);
        assertThat(req.modelId()).isEqualTo(legacy.modelId());
        assertThat(req.userContent()).isEqualTo(legacy.userContent());
        assertThat(req.temperature()).isEqualTo(legacy.temperature());
        assertThat(req.maxTokens()).isEqualTo(legacy.maxTokens());
        assertThat(req.timeout()).isEqualTo(legacy.timeout());
        assertThat(req.toolSchemas()).isEqualTo(legacy.toolSchemas());

        assertThat(req.systemPrompt()).isEqualTo(STABLE)
                .as("⛔ legacy 裁剪说明 / 记忆 / RAG 混进了可信指令区")
                .doesNotContain("裁剪说明").doesNotContain(MEMORY).doesNotContain(RAG);

        // [D27] 首轮锚独立 pinned、历史不带锚、currentInput 留空 ⇒ 任务只发一遍。
        assertThat(req.messages()).filteredOn(m -> m.content().contains(FIRST)).hasSize(1);
        assertThat(req.messages()).filteredOn(m -> m.content().contains("历史助手")).hasSize(1);
        assertThat(req.messages()).filteredOn(m -> m.content().contains("裁剪说明")).isEmpty();

        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ModelMessage.Role.USER);
            assertThat(m.content()).contains("<compacted_context trust=\"untrusted\">")
                    .contains("[memory]").contains(MEMORY)
                    .contains("</compacted_context>");
        });
        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ModelMessage.Role.USER);
            assertThat(m.content()).contains("<compacted_context trust=\"untrusted\">")
                    .contains("[rag[0]]").contains(RAG)
                    .contains("</compacted_context>");
        });
        assertThat(req.messages())
                .filteredOn(m -> m.content().contains("<compacted_context"))
                .allSatisfy(m -> assertThat(m.role()).isEqualTo(ModelMessage.Role.USER));

        assertThat(out.beforeTokens()).isPositive();
        assertThat(out.afterTokens()).isPositive();
        assertThat(out.droppedTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("⛔ 模型能力按 modelName 精确匹配：绝不拿目录里另一台模型的窗口给本机算预算")
    void capabilitiesNeverFallBackToAnotherModel() {
        // 目录里只有一台 100 万窗口的模型；本 Run 用的是未登记的 model-m7b。
        catalog.definitions = List.of(definition("other-model", 1_000_000, 995_904));
        RequestAssemblyPort adapter = adapter(true, "{}");
        // unknown 能力 → Resolver 回落 hardCeiling+4096=132096 作为总窗口。
        // 16000 + 200000 远超它；若错误拿到 other-model 的 100 万窗口就不会拒。
        RequestAssemblyInput input = new RequestAssemblyInput(
                TENANT, "run:99", AGENT, VERSION, MODEL, STABLE, Map.of(),
                FIRST, "", List.of(), List.of(), "", 200_000, 9L, -1L, null, 0, legacy());

        assertThatThrownBy(() -> adapter.assemble(input))
                .isInstanceOf(RequestAssemblyException.class)
                .extracting(e -> ((RequestAssemblyException) e).reason().name())
                .isEqualTo("CONTEXT_WINDOW_EXCEEDED");
    }

    @Test
    @DisplayName("⛔ 冻结策略非法 → CONTEXT_POLICY_INVALID，不静默回退默认水位")
    void invalidFrozenPolicyFailsClosed() {
        RequestAssemblyPort adapter = adapter(true, "{\"context\":{\"strategy\":\"BOGUS\"}}");

        assertThatThrownBy(() -> adapter.assemble(input(legacy(), List.of(), "")))
                .isInstanceOf(RequestAssemblyException.class)
                .extracting(e -> ((RequestAssemblyException) e).reason().name())
                .isEqualTo("CONTEXT_POLICY_INVALID");
    }

    @Test
    @DisplayName("⛔ 冻结版本定义缺失 → CONTEXT_ASSEMBLY_FAILED，绝不回退 legacy 继续发模型请求")
    void missingFrozenDefinitionFailsClosed() {
        RequestAssemblyPort adapter = adapter(true, "{}");
        defs.returnEmpty = true;

        assertThatThrownBy(() -> adapter.assemble(input(legacy(), List.of(), "")))
                .isInstanceOf(RequestAssemblyException.class)
                .extracting(e -> ((RequestAssemblyException) e).reason().name())
                .isEqualTo("CONTEXT_ASSEMBLY_FAILED");
    }

    @Test
    @DisplayName("⛔ Agent 级 strategy=OFF：同样返回 legacy 同一引用（与总开关同构的关闭等价）")
    void offStrategyAlsoReturnsLegacyByIdentity() {
        RequestAssemblyPort adapter = adapter(true,
                "{\"context\":{\"enabled\":true,\"strategy\":\"OFF\"}}");
        ModelRequest legacy = legacy();

        RequestAssemblyResult out = adapter.assemble(input(legacy, List.of(), MEMORY));

        assertThat(out.request()).isSameAs(legacy);
        assertThat(out.assembled()).isFalse();
        assertThat(out.beforeTokens()).isZero();
        assertThat(out.afterTokens()).isZero();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private RequestAssemblyPort adapter(boolean enabled, String generationParams) {
        defs.generationParams = generationParams;
        return new ContextRequestAssemblyAdapter(
                new GlobalContextPolicy(enabled, 128_000, 16_000, 0.10),
                new ContextAssembler(new HeuristicTokenCounter(), Thresholds.defaults()),
                (delta, oldSummary) -> Optional.empty(), defs, catalog);
    }

    private static ModelRequest legacy() {
        return new ModelRequest(MODEL, STABLE + "\n[裁剪说明] 早期轮次已移除",
                FIRST, 0.37d, 1024, Duration.ofSeconds(11),
                Map.of("grade_homework", Map.of("type", "object")),
                List.of(ModelMessage.user(FIRST)));
    }

    private static RequestAssemblyInput input(ModelRequest legacy,
                                              List<ModelMessage> history,
                                              String memory, String... rag) {
        return new RequestAssemblyInput(
                TENANT, "run:42", AGENT, VERSION, MODEL, STABLE, legacy.toolSchemas(),
                FIRST, memory, List.of(rag), history, "", legacy.maxTokens(),
                42L, -1L, null, 0, legacy);
    }

    private static ModelDefinition definition(String name, int window, int maxInput) {
        return new ModelDefinition(UUID.nameUUIDFromBytes(name.getBytes()).toString(),
                "test", "agent-chat", name, null, null, false, true, true, false,
                null, true, 0, null, null, null,
                new ModelContextCapabilities(window, maxInput, null, false, false));
    }

    private static CapAgentDef agentDef(String generationParams) {
        return new CapAgentDef(TENANT, AGENT, VERSION, "测试 Agent", null,
                List.of(), List.of(), List.of(), List.of(), "{}", "{}",
                "{\"inlineInstructions\":\"" + STABLE + "\"}", MODEL, generationParams,
                "fp-test", 1, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
    }

    private static final class StubAgentDefs implements CapAgentDefPort {
        String generationParams = "{}";
        boolean failOnRead;
        boolean returnEmpty;
        int reads;

        @Override
        public boolean insert(CapAgentDef def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CapAgentDef> find(String tenantId, String agentId, int version) {
            reads++;
            if (failOnRead) {
                throw new AssertionError("⛔ 关闭态不应读取冻结策略");
            }
            return returnEmpty ? Optional.empty()
                    : Optional.of(agentDef(generationParams));
        }

        @Override
        public Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CapAgentDef> findLatestPublished(String tenantId, String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CapAgentDef> listLatestPerAgent(String tenantId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubCatalog implements ModelCatalogPort {
        List<ModelDefinition> definitions = List.of();
        boolean failOnRead;
        int reads;

        @Override
        public List<ModelDefinition> list() {
            reads++;
            if (failOnRead) {
                throw new AssertionError("⛔ 关闭态不应读取模型能力");
            }
            return definitions;
        }

        @Override
        public Optional<ModelDefinition> get(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelDefinition save(ModelDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ModelDefinition> defaultForScenario(String scenario) {
            throw new UnsupportedOperationException("⛔ 能力读取不得回落场景默认模型");
        }

        @Override
        public Optional<ModelDefinition> byScenarioAndName(String scenario, String modelName) {
            throw new UnsupportedOperationException("⛔ 能力读取必须精确匹配全目录，不走解析回落");
        }
    }
}
