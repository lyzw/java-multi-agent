package cn.studykid.multiagent.routing;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三值求值测试（10 号设计 §2 之③「失败语义必须冻结」的行为证明）。
 *
 * <p>⚠️ <b>本类最重要的三条断言</b>：
 * <ol>
 *   <li>缺失字段参与 {@code eq}/{@code in}/字符串算子时是 {@link Truth#ERROR}，<b>不是 FALSE</b>；</li>
 *   <li>跨类型比较是 {@code ERROR}，<b>不是 FALSE</b>；</li>
 *   <li>短路顺序按声明序，因而<b>同一 (策略, 输入) 两次求值得到同一个 reason</b>。</li>
 * </ol>
 * 这三条一旦写成 FALSE，「字段名写错的规则」就从「可归因的配置缺陷」
 * 退化成「业务上就是不匹配」，而后者在线上是没有信号的。
 */
class PredicateEvaluatorTest {

    private static final RoutingInput EMPTY = new RoutingInput(Map.of(), Map.of());

    // ── 布尔组合 ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ const/and/or/not 的布尔代数正确")
    void booleanAlgebra() {
        assertThat(eval("{\"const\":true}")).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"const\":false}")).isEqualTo(Truth.FALSE);
        assertThat(eval("{\"not\":{\"const\":false}}")).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"and\":[{\"const\":true},{\"const\":true}]}")).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"and\":[{\"const\":true},{\"const\":false}]}")).isEqualTo(Truth.FALSE);
        assertThat(eval("{\"or\":[{\"const\":false},{\"const\":true}]}")).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"or\":[{\"const\":false},{\"const\":false}]}")).isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️⚠️ ERROR 不污染已经决定的结果，但也不被吞掉")
    void errorParticipatesWithoutMaskingDecision() {
        // 合取里已有 FALSE ⇒ 结果与 ERROR 无关，可短路成 FALSE（这是<b>判定</b>而不是「出错被忽略」）。
        assertThat(eval("{\"and\":[{\"eq\":[\"in.missing\",\"x\"]},{\"const\":false}]}"))
                .isEqualTo(Truth.FALSE);
        // 合取里既无 FALSE 也无决定性 TRUE，但有 ERROR ⇒ 结果必须是 ERROR。
        assertThat(eval("{\"and\":[{\"const\":true},{\"eq\":[\"in.missing\",\"x\"]}]}"))
                .isEqualTo(Truth.ERROR);
        // 析取里已有 TRUE ⇒ FALSE 与 ERROR 都不影响结论。
        assertThat(eval("{\"or\":[{\"const\":true},{\"eq\":[\"in.missing\",\"x\"]}]}"))
                .isEqualTo(Truth.TRUE);
        // 析取里全是 FALSE + ERROR ⇒ ERROR（不能说「都不成立」，因为有一个根本没判成）。
        assertThat(eval("{\"or\":[{\"const\":false},{\"eq\":[\"in.missing\",\"x\"]}]}"))
                .isEqualTo(Truth.ERROR);
    }

    @Test
    @DisplayName("✅ 取反把 ERROR 原样透传（「不知道」的相反还是「不知道」）")
    void notPropagatesError() {
        assertThat(eval("{\"not\":{\"eq\":[\"in.missing\",\"x\"]}}")).isEqualTo(Truth.ERROR);
    }

    @Test
    @DisplayName("⚠️ 短路按声明序 ⇒ reason 可重放（第一个 ERROR 取声明序最前者）")
    void shortCircuitIsOrderDeterministic() {
        Predicate predicate = PredicateCodec.parse(
                "{\"and\":[{\"eq\":[\"in.a\",\"x\"]},{\"eq\":[\"in.b\",\"y\"]}]}", "test");
        RoutingInput input = new RoutingInput(Map.of(), Map.of());

        Truth first = PredicateEvaluator.evaluate(predicate, input).truth();
        String reason = PredicateEvaluator.evaluate(predicate, input).reason();

        assertThat(first).isEqualTo(Truth.ERROR);
        assertThat(reason).as("重放必须报同一个原因，否则两次跑出的决策快照无法比对")
                .contains("in.a");
        assertThat(reason).doesNotContain("in.b");
    }

    // ── eq ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 同类型比较：相等 TRUE、不等 FALSE")
    void eqComparesSameTypes() {
        assertThat(eval("{\"eq\":[\"in.topic\",\"order_status\"]}",
                Map.of("topic", "order_status"))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"eq\":[\"in.topic\",\"refund\"]}",
                Map.of("topic", "order_status"))).isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️⚠️ 跨类型比较判 ERROR，不判 FALSE")
    void crossTypeComparisonIsErrorNotFalse() {
        assertThat(eval("{\"eq\":[\"in.count\",1]}", Map.of("count", "1")))
                .as("字符串 \"1\" 与数字 1 比较：判 FALSE 就把「类型写错」读成了「不匹配」")
                .isEqualTo(Truth.ERROR);
    }

    @Test
    @DisplayName("✅ 数值按大小相等比较，不按 Java 装箱类型")
    void numbersCompareByValue() {
        assertThat(eval("{\"eq\":[\"in.count\",1.0]}", Map.of("count", 1))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"eq\":[\"in.count\",1]}", Map.of("count", 1L))).isEqualTo(Truth.TRUE);
    }

    @Test
    @DisplayName("⚠️⚠️ 缺失字段参与比较判 ERROR（字段名写错必须可见）")
    void missingFieldIsError() {
        assertThat(eval("{\"eq\":[\"in.typo\",\"x\"]}", Map.of("topic", "order_status")))
                .isEqualTo(Truth.ERROR);
    }

    // ── in ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ in：命中 TRUE、不命中 FALSE")
    void inMembership() {
        assertThat(eval("{\"in\":[\"in.topic\",[\"refund\",\"order_status\"]]}",
                Map.of("topic", "order_status"))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"in\":[\"in.topic\",[\"refund\"]]}",
                Map.of("topic", "policy"))).isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️ in：集合里没有与输入同类型的项 ⇒ ERROR（而不是「不在集合里」）")
    void inWithIncompatibleSetIsError() {
        assertThat(eval("{\"in\":[\"in.topic\",[1,2]]}", Map.of("topic", "order_status")))
                .isEqualTo(Truth.ERROR);
    }

    // ── null ───────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ null 是全文法里唯一「缺失也算数」的算子")
    void nullOperatorTreatsAbsenceAsMatch() {
        assertThat(eval("{\"null\":[\"in.absent\"]}", Map.of("topic", "x")))
                .as("字段没传 ⇒ 缺失")
                .isEqualTo(Truth.TRUE);
        assertThat(eval("{\"null\":[\"in.topic\"]}", Map.of("topic", "x")))
                .as("传了值 ⇒ 不缺失")
                .isEqualTo(Truth.FALSE);
        assertThat(PredicateEvaluator.evaluate(
                PredicateCodec.parse("{\"null\":[\"in.topic\"]}", "test"),
                new RoutingInput(Collections.singletonMap("topic", null), Map.of())).truth())
                .as("传了 JSON null 与没传字段同判：路由语义里二者都表示没有值可用")
                .isEqualTo(Truth.TRUE);
    }

    // ── 字符串算子 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ startsWith/endsWith/contains 对字符串成立")
    void stringOperators() {
        assertThat(eval("{\"startsWith\":[\"in.orderId\",\"SO-\"]}",
                Map.of("orderId", "SO-000001"))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"endsWith\":[\"in.orderId\",\"001\"]}",
                Map.of("orderId", "SO-000001"))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"contains\":[\"in.orderId\",\"999\"]}",
                Map.of("orderId", "SO-000001"))).isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️ 字符串算子作用在非字符串输入上判 ERROR（不是「不匹配」）")
    void stringOperatorsOnNonStringAreError() {
        assertThat(eval("{\"startsWith\":[\"in.count\",\"1\"]}", Map.of("count", 1)))
                .isEqualTo(Truth.ERROR);
    }

    @Test
    @DisplayName("✅ contains 是字符串子串语义，不是集合语义")
    void containsIsSubstringNotMembership() {
        assertThat(eval("{\"contains\":[\"in.tags\",\"vi\"]}", Map.of("tags", "vip")))
                .isEqualTo(Truth.TRUE);
        assertThat(eval("{\"contains\":[\"in.tags\",\"vip\"]}", Map.of("tags", List.of("vip"))))
                .as("数组输入交给 contains：判 ERROR，让配置者改用 anyOf")
                .isEqualTo(Truth.ERROR);
    }

    // ── anyOf ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ anyOf：两侧皆集合，交集非空即 TRUE")
    void anyOfIntersectsArrays() {
        assertThat(eval("{\"anyOf\":[\"in.tags\",[\"vip\",\"staff\"]]}",
                Map.of("tags", List.of("new", "vip")))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"anyOf\":[\"in.tags\",[\"vip\"]]}",
                Map.of("tags", List.of("new")))).isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️ anyOf 作用在标量输入上判 ERROR")
    void anyOfOnScalarIsError() {
        assertThat(eval("{\"anyOf\":[\"in.topic\",[\"vip\"]]}", Map.of("topic", "order_status")))
                .isEqualTo(Truth.ERROR);
    }

    // ── re ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ re 是<b>全串匹配</b>口径（find 与 matches 给出不同答案，口径必须冻结）")
    void regexIsFullMatchNotFind() {
        assertThat(eval("{\"re\":[\"in.orderId\",\"SO-\\\\d{6}\"]}",
                Map.of("orderId", "SO-000001"))).isEqualTo(Truth.TRUE);
        assertThat(eval("{\"re\":[\"in.orderId\",\"SO-\\\\d{6}\"]}",
                Map.of("orderId", "xSO-000001y")))
                .as("若口径是 find，这条会判 TRUE —— 样本回放与运行期的结论就会分家")
                .isEqualTo(Truth.FALSE);
    }

    @Test
    @DisplayName("⚠️ re 作用在非字符串输入上判 ERROR")
    void regexOnNonStringIsError() {
        assertThat(eval("{\"re\":[\"in.count\",\"\\\\d+\"]}", Map.of("count", 1)))
                .isEqualTo(Truth.ERROR);
    }

    // ── ctx ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ ctx 值来自服务端注入，与 in 同口径比较")
    void ctxValuesResolve() {
        RoutingInput input = new RoutingInput(Map.of(),
                Map.of(CtxName.CHANNEL, "app", CtxName.RUN_ATTEMPT, 2));

        assertThat(PredicateEvaluator.evaluate(
                PredicateCodec.parse("{\"eq\":[\"ctx.channel\",\"app\"]}", "test"), input).truth())
                .isEqualTo(Truth.TRUE);
        assertThat(PredicateEvaluator.evaluate(
                PredicateCodec.parse("{\"eq\":[\"ctx.run.attempt\",2]}", "test"), input).truth())
                .isEqualTo(Truth.TRUE);
        assertThat(PredicateEvaluator.evaluate(
                PredicateCodec.parse("{\"eq\":[\"ctx.locale\",\"zh-CN\"]}", "test"), input).truth())
                .as("未注入的 ctx 项是缺失 ⇒ ERROR，不是「不等于」")
                .isEqualTo(Truth.ERROR);
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static Truth eval(String json) {
        return eval(json, Map.of());
    }

    private static Truth eval(String json, Map<String, Object> in) {
        return PredicateEvaluator.evaluate(
                PredicateCodec.parse(json, "test"), new RoutingInput(in, Map.of())).truth();
    }
}
