package cn.studykid.multiagent.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort.ProviderCredentialView;

@DisplayName("ModelCredentialProvider · DB 优先 / env 回落")
class ModelCredentialProviderTest {

    /** 内存桩：模拟「带外加密存储」的密钥端口。 */
    static final class StubCredentialPort implements ProviderCredentialPort {
        final Map<String, String> store = new HashMap<>();

        @Override
        public Optional<String> resolveApiKey(String provider) {
            return Optional.ofNullable(store.get(provider));
        }

        @Override
        public void save(String provider, String apiKey) {
            store.put(provider, apiKey);
        }

        @Override
        public void delete(String provider) {
            store.remove(provider);
        }

        @Override
        public java.util.List<ProviderCredentialView> listMasked() {
            return store.entrySet().stream()
                    .map(e -> new ProviderCredentialView(e.getKey(),
                            ProviderCredentialView.mask(e.getValue()), null))
                    .toList();
        }

        @Override
        public Optional<ProviderCredentialView> masked(String provider) {
            return Optional.ofNullable(store.get(provider))
                    .map(k -> new ProviderCredentialView(provider,
                            ProviderCredentialView.mask(k), null));
        }
    }

    private ModelCredentialProvider providerWith(StubCredentialPort port, String envKey) {
        StandardEnvironment env = new StandardEnvironment();
        if (envKey != null) {
            Map<String, Object> props = new HashMap<>();
            props.put("agent.model.minimax.api-key", envKey);
            env.getPropertySources().addFirst(new MapPropertySource("test-props", props));
        }
        ModelCredentialProvider p = new ModelCredentialProvider(env);
        p.credentialPort = port;
        return p;
    }

    @Test
    @DisplayName("✅ DB 有密钥 → 优先返回 DB 值（不回落 env）")
    void dbFirst() {
        StubCredentialPort port = new StubCredentialPort();
        port.save("openai", "db-openai-key");
        ModelCredentialProvider p = providerWith(port, "env-openai-key-should-ignore");

        assertThat(p.apiKeyFor("openai")).isEqualTo("db-openai-key");
    }

    @Test
    @DisplayName("✅ DB 无密钥、env 有 → 回落环境变量")
    void envFallback() {
        StubCredentialPort port = new StubCredentialPort(); // 空
        ModelCredentialProvider p = providerWith(port, "env-minimax-key");

        assertThat(p.apiKeyFor("minimax")).isEqualTo("env-minimax-key");
    }

    @Test
    @DisplayName("✅ DB 与 env 皆无 → 返回 null（调用方据此回落 Fake 网关）")
    void neither() {
        StubCredentialPort port = new StubCredentialPort();
        ModelCredentialProvider p = providerWith(port, null);

        assertThat(p.apiKeyFor("unknown")).isNull();
    }

    @Test
    @DisplayName("✅ 未装配 credentialPort（无 DB 密钥）→ 纯 env 回落，不 NPE")
    void noPort() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        props.put("agent.model.deepseek.api-key", "env-deepseek-key");
        env.getPropertySources().addFirst(new MapPropertySource("test-props", props));
        ModelCredentialProvider p = new ModelCredentialProvider(env); // credentialPort=null
        assertThat(p.apiKeyFor("deepseek")).isEqualTo("env-deepseek-key");
        assertThat(p.apiKeyFor("missing")).isNull();
    }
}
