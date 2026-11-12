package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.ContextStateEnvelope;
import cn.studykid.multiagent.context.ContextStateEnvelope.SealInput;
import cn.studykid.multiagent.context.StructuredState;
import cn.studykid.multiagent.context.StructuredStateCodec;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Accepted;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Input;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;

/**
 * S3-M8-a 组合根装配测试：唯一 {@code ContextStateVerifier} Bean 必须接真算法、真配置，
 * ⛔ 不能是 mock / 空实现 / 第二套策略指纹（D49 / G1 A.5）。
 */
class ContextStateVerifierWiringTest {

    private static final String KEY = "m8a-independent-hmac-key";
    private static final String OTHER_KEY = "another-independent-key";
    private static final String POLICY = "cp_v1";
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final long NOW = 1_700_000_000L;
    private static final int MAX_CHARS = 16_384;

    @Test
    @DisplayName("✅ 组合根产出唯一真实 verifier Bean，且只使用独立 state HMAC 配置")
    void configurationCreatesSingleRealVerifierBean() {
        ContextStateVerifier verifier = configuration().contextStateVerifier(properties(KEY, MAX_CHARS, 1800),
                clock(NOW));

        assertThat(verifier).isInstanceOf(ContextStateVerifierAdapter.class);
    }

    @Test
    @DisplayName("✅ Spring Binder 真装配：唯一 Bean + yml 配置真生效 + 真封包可验通（非空转）")
    void springContextBindsSingleNonNoOpVerifier() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ContextStateVerifierConfiguration.class, TestClockConfig.class)
                .withPropertyValues(
                        "agent.context.state-hmac-key=" + KEY,
                        "agent.context.max-state-chars=" + MAX_CHARS,
                        "agent.context.checkpoint-ttl-seconds=1800")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(ContextStateVerifier.class)).hasSize(1);
                    ContextStateVerifier bean = context.getBean(ContextStateVerifier.class);
                    assertThat(bean).isInstanceOf(ContextStateVerifierAdapter.class);
                    assertThat(context.getBean(AgentContextProperties.class).getMaxStateChars())
                            .isEqualTo(MAX_CHARS);
                    assertThat(bean.verify(new Input(validEnvelope(), TENANT, 8, POLICY)))
                            .isInstanceOf(Accepted.class);
                });
    }

    @Test
    @DisplayName("✅ 非空转：用同一独立 key 真封包可验通；换 key 立即 HMAC_MISMATCH")
    void adapterDelegatesToRealEnvelopeAlgorithm() {
        String json = stateJson();
        String digest = StructuredStateCodec.digest(state());
        String envelope = ContextStateEnvelope.seal(
                new SealInput(json, POLICY, TENANT, 6, digest, 60),
                key(KEY), NOW, MAX_CHARS);

        Result accepted = verifier(KEY, MAX_CHARS, NOW)
                .verify(new Input(envelope, TENANT, 8, POLICY));
        assertThat(accepted).isInstanceOf(Accepted.class);
        Accepted ok = (Accepted) accepted;
        assertThat(ok.stateJson()).isEqualTo(json);
        assertThat(ok.policyVersion()).isEqualTo(POLICY);
        assertThat(ok.upToTurn()).isEqualTo(6);
        assertThat(ok.digest()).isEqualTo(digest);

        assertDropped(verifier(OTHER_KEY, MAX_CHARS, NOW)
                .verify(new Input(envelope, TENANT, 8, POLICY)),
                DropReason.HMAC_MISMATCH);
    }

    @Test
    @DisplayName("✅ fail-closed：空独立密钥 / 未裁决策略指纹一律静默丢弃")
    void blankKeyOrUnresolvedPolicyIsFailClosed() {
        String envelope = validEnvelope();

        assertDropped(verifier("", MAX_CHARS, NOW)
                .verify(new Input(envelope, TENANT, 8, POLICY)),
                DropReason.KEY_UNAVAILABLE);
        assertDropped(verifier(KEY, MAX_CHARS, NOW)
                .verify(new Input(envelope, TENANT, 8, null)),
                DropReason.POLICY_VERSION_MISMATCH);
        assertDropped(verifier(KEY, MAX_CHARS, NOW)
                .verify(new Input(envelope, TENANT, 8, "  ")),
                DropReason.POLICY_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("✅ max-state-chars 来自 yml 绑定且真生效；exp 使用注入时钟而非系统时间")
    void configuredLengthAndInjectedClockAreEnforced() {
        String envelope = validEnvelope();

        assertDropped(verifier(KEY, envelope.length() - 1, NOW)
                .verify(new Input(envelope, TENANT, 8, POLICY)),
                DropReason.TOO_LARGE);

        String shortLived = ContextStateEnvelope.seal(
                new SealInput(stateJson(), POLICY, TENANT, 1,
                        StructuredStateCodec.digest(state()), 5),
                key(KEY), NOW, MAX_CHARS);
        assertDropped(verifier(KEY, MAX_CHARS, NOW + 5)
                .verify(new Input(shortLived, TENANT, 8, POLICY)),
                DropReason.EXPIRED);
        assertThat(verifier(KEY, MAX_CHARS, NOW + 4)
                .verify(new Input(shortLived, TENANT, 8, POLICY)))
                .isInstanceOf(Accepted.class);
    }

    @Test
    @DisplayName("✅ 无 state 是 Absent，不是失败；配置形状非法启动期即拒")
    void absentIsNotFailureAndInvalidShapeFailsFast() {
        assertThat(verifier(KEY, MAX_CHARS, NOW)
                .verify(new Input(null, TENANT, 8, POLICY)))
                .isInstanceOf(ContextStateVerifier.Absent.class);
        assertThat(verifier(KEY, MAX_CHARS, NOW)
                .verify(new Input("   ", TENANT, 8, POLICY)))
                .isInstanceOf(ContextStateVerifier.Absent.class);

        assertThatThrownBy(() -> configuration().contextStateVerifier(
                properties(KEY, 0, 1800), clock(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-state-chars");
        assertThatThrownBy(() -> configuration().contextStateVerifier(
                properties(KEY, MAX_CHARS, 0), clock(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpoint-ttl-seconds");
    }

    private static ContextStateVerifierConfiguration configuration() {
        return new ContextStateVerifierConfiguration();
    }

    private static AgentContextProperties properties(String key, int maxChars, int ttlSeconds) {
        AgentContextProperties p = new AgentContextProperties();
        p.setStateHmacKey(key);
        p.setMaxStateChars(maxChars);
        p.setCheckpointTtlSeconds(ttlSeconds);
        return p;
    }

    private static ContextStateVerifier verifier(String key, int maxChars, long now) {
        return new ContextStateVerifierAdapter(key, maxChars, clock(now));
    }

    private static Clock clock(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String validEnvelope() {
        return ContextStateEnvelope.seal(
                new SealInput(stateJson(), POLICY, TENANT, 1,
                        StructuredStateCodec.digest(state()), 60),
                key(KEY), NOW, MAX_CHARS);
    }

    private static String stateJson() {
        return StructuredStateCodec.toJson(state());
    }

    private static StructuredState state() {
        return new StructuredState("目标", List.of("约束"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), 1, 9, POLICY);
    }

    private static void assertDropped(Result result, DropReason expected) {
        assertThat(result).as("⛔ 期望静默丢弃 %s", expected).isInstanceOf(Discarded.class);
        assertThat(((Discarded) result).reason()).isEqualTo(expected);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentContextProperties.class)
    static class TestClockConfig {
        @Bean
        Clock fixedClock() {
            return clock(NOW);
        }
    }
}
