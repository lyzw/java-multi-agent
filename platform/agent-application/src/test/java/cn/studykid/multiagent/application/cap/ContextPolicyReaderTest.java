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

    @Test
    @DisplayName("✅ 快照派生：未声明 / 空对象 / 非对象配置一律 {}（回落默认，不等于关闭）")
    void snapshotFallsBackToEmptyObject() {
        assertThat(ContextPolicyReader.snapshotJson(null)).isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotJson("")).isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotJson("not-json")).isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotJson("{\"temperature\":0.3}")).isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotJson("{\"context\":{}}")).isEqualTo("{}");

        assertThat(ContextPolicyReader.snapshotFromAgentConfig(null)).isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotFromAgentConfig("{\"kind\":\"tutor\"}"))
                .isEqualTo("{}");
        assertThat(ContextPolicyReader.snapshotFromAgentConfig("[]")).isEqualTo("{}");
    }

    @Test
    @DisplayName("✅ 快照派生：显式 context 归一化为稳定稀疏 JSON（键序无关、只含声明键）")
    void snapshotIsCanonicalAndSparse() {
        String contextJson = "{\"maxInputTokens\":12000,\"enabled\":true,"
                + "\"strategy\":\"TIERED\",\"recentTurns\":7}";
        String generationParams = "{\"temperature\":0.3,\"context\":" + contextJson + "}";
        String first = "{\"instructions\":\"{}\",\"generationParams\":"
                + generationParams + "}";
        String second = """
                {"generationParams":{"context":{"recentTurns":7,"strategy":"TIERED",
                  "enabled":true,"maxInputTokens":12000},"temperature":0.3}}
                """;
        String expected = "{\"enabled\":true,\"maxInputTokens\":12000,"
                + "\"recentTurns\":7,\"strategy\":\"TIERED\"}";
        assertThat(ContextPolicyReader.snapshotFromAgentConfig(first)).isEqualTo(expected);
        assertThat(ContextPolicyReader.snapshotFromAgentConfig(second)).isEqualTo(expected);

        // generationParams 为「承载 JSON 文本的字符串」的历史形态也走同一实现。
        assertThat(ContextPolicyReader.snapshotFromAgentConfig(
                "{\"generationParams\":\"{\\\"context\\\":{\\\"recentTurns\\\":7}}\"}"))
                .isEqualTo("{\"recentTurns\":7}");

        // 快照声明键必须与 read() 解析出的值同源，不能出现两套真相。
        ContextPolicy parsed = ContextPolicyReader.read(generationParams);
        assertThat(parsed.maxInputTokens()).isEqualTo(12_000);
        assertThat(parsed.recentTurns()).isEqualTo(7);
        assertThat(parsed.strategy()).isEqualTo(ContextPolicy.Strategy.TIERED);
    }

    @Test
    @DisplayName("⛔ 快照派生与运行期读取同判据：非对象 / 非法策略一律 fail-closed")
    void snapshotDerivationIsFailClosed() {
        String[] badContexts = {
                "\"TIERED\"",
                "{\"trigerRatio\":0.8}",
                "{\"strategy\":\"AGGRESSIVE\"}",
                "{\"recentTurns\":1}"
        };
        for (String context : badContexts) {
            String generationParams = "{\"context\":" + context + "}";
            assertThatThrownBy(() -> ContextPolicyReader.snapshotJson(generationParams))
                    .as("快照派生必须拒：" + generationParams)
                    .isInstanceOf(ContextPolicyRejectedException.class);

            String config = "{\"generationParams\":" + generationParams + "}";
            assertThatThrownBy(() -> ContextPolicyReader.snapshotFromAgentConfig(config))
                    .as("旧直发快照派生必须拒：" + config)
                    .isInstanceOf(ContextPolicyRejectedException.class);
        }
    }
}
