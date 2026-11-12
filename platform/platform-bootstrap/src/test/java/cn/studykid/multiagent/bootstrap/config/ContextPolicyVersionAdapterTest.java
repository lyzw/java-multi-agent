package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort.Input;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * S3-M8-a 策略指纹裁决适配器的行为测试（不启 Spring，直接构造）。
 *
 * <p>钉死三件事：关闭态零读取、开启态与统一装配链路同源派生、任何裁决异常都
 * 退回 {@code null}（⇒ 编排器静默丢弃客户端压缩态，而不是报错 / 放行旧状态）。
 */
class ContextPolicyVersionAdapterTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String AGENT = "agent-1";
    private static final int VERSION = 3;
    private static final String MODEL = "model-x";

    private final CapAgentDefPort defs = mock(CapAgentDefPort.class);
    private final ModelCatalogPort catalog = mock(ModelCatalogPort.class);

    private static final Input INPUT = new Input(TENANT, AGENT, VERSION, MODEL, 2048);

    @Test
    @DisplayName("⛔ 总开关关闭：零定义读取 / 零能力读取，直接返回 null")
    void disabledShortCircuitsWithoutAnyRead() {
        ContextPolicyVersionPort adapter = adapter(false);

        assertThat(adapter.resolve(INPUT)).isNull();
        verifyNoInteractions(defs, catalog);
    }

    @Test
    @DisplayName("✅ 开启态由同一裁决内核派生稳定指纹，读的是冻结版本")
    void enabledResolvesVersionFromFrozenDefinition() {
        when(defs.find(TENANT, AGENT, VERSION)).thenReturn(Optional.of(definition("{}")));
        when(catalog.list()).thenReturn(List.of(model(MODEL, 128_000, 120_000)));
        ContextPolicyVersionPort adapter = adapter(true);

        String first = adapter.resolve(INPUT);
        String second = adapter.resolve(INPUT);

        assertThat(first).startsWith("cp_").isEqualTo(second);
        verify(defs, org.mockito.Mockito.times(2))
                .find(TENANT, AGENT, VERSION);
    }

    @Test
    @DisplayName("⛔ 策略任一字段变化即换指纹：不是全局常量，也不从信封反推")
    void policyChangeInvalidatesVersion() {
        ContextPolicyVersionPort adapter = adapter(true);
        when(catalog.list()).thenReturn(List.of(model(MODEL, 128_000, 120_000)));
        when(defs.find(TENANT, AGENT, VERSION))
                .thenReturn(Optional.of(definition("{}")))
                .thenReturn(Optional.of(definition("{\"context\":{\"recentTurns\":7}}")));

        String before = adapter.resolve(INPUT);
        String after = adapter.resolve(INPUT);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("⛔ 定义缺失 / 策略非法 / 模型能力不可用：一律 null，不抛给 HTTP 层")
    void resolutionFailuresAreFailClosed() {
        ContextPolicyVersionPort adapter = adapter(true);
        when(defs.find(anyString(), anyString(), anyInt())).thenReturn(Optional.empty());
        assertThat(adapter.resolve(INPUT)).isNull();

        when(defs.find(TENANT, AGENT, VERSION))
                .thenReturn(Optional.of(definition("{\"context\":{\"strategy\":\"BOGUS\"}}")));
        assertThat(adapter.resolve(INPUT)).isNull();

        when(defs.find(TENANT, AGENT, VERSION))
                .thenReturn(Optional.of(definition("{}")));
        when(catalog.list()).thenReturn(List.of(model("other-model", 1_000_000, 990_000)));
        // 能力未知仍由裁决内核上调 reserve，得到一个保守但合法的版本，绝不回落别的模型窗口。
        assertThat(adapter.resolve(INPUT)).startsWith("cp_");
    }

    private ContextPolicyVersionPort adapter(boolean enabled) {
        return new ContextPolicyVersionAdapter(
                new GlobalContextPolicy(enabled, 128_000, 16_000, 0.10), defs, catalog);
    }

    private static CapAgentDef definition(String generationParams) {
        return new CapAgentDef(
                TENANT, AGENT, VERSION, "测试助手", "描述", List.of(), List.of(),
                List.of(), List.of(), "{}", "{}", "{}", MODEL, generationParams,
                "fp-" + generationParams.hashCode(), 1, Instant.now(), Instant.now());
    }

    private static ModelDefinition model(String name, int total, int maxInput) {
        return new ModelDefinition(
                "id-" + name, "minimax", "CHAT", name, null, null, false,
                true, true, false, null, true, 0, null, null, null,
                new ModelContextCapabilities(total, maxInput, "heuristic", false, false));
    }
}
