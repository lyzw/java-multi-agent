package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 谓词 → {@link Dnf} 的<b>有界</b>展开（10 号设计 §5）。
 *
 * <p><b>展开的等价性只在封闭文法上成立</b>，这也是 §4.4 要把词汇表封死的原因之一：
 * <ul>
 *   <li>{@code in(x,[a,b])} 折成 {@code eq(x,a) ∨ eq(x,b)} —— 成立的前提是
 *       「{@code x} 是标量」，而求值器对非标量输入判 ERROR，不会给出与展开不一致的 TRUE；</li>
 *   <li>{@code anyOf(x,[a,b])} <b>故意不折</b>：它的输入是<b>数组</b>，
 *       {@code anyOf(x,[a]) ∧ anyOf(x,[b])} 在 {@code x=[a,b]} 时可以同时成立，
 *       折成等式就会误判永假 —— 误拒一条合法策略比漏判更糟。</li>
 * </ul>
 *
 * <p>⚠️ <b>超预算直接抛 {@link RoutingConfigException}</b>（V10），
 * 返回一个「不完整但看起来正常」的 DNF 是最坏的选择：守卫会拿着残缺的子句集做
 * 覆盖判定，把「判不出来」伪装成「没冲突」。
 */
public final class DnfExpander {

    private DnfExpander() {
    }

    /**
     * 展开一个谓词。
     *
     * @param predicate AST
     * @return 等价的 DNF
     * @throws RoutingConfigException 展开积超过 {@link Dnf#MAX_CLAUSES}
     */
    public static @NonNull Dnf toDnf(@NonNull Predicate predicate) {
        if (predicate instanceof Predicate.Const constant) {
            return constant.value() ? Dnf.top() : Dnf.bottom();
        }
        if (predicate instanceof Predicate.And and) {
            Dnf product = Dnf.top();
            for (Predicate term : and.terms()) {
                product = product.and(toDnf(term));
            }
            return product;
        }
        if (predicate instanceof Predicate.Or or) {
            Dnf sum = Dnf.bottom();
            for (Predicate term : or.terms()) {
                sum = sum.or(toDnf(term));
            }
            return sum;
        }
        if (predicate instanceof Predicate.Not not) {
            return toDnf(not.term()).not();
        }
        if (predicate instanceof Predicate.Eq eq) {
            Dnf.Literal literal = equalityLiteral(eq.left(), eq.right());
            return literal == null
                    ? Dnf.of(Dnf.Literal.opaque(false, eq.signature()))
                    : Dnf.of(literal);
        }
        if (predicate instanceof Predicate.In in) {
            return membershipExpanded(in.value(), in.set().items());
        }
        if (predicate instanceof Predicate.Null isNull) {
            return Dnf.of(Dnf.Literal.equality(false,
                    "null(" + isNull.value().signature() + ")", "missing"));
        }
        if (predicate instanceof Predicate.AnyOf anyOf) {
            // 见类注释：数组交集不是等式，不能展开。
            return Dnf.of(Dnf.Literal.opaque(false, anyOf.signature()));
        }
        if (predicate instanceof Predicate.StartsWith startsWith) {
            return Dnf.of(Dnf.Literal.opaque(false, startsWith.signature()));
        }
        if (predicate instanceof Predicate.EndsWith endsWith) {
            return Dnf.of(Dnf.Literal.opaque(false, endsWith.signature()));
        }
        if (predicate instanceof Predicate.Contains contains) {
            return Dnf.of(Dnf.Literal.opaque(false, contains.signature()));
        }
        if (predicate instanceof Predicate.Matches matches) {
            return Dnf.of(Dnf.Literal.opaque(false, matches.signature()));
        }
        throw new IllegalStateException("未实现的谓词: " + predicate.getClass());
    }

    /**
     * {@code eq} 折成等式文字的条件：<b>一侧是引用、另一侧是标量字面量</b>。
     *
     * <p>两侧都是引用（{@code in.a == in.b}）或都是字面量时不做等式推理：
     * 前者无法归到某个字段的常量上，后者根本是配置错误（恒真或恒假，V4 之外由 V3 的
     * 「至少引用一个输入字段」类判定处理）。
     *
     * @return 等式文字；不可折时为 {@code null}
     */
    private static Dnf.@Nullable Literal equalityLiteral(Predicate.@NonNull Value left,
                                                          Predicate.@NonNull Value right) {
        boolean leftLiteral = left instanceof Predicate.LiteralValue;
        boolean rightLiteral = right instanceof Predicate.LiteralValue;
        if (leftLiteral == rightLiteral) {
            // 两侧都是引用，或两侧都是字面量：没有「某字段的某个常量」可归。
            return null;
        }
        Predicate.Value reference = leftLiteral ? right : left;
        if (!(reference instanceof Predicate.InputPath)
                && !(reference instanceof Predicate.CtxValue)) {
            return null;
        }
        Predicate.LiteralValue literal =
                (Predicate.LiteralValue) (leftLiteral ? left : right);
        return Dnf.Literal.equality(false, "eq(" + reference.signature() + ")",
                canonical(literal.value()));
    }

    /** {@code in}：按字面量逐项展开为等式析取（§5「{@code in} 子句按字面量积展开」）。 */
    private static @NonNull Dnf membershipExpanded(Predicate.@NonNull Value value,
                                                   @NonNull List<Object> items) {
        if (!(value instanceof Predicate.InputPath) && !(value instanceof Predicate.CtxValue)) {
            return Dnf.of(Dnf.Literal.opaque(false, "in(" + value.signature() + "," + items + ")"));
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (Object item : items) {
            distinct.add(canonical(item));
        }
        if (distinct.isEmpty()) {
            // 空集合 = 恒假（永假规则，V4 会据此拒发布）。
            return Dnf.bottom();
        }
        String key = "eq(" + value.signature() + ")";
        List<Dnf.Clause> clauses = new ArrayList<>();
        for (String canonicalItem : distinct) {
            clauses.add(new Dnf.Clause(List.of(Dnf.Literal.equality(false, key, canonicalItem))));
        }
        return new Dnf(List.copyOf(clauses));
    }

    /**
     * 字面量的规范化值（<b>带类型标签</b>）。
     *
     * <p>⚠️ 不带类型标签会把字符串 {@code "1"} 与数字 {@code 1} 判成同一个常量，
     * 而求值器对二者是跨类型 ERROR —— 于是守卫说「恒真」、引擎说「判不了」，
     * 校验结论与执行结论分家。数值统一走 {@link java.math.BigDecimal#stripTrailingZeros()}，
     * 让 {@code 1}、{@code 1.0} 与 {@code 1.00} 是同一个常量（与求值器的数值相等口径一致）。
     */
    static @NonNull String canonical(@NonNull Object value) {
        if (value instanceof Number number) {
            return "n:" + new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean flag) {
            return "b:" + flag;
        }
        return "s:" + value;
    }
}
