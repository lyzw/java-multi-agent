package cn.studykid.multiagent.ports.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ModelDefinition} 不可变值对象的校验与展示名回退。
 */
class ModelDefinitionTest {

    private static ModelDefinition def(String provider, String scenario, String name) {
        return new ModelDefinition(null, provider, scenario, name, null, null,
                false, true, true, false, null, true, 0, null, null, null);
    }

    @Test
    void rejectsBlankProvider() {
        assertThatThrownBy(() -> new ModelDefinition(null, "", "agent-chat",
                "m", null, null, false, true, true, false, null, true, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankScenario() {
        assertThatThrownBy(() -> new ModelDefinition(null, "openai", "",
                "m", null, null, false, true, true, false, null, true, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankModelName() {
        assertThatThrownBy(() -> new ModelDefinition(null, "openai", "agent-chat",
                "  ", null, null, false, true, true, false, null, true, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labelFallsBackToModelNameWhenDisplayNameBlank() {
        assertThat(def("openai", "agent-chat", "gpt-4o").label()).isEqualTo("gpt-4o");
        ModelDefinition withDisplay = new ModelDefinition(null, "openai", "agent-chat",
                "gpt-4o", "GPT-4o 对话", null, false, true, true, false, null, true, 0, null, null, null);
        assertThat(withDisplay.label()).isEqualTo("GPT-4o 对话");
    }
}
