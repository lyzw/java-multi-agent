package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 谓词的<b>析取范式</b>（10 号设计 §5 的核心技巧：先规范化为 DNF 再做<b>精确</b>判定）。
 *
 * <p><b>形态</b>：{@code DNF = 子句列表}，{@code 子句 = 文字列表}（合取），
 * 空子句列表 = 恒假，<b>含一个空子句 = 恒真</b>。
 *
 * <p>⚠️ <b>为什么要展开 {@code in} 与 {@code anyOf}</b>：
 * {@code in(x,[a,b])} 在守卫看来就是 {@code eq(x,a) ∨ eq(x,b)}。
 * 只有把它们折成同一种「等式文字」，下面两条判定才是<b>精确</b>而不是启发式的：
 * <ul>
 *   <li>同一子句里 {@code eq(in.k)} 出现两个不同常量 ⇒ 这条规则永假（V4）；</li>
 *   <li>前一条规则的真值集合覆盖后一条 ⇒ 后一条永不执行（V5）。</li>
 * </ul>
 * 若 {@code in} 保持黑盒，「{@code in(x,[1,2])} 与 {@code eq(x,3)} 矛盾」就判不出来 ——
 * 而那正是配置者最想被提醒的一类错误。
 *
 * <p>⚠️ <b>展开的代价由预算把守，超预算拒发布而不是降级</b>（V10，见 {@link DnfExpander}）：
 * 降级成启发式或跳过 V4-V6 都会让「策略通过校验」这句话失去含义。
 *
 * @param clauses 子句（合取）列表，析取语义
 */
public record Dnf(@NonNull List<Clause> clauses) {

    /** DNF 展开积的预算上限（§4.4）。 */
    public static final int MAX_CLAUSES = PredicateLimits.MAX_DNF_CONJUNCTIONS;

    public Dnf {
        clauses = List.copyOf(clauses);
    }

    /** 恒真：含一个空子句（什么都不要求）。 */
    public static @NonNull Dnf top() {
        return new Dnf(List.of(Clause.empty()));
    }

    /** 恒假：没有子句可走。 */
    public static @NonNull Dnf bottom() {
        return new Dnf(List.of());
    }

    /** 单个原子（正文字）。 */
    public static @NonNull Dnf of(@NonNull Literal literal) {
        return new Dnf(List.of(new Clause(List.of(literal))));
    }

    /**
     * 一个子句 = 若干文字的合取。
     *
     * @param literals 文字（顺序只用于报告，判定按集合语义）
     */
    public record Clause(@NonNull List<Literal> literals) {

        public Clause {
            literals = List.copyOf(literals);
        }

        /** 空子句（恒真合取）。 */
        public static @NonNull Clause empty() {
            return new Clause(List.of());
        }

        /**
         * 本子句是否<b>可满足</b>（能有一句输入让它成立）。
         *
         * <p>两种不可满足：同一原子同时以正负出现；同一等式键出现两个不同常量。
         * 二者都是<b>精确</b>判定 —— 在本词汇表里没有第三种矛盾形式，
         * 因为不透明原子（前缀、正则）不与任何东西做等式推理。
         */
        public boolean isContradictory() {
            Map<String, Boolean> polarities = new LinkedHashMap<>();
            Map<String, Set<String>> equalities = new LinkedHashMap<>();
            for (Literal literal : literals) {
                Boolean previous = polarities.get(literal.atom());
                if (previous != null && previous != literal.negated()) {
                    return true;
                }
                polarities.put(literal.atom(), literal.negated());
                if (!literal.negated() && literal.equalityValue() != null) {
                    equalities.computeIfAbsent(literal.key(), k -> new LinkedHashSet<>())
                            .add(literal.equalityValue());
                }
            }
            return equalities.values().stream().anyMatch(values -> values.size() > 1);
        }

        /** 该子句是否<b>必然成立</b>（没有任何约束）。 */
        public boolean isTautology() {
            return literals.isEmpty();
        }
    }

    /**
     * 一个文字（literal）。
     *
     * @param negated       是否取反
     * @param atom          正原子的签名（同一 {@code atom} 的正负形式互为矛盾）
     * @param key           等式键（同一键上至多一个常量成立）；不透明原子用 {@code atom} 兜底
     * @param equalityValue 该等式要求的常量规范化值；不透明原子为 {@code null}（不参与等式推理）
     */
    public record Literal(boolean negated,
                          @NonNull String atom,
                          @NonNull String key,
                          @Nullable String equalityValue) {

        /** 可参与等式推理的原子（相等、集合成员、判缺失）。 */
        public static @NonNull Literal equality(boolean negated, @NonNull String key,
                                                @NonNull String value) {
            return new Literal(negated, key + "," + value, key, value);
        }

        /** 不参与等式推理的原子（前缀/后缀/子串/正则）。 */
        public static @NonNull Literal opaque(boolean negated, @NonNull String atom) {
            return new Literal(negated, atom, atom, null);
        }

        /** 取反。 */
        public @NonNull Literal negate() {
            return new Literal(!negated, atom, key, equalityValue);
        }
    }

