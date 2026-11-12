package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;

/**
 * {@code agent.context.*} 真实 Binder 绑定测试（ADR-013 / M2 / RV-12）。
 *
 * <p>⚠️ <b>为什么必须用真实 Binder，而不是直接 {@code new AgentContextProperties()}</b>：
 * Java 字段默认值（如 {@code unknownCapabilityReserveRatio = 0.10}）会让
 * 「删掉 yml 键」这类事故<b>照样全绿</b>——字段仍是 0.10，R10 看似生效，
 * 实际运维改 env 永远改不动它。本类做两件事把这一静默形态钉死：
 * <ol>
 *   <li><b>默认值口径</b>：从真实 {@code application.yml} 绑定，锁死影子期
 *       {@code enabled=false} / reserve=0.10 / alpha=0.20 / minSamples=30；</li>
 *   <li><b>环境覆盖穿透</b>：注入一组测试 env 值再绑一次，断言每个关键字段
 *       <b>真的等于覆盖值</b>。若 yml 键缺失、挂错父键、或字段名与 kebab-case
 *       对不上，覆盖值无法穿透 → 断言红（而不是悄悄落回 Java 默认）。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>不依赖数据库与 broker</b>：只读 yml + Spring Binder，CI 无库可跑。
 */
class AgentContextPropertiesBindingTest {

    @Test
    @DisplayName("✅ 真实 yml 绑定出影子期默认值：enabled=false / reserve=0.10 / alpha=0.20 / n=30")
    void defaultsBindFromRealYml() {
        AgentContextProperties p = bind(yamlEnvironment(Map.of()));

        assertThat(p.isEnabled())
                .as("⛔ 影子期总开关默认必须为 false（G1：与「无压缩功能」逐位一致）")
                .isFalse();
        assertThat(p.getHardCeilingTokens()).isEqualTo(128_000);
        assertThat(p.getDefaultMaxInputTokens()).isEqualTo(16_000);
        assertThat(p.getDefaultTriggerRatio()).isEqualTo(0.70);
        assertThat(p.getDefaultTargetRatio()).isEqualTo(0.40);
        assertThat(p.getUnknownCapabilityReserveRatio())
                .as("⛔ R10 默认 reserve 必须是 0.10")
                .isEqualTo(0.10);
        assertThat(p.getCalibration().isEnabled())
                .as("⛔ 影子期校准默认开启（只读，不影响请求）")
                .isTrue();
        assertThat(p.getCalibration().getAlpha()).isEqualTo(0.20);
        assertThat(p.getCalibration().getMinSamples()).isEqualTo(30);
        assertThat(p.getCalibration().getBiasClampMin()).isEqualTo(0.80);
        assertThat(p.getCalibration().getBiasClampMax()).isEqualTo(2.00);
        // S3-M10：RAG 配额四项默认值与 yml 同源；关闭态不消费，但配置契约必须真实绑定。
        assertThat(p.getRagQuota().getTokenBudget()).isEqualTo(4_096);
        assertThat(p.getRagQuota().getMaxHits()).isEqualTo(8);
        assertThat(p.getRagQuota().getMaxHitTokens()).isEqualTo(1_200);
        assertThat(p.getRagQuota().getMinKeptTokens()).isEqualTo(64);
    }

