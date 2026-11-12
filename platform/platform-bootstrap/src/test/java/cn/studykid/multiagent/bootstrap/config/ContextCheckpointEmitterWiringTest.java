package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import cn.studykid.multiagent.context.StructuredState;
import cn.studykid.multiagent.context.StructuredStateCodec;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter.Emitted;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter.Input;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Accepted;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * S3-M8-b-2 组合根装配测试：唯一 {@code ContextCheckpointEmitter} Bean 必须委托真算法、
 * 真配置，且与接收侧 {@code ContextStateVerifier} <b>共用同一把独立 HMAC 密钥 / 同一
 * 长度上限 / 同一时钟</b>——否则「发出的 state 下一轮被自己丢弃」会成为无报错的功能空转。
 *
 * <p>本类钉死的核心闭环（G1 A.5 / D49）：emit 产出的不透明 state 能被同一组合根的
 * verifier 验通；换 key / 超长 / 空密钥一律不发射或验不过（fail-soft / fail-closed）。
 */
class ContextCheckpointEmitterWiringTest {

    private static final String KEY = "m8b2-independent-hmac-key";
    private static final String OTHER_KEY = "another-independent-key";
    private static final String POLICY = "cp_m8b2";
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final long NOW = 1_700_000_000L;
    private static final int MAX_CHARS = 16_384;
    private static final int TTL = 1800;

    @Test
    @DisplayName("✅ 组合根产出唯一真实 emitter Bean（非空实现 / 非 mock）")
    void configurationCreatesSingleRealEmitterBean() {
        ContextCheckpointEmitter emitter = configuration().contextCheckpointEmitter(
                properties(KEY, MAX_CHARS, TTL), clock(NOW));

        assertThat(emitter).isInstanceOf(ContextCheckpointEmitterAdapter.class);
    }

