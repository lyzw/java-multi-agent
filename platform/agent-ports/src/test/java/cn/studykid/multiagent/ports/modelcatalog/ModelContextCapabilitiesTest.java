package cn.studykid.multiagent.ports.modelcatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模型级上下文能力的默认值与配置期校验（ADR-013 / §3.2-A / R10）。 */
class ModelContextCapabilitiesTest {

    @Test
    @DisplayName("✅ 默认值逐字对齐 DDL：32768 / 28672 / null / false / false")
    void defaultsMatchDdl() {
        ModelContextCapabilities c = ModelContextCapabilities.defaults();
        assertThat(c.contextWindowTokens()).isEqualTo(32_768);
        assertThat(c.maxInputTokens()).isEqualTo(28_672);
        assertThat(c.tokenizerId()).isNull();
        assertThat(c.supportsPromptCaching()).isFalse();
        assertThat(c.supportsTokenizeApi()).isFalse();
    }

    @Test
    @DisplayName("✅ 唯一构造器：禁止留兼容构造器（铁律 #10）")
    void noCompatibilityConstructor() {
        assertThat(ModelContextCapabilities.class.getDeclaredConstructors()).hasSize(1);
        assertThat(ModelContextCapabilities.class.getRecordComponents()).hasSize(5);
    }

    @Test
    @DisplayName("⛔ 窗口/输入越界一律配置期拒绝")
    void rangesAreEnforced() {
        assertThatThrownBy(() -> new ModelContextCapabilities(4_095, 1_024, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextCapabilities(1_000_001, 1_024, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextCapabilities(32_768, 1_023, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextCapabilities(32_768, 30_000, null, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出预留");
    }

    @Test
    @DisplayName("✅ 恰好等于「总窗口 − 4096」是合法边界")
    void upperBoundaryIsInclusive() {
        ModelContextCapabilities c =
                new ModelContextCapabilities(32_768, 28_672, "o200k_base", true, true);
        assertThat(c.maxInputTokens()).isEqualTo(28_672);
        assertThat(c.tokenizerId()).isEqualTo("o200k_base");
        assertThat(c.supportsPromptCaching()).isTrue();
    }

    @Test
    @DisplayName("⛔ tokenizerId 过长直接拒绝，避免把任意文本塞进目录列")
    void tokenizerLengthIsBounded() {
        assertThatThrownBy(() -> new ModelContextCapabilities(
                32_768, 28_672, "x".repeat(129), false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
