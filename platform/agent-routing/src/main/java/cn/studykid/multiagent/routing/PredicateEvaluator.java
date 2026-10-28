package cn.studykid.multiagent.routing;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 谓词求值器（10 号设计 §2 之①「求值必须确定性」与 ③「失败语义冻结」的执行处）。
 *
 * <p><b>三条不可让的行为</b>：
 * <ol>
 *   <li><b>三值，且 {@link Truth#ERROR} 永不折算成 {@link Truth#FALSE}</b> ——
 *       见 {@link Truth} 的理由；</li>
 *   <li><b>跨类型比较判 ERROR 而不是 FALSE</b>：
 *       {@code eq(in.topic, 1)} 而实际输入是字符串时，「不相等」是假象，
 *       真相是这条规则<b>从来没在它声称的字段上生效</b>；
 *       判 FALSE 就是把配置缺陷读成业务结果。</li>
 *   <li><b>短路顺序 = 声明顺序</b>：合取遇 {@code FALSE} 即停、析取遇 {@code TRUE} 即停，
 *       未短路时的第一个 {@code ERROR} 取声明序最前者 ——
 *       否则同一份 (策略, 输入) 两次求值可能报出不同的错，重放就无从谈起。</li>
 * </ol>
 *
 * <p>⚠️ <b>纯函数，不读时钟、不读环境、不碰 IO</b>：
 * 输入全部来自 {@link RoutingInput}（其中时间已是调用方注入的日粒度值）。
 * 只要多一个隐式输入，「同输入必同输出」就不再成立，
 * 而 §6.1 的重放不变式是整份设计的价值所在。
 */
public final class PredicateEvaluator {

    private PredicateEvaluator() {
    }

    /**
     * 一次求值的结果。
     *
     * @param truth  三值结果
     * @param reason {@link Truth#ERROR} 时的原因（进决策快照的 trace，供配置者定位）；
     *               其余两值恒为 {@code null}
     */
    public record Outcome(@NonNull Truth truth, @Nullable String reason) {

        static final Outcome TRUE = new Outcome(Truth.TRUE, null);
        static final Outcome FALSE = new Outcome(Truth.FALSE, null);

        static @NonNull Outcome error(@NonNull String reason) {
            return new Outcome(Truth.ERROR, reason);
        }

        /** 是否命中（<b>只有 {@link Truth#TRUE} 算命中</b>）。 */
        public boolean matched() {
            return truth == Truth.TRUE;
        }
    }

    /**
     * 求值一个谓词。
     *
     * @param predicate AST（必须已由 {@link PredicateCodec} 产出，本方法不再校验文法）
     * @param input     规范化输入
     * @return 三值结果
     */
    public static @NonNull Outcome evaluate(@NonNull Predicate predicate,
                                            @NonNull RoutingInput input) {
        if (predicate instanceof Predicate.Const constant) {
            return constant.value() ? Outcome.TRUE : Outcome.FALSE;
        }
        if (predicate instanceof Predicate.And and) {
            return combine(and.terms(), input, true);
        }
        if (predicate instanceof Predicate.Or or) {
            return combine(or.terms(), input, false);
        }
        if (predicate instanceof Predicate.Not not) {
            Outcome inner = evaluate(not.term(), input);
            return switch (inner.truth()) {
                case TRUE -> Outcome.FALSE;
                case FALSE -> Outcome.TRUE;
                case ERROR -> inner;
            };
        }
        if (predicate instanceof Predicate.Eq eq) {
            return eq(input, eq.left(), eq.right());
        }
        if (predicate instanceof Predicate.In in) {
            return in(input, in.value(), in.set());
        }
        if (predicate instanceof Predicate.Null isNull) {
            // ⚠️ 全文法里唯一「缺失也算数」的算子：判缺失必须有正当出口，
            //    否则配置者只能靠「eq 恒不等」的假象来表达，而那正是 FALSE/ERROR 混为一谈。
            return isMissing(input.resolve(isNull.value())) ? Outcome.TRUE : Outcome.FALSE;
        }
        if (predicate instanceof Predicate.StartsWith startsWith) {
            return stringPair(input, startsWith.value(), startsWith.prefix(), "startsWith",
                    (value, other) -> value.startsWith(other) ? Outcome.TRUE : Outcome.FALSE);
        }
        if (predicate instanceof Predicate.EndsWith endsWith) {
            return stringPair(input, endsWith.value(), endsWith.suffix(), "endsWith",
                    (value, other) -> value.endsWith(other) ? Outcome.TRUE : Outcome.FALSE);
        }
        if (predicate instanceof Predicate.Contains contains) {
            return stringPair(input, contains.haystack(), contains.needle(), "contains",
                    (value, other) -> value.contains(other) ? Outcome.TRUE : Outcome.FALSE);
        }
        if (predicate instanceof Predicate.AnyOf anyOf) {
            return anyOf(input, anyOf.value(), anyOf.set());
        }
        if (predicate instanceof Predicate.Matches matches) {
            Object value = input.resolve(matches.value());
            if (isMissing(value)) {
                return Outcome.error("re 的取值缺失: " + describe(matches.value(), value));
            }
            if (!(value instanceof String text)) {
                return Outcome.error("re 的取值必须是字符串，实际为 " + categoryOf(value)
                        + ": " + describe(matches.value(), value));
            }
            return matches.regex().test(text) ? Outcome.TRUE : Outcome.FALSE;
        }
        // ⚠️ 不可达：Predicate 是 sealed 的，新增算子会在**编译期**撞车。
        throw new IllegalStateException("未实现的谓词: " + predicate.getClass());
    }

    // ── 组合 ────────────────────────────────────────────────────────

    /**
     * 合取/析取的短路求值。
     *
     * @param terms    子项（声明序）
     * @param wantFalseStop {@code true} = 合取（遇 FALSE 停），{@code false} = 析取（遇 TRUE 停）
     */
    private static @NonNull Outcome combine(@NonNull List<Predicate> terms,
                                            @NonNull RoutingInput input,
                                            boolean wantFalseStop) {
        String firstError = null;
        for (Predicate term : terms) {
            Outcome outcome = evaluate(term, input);
            if (wantFalseStop && outcome.truth() == Truth.FALSE) {
                return Outcome.FALSE;
            }
            if (!wantFalseStop && outcome.truth() == Truth.TRUE) {
                return Outcome.TRUE;
            }
            if (outcome.truth() == Truth.ERROR && firstError == null) {
                firstError = outcome.reason();
            }
        }
        if (firstError != null) {
            // ⚠️ 没有 FALSE 也没有决定性的 TRUE，但存在 ERROR：结果必须是 ERROR，
            //    不能因为「其它项都不成立」就当成 FALSE 收场。
            return Outcome.error(firstError);
        }
        return wantFalseStop ? Outcome.TRUE : Outcome.FALSE;
    }

    // ── 原子 ────────────────────────────────────────────────────────

    private static @NonNull Outcome eq(@NonNull RoutingInput input,
                                       Predicate.@NonNull Value left,
                                       Predicate.@NonNull Value right) {
        Object l = input.resolve(left);
        Object r = input.resolve(right);
        if (isMissing(l) || isMissing(r)) {
            return Outcome.error("比较的一侧取值缺失: " + describe(left, l) + " vs " + describe(right, r));
        }
        Category lc = categoryOf(l);
        Category rc = categoryOf(r);
        if (lc != rc) {
            return Outcome.error("比较的两侧类型不相容（" + lc + " vs " + rc + "）: "
                    + describe(left, l) + " vs " + describe(right, r)
                    + " —— 判 FALSE 会把「字段名/类型写错」伪装成「条件不成立」");
        }
        return scalarEquals(l, r) ? Outcome.TRUE : Outcome.FALSE;
    }

    private static @NonNull Outcome in(@NonNull RoutingInput input,
                                       Predicate.@NonNull Value value,
                                       Predicate.@NonNull SetLiteral set) {
        Object actual = input.resolve(value);
        if (isMissing(actual)) {
            return Outcome.error("集合比较的取值缺失: " + describe(value, actual));
        }
        Category actualCategory = categoryOf(actual);
        boolean comparableFound = false;
        for (Object item : set.items()) {
            if (categoryOf(item) != actualCategory) {
                continue;
            }
            comparableFound = true;
            if (scalarEquals(actual, item)) {
                return Outcome.TRUE;
            }
        }
        if (!comparableFound) {
            return Outcome.error("取值类型为 " + actualCategory + "，但集合 " + set.items()
                    + " 里没有同类型项，无法判定是否属于该集合");
        }
        return Outcome.FALSE;
    }

    private static @NonNull Outcome anyOf(@NonNull RoutingInput input,
                                          Predicate.@NonNull Value value,
                                          Predicate.@NonNull ArrayLiteral set) {
        Object actual = input.resolve(value);
        if (isMissing(actual)) {
            return Outcome.error("anyOf 的取值缺失: " + describe(value, actual));
        }
        if (!(actual instanceof List<?> list)) {
            return Outcome.error("anyOf 要求输入是数组，实际为 " + categoryOf(actual)
                    + ": " + describe(value, actual));
        }
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            Category elementCategory = categoryOf(element);
            for (Object item : set.items()) {
                if (categoryOf(item) == elementCategory && scalarEquals(element, item)) {
                    return Outcome.TRUE;
                }
            }
        }
        return Outcome.FALSE;
    }

    /** 字符串二元算子的公共部分：任一侧缺失或非字符串一律 ERROR。 */
    private interface StringOperation {
        @NonNull Outcome apply(String value, String other);
    }

    private static @NonNull Outcome stringPair(@NonNull RoutingInput input,
                                               Predicate.@NonNull Value left,
                                               Predicate.@NonNull Value right,
                                               @NonNull String operator,
                                               @NonNull StringOperation operation) {
        Object l = input.resolve(left);
        Object r = input.resolve(right);
        if (isMissing(l) || isMissing(r)) {
            return Outcome.error(operator + " 的一侧取值缺失: " + describe(left, l)
                    + " vs " + describe(right, r));
        }
        if (!(l instanceof String value)) {
            return Outcome.error(operator + " 的左值必须是字符串，实际为 " + categoryOf(l)
                    + ": " + describe(left, l));
        }
        if (!(r instanceof String other)) {
            return Outcome.error(operator + " 的右值必须是字符串，实际为 " + categoryOf(r)
                    + ": " + describe(right, r));
        }
        return operation.apply(value, other);
    }

    // ── 标量比较 ────────────────────────────────────────────────────

    /** 标量的比较种类（<b>只有同种类才可比</b>，跨种类判 ERROR）。 */
    enum Category {
        STRING, NUMBER, BOOLEAN, OTHER
    }

    private static @NonNull Category categoryOf(@Nullable Object value) {
        if (value instanceof String) {
            return Category.STRING;
        }
        if (value instanceof Number) {
            return Category.NUMBER;
        }
        if (value instanceof Boolean) {
            return Category.BOOLEAN;
        }
        return Category.OTHER;
    }

    /**
     * 缺失判定：{@link RoutingInput#ABSENT} 与 JSON {@code null} 同判。
     *
     * <p>二者在 {@link Predicate.Null} 上必须同判（「字段没传」与「传了 null」
     * 在路由语义里没有区别），而在其它算子上都由 {@code ERROR} 兜住，
     * 所以这里不需要区分。
     */
    private static boolean isMissing(@Nullable Object value) {
        return value == null || value == RoutingInput.ABSENT;
    }

    private static boolean scalarEquals(@NonNull Object left, @NonNull Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            // ⚠️ 用 BigDecimal 而不是 equals：1（Integer）与 1.0（Double）必须相等，
            //    否则「整数比小数」这类写法会给出 FALSE 而没有任何人看得出为什么。
            return toDecimal(a).compareTo(toDecimal(b)) == 0;
        }
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return a.equals(b);
        }
        if (left instanceof String a && right instanceof String b) {
            return a.equals(b);
        }
        return false;
    }

    private static @NonNull BigDecimal toDecimal(@NonNull Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private static @NonNull String describe(Predicate.@NonNull Value value, @Nullable Object resolved) {
        return value.signature() + "=" + (isMissing(resolved) ? "<缺失>" : String.valueOf(resolved));
    }
}