    /** 析取（并）。 */
    public @NonNull Dnf or(@NonNull Dnf other) {
        List<Clause> merged = new ArrayList<>(clauses);
        merged.addAll(other.clauses);
        return new Dnf(merged);
    }

    /**
     * 合取（积）：子句做笛卡尔积并合并文字。
     *
     * @param other 另一侧
     * @return 积
     * @throws RoutingConfigException 积超过 {@link #MAX_CLAUSES}（V10）
     */
    public @NonNull Dnf and(@NonNull Dnf other) {
        if (clauses.isEmpty() || other.clauses.isEmpty()) {
            return bottom();
        }
        List<Clause> products = new ArrayList<>();
        for (Clause left : clauses) {
            for (Clause right : other.clauses) {
                List<Literal> merged = new ArrayList<>(left.literals());
                merged.addAll(right.literals());
                products.add(new Clause(merged));
                if (products.size() > MAX_CLAUSES) {
                    throw overBudget();
                }
            }
        }
        return new Dnf(products);
    }

    /**
     * 否定：{@code ¬(C1 ∨ … ∨ Cn) = ¬C1 ∧ … ∧ ¬Cn}，每个 {@code ¬Ci} 是文字取反后的析取。
     *
     * <p>⚠️ 这一步正是<b>积爆炸</b>的来源（每个子句贡献一次分支选择），
     * 所以 {@code not} 嵌套 {@code and} 的策略最容易被 V10 拒 ——
     * 这不是引擎的缺陷，而是「这条策略没人读得懂」的可测量信号。
     */
    public @NonNull Dnf not() {
        if (clauses.isEmpty()) {
            return top();
        }
        List<Dnf> negatedClauses = new ArrayList<>();
        for (Clause clause : clauses) {
            if (clause.literals().isEmpty()) {
                // ¬⊤ = ⊥：从空子句里挑不出一个文字，展开积为 0 个子句。
                return bottom();
            }
            List<Clause> branch = new ArrayList<>();
            for (Literal literal : clause.literals()) {
                branch.add(new Clause(List.of(literal.negate())));
            }
            negatedClauses.add(new Dnf(branch));
        }
        Dnf product = top();
        for (Dnf factor : negatedClauses) {
            product = product.and(factor);
        }
        return product;
    }

    /** 子句总数（V10 报告与排错都要它）。 */
    public int clauseCount() {
        return clauses.size();
    }

    /** 可满足的子句（跳过永假的）。 */
    public @NonNull List<Clause> satisfiableClauses() {
        return clauses.stream().filter(clause -> !clause.isContradictory()).toList();
    }

    /** 是否<b>恒假</b>：没有子句，或所有子句都矛盾（守卫 V4 的判据）。 */
    public boolean isUnsatisfiable() {
        return satisfiableClauses().isEmpty();
    }

    /** 是否<b>恒真</b>：存在空子句且...（保守判定：任一子句无约束即恒真）。 */
    public boolean isTautology() {
        return clauses.stream().anyMatch(Clause::isTautology);
    }

    /**
     * 本范式是否<b>覆盖</b>{@code other}（{@code this ⊇ other}）：
     * {@code other} 的每个可满足子句都被 {@code this} 的某个可满足子句<b>子集包含</b>。
     *
     * <p>守卫 V5 用它判「前条 covers 本条 ⇒ 本条永不执行」。
     *
     * <p>⚠️ <b>可靠但不完备</b>：子集包含成立时覆盖必然成立（所以不会误拒好策略之外的东西），
     * 但存在「语义上覆盖而文字集合不子集包含」的情形（例如需要算术推理的区间包含）。
     * 本词汇表里没有算术，所以这条差距是<b>可枚举的窄</b>；
     * 报告措辞因此写「被前条遮蔽」而不是「不可能命中」。
     */
    public boolean covers(@NonNull Dnf other) {
        List<Clause> mine = satisfiableClauses();
        for (Clause theirs : other.satisfiableClauses()) {
            boolean subsumed = mine.stream().anyMatch(mineClause ->
                    subsumes(mineClause, theirs));
            if (!subsumed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 恒假子句之外的<b>逐原子</b>包含判定。
     *
     * <p>注意方向：文字<b>更少</b>的子句条件更宽，因此它覆盖文字更多的子句。
     */
    private static boolean subsumes(@NonNull Clause narrower, @NonNull Clause wider) {
        Set<String> widerAtoms = new LinkedHashSet<>();
        for (Literal literal : wider.literals()) {
            widerAtoms.add(literalKey(literal));
        }
        for (Literal literal : narrower.literals()) {
            if (!widerAtoms.contains(literalKey(literal))) {
                return false;
            }
        }
        return true;
    }

    private static @NonNull String literalKey(@NonNull Literal literal) {
        return (literal.negated() ? "!" : "+") + literal.atom();
    }

    private static @NonNull RoutingConfigException overBudget() {
        return new RoutingConfigException("dnf",
                "DNF 展开积超过预算 " + MAX_CLAUSES + "：本策略的矛盾/遮蔽判定无法做到精确，"
                        + "因此<b>拒绝发布</b>而不是跳过守卫 —— 请把这条规则拆成多条窄规则");
    }
}
