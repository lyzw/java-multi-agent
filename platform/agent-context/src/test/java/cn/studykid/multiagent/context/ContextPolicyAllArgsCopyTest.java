package cn.studykid.multiagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全参构造逐字段带过守卫（铁律 #11 / 《方案》§5.8）。
 *
 * <p>⚠️ 项目曾因「给 record 加字段却留兼容构造器」，在
 * {@code withPublished} 里把 instructions / modelRef / generationParams
 * 静默清成空值。本用例用<b>字面量</b>逐字段断言，
 * 任何新增字段若未随拷贝点带过都会在这里暴露。
 */
class ContextPolicyAllArgsCopyTest {

    @Test
    @DisplayName("✅ defaults() 与《方案》§3.2-B 默认值逐字段一致")
    void defaultsMatchDesign() {
        ContextPolicy p = ContextPolicy.defaults();

        assertThat(p.enabled()).isTrue();
        assertThat(p.maxInputTokens()).isEqualTo(16_000);
        assertThat(p.triggerRatio()).isEqualTo(0.70);
        assertThat(p.targetRatio()).isEqualTo(0.40);
        assertThat(p.reserveRatio()).isEqualTo(0.10);
        assertThat(p.recentTurns()).isEqualTo(6);
        assertThat(p.pinFirstUserTurn()).isTrue();
        assertThat(p.ragRatio()).isEqualTo(0.30);
        assertThat(p.memoryRatio()).isEqualTo(0.15);
        assertThat(p.summaryRatio()).isEqualTo(0.25);
        assertThat(p.strategy()).isEqualTo(ContextPolicy.Strategy.TIERED);
        assertThat(p.summarizerModel()).isNull();
        assertThat(p.summaryMaxTokens()).isEqualTo(1_200);
        assertThat(p.summaryTimeoutMs()).isEqualTo(8_000);
        assertThat(p.maxCompactionsPerRun()).isEqualTo(3);
        assertThat(p.carryOver()).isEqualTo(ContextPolicy.CarryOver.CLIENT);
        assertThat(p.checkpointTtlSeconds()).isEqualTo(1_800);
        assertThat(p.policyVersion()).isEqualTo("cp_default");
    }

    @Test
    @DisplayName("✅ off() 只改 enabled / strategy，其余字段逐字段带过（[D11]）")
    void offKeepsEveryOtherField() {
        ContextPolicy d = ContextPolicy.defaults();
        ContextPolicy off = ContextPolicy.off();

        assertThat(off.enabled()).isFalse();
        assertThat(off.strategy()).isEqualTo(ContextPolicy.Strategy.OFF);
        assertThat(off.maxInputTokens()).isEqualTo(d.maxInputTokens());
        assertThat(off.triggerRatio()).isEqualTo(d.triggerRatio());
        assertThat(off.targetRatio()).isEqualTo(d.targetRatio());
        assertThat(off.reserveRatio()).isEqualTo(d.reserveRatio());
        assertThat(off.recentTurns()).isEqualTo(d.recentTurns());
        assertThat(off.pinFirstUserTurn()).isEqualTo(d.pinFirstUserTurn());
        assertThat(off.ragRatio()).isEqualTo(d.ragRatio());
        assertThat(off.memoryRatio()).isEqualTo(d.memoryRatio());
        assertThat(off.summaryRatio()).isEqualTo(d.summaryRatio());
        assertThat(off.summarizerModel()).isEqualTo(d.summarizerModel());
        assertThat(off.summaryMaxTokens()).isEqualTo(d.summaryMaxTokens());
        assertThat(off.summaryTimeoutMs()).isEqualTo(d.summaryTimeoutMs());
        assertThat(off.maxCompactionsPerRun()).isEqualTo(d.maxCompactionsPerRun());
        assertThat(off.carryOver()).isEqualTo(d.carryOver());
        assertThat(off.checkpointTtlSeconds()).isEqualTo(d.checkpointTtlSeconds());
    }

    @Test
    @DisplayName("✅ 唯一构造器：禁止留兼容构造器（铁律 #11）")
    void noCompatibilityConstructor() {
        assertThat(ContextPolicy.class.getDeclaredConstructors()).hasSize(1);
        assertThat(ContextPolicy.class.getRecordComponents()).hasSize(18);
    }

    @Test
    @DisplayName("✅ 所有 withXxx 投影逐字段带过：改一个字段不得清空其他字段")
    void withMethodsCarryEveryField() {
        // 刻意用「非默认值」构造，清空成默认值才会暴露；若为默认值则漏带也看不出来。
        ContextPolicy base = ContextPolicyParser.parse(new java.util.LinkedHashMap<>(
                java.util.Map.ofEntries(
                        java.util.Map.entry("maxInputTokens", 24_000),
                        java.util.Map.entry("triggerRatio", 0.80),
                        java.util.Map.entry("targetRatio", 0.35),
                        java.util.Map.entry("reserveRatio", 0.12),
                        java.util.Map.entry("recentTurns", 8),
                        java.util.Map.entry("pinFirstUserTurn", false),
                        java.util.Map.entry("ragRatio", 0.20),
                        java.util.Map.entry("memoryRatio", 0.10),
                        java.util.Map.entry("summaryRatio", 0.20),
                        java.util.Map.entry("strategy", "TRIM_ONLY"),
                        java.util.Map.entry("summarizerModel", "cheap-model"),
                        java.util.Map.entry("summaryMaxTokens", 900),
                        java.util.Map.entry("summaryTimeoutMs", 5_000),
                        java.util.Map.entry("maxCompactionsPerRun", 2),
                        java.util.Map.entry("checkpointTtlSeconds", 600))));

        ContextPolicy p = base.withMaxInputTokens(9_000)
                .withReserveRatio(0.20)
                .withStrategy(ContextPolicy.Strategy.TIERED)
                .withPolicyVersion("cp_test");

        assertThat(p.maxInputTokens()).isEqualTo(9_000);
        assertThat(p.reserveRatio()).isEqualTo(0.20);
        assertThat(p.strategy()).isEqualTo(ContextPolicy.Strategy.TIERED);
        assertThat(p.policyVersion()).isEqualTo("cp_test");

        // ⚠️ 其余字段必须与 base 逐字段一致（漏带即在这里变回默认值）。
        assertThat(p.enabled()).isEqualTo(base.enabled());
        assertThat(p.triggerRatio()).isEqualTo(0.80);
        assertThat(p.targetRatio()).isEqualTo(0.35);
        assertThat(p.recentTurns()).isEqualTo(8);
        assertThat(p.pinFirstUserTurn()).isFalse();
        assertThat(p.ragRatio()).isEqualTo(0.20);
        assertThat(p.memoryRatio()).isEqualTo(0.10);
        assertThat(p.summaryRatio()).isEqualTo(0.20);
        assertThat(p.summarizerModel()).isEqualTo("cheap-model");
        assertThat(p.summaryMaxTokens()).isEqualTo(900);
        assertThat(p.summaryTimeoutMs()).isEqualTo(5_000);
        assertThat(p.maxCompactionsPerRun()).isEqualTo(2);
        assertThat(p.carryOver()).isEqualTo(ContextPolicy.CarryOver.CLIENT);
        assertThat(p.checkpointTtlSeconds()).isEqualTo(600);

        ContextPolicy disabled = base.withEnabled(false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.maxInputTokens()).isEqualTo(24_000);
        assertThat(disabled.summaryMaxTokens()).isEqualTo(900);
        assertThat(disabled.recentTurns()).isEqualTo(8);
    }
}
