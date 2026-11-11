package cn.studykid.multiagent.api.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型配置「上下文与压缩」契约（ADR-013 / M1）。
 *
 * <p>⚠️ <b>本类专防两类静默失真</b>：
 * ① 旧 payload 因新增 primitive 字段被 Jackson 判 400（前端/运维脚本莫名全挂）；
 * ② 新增列只进 INSERT 不进 {@code ON CONFLICT DO UPDATE SET}，
 * 后台每次 PUT 把能力覆盖回默认值（由源码守卫覆盖，见
 * {@code ModelCatalogContextCapabilityGuardTest}）。
 */
class AdminModelConfigContextContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 改动前的 13 字段 payload（新列一个都不带）。 */
    private static final String LEGACY_PAYLOAD = """
            {"provider":"minimax","scenario":"agent-chat","modelName":"MiniMax-M2.5",
             "displayName":"MiniMax M2.5","baseUrl":"https://api.minimax.cn/v1",
             "isDefault":true,"supportsTools":true,"supportsStreaming":true,
             "supportsVision":false,"dimension":null,"isEnabled":true,
             "sortOrder":0,"description":"默认"}
            """;

    @Test
    @DisplayName("✅ 旧 payload 不带 5 个新键仍可反序列化：不得因 primitive 缺字段判 400")
    void legacyPayloadRemainsCompatible() {
        AdminModelConfigController.ModelConfigRequest req =
                MAPPER.readValue(LEGACY_PAYLOAD,
                        AdminModelConfigController.ModelConfigRequest.class);

        assertThat(req.provider()).isEqualTo("minimax");
        assertThat(req.contextWindowTokens()).isNull();
        assertThat(req.maxInputTokens()).isNull();
        assertThat(req.tokenizerId()).isNull();
        assertThat(req.supportsPromptCaching()).isNull();
        assertThat(req.supportsTokenizeApi()).isNull();
    }

    @Test
    @DisplayName("✅ 新字段一律为包装类型（primitive 会让旧 payload 直接 400）")
    void newFieldsAreNullableWrappers() {
        var components = AdminModelConfigController.ModelConfigRequest.class.getRecordComponents();
        assertThat(components).hasSize(18);
        for (String name : new String[]{"contextWindowTokens", "maxInputTokens",
                "tokenizerId", "supportsPromptCaching", "supportsTokenizeApi"}) {
            var type = java.util.Arrays.stream(components)
                    .filter(c -> c.getName().equals(name)).findFirst().orElseThrow().getType();
            assertThat(type)
                    .as(name + " 必须是包装/引用类型，不能是 primitive")
                    .isIn(Integer.class, String.class, Boolean.class);
        }
    }

    @Test
    @DisplayName("✅ 缺省回落到 DDL 默认值：32768 / 28672 / null / false / false（R10）")
    void missingFieldsFallBackToDdlDefaults() {
        var req = MAPPER.readValue(LEGACY_PAYLOAD,
                AdminModelConfigController.ModelConfigRequest.class);
        ModelContextCapabilities caps =
                AdminModelConfigController.contextCapabilities(req);

        assertThat(caps.contextWindowTokens()).isEqualTo(32_768);
        assertThat(caps.maxInputTokens()).isEqualTo(28_672);
        assertThat(caps.tokenizerId()).isNull();
        assertThat(caps.supportsPromptCaching()).isFalse();
        assertThat(caps.supportsTokenizeApi()).isFalse();
        assertThat(caps).isEqualTo(ModelContextCapabilities.defaults());
    }

    @Test
    @DisplayName("✅ 只给新窗口不输入上限 → 自动取「总窗口 − 4096」")
    void derivedMaxInputTokensFollowsWindow() {
        var req = new AdminModelConfigController.ModelConfigRequest(
                "openai", "agent-chat", "gpt", "", "",
                false, true, true, false, null, true, 0, "",
                65_536, null, "cl100k_base", true, false);
        ModelContextCapabilities caps =
                AdminModelConfigController.contextCapabilities(req);

        assertThat(caps.contextWindowTokens()).isEqualTo(65_536);
        assertThat(caps.maxInputTokens()).isEqualTo(61_440);
        assertThat(caps.tokenizerId()).isEqualTo("cl100k_base");
        assertThat(caps.supportsPromptCaching()).isTrue();
        assertThat(caps.supportsTokenizeApi()).isFalse();
    }

    @Test
    @DisplayName("⛔ 输入硬顶 > 总窗口 − 4096 必须配置期拒绝，绝不运行期悄悄夹紧")
    void invalidWindowIsRejected() {
        var req = new AdminModelConfigController.ModelConfigRequest(
                "openai", "agent-chat", "gpt", "", "",
                false, true, true, false, null, true, 0, "",
                32_768, 30_000, null, false, false);
        assertThatThrownBy(() -> AdminModelConfigController.contextCapabilities(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出预留");
    }

    @Test
    @DisplayName("✅ 响应回显 5 项能力（与 catalog 值逐字段一致）")
    void responseCarriesCapabilities() {
        ModelContextCapabilities caps = new ModelContextCapabilities(
                65_536, 60_000, "o200k_base", true, false);
        ModelDefinition d = new ModelDefinition(
                "id-1", "openai", "agent-chat", "gpt", "GPT", "https://x/v1",
                true, true, true, false, null, true, 0, "备注",
                null, null, caps);

        var resp = AdminModelConfigController.ModelConfigResponse.from(d);
        assertThat(resp.contextWindowTokens()).isEqualTo(65_536);
        assertThat(resp.maxInputTokens()).isEqualTo(60_000);
        assertThat(resp.tokenizerId()).isEqualTo("o200k_base");
        assertThat(resp.supportsPromptCaching()).isTrue();
        assertThat(resp.supportsTokenizeApi()).isFalse();
    }
}
