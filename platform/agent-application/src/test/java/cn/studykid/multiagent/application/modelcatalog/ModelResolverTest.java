package cn.studykid.multiagent.application.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;

/**
 * {@link ModelResolver} 解析语义测试（桩端口，无 DB）。
 *
 * <p>覆盖：显式模型名优先、场景默认、回落 default 场景、fail-closed、禁用模型跳过。
 */
class ModelResolverTest {

    private static ModelDefinition def(String id, String provider, String scenario,
                                      String name, boolean isDefault, boolean enabled) {
        return new ModelDefinition(id, provider, scenario, name, name, "https://x/v1",
                isDefault, true, true, false, null, enabled, 0, null, null, null);
    }

    /** 内存桩：按测试需要返回固定目录。 */
    private static final class StubCatalog implements ModelCatalogPort {
        final List<ModelDefinition> all;

        StubCatalog(List<ModelDefinition> all) {
            this.all = all;
        }

        @Override public List<ModelDefinition> list() { return all; }

        @Override public Optional<ModelDefinition> get(String id) {
            return all.stream().filter(d -> id.equals(d.id())).findFirst();
        }

        @Override public ModelDefinition save(ModelDefinition d) { return d; }

        @Override public void delete(String id) { }

        @Override public Optional<ModelDefinition> defaultForScenario(String scenario) {
            return all.stream().filter(d -> scenario.equals(d.scenario())
                            && d.isDefault() && d.isEnabled()).findFirst();
        }

        @Override public Optional<ModelDefinition> byScenarioAndName(String scenario, String name) {
            return all.stream().filter(d -> scenario.equals(d.scenario())
                            && name.equals(d.modelName()) && d.isEnabled()).findFirst();
        }
    }

    @Test
    void explicitModelNameInScenarioWins() {
        var catalog = new StubCatalog(List.of(
                def("1", "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5", true, true),
                def("2", "openai", ModelScenario.AGENT_CHAT, "gpt-4o", false, true)));
        var resolver = new ModelResolver(catalog);

        var resolved = resolver.resolve(ModelScenario.AGENT_CHAT, "gpt-4o");
        assertThat(resolved).contains(catalog.get("2").orElseThrow());
    }

    @Test
    void explicitNameNotFoundFallsToScenarioDefault() {
        var catalog = new StubCatalog(List.of(
                def("1", "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5", true, true),
                def("2", "openai", ModelScenario.AGENT_CHAT, "gpt-4o", false, true)));
        var resolver = new ModelResolver(catalog);

        var resolved = resolver.resolve(ModelScenario.AGENT_CHAT, "does-not-exist");
        assertThat(resolved).contains(catalog.get("1").orElseThrow());
    }

    @Test
    void noDefaultInScenarioFallsToDefaultScenario() {
        var catalog = new StubCatalog(List.of(
                def("1", "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5", false, true),
                def("9", "minimax", ModelScenario.DEFAULT, "MiniMax-M2.5", true, true)));
        var resolver = new ModelResolver(catalog);

        var resolved = resolver.resolve(ModelScenario.AGENT_CHAT, null);
        assertThat(resolved).contains(catalog.get("9").orElseThrow());
    }

    @Test
    void disabledModelIsSkippedEvenIfDefault() {
        // 默认位上的模型被禁用 → 视为无默认 → 回落 default 场景也无 → 空
        var catalog = new StubCatalog(List.of(
                def("1", "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5", true, false)));
        var resolver = new ModelResolver(catalog);

        assertThat(resolver.resolve(ModelScenario.AGENT_CHAT, null)).isEmpty();
    }

    @Test
    void nothingConfiguredIsEmptyAndRequiredThrows() {
        var resolver = new ModelResolver(new StubCatalog(List.of()));
        assertThat(resolver.resolve(ModelScenario.AGENT_CHAT, null)).isEmpty();
        assertThatThrownBy(() -> resolver.resolveRequired(ModelScenario.AGENT_CHAT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可用的模型配置");
    }

    @Test
    void explicitDisabledNameFallsThroughToDefault() {
        var catalog = new StubCatalog(List.of(
                def("1", "openai", ModelScenario.AGENT_CHAT, "gpt-4o", false, false),
                def("2", "minimax", ModelScenario.AGENT_CHAT, "MiniMax-M2.5", true, true)));
        var resolver = new ModelResolver(catalog);

        // 显式点名 gpt-4o 但已禁用 → byScenarioAndName 过滤 enabled，回落默认
        var resolved = resolver.resolve(ModelScenario.AGENT_CHAT, "gpt-4o");
        assertThat(resolved).contains(catalog.get("2").orElseThrow());
    }
}
