package cn.studykid.multiagent.application.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 输出契约的 {@code additionalProperties} 布尔判定测试（10 号文档 §8 · G-5）。
 *
 * <p><b>为什么单独钉这一处</b>：旧实现写的是
 * {@code "false".equals(schema.path("additionalProperties").asText(null))} ——
 * 字符串比较会把 {@code "FALSE"}、{@code 0}、{@code {}} <b>一律读成「没写 false」</b>，
 * 也就是<b>静默放弃这条约束</b>。契约作者以为限死了越界字段，实际限了个寂寞：
 * 这正是本项目反复消除的「看起来生效、实际不生效」。
 *
 * <p>⚠️ 报错方向也必须是「契约不合法」而不是「输出不符合契约」：
 * 后者会把配置缺陷伪装成子 Agent 的产出问题，把排查的人支去查子 Agent。
 *
 * <p>§3.4 让路由输入契约复用同一个校验器，所以这条口径一旦定错，
 * 会同时传染到「委派输入契约」—— 先在这里钉住。
 */
class ChildResultContractAdditionalPropertiesTest {

    /** 规范写法：JSON 布尔 {@code false}。 */
    private static final String BOOL_FALSE = """
            {"type":"object","additionalProperties":false,
             "properties":{"answer":{"type":"string"}},"required":["answer"]}""";

    /** 存量兼容写法：字符串 {@code "false"}（cap_result_contract 里就是这么存的）。 */
    private static final String STRING_FALSE = """
            {"type":"object","additionalProperties":"false",
             "properties":{"answer":{"type":"string"}},"required":["answer"]}""";

    @Test
    @DisplayName("✅ JSON 布尔 false → 拒收未声明字段")
    void jsonBooleanFalseIsEnforced() {
        assertThat(ChildResultContractValidator.validate(BOOL_FALSE, "{\"answer\":\"a\",\"x\":1}")
                .valid())
                .as("Schema 写的是布尔 false，约束必须生效")
                .isFalse();
        assertThat(ChildResultContractValidator.validate(BOOL_FALSE, "{\"answer\":\"a\"}")
                .valid()).isTrue();
    }

    @Test
    @DisplayName("✅ 字符串 \"false\" → 同样生效（存量契约写法不因此失效）")
    void stringFalseIsEnforced() {
        assertThat(ChildResultContractValidator.validate(STRING_FALSE, "{\"answer\":\"a\",\"x\":1}")
                .valid())
                .as("兼容存量字符串写法：它同样表示「拒绝未声明字段」")
                .isFalse();
    }

    @Test
    @DisplayName("🔒 歧义写法 \"FALSE\" → 报「契约不合法」，不得当成没写")
    void capitalFalseStringIsSchemaError() {
        String contract = """
                {"type":"object","additionalProperties":"FALSE",
                 "properties":{"answer":{"type":"string"}},"required":["answer"]}""";

        var result = ChildResultContractValidator.validate(contract, "{\"answer\":\"a\"}");

        assertThat(result.valid())
                .as("旧实现会把 \"FALSE\" 读成「不是 false」→ 静默放行越界字段")
                .isFalse();
        assertThat(result.reason())
                .as("报错方向要说清是**契约**写错，不是子 Agent 的输出写错")
                .contains("输出契约不合法")
                .contains("additionalProperties");
    }

    @Test
    @DisplayName("🔒 歧义写法 0（数字）→ 同样报「契约不合法」")
    void numericZeroIsSchemaError() {
        String contract = """
                {"type":"object","additionalProperties":0,
                 "properties":{"answer":{"type":"string"}},"required":["answer"]}""";

        var result = ChildResultContractValidator.validate(contract, "{\"answer\":\"a\"}");

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("输出契约不合法");
    }

    @Test
    @DisplayName("✅ 缺省与 true → 不约束（多传字段是允许的）")
    void absentOrTrueDoesNotConstrain() {
        String absent = """
                {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}""";
        String boolTrue = """
                {"type":"object","additionalProperties":true,
                 "properties":{"answer":{"type":"string"}},"required":["answer"]}""";
        String output = "{\"answer\":\"a\",\"x\":1}";

        assertThat(ChildResultContractValidator.validate(absent, output).valid()).isTrue();
        assertThat(ChildResultContractValidator.validate(boolTrue, output).valid()).isTrue();
    }

    @Test
    @DisplayName("⚠️ 嵌套层的歧义写法也要被抓出来（items 里的子 schema）")
    void nestedAmbiguousValueIsSchemaError() {
        String contract = """
                {"type":"object","properties":{"rows":{"type":"array",
                 "items":{"type":"object","additionalProperties":"0",
                 "properties":{"a":{"type":"string"}}}}}}""";

        var result = ChildResultContractValidator.validate(contract, "{\"rows\":[{\"a\":\"x\"}]}");

        assertThat(result.valid())
                .as("additionalProperties 的口径不得因为「藏在数组里」就失效")
                .isFalse();
        assertThat(result.reason()).contains("输出契约不合法");
    }
}
