package cn.studykid.multiagent.context;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 解析与发布期门禁共用同一份校验实现的行为测试（ADR-013 / G1）。 */
class ContextPolicyParserTest {

    private static Map<String, Object> ctx(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("✅ 未声明 context → 全局默认值，旧 Agent 逐字不变")
    void missingContextFallsBackToDefaults() {
        ContextPolicy p = ContextPolicyParser.parse(null);
        assertThat(p.enabled()).isTrue();
        assertThat(p.maxInputTokens()).isEqualTo(16_000);
        assertThat(p.triggerRatio()).isEqualTo(0.70);
        assertThat(p.targetRatio()).isEqualTo(0.40);
        assertThat(p.strategy()).isEqualTo(ContextPolicy.Strategy.TIERED);
        assertThat(ContextPolicyParser.parse(Map.of())).isEqualTo(p);
    }

    @Test
    @DisplayName("✅ 部分覆盖只改声明的键，其余默认值逐字段带过")
    void partialOverrideKeepsEveryOtherField() {
        ContextPolicy p = ContextPolicyParser.parse(ctx("maxInputTokens", 8_000,
                "recentTurns", 9, "strategy", "TRIM_ONLY"));

        assertThat(p.maxInputTokens()).isEqualTo(8_000);
        assertThat(p.recentTurns()).isEqualTo(9);
        assertThat(p.strategy()).isEqualTo(ContextPolicy.Strategy.TRIM_ONLY);
        assertThat(p.triggerRatio()).isEqualTo(0.70);
        assertThat(p.targetRatio()).isEqualTo(0.40);
        assertThat(p.reserveRatio()).isEqualTo(0.10);
        assertThat(p.ragRatio()).isEqualTo(0.30);
        assertThat(p.memoryRatio()).isEqualTo(0.15);
        assertThat(p.summaryRatio()).isEqualTo(0.25);
        assertThat(p.carryOver()).isEqualTo(ContextPolicy.CarryOver.CLIENT);
        assertThat(p.summaryTimeoutMs()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("✅ 解析结果不自带 policyVersion：必须由 Resolver 在 Run 创建期固化")
    void parserNeverDerivesPolicyVersion() {
        assertThat(ContextPolicyParser.parse(null).policyVersion()).isEmpty();
        assertThat(ContextPolicyParser.parse(ctx("recentTurns", 3)).policyVersion()).isEmpty();
    }

    @Test
    @DisplayName("✅ 显式 enabled=false 保留其余字段，便于灰度对照")
    void disabledKeepsOtherFields() {
        ContextPolicy p = ContextPolicyParser.parse(ctx("enabled", false, "recentTurns", 11));
        assertThat(p.enabled()).isFalse();
        assertThat(p.recentTurns()).isEqualTo(11);
        assertThat(p.triggerRatio()).isEqualTo(0.70);
    }

    @Test
    @DisplayName("⛔ 未知键 / 比例反转 / 比例和越界 / 类型错 / 非法枚举一律发布期拒绝")
    void illegalValuesAreRejected() {
        java.util.List<Map<String, Object>> bad = java.util.List.of(
                ctx("trigerRatio", 0.8),
                ctx("targetRatio", 0.75, "triggerRatio", 0.70),
                ctx("ragRatio", 0.6, "memoryRatio", 0.6, "summaryRatio", 0.6),
                ctx("triggerRatio", "0.70"),
                ctx("maxInputTokens", 12.5),
                ctx("strategy", "AGGRESSIVE"),
                ctx("carryOver", "DISK"),
                ctx("recentTurns", 1),
                ctx("enabled", "false"),
                ctx("summarizerModel", 42)
        );
        for (Map<String, Object> m : bad) {
            assertThatThrownBy(() -> ContextPolicyParser.parse(m))
                    .as("非法配置必须 fail-closed 拒绝：" + m)
                    .isInstanceOf(ContextPolicyRejectedException.class)
                    .satisfies(e -> assertThat(
                                    ((ContextPolicyRejectedException) e).reason())
                            .isEqualTo(ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID));
        }
    }

    @Test
    @DisplayName("✅ 合法全量配置逐字段投影正确")
    void fullOverrideMapsAllFields() {
        ContextPolicy p = ContextPolicyParser.parse(ctx(
                "enabled", true, "maxInputTokens", 24_000,
                "triggerRatio", 0.80, "targetRatio", 0.35,
                "reserveRatio", 0.12, "recentTurns", 8,
                "pinFirstUserTurn", false,
                "ragRatio", 0.20, "memoryRatio", 0.10, "summaryRatio", 0.20,
                "strategy", "TIERED", "summarizerModel", "cheap-model",
                "summaryMaxTokens", 900, "summaryTimeoutMs", 5_000,
                "maxCompactionsPerRun", 2, "carryOver", "CLIENT",
                "checkpointTtlSeconds", 600));

        assertThat(p.maxInputTokens()).isEqualTo(24_000);
        assertThat(p.triggerRatio()).isEqualTo(0.80);
        assertThat(p.targetRatio()).isEqualTo(0.35);
        assertThat(p.reserveRatio()).isEqualTo(0.12);
        assertThat(p.recentTurns()).isEqualTo(8);
        assertThat(p.pinFirstUserTurn()).isFalse();
        assertThat(p.ragRatio()).isEqualTo(0.20);
        assertThat(p.memoryRatio()).isEqualTo(0.10);
        assertThat(p.summaryRatio()).isEqualTo(0.20);
        assertThat(p.summarizerModel()).isEqualTo("cheap-model");
        assertThat(p.summaryMaxTokens()).isEqualTo(900);
        assertThat(p.summaryTimeoutMs()).isEqualTo(5_000);
        assertThat(p.maxCompactionsPerRun()).isEqualTo(2);
        assertThat(p.checkpointTtlSeconds()).isEqualTo(600);
    }
}
