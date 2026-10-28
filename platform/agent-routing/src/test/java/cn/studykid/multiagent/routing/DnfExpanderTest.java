package cn.studykid.multiagent.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有界 DNF 展开测试（10 号设计 §5 的判定基础 + §9 S2 出口条件「DNF 超预算被拒」）。
 *
 * <p><b>本类要证明的是「展开后判定为什么是精确的」</b>：
 * {@code in} 折成等式析取之后，「同字段不同常量」这类矛盾就变成子句里的<b>可见事实</b>，
 * 而不需要任何启发式猜测。反过来，{@code anyOf} 故意不折 ——
 * 折了会把一条<b>合法</b>策略误判成永假，而误拒比漏拒更伤人。
 */
class DnfExpanderTest {

    private static Dnf dnf(String json) {
        return DnfExpander.toDnf(PredicateCodec.parse(json, "test"));
    }

    @Test
    @DisplayName("✅ const 折成恒真/恒假范式")
    void constFoldsToTopAndBottom() {
        assertThat(dnf("{\"const\":true}").isTautology()).isTrue();
        assertThat(dnf("{\"const\":false}").isUnsatisfiable()).isTrue();
        assertThat(dnf("{\"const\":false}").clauseCount()).isZero();
    }

    @Test
    @DisplayName("✅ 同一字段被要求等于两个不同常量 ⇒ 永假（守卫 V4 的判据）")
    void distinctConstantsOnSameFieldAreContradictory() {
        Dnf form = dnf("{\"and\":[{\"eq\":[\"in.topic\",\"order_status\"]},"
                + "{\"eq\":[\"in.topic\",\"refund\"]}]}");

        assertThat(form.isUnsatisfiable())
                .as("topic 不可能同时等于两个常量：这条规则一次也不会执行")
                .isTrue();
    }

    @Test
    @DisplayName("✅ 同一原子的正负形式 ⇒ 永假")
    void complementaryAtomsAreContradictory() {
        assertThat(dnf("{\"and\":[{\"startsWith\":[\"in.a\",\"SO-\"]},"
                + "{\"not\":{\"startsWith\":[\"in.a\",\"SO-\"]}}]}").isUnsatisfiable()).isTrue();
    }

    @Test
    @DisplayName("⚠️⚠️ {@code in} 与 {@code eq} 共享等式键：跨算子的矛盾也判得出来")
    void inAndEqShareTheEqualityKey() {
        Dnf disjoint = dnf("{\"and\":[{\"in\":[\"in.topic\",[\"refund\",\"policy\"]]},"
                + "{\"eq\":[\"in.topic\",\"order_status\"]}]}");
        assertThat(disjoint.isUnsatisfiable())
                .as("「属于 {refund,policy}」与「等于 order_status」不可能同时成立")
                .isTrue();

        Dnf consistent = dnf("{\"and\":[{\"in\":[\"in.topic\",[\"refund\",\"order_status\"]]},"
                + "{\"eq\":[\"in.topic\",\"order_status\"]}]}");
        assertThat(consistent.isUnsatisfiable()).isFalse();
        assertThat(consistent.satisfiableClauses()).hasSize(1);
    }

    @Test
    @DisplayName("✅ {@code in} 按字面量展开：N 项 ⇒ N 个子句")
    void inExpandsPerItem() {
        assertThat(dnf("{\"in\":[\"in.topic\",[\"a\",\"b\",\"c\"]]}").clauseCount()).isEqualTo(3);
        assertThat(dnf("{\"in\":[\"in.topic\",[\"a\",\"a\"]]}").clauseCount())
                .as("重复项合并：展开积按<b>不同常量</b>计，不按书写次数计")
                .isEqualTo(1);
        assertThat(dnf("{\"in\":[\"in.topic\",[]]}").isUnsatisfiable())
                .as("空集合 = 恒假，V4 据此拒发布")
                .isTrue();
    }

