package cn.studykid.multiagent.application.cap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 参数策略引擎单测（P1-T5 · §4.3 / §4.4）。
 */
class ParamPolicyEngineTest {

    private static ParamPolicyEngine.Evaluation evaluate(String toolJson, String skillJson,
                                                         Map<String, Object> args) {
        return ParamPolicyEngine.evaluate(
                ParamPolicyEngine.parse(toolJson),
                ParamPolicyEngine.parse(skillJson),
                args);
    }

    @Test
    @DisplayName("✅ in_allowlist：值在白名单内则通过")
    void allowlistAcceptsListedValue() {
        var result = evaluate("[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                        + "\"value\":[\"school.edu\",\"parent.org\"]}]", "[]",
                Map.of("toDomain", "school.edu"));

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("⛔ in_allowlist：值不在白名单内则拒绝")
    void allowlistRejectsUnlistedValue() {
        var result = evaluate("[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                        + "\"value\":[\"school.edu\"]}]", "[]",
                Map.of("toDomain", "evil.example"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).contains("toDomain");
    }

    @Test
    @DisplayName("✅ lte：数值不大于阈值则通过")
    void lteAcceptsWithinThreshold() {
        var result = evaluate("[{\"param\":\"count\",\"op\":\"lte\",\"value\":10}]", "[]",
                Map.of("count", 10));

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("⛔ lte：数值超过阈值则拒绝")
    void lteRejectsAboveThreshold() {
        var result = evaluate("[{\"param\":\"count\",\"op\":\"lte\",\"value\":10}]", "[]",
                Map.of("count", 11));

        assertThat(result.passed()).isFalse();
    }

    @Test
    @DisplayName("⛔ 类型不符则拒绝（数值不能与字符串比较）")
    void typeMismatchIsRejected() {
        var lteMismatch = evaluate("[{\"param\":\"count\",\"op\":\"lte\",\"value\":10}]", "[]",
                Map.of("count", "10"));
        assertThat(lteMismatch.passed())
                .as("⛔ 字符串参数通过了数值比较：类型不符必须拒绝（§4.3）")
                .isFalse();

        var allowlistMismatch = evaluate("[{\"param\":\"count\",\"op\":\"in_allowlist\","
                + "\"value\":[10]}]", "[]", Map.of("count", "10"));
        assertThat(allowlistMismatch.passed())
                .as("⛔ 字符串 \"10\" 通过了数值白名单：数值与字符串永不等价")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ 策略要求的参数缺失则拒绝")
    void missingRequiredParamIsRejected() {
        var result = evaluate("[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                + "\"value\":[\"school.edu\"]}]", "[]", Map.of());

        assertThat(result.passed())
                .as("⛔ 缺失参数被放行：策略形同虚设")
                .isFalse();
    }

    @Test
    @DisplayName("✅ null / [] / {} 三种「本层无附加策略」形态等价")
    void nullEmptyAndBraceMeanNoAdditionalPolicy() {
        Map<String, Object> args = Map.of("toDomain", "school.edu");
        String toolJson = "[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                + "\"value\":[\"school.edu\"]}]";

        assertThat(evaluate(toolJson, null, args).passed()).isTrue();
        assertThat(evaluate(toolJson, "[]", args).passed()).isTrue();
        assertThat(evaluate(toolJson, "{}", args).passed()).isTrue();
    }

    @Test
    @DisplayName("✅ Skill 层与 Tool 层取交集（Skill 只能收紧）")
    void skillLayerIntersectsToolLayer() {
        String toolJson = "[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                + "\"value\":[\"school.edu\",\"parent.org\"]}]";
        String skillJson = "[{\"param\":\"toDomain\",\"op\":\"in_allowlist\","
                + "\"value\":[\"school.edu\"]}]";

        assertThat(evaluate(toolJson, skillJson, Map.of("toDomain", "parent.org")).passed())
                .as("⛔ Skill 层白名单把 Tool 层的更宽集合放行了")
                .isFalse();
        assertThat(evaluate(toolJson, skillJson, Map.of("toDomain", "school.edu")).passed())
                .isTrue();
    }

    @Test
    @DisplayName("⛔ Skill 层不能放宽 Tool 层的 lte 阈值")
    void skillLayerCannotLoosenLte() {
        String toolJson = "[{\"param\":\"count\",\"op\":\"lte\",\"value\":10}]";
        String skillJson = "[{\"param\":\"count\",\"op\":\"lte\",\"value\":100}]";

        assertThat(evaluate(toolJson, skillJson, Map.of("count", 50)).passed())
                .as("⛔ Skill 层抬高了阈值：Skill 参数策略只能进一步收紧（§4.3）")
                .isFalse();
    }

    @Test
    @DisplayName("✅ lte 阈值取更严者")
    void stricterLteThresholdWins() {
        String toolJson = "[{\"param\":\"count\",\"op\":\"lte\",\"value\":100}]";
        String skillJson = "[{\"param\":\"count\",\"op\":\"lte\",\"value\":10}]";

        assertThat(evaluate(toolJson, skillJson, Map.of("count", 50)).passed()).isFalse();
        assertThat(evaluate(toolJson, skillJson, Map.of("count", 5)).passed()).isTrue();
    }

    @Test
    @DisplayName("✅ 同一参数同时受 in_allowlist 与 lte 约束时，二者均须满足")
    void allowlistAndLteMustBothHold() {
        String toolJson = "[{\"param\":\"n\",\"op\":\"in_allowlist\",\"value\":[5,50]},"
                + "{\"param\":\"n\",\"op\":\"lte\",\"value\":10}]";

        assertThat(evaluate(toolJson, "[]", Map.of("n", 5)).passed()).isTrue();
        assertThat(evaluate(toolJson, "[]", Map.of("n", 50)).passed())
                .as("⛔ 只满足了白名单却忽略了 lte 阈值")
                .isFalse();
        assertThat(evaluate(toolJson, "[]", Map.of("n", 3)).passed())
                .as("⛔ 只满足了阈值却不在白名单内")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ 未知 op 必须被拒（§4.3：未知 op → 配置期拒）")
    void unknownOpIsRejected() {
        assertThatThrownBy(() -> ParamPolicyEngine.parse(
                "[{\"param\":\"p\",\"op\":\"regex_match\",\"value\":\".*\"}]"))
                .isInstanceOf(ParamPolicyConfigException.class);
    }

    @Test
    @DisplayName("⛔ 策略结构非法必须被拒（不得当作无约束放行）")
    void malformedPolicyIsRejected() {
        assertThatThrownBy(() -> ParamPolicyEngine.parse("[{\"op\":\"lte\",\"value\":1}]"))
                .isInstanceOf(ParamPolicyConfigException.class);
        assertThatThrownBy(() -> ParamPolicyEngine.parse("[{\"param\":\"p\",\"op\":\"lte\"}]"))
                .isInstanceOf(ParamPolicyConfigException.class);
        assertThatThrownBy(() -> ParamPolicyEngine.parse("not-json"))
                .isInstanceOf(ParamPolicyConfigException.class);
        assertThatThrownBy(() -> ParamPolicyEngine.parse(
                "[{\"param\":\"p\",\"op\":\"in_allowlist\",\"value\":\"x\"}]"))
                .as("⛔ in_allowlist 的 value 不是数组却未报错")
                .isInstanceOf(ParamPolicyConfigException.class);
    }

    @Test
    @DisplayName("✅ 无策略时一切参数放行（该层未配置附加策略）")
    void noPolicyAllowsAnything() {
        assertThat(evaluate("[]", "[]", Map.of("x", 1)).passed()).isTrue();
        assertThat(evaluate("[]", null, Map.of()).passed()).isTrue();
    }

    @Test
    @DisplayName("✅ 策略解析结果可稳定回写为 JSON（供指纹计算使用）")
    void policiesRoundTripToJson() {
        List<ParamPolicyEngine.ParamPolicy> policies = ParamPolicyEngine.parse(
                "[{\"param\":\"p\",\"op\":\"lte\",\"value\":3}]");

        assertThat(policies).hasSize(1);
        assertThat(policies.get(0).param()).isEqualTo("p");
        assertThat(policies.get(0).op()).isEqualTo(ParamPolicyEngine.Op.LTE);
        assertThat(ParamPolicyEngine.toJson(policies)).contains("\"op\":\"lte\"");
    }
}
