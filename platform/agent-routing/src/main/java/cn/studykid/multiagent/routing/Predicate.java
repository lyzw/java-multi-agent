package cn.studykid.multiagent.routing;

import java.util.List;
import java.util.List;

import org.jspecify.annotations.NonNull;

import com.google.re2j.Pattern;

/**
 * 谓词封闭文法（10 号设计 §4.4）：<b>没有脚本、没有模板、没有插值、没有外部调用</b>。
 *
 * <p>⚠️ <b>为什么必须是封闭文法</b>：路由决策要满足重放不变式
 * {@code (policyRef, canonicalInputSha256) → finalTarget 唯一}。
 * 只要文法里存在一个「能调用外部」或「能求值任意表达式」的构造，
 * 决策就变成 {@code f(策略, 输入, 环境)} —— 第三个变量让重放失去意义，
 * 「可复核」立刻退化成口号。
 *
 * <p><b>确定性的三处来源</b>：
 * <ol>
 *   <li><b>词汇表封闭</b>：值只能取自 {@code in.<路径>}、{@code ctx.<固定项>} 与字面量，
 *       没有第三种取法（{@link Value} 是 sealed 的，编译器不允许新增）；</li>
 *   <li><b>求值序固定</b>：规则按 {@code ordinal} 升序、合取/析取按声明序，
 *       短路顺序也是确定的（见 {@link PredicateEvaluator}）；</li>
 *   <li><b>正则不返回</b>：{@link Regex} 用 RE2/J 编译，线性时间，
 *       不存在「这条输入比那条慢一个数量级」。</li>
 * </ol>
 *
 * <p>⚠️ {@link #signature()} 是守卫层的<b>同一性判据</b>：
 * V4/V5/V6 只能比较<b>签名相同</b>的原子（同字段、同算子、同字面量），
 * 签名不同的原子在守卫看来是<b>互不相关</b>的两件事 ——
 * 这既避免了不完备的推断被当成完备，也让「判不出来」这件事可解释。
 */
public sealed interface Predicate {

    /** 规范化签名字符串（守卫层的同一性判据）。 */
    @NonNull String signature();