    @Test
    @DisplayName("✅ Spring Binder 真装配：唯一 Bean + yml 配置生效，且不覆盖接收侧 verifier Bean")
    void springContextBindsSingleRealEmitterAlongsideVerifier() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ContextCheckpointEmitterConfiguration.class,
                        ContextStateVerifierConfiguration.class, TestClockConfig.class)
                .withPropertyValues(
                        "agent.context.state-hmac-key=" + KEY,
                        "agent.context.max-state-chars=" + MAX_CHARS,
                        "agent.context.checkpoint-ttl-seconds=" + TTL)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(ContextCheckpointEmitter.class)).hasSize(1);
                    assertThat(context.getBeansOfType(ContextStateVerifier.class)).hasSize(1);
                    assertThat(context.getBean(ContextCheckpointEmitter.class))
                            .isInstanceOf(ContextCheckpointEmitterAdapter.class);
                    assertThat(context.getBean(AgentContextProperties.class).getMaxStateChars())
                            .isEqualTo(MAX_CHARS);
                });
    }

    @Test
    @DisplayName("✅ 核心闭环非空转：emit 产出的 state 能被同一组合根的 verifier 验通（text→state→text）")
    void emittedStateIsVerifiedBySameRootVerifier() {
        Emitted emitted = emitter(KEY, MAX_CHARS, TTL, NOW).emit(validInput());

        assertThat(emitted).as("⛔ 有效历史 + 独立密钥下必须产出 checkpoint（否则契约空转）").isNotNull();
        assertThat(emitted.state()).isNotEmpty();
        assertThat(emitted.policyVersion()).isEqualTo(POLICY);
        assertThat(emitted.digest()).startsWith("sha256:");
        assertThat(emitted.upToTurn()).isEqualTo(2);
        assertThat(emitted.tier()).isEqualTo(2);
        assertThat(emitted.fallback()).isFalse();

        // 下一轮接收侧用同一把 key / 同一 history 长度验通 —— 签发/校验闭环。
        Result result = verifier(KEY, MAX_CHARS, NOW).verify(
                new ContextStateVerifier.Input(emitted.state(), TENANT, 3, POLICY));
        assertThat(result).isInstanceOf(Accepted.class);
        Accepted ok = (Accepted) result;
        assertThat(ok.policyVersion()).isEqualTo(POLICY);
        assertThat(ok.upToTurn()).isEqualTo(2);
        assertThat(ok.digest()).isEqualTo(emitted.digest());
        assertThat(ok.stateJson()).contains("你必须保留单位。");
    }

    @Test
    @DisplayName("✅ 换 key 立即 HMAC_MISMATCH：封装不是自说自话，跨密钥不可伪造")
    void emittedStateFailsUnderDifferentKey() {
        Emitted emitted = emitter(KEY, MAX_CHARS, TTL, NOW).emit(validInput());
        assertThat(emitted).isNotNull();

        assertDropped(verifier(OTHER_KEY, MAX_CHARS, NOW).verify(
                new ContextStateVerifier.Input(emitted.state(), TENANT, 3, POLICY)),
                DropReason.HMAC_MISMATCH);
    }

    @Test
    @DisplayName("✅ fail-soft：空独立密钥 / 无新增轮次 / 状态超长一律返回 null（不发射）")
    void noEmitWhenKeyBlankOrNoNewOrTooLarge() {
        // 空 key = 影子期预期行为：无法签发下一轮会接受的 state，静默不发。
        assertThat(emitter("", MAX_CHARS, TTL, NOW).emit(validInput())).isNull();
        // 无新增：上一轮状态已覆盖全部历史 → 算法层返回 null。
        assertThat(emitter(KEY, MAX_CHARS, TTL, NOW).emit(inputWithNoNewRounds())).isNull();
        // 状态超长：发出去也会被六项校验丢弃 → 不浪费字节。
        assertThat(emitter(KEY, 16, TTL, NOW).emit(validInput())).isNull();
    }

    @Test
    @DisplayName("✅ 非法历史输入 fail-closed：tier 0 / 空策略指纹在端口边界即拒")
    void invalidInputIsRejectedAtPortBoundary() {
        assertThatThrownBy(() -> new Input(TENANT, "chat:run-1", "model-a",
                history(), 2, POLICY, null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier");
        assertThatThrownBy(() -> new Input(TENANT, "chat:run-1", "model-a",
                history(), 2, "  ", null, 2, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyVersion");
    }

    @Test
    @DisplayName("✅ 配置形状非法启动期即拒：max-state-chars / TTL 非正")
    void invalidConfigurationFailsFast() {
        assertThatThrownBy(() -> configuration().contextCheckpointEmitter(
                properties(KEY, 0, TTL), clock(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration().contextCheckpointEmitter(
                properties(KEY, MAX_CHARS, 0), clock(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static ContextCheckpointEmitterConfiguration configuration() {
        return new ContextCheckpointEmitterConfiguration();
    }

    private static AgentContextProperties properties(String key, int maxChars, int ttlSeconds) {
        AgentContextProperties p = new AgentContextProperties();
        p.setStateHmacKey(key);
        p.setMaxStateChars(maxChars);
        p.setCheckpointTtlSeconds(ttlSeconds);
        return p;
    }

    private static ContextCheckpointEmitter emitter(String key, int maxChars, int ttl, long now) {
        return new ContextCheckpointEmitterAdapter(key, maxChars, ttl, clock(now));
    }

    private static ContextStateVerifier verifier(String key, int maxChars, long now) {
        return new ContextStateVerifierAdapter(key, maxChars, clock(now));
    }

    private static Clock clock(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static Input validInput() {
        return new Input(TENANT, "chat:run-1", "model-a", history(), 2, POLICY, null, 2, false);
    }

    private static Input inputWithNoNewRounds() {
        // 上一轮已验状态：约束已存在、upToSeq=2 覆盖本轮全部历史 → 无新增（与工厂测试同口径）。
        String carry = StructuredStateCodec.toJson(new StructuredState(
                "", List.of("你必须保留单位。"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), 0, 2, POLICY));
        return new Input(TENANT, "chat:run-1", "model-a", history(), 2, POLICY, carry, 2, false);
    }

    private static List<ModelMessage> history() {
        return List.of(
                ModelMessage.user("你必须保留单位。"),
                ModelMessage.assistant("好的，我会保留单位。"));
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
