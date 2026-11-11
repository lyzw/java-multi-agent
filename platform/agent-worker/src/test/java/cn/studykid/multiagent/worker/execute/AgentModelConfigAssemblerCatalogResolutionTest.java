package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 模型配置装配器的「统一模型目录解析」行为验证。
 *
 * <p>覆盖：modelRef 命中目录 / 未命中但有场景默认（回落默认）/
 * 目录为空（回落字面名，fail-soft）/ 未注入解析器（原样用 modelRef）。
 */
class AgentModelConfigAssemblerCatalogResolutionTest {

    private static final String TENANT = "t-resolve";
    private static final String AGENT = "a-resolve";

    private static ModelDefinition agentChatDefault() {
        return new ModelDefinition(null, "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5",
                "MiniMax M2.5", "https://api.minimax.cn/v1",
                true, true, true, false, null, true, 0, "默认对话模型", null, null,
                ModelContextCapabilities.defaults());
    }

    @Test
    @DisplayName("✅ modelRef 命中目录 → 取目录 modelName")
    void resolvesModelRefFromCatalogWhenPresent() {
        StubCatalogPort catalog = new StubCatalogPort(List.of(agentChatDefault()));
        ModelResolver resolver = new ModelResolver(catalog);
        var query = agentDefsWithModelRef("MiniMax-M2.5");

        var settings = new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                new ScriptedToolDefs(), resolver).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("MiniMax-M2.5");
    }

    @Test
    @DisplayName("✅ modelRef 未命中但场景有默认 → 回落场景默认")
    void fallsBackToScenarioDefaultWhenModelRefUnmatched() {
        StubCatalogPort catalog = new StubCatalogPort(List.of(agentChatDefault()));
        ModelResolver resolver = new ModelResolver(catalog);
        // 该 Agent 写了一个目录里不存在的模型名
        var query = agentDefsWithModelRef("some-legacy-model");

        var settings = new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                new ScriptedToolDefs(), resolver).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("MiniMax-M2.5");
    }

    @Test
    @DisplayName("✅ 目录为空（未配置）/ 无默认 → 回落字面 modelRef（fail-soft，不破坏既有数据）")
    void fallsBackToLiteralWhenCatalogEmpty() {
        StubCatalogPort catalog = new StubCatalogPort(List.of()); // 空目录
        ModelResolver resolver = new ModelResolver(catalog);
        var query = agentDefsWithModelRef("some-legacy-model");

        var settings = new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                new ScriptedToolDefs(), resolver).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("some-legacy-model");
    }

    @Test
    @DisplayName("✅ modelRef 为 catalog id（UUID）→ 按 id 精确解析取 modelName")
    void resolvesByCatalogId() {
        String catalogId = "11111111-1111-1111-1111-111111111111";
        ModelDefinition bound = new ModelDefinition(catalogId, "minimax", ModelScenario.AGENT_CHAT,
                "resolved-by-id-model", "按 id 绑定的模型", "https://api.minimax.cn/v1",
                true, true, true, false, null, true, 0, "Per-Agent 绑目录 id", null, null,
                ModelContextCapabilities.defaults());
        StubCatalogPort catalog = new StubCatalogPort(List.of(bound));
        ModelResolver resolver = new ModelResolver(catalog);
        // Agent 定义里直接存目录记录的 id（不是模型名）
        var query = agentDefsWithModelRef(catalogId);

        var settings = new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                new ScriptedToolDefs(), resolver).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("resolved-by-id-model");
    }

    @Test
    @DisplayName("✅ 未注入解析器（3 参构造）→ 原样使用 modelRef，向后兼容")
    void literalWhenResolverAbsent() {
        var query = agentDefsWithModelRef("MiniMax-M2.5");

        var settings = new AgentModelConfigAssembler(query, new ScriptedClosureResolver(),
                new ScriptedToolDefs()).assemble(TENANT, AGENT, 1);

        assertThat(settings).isPresent();
        assertThat(settings.get().modelId()).isEqualTo("MiniMax-M2.5");
    }

    // ── 极简替身 ──

    /**
     * 只关心「模型引用」的替身（2026-11-09 · 权威源定 A）：
     * 配置真源是 {@code cap_agent_def}，模型引用取自其 {@code model_ref} 列。
     */
    private static ScriptedAgentDefPort agentDefsWithModelRef(String modelRef) {
        ScriptedAgentDefPort defs = new ScriptedAgentDefPort();
        defs.modelRef = modelRef;
        return defs;
    }

    private static final class ScriptedClosureResolver implements ClosureResolver {
        @Override
        public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                                @NonNull String agentId, int agentVersion) {
            return new EffectiveClosure(agentId, agentVersion, List.of(), List.of(), List.of());
        }

        @Override
        public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                       @NonNull String agentId) {
            return resolve(tenantId, agentId, 1);
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
        public @NonNull List<CapToolDef> listByMcpServer(String mcpServerId) {
            return List.of();
        }

        @Override
        public @NonNull List<CapToolDef> listAll() {
            return List.of();
        }
    }

    private static final class StubCatalogPort implements ModelCatalogPort {
        private final List<ModelDefinition> all;

        StubCatalogPort(List<ModelDefinition> all) {
            this.all = all;
        }

        @Override
        public @NonNull List<ModelDefinition> list() {
            return all;
        }

        @Override
        public @NonNull Optional<ModelDefinition> get(@NonNull String id) {
            return all.stream().filter(d -> id.equals(d.id())).findFirst();
        }

        @Override
        public @NonNull ModelDefinition save(@NonNull ModelDefinition definition) {
            return definition;
        }

        @Override
        public void delete(@NonNull String id) {
            // no-op
        }

        @Override
        public @NonNull Optional<ModelDefinition> defaultForScenario(@NonNull String scenario) {
            return all.stream()
                    .filter(d -> d.scenario().equals(scenario) && d.isDefault() && d.isEnabled())
                    .findFirst();
        }

        @Override
        public @NonNull Optional<ModelDefinition> byScenarioAndName(@NonNull String scenario,
                                                                  @NonNull String modelName) {
            return all.stream()
                    .filter(d -> d.scenario().equals(scenario)
                            && d.modelName().equals(modelName) && d.isEnabled())
                    .findFirst();
        }
    }
}