    @Test
    @DisplayName("⚠️ {@code anyOf} <b>不</b>展开：数组交集不是等式")
    void anyOfStaysOpaque() {
        Dnf form = dnf("{\"and\":[{\"anyOf\":[\"in.tags\",[\"a\"]]},"
                + "{\"anyOf\":[\"in.tags\",[\"b\"]]}]}");

        assertThat(form.isUnsatisfiable())
                .as("tags=[a,b] 时两个 anyOf 同时成立；折成等式就会误判永假、误拒合法策略")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 覆盖判定：条件更宽的前条 covers 条件更窄的后条（守卫 V5 的判据）")
    void coverageUsesLiteralSubsumption() {
        Dnf broader = dnf("{\"eq\":[\"in.topic\",\"order_status\"]}");
        Dnf narrower = dnf("{\"and\":[{\"eq\":[\"in.topic\",\"order_status\"]},"
                + "{\"startsWith\":[\"in.orderId\",\"SO-\"]}]}");

        assertThat(broader.covers(narrower)).isTrue();
        assertThat(narrower.covers(broader))
                .as("反向不成立：加了约束的那条覆盖不了宽的")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 不同类型常量的等式键不互相矛盾（{@code \"1\"} 与 {@code 1} 是两个常量）")
    void typeTaggedConstantsDoNotCollapse() {
        assertThat(dnf("{\"and\":[{\"eq\":[\"in.a\",\"1\"]},{\"eq\":[\"in.a\",1]}]}")
                .isUnsatisfiable())
                .as("求值器对跨类型比较判 ERROR，守卫也不能把它们当同一个常量")
                .isTrue();
    }

    @Test
    @DisplayName("⛔ 展开积超预算 ⇒ 拒（V10），而不是返回残缺 DNF")
    void overBudgetIsRejectedNotTruncated() {
        // 3 个 17 项的 in 相与 = 17³ = 4913 > 4096。
        StringBuilder json = new StringBuilder("{\"and\":[");
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                json.append(',');
            }
            json.append("{\"in\":[\"in.f").append(group).append("\",[");
            for (int item = 0; item < 17; item++) {
                json.append(item > 0 ? "," : "").append('"').append(group).append('-').append(item).append('"');
            }
            json.append("]]}");
        }
        json.append("]}");

        assertThatThrownBy(() -> dnf(json.toString()))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("DNF 展开积超过预算");
    }

    @Test
    @DisplayName("✅ 预算边界内的积正常展开（4096 = 16³ 恰在上限）")
    void budgetBoundaryAccepts() {
        StringBuilder json = new StringBuilder("{\"and\":[");
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                json.append(',');
            }
            json.append("{\"in\":[\"in.f").append(group).append("\",[");
            for (int item = 0; item < 16; item++) {
                json.append(item > 0 ? "," : "").append('"').append(group).append('-').append(item).append('"');
            }
            json.append("]]}");
        }
        json.append("]}");

        assertThat(dnf(json.toString()).clauseCount()).isEqualTo(4096);
    }

    @Test
    @DisplayName("✅ not 分布：¬(A∨B) = ¬A∧¬B（合取式，两约束同时生效）")
    void negationDistributes() {
        Dnf form = dnf("{\"not\":{\"or\":[{\"eq\":[\"in.a\",\"x\"]},{\"eq\":[\"in.b\",\"y\"]}]}}");

        assertThat(form.clauseCount())
                .as("两个析取支各贡献一个取反文字，积成 1 个子句 2 个文字")
                .isEqualTo(1);
        assertThat(form.satisfiableClauses().get(0).literals()).hasSize(2);
        assertThat(form.isUnsatisfiable()).isFalse();
    }

    @Test
    @DisplayName("✅ not 作用在 in 上按项展开：¬in(x,[a,b]) = ¬eq(x,a) ∧ ¬eq(x,b)")
    void negationOfInBecomesConjunctionOfNegations() {
        Dnf form = dnf("{\"not\":{\"in\":[\"in.a\",[\"x\",\"y\"]]}}");

        assertThat(form.clauseCount())
                .as("德摩根后是合取：¬in(x,[a,b]) 落成 1 个子句里的 2 个取反文字")
                .isEqualTo(1);
        assertThat(form.satisfiableClauses().get(0).literals())
                .as("每个集合项都贡献一个文字 —— 项数直接乘进文字/子句预算，这是 §5 说「not/in 是预算大户」的原因")
                .hasSize(2);
        assertThat(form.satisfiableClauses().get(0).literals())
                .allSatisfy(literal -> assertThat(literal.negated()).isTrue());
    }

    @Test
    @DisplayName("✅ 双重否定回到原式（¬⊥ = ⊤）")
    void doubleNegationReturnsTop() {
        assertThat(dnf("{\"not\":{\"const\":false}}").isTautology()).isTrue();
        assertThat(dnf("{\"not\":{\"const\":true}}").isUnsatisfiable()).isTrue();
    }
}
