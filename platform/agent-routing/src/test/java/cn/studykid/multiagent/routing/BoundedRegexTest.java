package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受限正则（{@code BoundedRegex}）测试（10 号设计 §4.4，§9 S2 出口条件「RE2 防 ReDoS」）。
 *
 * <p><b>要证明的是「一条策略不能把委派线程点着」</b>：路由发生在委派链路上，
 * 一个回溯型正则配上长输入就是拒绝服务，而它的触发条件是<b>配置文本</b> ——
 * 等于把可用性交给写策略的人。所以这里用 RE2/J（线性时间、无回溯）而不是 JDK 的 {@code java.util.regex}。
 */
class BoundedRegexTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    /** 一个只有字符串字段 {@code x} 的词汇表，够构造输入。 */
    private static final InputSchema SCHEMA = new InputSchema(
            Map.of("x", new InputSchema.FieldType(InputSchema.Scalar.STRING, false)),
            Set.of(), Map.of());

    @Test
    @DisplayName("✅ 回溯爆炸型正则在线性时间内判完：JDK 正则会在这里挂死，RE2 不会")
    void catastrophicBacktrackingPatternIsStillBounded() {
        Predicate predicate = PredicateCodec.parse("{\"re\":[\"in.x\",\"(a+)+$\"]}", "test");
        // 200 个 a 后接一个不匹配的字符：JDK 的 (a+)+ 需要指数级回溯才能得出「不匹配」。
        String hostile = "a".repeat(200) + "c";
        RoutingInput input = RoutingInput.of(SCHEMA, Map.of("x", hostile), "t-a",
                null, null, 1, 0, DAY);

        long started = System.nanoTime();
        PredicateEvaluator.Outcome outcome = PredicateEvaluator.evaluate(predicate, input);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(outcome.truth())
                .as("RE2 求值必然给出确定答案，而不是跑不完")
                .isEqualTo(Truth.FALSE);
        assertThat(elapsedMillis)
                .as("这条断言的意义是「配置文本不能拖垮委派线程」；若哪天换回 JDK 正则，它会先红")
                .isLessThan(200L);
    }

    @Test
    @DisplayName("⚠️ 反向引用在解析期就被拒：RE2 方言没有它，配置者写的必须是 RE2 能给的")
    void backreferenceIsRejectedAtParseTime() {
        assertThatThrownBy(() -> PredicateCodec.parse("{\"re\":[\"in.x\",\"(a)\\\\1\"]}", "test"))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("正则不合法");
    }

    @Test
    @DisplayName("⚠️ 正则长度超 128 直接拒：长度上限挡住「贴一大坨」这一类不可审计的模式")
    void overlongPatternIsRejected() {
        String longPattern = "a".repeat(PredicateLimits.MAX_REGEX_LENGTH + 1);

        assertThatThrownBy(() -> PredicateCodec.parse(
                "{\"re\":[\"in.x\",\"" + longPattern + "\"]}", "test"))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("正则");
    }

    @Test
    @DisplayName("✅ 命中口径是<b>全串匹配</b>（冻结口径，避免「加了锚点才生效」的隐性差异）")
    void matchingIsWholeString() {
        // \\\\d：Java 字面量给 JSON 一个反斜杠，JSON 再解码回正则的 \d。
        assertThat(evaluate("SO-\\\\d{4}", "SO-0001")).isEqualTo(Truth.TRUE);
        assertThat(evaluate("SO-\\\\d{4}", "XSO-0001"))
                .as("RE2.matches 等价于 ^...$：不写锚点也不会「部分命中」")
                .isEqualTo(Truth.FALSE);
    }

    private static Truth evaluate(String pattern, String value) {
        Predicate predicate = PredicateCodec.parse("{\"re\":[\"in.x\",\"" + pattern + "\"]}", "test");
        return PredicateEvaluator.evaluate(predicate,
                RoutingInput.of(SCHEMA, Map.of("x", value), "t-a", null, null, 1, 0, DAY)).truth();
    }
}
