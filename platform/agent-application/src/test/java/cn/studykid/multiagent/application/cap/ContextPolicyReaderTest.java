package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import cn.studykid.multiagent.context.ContextPolicy;
import cn.studykid.multiagent.context.ContextPolicyRejectedException;
import cn.studykid.multiagent.context.ContextPolicyValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨表面闭环守卫：发布期门禁与运行期读取必须同判、同值（ADR-013 / G1）。
 *
 * <p>⚠️ 若有人给运行期另写一套 Jackson 归一化，本类的
 * 「合法 ⇔ 可读」「非法 ⇔ 双拒」两组断言会立刻变红。
 */
class ContextPolicyReaderTest {

    @Test
    @DisplayName("✅ 未声明 / 空串 / 空对象 / 不可解析 JSON 一律回落默认（旧 Agent 行为不变）")
    void missingOrUnreadableFallsBackToDefaults() {
        for (String json : new String[]{null, "", "   ", "{}", "not-json", "[]"}) {
            ContextPolicy p = ContextPolicyReader.read(json);
            assertThat(p.maxInputTokens()).as(json).isEqualTo(16_000);
            assertThat(p.triggerRatio()).isEqualTo(0.70);
            assertThat(p.targetRatio()).isEqualTo(0.40);
        }
    }

    @Test
    @DisplayName("✅ 合法 context 的完整 JSON 逐字段投影")
    void readsFullContextObject() {
        String json = """
                {"temperature": 0.7,
                 "context": {"maxInputTokens": 8000, "triggerRatio": 0.75,
                             "targetRatio": 0.30, "recentTurns": 9,
                             "strategy": "TRIM_ONLY", "carryOver": "CLIENT",
                             "summarizerModel": "cheap"}}
                """;
        ContextPolicy p = ContextPolicyReader.read(json);
        assertThat(p.maxInputTokens()).isEqualTo(8_000);
        assertThat(p.triggerRatio()).isEqualTo(0.75);
        assertThat(p.targetRatio()).isEqualTo(0.30);
        assertThat(p.recentTurns()).isEqualTo(9);
        assertThat(p.strategy()).isEqualTo(ContextPolicy.Strategy.TRIM_ONLY);
        assertThat(p.summarizerModel()).isEqualTo("cheap");
        // 未声明的键必须逐字带过默认值，不得被清空
        assertThat(p.ragRatio()).isEqualTo(0.30);
        assertThat(p.checkpointTtlSeconds()).isEqualTo(1_800);
    }

    @Test
    @DisplayName("⛔ context 非对象：发布期拒、运行期也必须拒，绝不静默回落默认")
    void nonObjectContextIsRejectedOnBothSides() {
        String json = "{\"context\": \"TIERED\"}";
        JsonNode node = ContextPolicyReader.contextNode(json);
        assertThat(node).isNotNull();
        assertThat(node.isObject()).isFalse();
        assertThatThrownBy(() -> ContextPolicyReader.read(json))
                .isInstanceOf(ContextPolicyRejectedException.class);
    }

    @Test
    @DisplayName("✅ 合法策略：发布期校验通过 ⇔ 运行期解析成功且值一致")
    void legalPolicyPassesBothSides() {
        String json = "{\"context\":{\"maxInputTokens\":12000,\"recentTurns\":7}}";
        JsonNode node = ContextPolicyReader.contextNode(json);
        List<ContextPolicyValidator.Violation> violations =
                ContextPolicyValidator.validate(ContextPolicyReader.toPlainMap(node));
        assertThat(violations).isEmpty();
        assertThat(ContextPolicyReader.read(json).maxInputTokens()).isEqualTo(12_000);
    }

    @Test
    @DisplayName("⛔ 非法策略：发布期拒 ⇔ 运行期也拒（禁止「发布被拒、旧快照照常跑」）")
    void illegalPolicyIsRejectedOnBothSides() {
        String[] bad = {
                "{\"context\":{\"trigerRatio\":0.8}}",
                "{\"context\":{\"targetRatio\":0.8,\"triggerRatio\":0.7}}",
                "{\"context\":{\"strategy\":\"AGGRESSIVE\"}}",
                "{\"context\":{\"recentTurns\":1}}",
                "{\"context\":{\"enabled\":\"false\"}}"
        };
        for (String json : bad) {
            JsonNode node = ContextPolicyReader.contextNode(json);
            assertThat(ContextPolicyValidator.validate(ContextPolicyReader.toPlainMap(node)))
                    .as("发布期必须拒：" + json).isNotEmpty();
            assertThatThrownBy(() -> ContextPolicyReader.read(json))
                    .as("运行期也必须拒：" + json)
                    .isInstanceOf(ContextPolicyRejectedException.class);
        }
    }
}