    @Test
    @DisplayName("⚠️⚠️ 每个关键 env 必须能穿透到字段：缺键/挂错父键 → 覆盖值落不下来，测试红")
    void envOverridesFlowThroughEveryCriticalKey() {
        AgentContextProperties p = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_ENABLED", "true",
                "AGENT_CONTEXT_UNKNOWN_RESERVE", "0.15",
                "AGENT_CONTEXT_CALIBRATION_ENABLED", "false",
                "AGENT_CONTEXT_CALIBRATION_ALPHA", "0.33",
                "AGENT_CONTEXT_CALIBRATION_MIN_SAMPLES", "12",
                "AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET", "777",
                "AGENT_CONTEXT_RAG_QUOTA_MAX_HITS", "5",
                "AGENT_CONTEXT_RAG_QUOTA_MAX_HIT_TOKENS", "320",
                "AGENT_CONTEXT_RAG_QUOTA_MIN_KEPT_TOKENS", "17")));

        assertThat(p.isEnabled())
                .as("⛔ AGENT_CONTEXT_ENABLED 未穿透：enabled 键缺失或挂错父键")
                .isTrue();
        assertThat(p.getUnknownCapabilityReserveRatio())
                .as("⛔ AGENT_CONTEXT_UNKNOWN_RESERVE 未穿透到 unknownCapabilityReserveRatio："
                        + "R10 会静默用 Java 默认 0.10，运维改 env 永远无效")
                .isEqualTo(0.15);
        assertThat(p.getCalibration().isEnabled()).isFalse();
        assertThat(p.getCalibration().getAlpha())
                .as("⛔ AGENT_CONTEXT_CALIBRATION_ALPHA 未穿透：校准平滑系数静默落回 0.20")
                .isEqualTo(0.33);
        assertThat(p.getCalibration().getMinSamples()).isEqualTo(12);
        assertThat(p.getRagQuota().getTokenBudget())
                .as("⛔ RAG 配额 budget 未穿透：env 改了却仍用 Java 默认，开启态预算静默失真")
                .isEqualTo(777);
        assertThat(p.getRagQuota().getMaxHits()).isEqualTo(5);
        assertThat(p.getRagQuota().getMaxHitTokens()).isEqualTo(320);
        assertThat(p.getRagQuota().getMinKeptTokens()).isEqualTo(17);
    }

    @Test
    @DisplayName("⚠️⚠️ GlobalContextPolicy 第 4 参数必须来自配置，绝不硬编码 0（R10 行为锚点）")
    void globalPolicyCarriesConfiguredReserve() {
        AgentContextProperties withOverride = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_ENABLED", "true",
                "AGENT_CONTEXT_UNKNOWN_RESERVE", "0.17")));

        GlobalContextPolicy policy =
                new AgentContextConfiguration().globalContextPolicy(withOverride);

        assertThat(policy.unknownCapabilityReserveRatio())
                .as("⛔ reserve 被硬编码成 0：能力未知时不上调安全余量，"
                        + "估算偏小 → 静默超窗，且没有任何报错")
                .isEqualTo(0.17);
        assertThat(policy.enabled()).isTrue();   // 跟随 enabled 覆盖
        assertThat(policy.hardCeilingTokens()).isEqualTo(128_000);
    }

    @Test
    @DisplayName("✅ 启动期门禁拒绝非法全局配置（比例反转 / reserve 越界 / 硬顶非正）")
    void invalidGlobalConfigFailsFast() {
        AgentContextConfiguration config = new AgentContextConfiguration();

        AgentContextProperties reversed = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_TRIGGER_RATIO", "0.40",
                "AGENT_CONTEXT_TARGET_RATIO", "0.70")));
        assertThatThrownBy(() -> config.globalContextPolicy(reversed))
                .as("⛔ 比例反转被静默接受：压完立刻再触发，抖动风暴")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-ratio");

        AgentContextProperties zeroReserve = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_UNKNOWN_RESERVE", "0")));
        assertThatThrownBy(() -> config.globalContextPolicy(zeroReserve))
                .as("⛔ reserve=0 被静默接受：R10 安全余量形同虚设")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-capability-reserve-ratio");

        AgentContextProperties hugeReserve = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_UNKNOWN_RESERVE", "0.9")));
        assertThatThrownBy(() -> config.globalContextPolicy(hugeReserve))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-capability-reserve-ratio");

        AgentContextProperties badCeiling = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_HARD_CEILING_TOKENS", "0")));
        assertThatThrownBy(() -> config.globalContextPolicy(badCeiling))
                .as("⛔ 硬顶非正被静默接受")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hard-ceiling-tokens");
    }

    @Test
    @DisplayName("✅ 合法 env 覆盖下全局门禁放行（守卫不能只会拒，得能放行）")
    void validOverridePassesGate() {
        AgentContextProperties p = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_ENABLED", "true",
                "AGENT_CONTEXT_UNKNOWN_RESERVE", "0.20")));
        assertThat(new AgentContextConfiguration().globalContextPolicy(p))
                .as("合法覆盖必须能构造出策略，否则门禁过严会把 S2 全挡在门外")
                .isNotNull();
    }

    @Test
    @DisplayName("⚠️⚠️ RAG 配额四项 env 必须穿透到组合根 Options（缺键即静默用默认值）")
    void ragQuotaOverridesReachCompositionRootOptions() throws Exception {
        AgentContextProperties p = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET", "777",
                "AGENT_CONTEXT_RAG_QUOTA_MAX_HITS", "5",
                "AGENT_CONTEXT_RAG_QUOTA_MAX_HIT_TOKENS", "320",
                "AGENT_CONTEXT_RAG_QUOTA_MIN_KEPT_TOKENS", "17")));

        RagHitQuotaPort port = new RagHitQuotaConfiguration().ragHitQuotaPort(
                p, GlobalContextPolicy.defaults(), new HeuristicTokenCounter());
        java.lang.reflect.Field f = RagHitQuotaAdapter.class.getDeclaredField("options");
        f.setAccessible(true);
        cn.studykid.multiagent.context.RagHitQuota.Options options =
                (cn.studykid.multiagent.context.RagHitQuota.Options) f.get(port);

        assertThat(options.tokenBudget()).isEqualTo(777);
        assertThat(options.maxHits()).isEqualTo(5);
        assertThat(options.maxHitTokens()).isEqualTo(320);
        assertThat(options.minKeptTokens()).isEqualTo(17);
    }

    @Test
    @DisplayName("✅ RAG 配额非法配置启动期 fail-fast（非正 / min>max / 单块>总预算 / 超硬顶）")
    void invalidRagQuotaFailsFast() {
        RagHitQuotaConfiguration config = new RagHitQuotaConfiguration();
        GlobalContextPolicy global = GlobalContextPolicy.defaults();

        AgentContextProperties zero = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET", "0")));
        assertThatThrownBy(() -> config.ragHitQuotaPort(zero, global, new HeuristicTokenCounter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rag-quota.token-budget");

        AgentContextProperties minTooBig = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_RAG_QUOTA_MIN_KEPT_TOKENS", "4097")));
        assertThatThrownBy(() -> config.ragHitQuotaPort(minTooBig, global, new HeuristicTokenCounter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rag-quota.min-kept-tokens");

        AgentContextProperties hitTooBig = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_RAG_QUOTA_MAX_HIT_TOKENS", "8192")));
        assertThatThrownBy(() -> config.ragHitQuotaPort(hitTooBig, global, new HeuristicTokenCounter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rag-quota.max-hit-tokens");

        AgentContextProperties overCeiling = bind(yamlEnvironment(Map.of(
                "AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET", "128001")));
        assertThatThrownBy(() -> config.ragHitQuotaPort(overCeiling, global, new HeuristicTokenCounter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hard-ceiling-tokens");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static AgentContextProperties bind(Environment env) {
        return Binder.get(env)
                .bind("agent.context", Bindable.of(AgentContextProperties.class))
                .orElseThrow(() -> new IllegalStateException("agent.context 未绑定成功"));
    }

    /**
     * 把真实 application.yml 装入 {@link StandardEnvironment}，并在最前面叠一层
     * 测试用 env 覆盖（模拟运维在部署环境设置 {@code AGENT_CONTEXT_*} 变量）。
     */
    private static Environment yamlEnvironment(Map<String, Object> envOverrides) {
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : yamlPropertySources()) {
            env.getPropertySources().addFirst(source);
        }
        if (!envOverrides.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("test-env", envOverrides));
        }
        return env;
    }

    private static List<PropertySource<?>> yamlPropertySources() {
        try {
            return new YamlPropertySourceLoader().load("context-app", new FileSystemResource(
                    platformRoot().resolve("platform-bootstrap/src/main/resources/application.yml")));
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录");
    }
}