    /** 恒真/恒假常量谓词（写策略时的显式占位，守卫会据此判掉整条规则）。 */
    record Const(boolean value) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "const:" + value;
        }
    }

    /** 合取：按声明序短路求值。 */
    record And(@NonNull List<Predicate> terms) implements Predicate {
        public And {
            terms = List.copyOf(terms);
        }

        @Override
        public @NonNull String signature() {
            return "and[" + join(terms) + "]";
        }
    }

    /** 析取：按声明序短路求值。 */
    record Or(@NonNull List<Predicate> terms) implements Predicate {
        public Or {
            terms = List.copyOf(terms);
        }

        @Override
        public @NonNull String signature() {
            return "or[" + join(terms) + "]";
        }
    }

    /** 取反。 */
    record Not(@NonNull Predicate term) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "not(" + term.signature() + ")";
        }
    }

    /** 标量相等（跨类型比较判 ERROR，不判 FALSE —— 见 {@link PredicateEvaluator}）。 */
    record Eq(@NonNull Value left, @NonNull Value right) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "eq(" + left.signature() + "," + right.signature() + ")";
        }
    }

    /** 标量 ∈ 字面量集合。 */
    record In(@NonNull Value value, @NonNull SetLiteral set) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "in(" + value.signature() + "," + set.signature() + ")";
        }
    }

    /** 取值为「缺失或 JSON null」。 */
    record Null(@NonNull Value value) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "null(" + value.signature() + ")";
        }
    }

    /** 字符串前缀。 */
    record StartsWith(@NonNull Value value, @NonNull Value prefix) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "startsWith(" + value.signature() + "," + prefix.signature() + ")";
        }
    }

    /** 字符串后缀。 */
    record EndsWith(@NonNull Value value, @NonNull Value suffix) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "endsWith(" + value.signature() + "," + suffix.signature() + ")";
        }
    }

    /** 字符串子串（⚠️ <b>不是</b>集合语义 —— 集合语义是 {@link AnyOf}）。 */
    record Contains(@NonNull Value haystack, @NonNull Value needle) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "contains(" + haystack.signature() + "," + needle.signature() + ")";
        }
    }

    /** 两个集合的交集非空（两侧都必须是数组）。 */
    record AnyOf(@NonNull Value value, @NonNull ArrayLiteral set) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "anyOf(" + value.signature() + "," + set.signature() + ")";
        }
    }

    /** 正则全串匹配（RE2/J，线性时间）。 */
    record Matches(@NonNull Value value, @NonNull Regex regex) implements Predicate {
        @Override
        public @NonNull String signature() {
            return "re(" + value.signature() + "," + regex.signature() + ")";
        }
    }

    private static @NonNull String join(@NonNull List<Predicate> terms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(terms.get(i).signature());
        }
        return sb.toString();
    }

    /**
     * 谓词的<b>取值</b>：只有三种来源，没有第四种。
     *
     * <p>⚠️ sealed 是<b>结构性</b>的保证：以后想加一个「取环境变量」或
     * 「执行表达式」的取值方式，必须先改这个接口 ——
     * 那就会撞上重放不变式的评审，而不是撞上之后再补一条 javadoc 告诫。
     */
    sealed interface Value permits InputPath, CtxValue, LiteralValue {
        @NonNull String signature();
    }

    /** {@code in.<dottedPath>}：路由输入里的字段（只有 {@code input_schema} 声明过的路径可引用）。 */
    record InputPath(@NonNull String path) implements Value {
        public InputPath {
            if (!DOTTED_PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("非法的 in 路径: " + path);
            }
        }

        @Override
        public @NonNull String signature() {
            return "in." + path;
        }
    }

    /** {@code ctx.<name>}：服务端注入的上下文（模型无法伪造）。 */
    record CtxValue(@NonNull CtxName name) implements Value {
        @Override
        public @NonNull String signature() {
            return "ctx." + name.wireName();
        }
    }

    /** JSON 标量字面量。 */
    record LiteralValue(@NonNull Object value) implements Value {
        public LiteralValue {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("字面量只允许 JSON 标量，收到: "
                        + value.getClass().getSimpleName());
            }
        }

        @Override
        public @NonNull String signature() {
            return "lit:" + value;
        }
    }

    /** 字面量<b>集合</b>（{@code in} 的右值；不可变、去重、按插入序）。 */
    record SetLiteral(@NonNull List<Object> items) {
        public SetLiteral {
            items = List.copyOf(items);
            items.forEach(item -> {
                if (!(item instanceof String || item instanceof Number
                        || item instanceof Boolean)) {
                    throw new IllegalArgumentException("集合字面量只允许标量项");
                }
            });
        }

        public @NonNull String signature() {
            return "set:" + items;
        }
    }

    /** 数组<b>字面量</b>（{@code anyOf} 的右值）。 */
    record ArrayLiteral(@NonNull List<Object> items) {
        public ArrayLiteral {
            items = List.copyOf(items);
        }

        public @NonNull String signature() {
            return "arr:" + items;
        }
    }

    /**
     * 受限正则（10 号设计 §4.4 的 {@code BoundedRegex}）。
     *
     * <p>⚠️ <b>两道闸，缺一不可</b>：
     * <ul>
     *   <li><b>长度 ≤{@value PredicateLimits#MAX_REGEX_LENGTH}</b>：挡住「贴一大坨」；</li>
     *   <li><b>RE2/J 编译</b>：挡住回溯爆炸。Java 原生 {@code Pattern} 是回溯引擎，
     *       一个 {@code (a+)+b} 就能让一次委派卡死整个 Worker ——
     *       而策略是<b>配置方写的</b>，等于把 DoS 入口开给了配置。</li>
     * </ul>
     * RE2/J 不支持后向引用等构造，编译期就拒（错误在配置期暴露，不是运行期）。
     */
    record Regex(@NonNull String source, @NonNull Pattern compiled) {
        public Regex {
            if (source.length() > PredicateLimits.MAX_REGEX_LENGTH) {
                throw new IllegalArgumentException("正则长度超过 "
                        + PredicateLimits.MAX_REGEX_LENGTH + ": " + source.length());
            }
        }

        /**
         * 用 RE2/J 编译一段正则。
         *
         * @param source 正则源码
         * @return 编译结果
         * @throws IllegalArgumentException 语法不合法或用到 RE2 不支持的构造
         */
        public static @NonNull Regex of(@NonNull String source) {
            try {
                return new Regex(source, Pattern.compile(source));
            } catch (com.google.re2j.PatternSyntaxException e) {
                throw new IllegalArgumentException("正则不合法（RE2/J 拒绝，"
                        + "常见原因是后向引用等回溯引擎专有构造）: " + e.getMessage(), e);
            }
        }

        /**
         * 全串匹配。
         *
         * <p>⚠️ <b>口径必须冻结</b>：{@code matches} 与 {@code find} 对同一个模式给出
         * 不同答案，而配置者与守卫层若各按一种口径理解，
         * 「样本回放通过」与「运行期命中」就不是同一件事。
         */
        public boolean test(@NonNull String input) {
            return compiled.matches(input);
        }

        public @NonNull String signature() {
            return "re:" + source;
        }
    }

    /** 点分路径的词法（段名以字母开头，字母数字下划线，最多 8 段）。 */
    java.util.regex.Pattern DOTTED_PATH = java.util.regex.Pattern.compile(
            "[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){0,7}");
}
