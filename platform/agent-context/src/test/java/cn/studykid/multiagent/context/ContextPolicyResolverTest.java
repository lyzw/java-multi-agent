package cn.studykid.multiagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.context.ModelCapabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有效值裁决 / 快照固化 / policyVersion 失效语义（ADR-013 / 《方案》§3.4、§5.8）。
 *
 * <p>⚠️ 断言用字面量，绝不引用被测实现自己的常量。
 */
class ContextPolicyResolverTest {

    private static final GlobalContextPolicy ON =
            new GlobalContextPolicy(true, 128_000, 16_000, 0.10);

    private static final ModelCapabilities CAPS =
            new ModelCapabilities("model-1", 32_768, 28_672, null, false, false);

    private static ContextPolicyResolver.Input input(ContextPolicy p, ModelCapabilities caps,
                                                     GlobalContextPolicy g, int maxOut,
                                                     String agentVersion) {
        return new ContextPolicyResolver.Input(p, caps, g, maxOut, agentVersion);
    }

    @Test
    @DisplayName("✅ 总熔断 / Agent 关闭 / OFF 档一律收敛为同一个 off()（[D11]）")
    void disabledPathsReturnOff() {
        ContextPolicy defaults = ContextPolicy.defaults();
        assertThat(ContextPolicyResolver.resolve(
                input(defaults, CAPS, GlobalContextPolicy.defaults(), 4_096, "v1")))
                .isEqualTo(ContextPolicy.off());
        assertThat(ContextPolicyResolver.resolve(
                input(defaults.withEnabled(false), CAPS, ON, 4_096, "v1")))
                .isEqualTo(ContextPolicy.off());
        assertThat(ContextPolicyResolver.resolve(
                input(defaults.withStrategy(ContextPolicy.Strategy.OFF), CAPS, ON, 4_096, "v1")))
                .isEqualTo(ContextPolicy.off());
    }

    @Test
    @DisplayName("✅ 三层 MIN：Agent 上限 / 模型能力 / 全局硬顶取最小")
    void effectiveWindowIsMinOfThreeLayers() {
        ModelCapabilities narrow = new ModelCapabilities("model-1", 32_768, 12_000,
                null, false, false);
        ContextPolicy p = ContextPolicyResolver.resolve(
                input(ContextPolicy.defaults(), narrow, ON, 1_024, "v1"));
        assertThat(p.maxInputTokens()).isEqualTo(12_000);

        GlobalContextPolicy narrowGlobal =
                new GlobalContextPolicy(true, 9_000, 16_000, 0.10);
        assertThat(ContextPolicyResolver.resolve(
                input(ContextPolicy.defaults(), CAPS, narrowGlobal, 1_024, "v1"))
                .maxInputTokens()).isEqualTo(9_000);

        assertThat(ContextPolicyResolver.resolve(
                input(ContextPolicy.defaults().withMaxInputTokens(7_000), CAPS, ON, 1_024, "v1"))
                .maxInputTokens()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("⛔ 输入硬顶 + 输出上限超过模型总窗口 → 配置期拒绝，绝不悄悄夹紧")
    void windowOverflowIsRejected() {
        // window = min(30000, 28672, 128000) = 28672；+ 5000 输出 > 32768 总窗口。
        ContextPolicy wide = ContextPolicy.defaults().withMaxInputTokens(30_000);
        assertThatThrownBy(() -> ContextPolicyResolver.resolve(
                input(wide, CAPS, ON, 5_000, "v1")))
                .isInstanceOf(ContextPolicyRejectedException.class)
                .satisfies(e -> assertThat(((ContextPolicyRejectedException) e).reason())
                        .isEqualTo(ContextPolicyRejectedException.Reason.CONTEXT_WINDOW_EXCEEDED));
    }

    @Test
    @DisplayName("⛔ 比例反转在裁决层二次拒绝（旧快照可能绕过发布期门禁）")
    void invertedRatioIsRejectedEvenFromOldSnapshot() {
        ContextPolicy inverted = new ContextPolicy(
                true, 16_000, 0.40, 0.70, 0.10, 6, true,
                0.30, 0.15, 0.25, ContextPolicy.Strategy.TIERED,
                null, 1_200, 8_000, 3, ContextPolicy.CarryOver.CLIENT, 1_800, "");
        assertThatThrownBy(() -> ContextPolicyResolver.resolve(
                input(inverted, CAPS, ON, 1_024, "v1")))
                .isInstanceOf(ContextPolicyRejectedException.class)
                .satisfies(e -> assertThat(((ContextPolicyRejectedException) e).reason())
                        .isEqualTo(ContextPolicyRejectedException.Reason.CONTEXT_RATIO_INVERTED));
    }

    @Test
    @DisplayName("✅ policyVersion 稳定、格式固定，且四个输入任一变化即失效重建")
    void policyVersionIsDeterministicAndVersionSensitive() {
        ContextPolicy base = ContextPolicy.defaults();
        String v1 = ContextPolicyResolver.resolve(
                input(base, CAPS, ON, 1_024, "v1")).policyVersion();
        String v1Again = ContextPolicyResolver.resolve(
                input(base, CAPS, ON, 1_024, "v1")).policyVersion();

        assertThat(v1).isEqualTo(v1Again).startsWith("cp_").hasSize(19);
        assertThat(v1.substring(3)).matches("[0-9a-f]{16}");

        assertThat(ContextPolicyResolver.resolve(
                input(base.withMaxInputTokens(12_000), CAPS, ON, 1_024, "v1"))
                .policyVersion()).isNotEqualTo(v1);
        assertThat(ContextPolicyResolver.resolve(
                input(base, CAPS, ON, 1_024, "v2")).policyVersion()).isNotEqualTo(v1);
        assertThat(ContextPolicyResolver.resolve(
                input(base, new ModelCapabilities("model-1", 65_536, 28_672,
                        null, false, false), ON, 1_024, "v1")).policyVersion())
                .isNotEqualTo(v1);
        assertThat(ContextPolicyResolver.resolve(
                input(base, CAPS, new GlobalContextPolicy(true, 64_000, 16_000, 0.10),
                        1_024, "v1")).policyVersion()).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("✅ 模型能力未知 → 回落全局默认窗口并上调 reserve，且不假设任何供应商特性")
    void unknownCapabilitiesFallbackAndTightenReserve() {
        GlobalContextPolicy strict =
                new GlobalContextPolicy(true, 128_000, 16_000, 0.18);
        ContextPolicy p = ContextPolicyResolver.resolve(
                input(ContextPolicy.defaults(), ModelCapabilities.unknown("model-x"),
                        strict, 1_024, "v1"));

        assertThat(p.maxInputTokens()).isEqualTo(16_000);
        assertThat(p.reserveRatio()).isEqualTo(0.18);
        assertThat(p.policyVersion()).startsWith("cp_");
    }

    @Test
    @DisplayName("✅ 裁决不修改入参快照：在途 Run 不随当前发布漂移（[D4]）")
    void resolutionNeverMutatesSnapshot() {
        ContextPolicy snapshot = ContextPolicy.defaults();
        ContextPolicyResolver.resolve(input(snapshot, CAPS, ON, 1_024, "v1"));
        assertThat(snapshot.maxInputTokens()).isEqualTo(16_000);
        assertThat(snapshot.reserveRatio()).isEqualTo(0.10);
        assertThat(snapshot.policyVersion()).isEqualTo("cp_default");
    }
}
