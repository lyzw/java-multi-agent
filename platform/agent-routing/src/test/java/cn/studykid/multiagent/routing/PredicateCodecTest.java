package cn.studykid.multiagent.routing;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 谓词文法解析测试（10 号设计 §4.4 + §9 S2 出口条件「单测覆盖文法每条产生式」）。
 *
 * <p><b>覆盖口径</b>：文法的<b>每一条产生式各一条正向用例</b>，
 * 再加「解析期必须拒掉」的负向用例。正向用例的意义不是「能解析」，
 * 而是<b>解析成了哪个 AST 节点</b> —— 产生式与 AST 的对应一旦写歪，
 * 求值器与守卫会各自按自己的读法理解同一份策略文本。
 */
class PredicateCodecTest {

    private static final String WHERE = "test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 产生式覆盖 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 每条产生式都解析成对应的 AST 节点")
    void everyProductionParses() {
        assertThat(PredicateCodec.parse("{\"const\":true}", WHERE))
                .isEqualTo(new Predicate.Const(true));
        assertThat(PredicateCodec.parse("{\"const\":false}", WHERE))
                .isEqualTo(new Predicate.Const(false));

        assertThat(PredicateCodec.parse(
                "{\"and\":[{\"null\":[\"in.a\"]},{\"const\":true}]}", WHERE))
                .isEqualTo(new Predicate.And(List.of(
                        new Predicate.Null(new Predicate.InputPath("a")),
                        new Predicate.Const(true))));

        assertThat(PredicateCodec.parse(
                "{\"or\":[{\"eq\":[\"in.a\",\"lit\"]},{\"const\":false}]}", WHERE))
                .isEqualTo(new Predicate.Or(List.of(
                        new Predicate.Eq(new Predicate.InputPath("a"),
                                new Predicate.LiteralValue("lit")),
                        new Predicate.Const(false))));

        assertThat(PredicateCodec.parse("{\"not\":{\"const\":true}}", WHERE))
                .isEqualTo(new Predicate.Not(new Predicate.Const(true)));

        assertThat(PredicateCodec.parse("{\"eq\":[\"in.a\",\"ctx.channel\"]}", WHERE))
                .isEqualTo(new Predicate.Eq(new Predicate.InputPath("a"),
                        new Predicate.CtxValue(CtxName.CHANNEL)));

        assertThat(PredicateCodec.parse("{\"in\":[\"in.a\",[1,2,\"x\"]]}", WHERE))
                .isEqualTo(new Predicate.In(new Predicate.InputPath("a"),
                        new Predicate.SetLiteral(List.of(1, 2, "x"))));

        assertThat(PredicateCodec.parse("{\"null\":[\"in.a.b\"]}", WHERE))
                .isEqualTo(new Predicate.Null(new Predicate.InputPath("a.b")));

        assertThat(PredicateCodec.parse("{\"startsWith\":[\"in.a\",\"SO-\"]}", WHERE))
                .isEqualTo(new Predicate.StartsWith(new Predicate.InputPath("a"),
                        new Predicate.LiteralValue("SO-")));

        assertThat(PredicateCodec.parse("{\"endsWith\":[\"in.a\",\".pdf\"]}", WHERE))
                .isEqualTo(new Predicate.EndsWith(new Predicate.InputPath("a"),
                        new Predicate.LiteralValue(".pdf")));

        assertThat(PredicateCodec.parse("{\"contains\":[\"in.a\",\"in.b\"]}", WHERE))
                .isEqualTo(new Predicate.Contains(new Predicate.InputPath("a"),
                        new Predicate.InputPath("b")));

        assertThat(PredicateCodec.parse("{\"anyOf\":[\"in.tags\",[\"vip\"]]}", WHERE))
                .isEqualTo(new Predicate.AnyOf(new Predicate.InputPath("tags"),
                        new Predicate.ArrayLiteral(List.of("vip"))));

        assertThat(PredicateCodec.parse("{\"re\":[\"in.a\",\"SO-\\\\d{6}\"]}", WHERE))
                .isEqualTo(new Predicate.Matches(new Predicate.InputPath("a"),
                        Predicate.Regex.of("SO-\\d{6}")));
    }

    @Test
    @DisplayName("✅ {@code {\"lit\":...}} 转义：长成引用形状的字符串仍是字面量")
    void literalEscapeKeepsReferenceLookingStrings() {
        assertThat(PredicateCodec.parse("{\"eq\":[\"in.a\",{\"lit\":\"in.topic\"}]}", WHERE))
                .isEqualTo(new Predicate.Eq(new Predicate.InputPath("a"),
                        new Predicate.LiteralValue("in.topic")));
    }

    @Test
    @DisplayName("✅ 标量字面量保持原类型：{@code 7} 与 {@code \"7\"} 不是同一个常量")
    void scalarLiteralsKeepTheirType() {
        assertThat(rightValueOf("{\"eq\":[\"in.a\",true]}")).isEqualTo(Boolean.TRUE);
        assertThat(rightValueOf("{\"eq\":[\"in.a\",7]}")).isEqualTo(7);
        assertThat(rightValueOf("{\"eq\":[\"in.a\",\"7\"]}")).isEqualTo("7");
    }

    @Test
    @DisplayName("✅ 规则序号由服务端按声明序回填，文本里写的 ordinal 不作数")
    void ordinalsAreServerAssigned() {
        List<RuleSpec> rules = PredicateCodec.parseRules(MAPPER.readTree("""
                [{"ruleId":"r1","target":"a","when":{"const":true},"ordinal":99},
                 {"ruleId":"r2","target":"b","when":{"const":false},"ordinal":7}]
                """), WHERE);

        assertThat(rules).extracting(RuleSpec::ordinal).containsExactly(1, 2);
        assertThat(rules).extracting(RuleSpec::ruleId).containsExactly("r1", "r2");
    }

    // ── 解析期必须拒 ────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 未知算子拒（宽松解析会把「算子名写错」变成「恒不成立」）")
    void unknownOperatorIsRejected() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"gt\":[\"in.a\",1]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("未知谓词算子");
    }

    @Test
    @DisplayName("⛔ 谓词必须是单键对象")
    void predicateMustBeSingleKeyObject() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"const\":true,\"eq\":[\"in.a\",1]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("只有一个键");
        assertThatThrownBy(() -> PredicateCodec.parse("\"in.a\"", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("单键对象");
    }

    @Test
    @DisplayName("⛔ 空的 and/or 拒（恒真还是恒假？两种读法都说得通就不能进生产）")
    void emptyCombinatorIsRejected() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"and\":[]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("非空数组");
        assertThatThrownBy(() -> PredicateCodec.parse("{\"or\":[]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("非空数组");
    }

    @Test
    @DisplayName("⛔ 元数不对拒")
    void arityIsEnforced() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"eq\":[\"in.a\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("二元数组");
        assertThatThrownBy(() -> PredicateCodec.parse("{\"null\":[\"in.a\",\"in.b\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("一元数组");
    }

    @Test
    @DisplayName("⛔ 取值位置不接受 JSON null（判缺失必须用 null 算子）")
    void jsonNullIsNotALiteral() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"eq\":[\"in.a\",null]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("不接受 JSON null");
    }

    @Test
    @DisplayName("⛔ in 的右值必须是标量数组：引用与嵌套结构都拒")
    void inRightSideMustBeLiteralArray() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"in\":[\"in.a\",\"in.b\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("集合字面量必须是数组");
        assertThatThrownBy(() -> PredicateCodec.parse("{\"in\":[\"in.a\",[{\"a\":1}]]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("只允许标量项");
    }

    @Test
    @DisplayName("⛔ 词汇表之外的 ctx 字段拒（没有「未知即透传」这条路）")
    void unknownCtxIsRejected() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"eq\":[\"ctx.tenant\",\"t-1\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("未知的上下文字段");
    }

    @Test
    @DisplayName("⛔ 非法 in 路径词法拒")
    void malformedInputPathIsRejected() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"null\":[\"in.1abc\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("非法的 in 路径");
        assertThatThrownBy(() -> PredicateCodec.parse("{\"null\":[\"in.a-b\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("非法的 in 路径");
    }

    @Test
    @DisplayName("⛔ 硬上限：深度 / 节点数 / 字面量项数 / 正则长度超限即拒而非降级")
    void hardLimitsRejectInsteadOfDegrade() {
        assertThatThrownBy(() -> PredicateCodec.parse(nestedAnd(PredicateLimits.MAX_DEPTH + 1), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("嵌套深度超过上限");

        String wide = "{\"and\":[" + repeated(PredicateLimits.MAX_NODES + 1,
                "{\"null\":[\"in.x\"]}") + "]}";
        assertThatThrownBy(() -> PredicateCodec.parse(wide, WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("节点数超过上限");

        String bigSet = "{\"in\":[\"in.a\",[" + repeated(PredicateLimits.MAX_LITERAL_ITEMS + 1,
                "1") + "]]}";
        assertThatThrownBy(() -> PredicateCodec.parse(bigSet, WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("集合字面量超过");

        assertThatThrownBy(() -> PredicateCodec.parse("{\"re\":[\"in.a\",\""
                + "a".repeat(PredicateLimits.MAX_REGEX_LENGTH + 1) + "\"]}", WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("正则长度超过");
    }

    @Test
    @DisplayName("⛔ 规则条数上限（运行期最坏成本 = 条数 × 节点数，超限就没有可预算的上界）")
    void ruleCountIsCapped() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i <= PredicateLimits.MAX_RULES; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"ruleId\":\"r").append(i)
                    .append("\",\"target\":\"a\",\"when\":{\"const\":true}}");
        }
        json.append("]");

        assertThatThrownBy(() -> PredicateCodec.parseRules(MAPPER.readTree(json.toString()), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("规则条数超过上限");
    }

    @Test
    @DisplayName("⛔ 规则的未知键与缺字段拒")
    void ruleShapeIsEnforced() {
        assertThatThrownBy(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"ruleId\":\"r\",\"target\":\"a\",\"when\":{\"const\":true},\"onErr\":false}"), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("未定义的键");

        assertThatThrownBy(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"ruleId\":\"r\",\"target\":\"a\"}"), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("when");

        assertThatThrownBy(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"target\":\"a\",\"when\":{\"const\":true}}"), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("ruleId");
    }

    @Test
    @DisplayName("⛔ onError 只有两值：拼错不静默取默认，不写才取默认")
    void onErrorVocabularyIsClosed() {
        assertThatThrownBy(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"ruleId\":\"r\",\"target\":\"a\",\"when\":{\"const\":true},"
                        + "\"onError\":\"IGNORE\"}"), WHERE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ERROR≠MATCH");

        assertThatCode(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"ruleId\":\"r\",\"target\":\"a\",\"when\":{\"const\":true}}"), WHERE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⛔ 条件缺失的规则拒：无条件规则等于「所有请求都派给」")
    void unconditionalRuleIsRejected() {
        assertThatThrownBy(() -> PredicateCodec.parseRule(MAPPER.readTree(
                "{\"ruleId\":\"r\",\"target\":\"a\",\"when\":null}"), WHERE))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("when");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static Object rightValueOf(String json) {
        Predicate.Eq eq = (Predicate.Eq) PredicateCodec.parse(json, WHERE);
        return ((Predicate.LiteralValue) eq.right()).value();
    }

    /** {@code and} 嵌套 depth 层，最内层是 {@code {const:true}}。 */
    private static String nestedAnd(int depth) {
        String body = "{\"const\":true}";
        for (int i = 0; i < depth; i++) {
            body = "{\"and\":[" + body + "]}";
        }
        return body;
    }

    private static String repeated(int count, String term) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? "," : "").append(term);
        }
        return sb.toString();
    }
}
