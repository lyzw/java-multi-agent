package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.infrastructure.model.FakeModelGateway;
import cn.studykid.multiagent.infrastructure.model.MiniMaxModelGateway;
import cn.studykid.multiagent.infrastructure.model.ModelCredentialProvider;
import cn.studykid.multiagent.infrastructure.model.OpenAiCompatibleModelGateway;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;

/**
 * Worker 执行链「真实 LLM 网关」解析验证（关闭「两者都接」P1）。
 *
 * <p><b>⚠️ 锁死五条不变式</b>：
 * <ol>
 *   <li>统一模型目录给出 <b>minimax</b> 默认且已启用 + 可解析到密钥 →
 *       返回 {@link MiniMaxModelGateway}（真实调用，非 Fake）；</li>
 *   <li>统一模型目录给出 <b>openai</b> 默认且已启用 + 可解析到密钥 →
 *       返回 {@link OpenAiCompatibleModelGateway}（标准 OpenAI 协议真实调用）；</li>
 *   <li>目录为空、但 yml 配了 {@code agent.model.minimax.api-key} →
 *       回落 {@link MiniMaxModelGateway}（真实调用，历史部署兼容）；</li>
 *   <li>目录为空、yml 也无密钥 → {@link FakeModelGateway} 占位（链路可跑，但明示「非真实调用」）；</li>
 *   <li>目录给出<b>未知 provider</b>（无适配器）→ 回落 Fake 占位（fail-soft，不臆造网关）。</li>
 * </ol>
 *
 * <p><b>核心意图</b>：Worker 早已接通真实模型路径（种子默认 {@code MiniMax-M2.5} +
 * 解析器 + 网关工厂俱在），本测试证明「只要密钥可解析，就不会回落 Fake」——
 * 把「凭空以为接上了」变成可断言的事实。完整「上下文能否启动」由
 * {@code WorkerLoopWiringTest}（{@code IT_PG_URL} 门禁）在集成环境覆盖。
 */
class WorkerRealModelGatewayResolutionTest {

    /** MiniMax 真实调用：api-key 非空即启用（仅环境变量注入，不得写死或入库）。 */
    private static final String MINIMAX_URL = "https://api.minimax.cn/v1";

    private static ModelDefinition def(String provider, String modelName, String baseUrl,
                                       boolean enabled) {
        return new ModelDefinition(
                null, provider, ModelScenario.AGENT_CHAT, modelName, null, baseUrl,
                true, false, true, false, null, enabled, 0, null, null, null);
    }

    /** 只返回固定默认模型的解析器替身。 */
    private static final class StubResolver extends ModelResolver {
        private final Optional<ModelDefinition> def;

        StubResolver(Optional<ModelDefinition> def) {
            super(null);
            this.def = def;
        }

        @Override
        public Optional<ModelDefinition> defaultForScenario(String scenario) {
            return def;
        }
    }

    /** 按 provider 返回固定密钥 / 默认地址的凭据解析替身。 */
    private static final class StubCredentialProvider extends ModelCredentialProvider {
        private final Map<String, String> keys;
        private final Map<String, String> urls;

        StubCredentialProvider(Map<String, String> keys, Map<String, String> urls) {
            super(null);
            this.keys = keys;
            this.urls = urls;
        }

        @Override
        public String apiKeyFor(String provider) {
            return keys.get(provider);
        }

        @Override
        public String defaultBaseUrlFor(String provider) {
            return urls.get(provider);
        }
    }

    private static WorkerExecutionConfig configWith(ModelResolver resolver,
                                                   ModelCredentialProvider creds,
                                                   String minimaxApiKey) {
        WorkerExecutionConfig config = new WorkerExecutionConfig();
        setField(config, "modelResolver", resolver);
        setField(config, "modelCredentialProvider", creds);
        setField(config, "minimaxApiKey", minimaxApiKey);
        setField(config, "minimaxBaseUrl", MINIMAX_URL);
        setField(config, "minimaxDefaultModel", "MiniMax-M2.5");
        return config;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = WorkerExecutionConfig.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入字段 " + name, e);
        }
    }

    @Test
    @DisplayName("✅ 目录默认 minimax 且密钥可解析 → 真实 MiniMaxModelGateway（非 Fake）")
    void catalogMinimaxResolvesToRealGateway() {
        ModelDefinition d = def("minimax", "MiniMax-M2.5", MINIMAX_URL, true);
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.of(d)),
                new StubCredentialProvider(Map.of("minimax", "sk-minimax"), Map.of()),
                "");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(MiniMaxModelGateway.class)
                .isNotInstanceOf(FakeModelGateway.class);
    }

    @Test
    @DisplayName("✅ 目录默认 openai 且密钥可解析 → 真实 OpenAiCompatibleModelGateway（非 Fake）")
    void catalogOpenAiResolvesToRealGateway() {
        ModelDefinition d = def("openai", "gpt-4o", "https://api.openai.com/v1", true);
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.of(d)),
                new StubCredentialProvider(Map.of("openai", "sk-openai"), Map.of()),
                "");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(OpenAiCompatibleModelGateway.class)
                .isNotInstanceOf(FakeModelGateway.class);
    }

    @Test
    @DisplayName("✅ 目录为空、yml 配 minimax api-key → 回落真实 MiniMaxModelGateway")
    void emptyCatalogFallsBackToYmlRealGateway() {
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.empty()),
                new StubCredentialProvider(Map.of(), Map.of()),
                "sk-yml-minimax");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(MiniMaxModelGateway.class)
                .isNotInstanceOf(FakeModelGateway.class);
    }

    @Test
    @DisplayName("⛔ 目录为空、yml 也无密钥 → FakeModelGateway 占位（明示非真实调用）")
    void noCatalogNoKeyFallsBackToFake() {
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.empty()),
                new StubCredentialProvider(Map.of(), Map.of()),
                "");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(FakeModelGateway.class);
    }

    @Test
    @DisplayName("⛔ 目录给未知 provider（无适配器）→ 回落 Fake 占位（fail-soft，不臆造）")
    void unknownProviderFallsBackToFake() {
        ModelDefinition d = def("bananas", "banana-model", "https://api.bananas.example/v1", true);
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.of(d)),
                new StubCredentialProvider(Map.of("bananas", "sk-bananas"), Map.of()),
                "");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(FakeModelGateway.class);
    }

    @Test
    @DisplayName("⛔ 目录默认已启用但密钥不可解析 → 不伪装成真实调用（回落 Fake）")
    void enabledCatalogWithoutKeyDoesNotPretendReal() {
        ModelDefinition d = def("minimax", "MiniMax-M2.5", MINIMAX_URL, true);
        // ⚠️ 关键：apiKeyFor 返回 null（密钥缺失）→ 不应走真实网关
        WorkerExecutionConfig config = configWith(
                new StubResolver(Optional.of(d)),
                new StubCredentialProvider(Map.of(), Map.of()),
                "");

        ModelGateway gateway = config.rawModelGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(FakeModelGateway.class);
    }
}
