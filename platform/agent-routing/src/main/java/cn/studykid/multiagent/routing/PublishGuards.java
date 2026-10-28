package cn.studykid.multiagent.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.studykid.multiagent.core.orchestration.RoutingMode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 发布期静态守卫 V1-V10（10 号设计 §5）。
 *
 * <p>⚠️ <b>本类是整份设计的价值所在</b>：只做规则求值器而不做这些守卫，
 * 等于把「写错的条件」从配置期推迟到线上 —— 而条件写错的症状不是报错，
 * 是<b>某条规则永远不命中</b>，现场只会看到「路由经常走兜底」。
 *
 * <p><b>执行顺序固定 V1→V10，且 V8 之前必须做完 V1/V3</b>：
 * 否则求值器抛在畸形输入上，报错方向会被带偏（配置者会去改规则，而真正的问题是字段名写错）。
 *
 * <p>⚠️ <b>拒绝而不是警告</b>是有意的主基调：V4/V5/V6/V10 都是「这条策略不可能按写的那样跑」，
 * 放行等于让配置者以为自己发布了一个东西。只有 V7 是警告 ——
 * 必选子没有规则覆盖在 {@code RULE_FIRST}/{@code SEMANTIC} 下是<b>合法</b>的（语义引擎可以覆盖它）。
 *
 * <p><b>诚实边界</b>（§5 末）：V4/V5/V6 的精确性建立在
 * ① DNF 展开积不超过 {@link Dnf#MAX_CLAUSES}（超了由 V10 拒，不降级）、
 * ② 等式推理只覆盖「同字段不同常量」与「互补原子」两类矛盾（本词汇表没有算术，
 * 所以 {@code eq(x,1) ∧ eq(x,2)} 之外的矛盾形式不存在）之上。
 */
public final class PublishGuards {

    /** 一条判定结果。 */
    public record Finding(@NonNull String code, @NonNull String message, boolean warning) {
    }

    /** 全量报告。 */
    public record Report(@NonNull List<Finding> findings) {

        /** 失败项（阻止发布）。 */
        public @NonNull List<Finding> violations() {
            return findings.stream().filter(finding -> !finding.warning()).toList();
        }

        /** 警告项（不阻止发布，但要进管理台提示）。 */
        public @NonNull List<Finding> warnings() {
            return findings.stream().filter(Finding::warning).toList();
        }

        /** 是否可发布。 */
        public boolean publishable() {
            return violations().isEmpty();
        }
    }

    private PublishGuards() {
    }

    /**
     * 执行 V1→V10。
     *
     * <p>V1（文法与上限）与 V2（目标 ∈ 父声明）在 {@link PredicateCodec} /
     * {@link PolicyCodec} / {@link PolicySpec} 的构造期就把守了 ——
     * <b>不合法的策略对象根本造不出来</b>，所以本方法从 V3 开始，
     * 而不是在校验器里重抄一遍构造器已经保证的事（两处迟早漂移）。
     *
     * @param policy    已构造的策略
     * @param tenantId  发布方租户（样本回放的 {@code ctx.tenantId} 来源）
     * @param today     回放用的 UTC 日（样本可用 {@code ctx.now.utcDate} 固定它）
     * @return 报告
     */
    public static @NonNull Report check(@NonNull PolicySpec policy,
                                        @NonNull String tenantId,
                                        @NonNull LocalDate today) {
        List<Finding> findings = new ArrayList<>();
        Map<RuleSpec, Dnf> forms = new LinkedHashMap<>();
        for (RuleSpec rule : policy.rules()) {
            try {
                forms.put(rule, DnfExpander.toDnf(rule.when()));
            } catch (RoutingConfigException e) {
                findings.add(new Finding("V10", "规则 " + rule.ruleId() + "：" + e.getMessage(), false));
            }
        }
        v3Vocabulary(policy, findings);
        v4Contradiction(policy, forms, findings);
        v5Shadowed(policy, forms, findings);
        v6DeadTarget(policy, forms, findings);
        v7RequiredCoverage(policy, forms, findings);
        v8Samples(policy, tenantId, today, findings);
        v9SampleCoverage(policy, findings);
        return new Report(List.copyOf(findings));
    }

    // ── V3 词汇表对齐 ──────────────────────────────────────────────

    /**
     * V3：引用的 {@code in.*} 路径 ∈ {@code input_schema} 且类型相容。
     *
     * <p>⚠️ <b>这是最容易被低估的一条</b>：字段名写错的规则<b>不会报错</b>，
     * 运行期只会永远 {@code NO_MATCH}（或按本引擎的口径永远 ERROR→SKIP），
     * 于是「写了不生效」在线上表现为「策略经常走兜底」。
     */
    private static void v3Vocabulary(@NonNull PolicySpec policy, @NonNull List<Finding> findings) {
        for (RuleSpec rule : policy.rules()) {
            String where = "规则 " + rule.ruleId() + "（序号 " + rule.ordinal() + "）";
            List<String> problems = new ArrayList<>();
            walk(rule.when(), policy.inputSchema(), problems);
            for (String problem : problems) {
                findings.add(new Finding("V3", where + "：" + problem, false));
            }
        }
    }

    private static void walk(@NonNull Predicate predicate, @NonNull InputSchema schema,
                             @NonNull List<String> problems) {
        if (predicate instanceof Predicate.And and) {
            and.terms().forEach(term -> walk(term, schema, problems));
            return;
        }
        if (predicate instanceof Predicate.Or or) {
            or.terms().forEach(term -> walk(term, schema, problems));
            return;
        }
        if (predicate instanceof Predicate.Not not) {
            walk(not.term(), schema, problems);
            return;
        }
        if (predicate instanceof Predicate.Eq eq) {
            checkComparison(eq.left(), eq.right(), schema, problems);
            checkComparison(eq.right(), eq.left(), schema, problems);
            return;
        }
        if (predicate instanceof Predicate.In in) {
            checkMembership(in.value(), in.set().items(), schema, "in", problems);
            return;
        }
        if (predicate instanceof Predicate.Null isNull) {
            checkPresence(isNull.value(), schema, problems);
            return;
        }
        if (predicate instanceof Predicate.AnyOf anyOf) {
            checkMembership(anyOf.value(), anyOf.set().items(), schema, "anyOf", problems);
            return;
        }
        if (predicate instanceof Predicate.StartsWith startsWith) {
            checkStringOperand(startsWith.value(), schema, "startsWith", problems);
            checkStringOperand(startsWith.prefix(), schema, "startsWith", problems);
            return;
        }
        if (predicate instanceof Predicate.EndsWith endsWith) {
            checkStringOperand(endsWith.value(), schema, "endsWith", problems);
            checkStringOperand(endsWith.suffix(), schema, "endsWith", problems);
            return;
        }
        if (predicate instanceof Predicate.Contains contains) {
            checkStringOperand(contains.haystack(), schema, "contains", problems);
            checkStringOperand(contains.needle(), schema, "contains", problems);
            return;
        }
        if (predicate instanceof Predicate.Matches matches) {
            checkStringOperand(matches.value(), schema, "re", problems);
        }
    }

    /** 一侧是引用、另一侧是字面量时，检查该引用声明过且字面量类型相容、且在枚举词汇内。 */
    private static void checkComparison(Predicate.@NonNull Value reference,
                                        Predicate.@NonNull Value other,
                                        @NonNull InputSchema schema,
                                        @NonNull List<String> problems) {
        if (!(reference instanceof Predicate.InputPath path)) {
            if (reference instanceof Predicate.CtxValue ctx) {
                checkAgainstCtx(ctx.name(), other, problems);
            }
            return;
        }
        InputSchema.FieldType type = schema.typeOf(path.path()).orElse(null);
        if (type == null) {
            problems.add("引用了 input_schema 未声明的路径 " + path.signature()
                    + "（该条件在运行期永远无法判定，规则等于白写）");
            return;
        }
        if (other instanceof Predicate.LiteralValue literal) {
            if (!schema.isCompatible(path.path(), literal.value())) {
                problems.add(path.signature() + " 的类型是 " + type
                        + "，与字面量 " + literal.value() + " 不相容");
            }
            schema.enumOf(path.path()).ifPresent(values -> {
                if (!containsScalar(values, literal.value())) {
                    problems.add(path.signature() + " 声明了枚举 " + values
                            + "，字面量 " + literal.value() + " 不在其中（永假条件）");
                }
            });
        } else if (!(other instanceof Predicate.InputPath)) {
            problems.add(path.signature() + " 与 " + other.signature()
                    + " 的比较无法静态判定类型（两侧都必须是引用或字面量）");
        }
    }

    private static void checkMembership(Predicate.@NonNull Value value,
                                        @NonNull List<Object> items,
                                        @NonNull InputSchema schema,
                                        @NonNull String operator,
                                        @NonNull List<String> problems) {
        if (value instanceof Predicate.CtxValue ctx) {
            for (Object item : items) {
                if (!isCompatibleWithCtx(ctx.name(), item)) {
                    problems.add("ctx." + ctx.name().wireName() + " 与字面量 " + item + " 类型不相容");
                }
            }
            return;
        }
        if (!(value instanceof Predicate.InputPath path)) {
            problems.add(operator + " 的左值必须是 in.*/ctx.* 引用，收到: " + value.signature());
            return;
        }
        InputSchema.FieldType type = schema.typeOf(path.path()).orElse(null);
        if (type == null) {
            problems.add("引用了 input_schema 未声明的路径 " + path.signature());
            return;
        }
        if ("in".equals(operator) && type.array()) {
            problems.add("in 的左值 " + path.signature() + " 是数组字段：数组成员判断请用 anyOf");
        }
        if ("anyOf".equals(operator) && !type.array()) {
            problems.add("anyOf 的左值 " + path.signature() + " 必须是数组字段（实际是 " + type + "）");
        }
        for (Object item : items) {
            InputSchema probed = new InputSchema(
                    Map.of("x", new InputSchema.FieldType(type.scalar(), false)), Set.of(), Map.of());
            if (!probed.isCompatible("x", item)) {
                problems.add(path.signature() + " 的元素类型是 " + type.scalar()
                        + "，集合里的 " + item + " 与之不相容");
            }
        }
        schema.enumOf(path.path()).ifPresent(values -> items.forEach(item -> {
            if (!containsScalar(values, item)) {
                problems.add(path.signature() + " 声明了枚举 " + values
                        + "，集合项 " + item + " 不在其中（永假条件）");
            }
        }));
    }

    /** {@code null} 与字符串类算子的操作数检查。 */
    private static void checkPresence(Predicate.@NonNull Value value, @NonNull InputSchema schema,
                                      @NonNull List<String> problems) {
        if (value instanceof Predicate.InputPath path
                && schema.typeOf(path.path()).isEmpty()) {
            problems.add("引用了 input_schema 未声明的路径 " + path.signature());
        }
    }

    private static void checkStringOperand(Predicate.@NonNull Value value,
                                           @NonNull InputSchema schema,
                                           @NonNull String operator,
                                           @NonNull List<String> problems) {
        if (value instanceof Predicate.LiteralValue) {
            return;
        }
        if (value instanceof Predicate.InputPath path) {
            InputSchema.FieldType type = schema.typeOf(path.path()).orElse(null);
            if (type == null) {
                problems.add(operator + " 引用了 input_schema 未声明的路径 " + path.signature());
            } else if (type.scalar() != InputSchema.Scalar.STRING || type.array()) {
                problems.add(operator + " 的操作数必须是字符串字段，" + path.signature()
                        + " 声明为 " + type);
            }
            return;
        }
        if (value instanceof Predicate.CtxValue ctx && ctx.name().kind() == CtxName.Kind.NUMBER) {
            problems.add(operator + " 不能作用于数值型上下文 ctx." + ctx.name().wireName());
        }
    }

    private static void checkAgainstCtx(@NonNull CtxName name, Predicate.@NonNull Value other,
                                        @NonNull List<String> problems) {
        if (other instanceof Predicate.LiteralValue literal
                && !isCompatibleWithCtx(name, literal.value())) {
            problems.add("ctx." + name.wireName() + " 是 " + name.kind()
                    + "，与字面量 " + literal.value() + " 不相容");
        }
    }

    private static boolean isCompatibleWithCtx(@NonNull CtxName name, @NonNull Object literal) {
        return switch (name.kind()) {
            case STRING, DATE -> literal instanceof String;
            case NUMBER -> literal instanceof Number;
        };
    }

    // ── V4/V5/V6/V7 范式判定 ───────────────────────────────────────

    /** V4：永假规则（DNF 全部子句矛盾）。命中即拒 —— 这类规则<b>永远不会执行</b>。 */
    private static void v4Contradiction(@NonNull PolicySpec policy,
                                        @NonNull Map<RuleSpec, Dnf> forms,
                                        @NonNull List<Finding> findings) {
        forms.forEach((rule, dnf) -> {
            if (dnf.isUnsatisfiable()) {
                findings.add(new Finding("V4",
                        "规则 " + rule.ruleId() + "（序号 " + rule.ordinal() + "，目标 " + rule.target()
                                + "）的条件<b>永假</b>：" + explainContradiction(rule.when())
                                + " —— 它一次也不会执行，而配置者以为它在生效", false));
            }
        });
    }

    /**
     * V5：被遮蔽规则（前条 covers 本条 ⇒ 本条永不执行）。
     *
     * <p>⚠️ 只在<b>两条都可达</b>时报：前面若有规则已覆盖本条，本条就是死代码，
     * 而短路求值让它永远轮不到 —— 这是「改了策略但行为没变」的唯一物理解释。
     */
    private static void v5Shadowed(@NonNull PolicySpec policy,
                                   @NonNull Map<RuleSpec, Dnf> forms,
                                   @NonNull List<Finding> findings) {
        List<RuleSpec> ordered = new ArrayList<>(forms.keySet());
        for (int later = 1; later < ordered.size(); later++) {
            RuleSpec laterRule = ordered.get(later);
            Dnf laterForm = forms.get(laterRule);
            if (laterForm.isUnsatisfiable()) {
                continue; // V4 已经报过，不再叠加一条同因的结论
            }
            for (int earlier = 0; earlier < later; earlier++) {
                RuleSpec earlierRule = ordered.get(earlier);
                Dnf earlierForm = forms.get(earlierRule);
                if (earlierForm.isUnsatisfiable()) {
                    continue; // 前条永假 ⇒ 不可能遮蔽任何规则（它自己也不会命中）
                }
                if (earlierForm.covers(laterForm)) {
                    findings.add(new Finding("V5",
                            "规则 " + laterRule.ruleId() + "（序号 " + laterRule.ordinal()
                                    + "）被前条 " + earlierRule.ruleId() + "（序号 "
                                    + earlierRule.ordinal() + "）遮蔽：前者成立时后者必成立，"
                                    + "而求值按序号短路 —— 本条永远不会执行", false));
                    break;
                }
            }
        }
    }

    /**
     * V6：死目标（{@code RULES_ONLY} 下某个可委派目标没有任何<b>可满足</b>规则指向它）。
     *
     * <p>⚠️ <b>只在 {@code RULES_ONLY} 判</b>：{@code RULE_FIRST}/{@code SEMANTIC} 下
     * 目标可以由语义引擎到达，此处判死就是误拒。
     */
    private static void v6DeadTarget(@NonNull PolicySpec policy,
                                     @NonNull Map<RuleSpec, Dnf> forms,
                                     @NonNull List<Finding> findings) {
        if (policy.mode() != RoutingMode.RULES_ONLY) {
            return;
        }
        Set<String> reachable = new LinkedHashSet<>();
        forms.forEach((rule, dnf) -> {
            if (!dnf.isUnsatisfiable()) {
                reachable.add(rule.target());
            }
        });
        if (policy.defaultTarget() != null) {
            reachable.add(policy.defaultTarget());
        }
        List<String> dead = policy.allowedTargets().stream()
                .filter(target -> !reachable.contains(target))
                .sorted().toList();
        if (!dead.isEmpty()) {
            findings.add(new Finding("V6",
                    "目标 " + dead + " 在 RULES_ONLY 下不可能被选中（无可满足的规则指向它，也不是兜底目标）"
                            + "：父声明了它却没有任何路径能走到，等于把一个子 Agent 挂在那里占位", false));
        }
    }

    /** V7：必选子没有任何<b>可满足</b>规则覆盖（<b>警告</b>，理由见类注释）。 */
    private static void v7RequiredCoverage(@NonNull PolicySpec policy,
                                           @NonNull Map<RuleSpec, Dnf> forms,
                                           @NonNull List<Finding> findings) {
        Set<String> covered = new LinkedHashSet<>();
        forms.forEach((rule, dnf) -> {
            if (!dnf.isUnsatisfiable()) {
                covered.add(rule.target());
            }
        });
        List<String> missing = policy.requiredTargets().stream()
                .filter(target -> !covered.contains(target))
                .sorted().toList();
        if (!missing.isEmpty()) {
            findings.add(new Finding("V7",
                    "必选子 " + missing + " 没有规则覆盖：在 RULE_FIRST/SEMANTIC 下可由语义引擎到达，"
                            + "但在 RULES_ONLY 下它永远不会被委派到", true));
        }
    }

    // ── V8/V9 样本 ─────────────────────────────────────────────────

    /** V8：全部样例过<b>同一个</b>确定性引擎，比对期望出口与目标。 */
    private static void v8Samples(@NonNull PolicySpec policy, @NonNull String tenantId,
                                  @NonNull LocalDate today, @NonNull List<Finding> findings) {
        for (SampleSpec sample : policy.samples()) {
            RoutingInput input = sample.toInput(policy.inputSchema(), tenantId, today);
            RoutingDecision decision = RoutingEngine.decide(policy, input, sample.modelCandidate());
            if (decision.resolution() != sample.via()) {
                findings.add(new Finding("V8", "样本「" + sample.label() + "」期望 " + sample.via()
                        + "，实际 " + decision.resolution()
                        + (decision.error() == null ? "" : "（" + decision.error() + "）"), false));
                continue;
            }
            if (!java.util.Objects.equals(decision.finalTarget(), sample.expectTarget())) {
                findings.add(new Finding("V8", "样本「" + sample.label() + "」期望目标 "
                        + sample.expectTarget() + "，实际 " + decision.finalTarget(), false));
            }
        }
    }

    /** V9：每个<b>规则指向的</b>目标至少被一条样例命中（没有样例覆盖的目标等于没验过）。 */
    private static void v9SampleCoverage(@NonNull PolicySpec policy,
                                         @NonNull List<Finding> findings) {
        if (policy.rules().isEmpty()) {
            return; // SEMANTIC 且无规则：没有「规则指向的目标」需要覆盖，V9 空真
        }
        Set<String> sampled = new LinkedHashSet<>();
        for (SampleSpec sample : policy.samples()) {
            if (sample.via() == Resolution.RULE_MATCH) {
                sampled.add(sample.expectTarget());
            }
        }
        List<String> unproven = policy.targetsInOrder().stream()
                .filter(target -> !sampled.contains(target)).toList();
        if (!unproven.isEmpty()) {
            findings.add(new Finding("V9", "目标 " + unproven
                    + " 有规则指向但没有任何样本命中它：这条路径从未被验证过，"
                    + "而 V4-V6 只保证条件不矛盾、不保证它<b>能</b>命中", false));
        }
    }

    // ── 辅助 ───────────────────────────────────────────────────────

    /** 把永假的原因翻成人话：找出互为矛盾的那一对原子或同键不同常量。 */
    private static @NonNull String explainContradiction(@NonNull Predicate predicate) {
        Dnf dnf = DnfExpander.toDnf(predicate);
        for (Dnf.Clause clause : dnf.clauses()) {
            Map<String, String> equalities = new LinkedHashMap<>();
            Set<String> atoms = new LinkedHashSet<>();
            for (Dnf.Literal literal : clause.literals()) {
                String polarityKey = (literal.negated() ? "!" : "+") + literal.atom();
                if (!atoms.add(polarityKey)) {
                    continue;
                }
                String opposite = (literal.negated() ? "+" : "-") + literal.atom();
                if (atoms.contains(opposite)) {
                    return "同一原子既要求成立又要求不成立：" + literal.atom();
                }
                if (!literal.negated() && literal.equalityValue() != null) {
                    String previous = equalities.put(literal.key(), literal.equalityValue());
                    if (previous != null && !previous.equals(literal.equalityValue())) {
                        return "同一字段被要求同时等于 " + previous + " 与 " + literal.equalityValue()
                                + "：" + literal.key();
                    }
                }
            }
        }
        return "展开后没有任何可满足的子句";
    }

    private static boolean containsScalar(@NonNull List<Object> values, @NonNull Object candidate) {
        for (Object value : values) {
            if (sameScalar(value, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameScalar(@Nullable Object left, @NonNull Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    /** 供调用方打印的报告文本（稳定码在前，便于按码检索）。 */
    public static @NonNull String render(@NonNull Report report) {
        StringBuilder sb = new StringBuilder();
        report.findings().forEach(finding -> sb
                .append(finding.warning() ? "[警告 " : "[拒绝 ")
                .append(finding.code()).append("] ")
                .append(finding.message()).append('\n'));
        return sb.toString();
    }
}
