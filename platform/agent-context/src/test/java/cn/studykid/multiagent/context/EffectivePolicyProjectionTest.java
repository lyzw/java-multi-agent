package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import cn.studykid.multiagent.context.ContextPolicy.CarryOver;
import cn.studykid.multiagent.context.ContextPolicy.Strategy;
import cn.studykid.multiagent.context.ContextPolicyRejectedException.Reason;
import cn.studykid.multiagent.ports.context.EffectiveContextPolicy;

/**
 * {@link EffectivePolicyProjection} 的逐字段映射与 fail-closed 行为（M7-a / ADR-013 D38）。
 *
 * <p>⚠️ 这里不引用 {@link ContextPolicy#defaults()} 当期望值：默认值一改，
 * 「字段漏映射」会被期望值一起带走，测试仍绿但运行期策略已静默漂移。
 * 每个用例都用<b>字面量</b>钉死 18 个字段，确保 M7-b 从快照构造 ContextSpec 时
 * 不会把「已裁决策略」悄悄投影成另一套水位。
 */
class EffectivePolicyProjectionTest {

    @Test
    @DisplayName("✅ 18 字段逐字段映射：端口投影 → 算法策略，零默认值回退")
    void toContextPolicyMapsEveryField() {
        EffectiveContextPolicy p = new EffectiveContextPolicy(
                false, 32_000, 0.68, 0.42, 0.12, 9, false,
                0.21, 0.17, 0.23, "TRIM_ONLY", "judge:summary",
                1_500, 9_000, 5, "SERVER", 3_600, "cp_custom_v7");

        ContextPolicy actual = EffectivePolicyProjection.toContextPolicy(p);

        assertThat(actual).isEqualTo(new ContextPolicy(
                false, 32_000, 0.68, 0.42, 0.12, 9, false,
                0.21, 0.17, 0.23, Strategy.TRIM_ONLY, "judge:summary",
                1_500, 9_000, 5, CarryOver.SERVER, 3_600, "cp_custom_v7"));
    }

    @Test
    @DisplayName("✅ 往返映射：ContextPolicy ↔ EffectiveContextPolicy 不丢字段 / 不改枚举名")
    void roundTripKeepsAllFields() {
        ContextPolicy source = customPolicy();

        ContextPolicy back = EffectivePolicyProjection.toContextPolicy(
                EffectivePolicyProjection.toEffectivePolicy(source));

        assertThat(back).isEqualTo(source);
    }

    @Test
    @DisplayName("✅ 反向映射输出端口枚举名字符串（端口层不依赖算法层枚举）")
    void toEffectivePolicyUsesEnumNames() {
        EffectiveContextPolicy p = EffectivePolicyProjection.toEffectivePolicy(customPolicy());

        assertThat(p.strategy()).isEqualTo("TIERED");
        assertThat(p.carryOver()).isEqualTo("CLIENT");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"BOGUS", "off", "tiered "})
    @DisplayName("⛔ strategy 非法/空 → CONTEXT_POLICY_INVALID（绝不退化成默认档）")
    void invalidStrategyFailsClosed(String value) {
        EffectiveContextPolicy p = withStrategy(value);

        assertThatThrownBy(() -> EffectivePolicyProjection.toContextPolicy(p))
                .isInstanceOf(ContextPolicyRejectedException.class)
                .extracting(e -> ((ContextPolicyRejectedException) e).reason())
                .isEqualTo(Reason.CONTEXT_POLICY_INVALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"BOGUS", "server", "CLIENT "})
    @DisplayName("⛔ carryOver 非法/空 → CONTEXT_POLICY_INVALID（绝不退化成默认载体）")
    void invalidCarryOverFailsClosed(String value) {
        EffectiveContextPolicy p = withCarryOver(value);

        assertThatThrownBy(() -> EffectivePolicyProjection.toContextPolicy(p))
                .isInstanceOf(ContextPolicyRejectedException.class)
                .extracting(e -> ((ContextPolicyRejectedException) e).reason())
                .isEqualTo(Reason.CONTEXT_POLICY_INVALID);
    }

    @Test
    @DisplayName("⛔ 双向入参 null 都 fail-closed（NPE，而非静默返回默认策略）")
    void nullInputsFailClosed() {
        assertThatThrownBy(() -> EffectivePolicyProjection.toContextPolicy(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EffectivePolicyProjection.toEffectivePolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static ContextPolicy customPolicy() {
        return new ContextPolicy(
                true, 24_000, 0.72, 0.45, 0.08, 4, true,
                0.28, 0.12, 0.20, Strategy.TIERED, "llm:summary",
                1_100, 7_000, 2, CarryOver.CLIENT, 900, "cp_roundtrip_9");
    }

    private static EffectiveContextPolicy withStrategy(String value) {
        return new EffectiveContextPolicy(
                true, 16_000, 0.70, 0.40, 0.10, 6, true,
                0.30, 0.15, 0.25, value, null, 1_200, 8_000,
                3, "CLIENT", 1_800, "cp_v1");
    }

    private static EffectiveContextPolicy withCarryOver(String value) {
        return new EffectiveContextPolicy(
                true, 16_000, 0.70, 0.40, 0.10, 6, true,
                0.30, 0.15, 0.25, "TIERED", null, 1_200, 8_000,
                3, value, 1_800, "cp_v1");
    }
}
