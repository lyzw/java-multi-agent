package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发布期守卫 V3-V10 测试（10 号设计 §5，§9 S2 出口条件「十条守卫各有用例」）。
 *
 * <p><b>本类证明的是设计里最容易被省掉的那半件事</b>：只做求值器，
 * 「写错的条件」会从配置期推迟到线上，而它的症状不是报错，是<b>策略经常走兜底</b>。
 * 所以每条守卫的断言都不停在「报了」，还要报得<b>能指导改哪一行</b>。
 *
 * <p>⚠️ V1（文法与上限）、V2（目标 ∈ 父声明）<b>不在本类</b>：
 * 它们在 {@link PredicateCodec} / {@link PolicyCodec} / {@link PolicySpec} 的构造期就守住了，
 * 这里只用 {@link #illegalPolicyNeverReachesTheGuards} 锁住「构造不出对象」这个事实。
 */
class PublishGuardsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final String TENANT = "t-a";

    /** 词汇表：topic 有枚举、orderId 字符串、amount 整数、tags 字符串数组。 */
    private static final String SCHEMA = """
            {"type":"object","required":["topic"],"additionalProperties":"false",
             "properties":{
               "topic":{"type":"string","enum":["order_status","refund","policy"]},
               "orderId":{"type":"string"},
               "amount":{"type":"integer"},
               "tags":{"type":"array","items":{"type":"string"}}}}
            """;

    // ── 干净策略 ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 三条规则 + 三个样本的 RULES_ONLY 策略：一条发现都没有")
    void cleanPolicyPassesAllGuards() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-refund","target":"refund-agent",
                  "when":{"eq":["in.topic","refund"]}},
                 {"ruleId":"r-status","target":"order-query",
                  "when":{"and":[{"eq":["in.topic","order_status"]},
                                  {"startsWith":["in.orderId","SO-"]}]}},
                 {"ruleId":"r-faq","target":"faq-answer",
                  "when":{"eq":["in.topic","policy"]}}]
                """, """
                [{"label":"退款走退款子","input":{"topic":"refund"},"expectTarget":"refund-agent"},
                 {"label":"带单号走查询子","input":{"topic":"order_status","orderId":"SO-1"},
                  "expectTarget":"order-query"},
                 {"label":"政策咨询走问答子","input":{"topic":"policy"},"expectTarget":"faq-answer"}]
                """, null, Set.of("refund-agent", "order-query", "faq-answer"), Set.of()));

        assertThat(report.findings())
                .as("合法策略不该被误拒：误拒会让配置者绕过守卫而不是修正策略")
                .isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    // ── V3 词汇表对齐 ───────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ V3：引用了 input_schema 没声明的路径 ⇒ 拒（字段名写错=规则白写）")
    void undeclaredPathIsRejected() {
        PublishGuards.Report report = check(oneRule("""
                {"and":[{"eq":["in.topic","refund"]},{"eq":["in.oderId","SO-1"]}]}"""));

        assertThat(codes(report)).contains("V3");
        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V3");
            assertThat(finding.message())
                    .as("要把写错的路径点名，否则配置者只能一条条猜")
                    .contains("in.oderId").contains("未声明的路径");
        });
        assertThat(report.publishable()).isFalse();
    }

    @Test
    @DisplayName("⚠️ V3：字面量不在字段枚举词汇内 ⇒ 拒（这是永假条件，不是不匹配）")
    void literalOutsideEnumIsRejected() {
        PublishGuards.Report report = check(oneRule("""
                {"eq":["in.topic","shipping"]}"""));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V3");
            assertThat(finding.message()).contains("in.topic").contains("不在其中").contains("shipping");
        });
    }

    @Test
    @DisplayName("⚠️ V3：整数字段与字符串字面量比较 ⇒ 拒（运行期只会 ERROR，等于规则永不成立）")
    void typeMismatchWithLiteralIsRejected() {
        PublishGuards.Report report = check(oneRule("""
                {"eq":["in.amount","100"]}"""));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V3");
            assertThat(finding.message()).contains("in.amount").contains("不相容");
        });
    }

    @Test
    @DisplayName("⚠️ V3：in 用在数组字段上、anyOf 用在标量字段上 ⇒ 都拒（算子与字段种类错配）")
    void membershipOperatorMustMatchFieldArity() {
        PublishGuards.Report arrayWithIn = check(oneRule("""
                {"in":["in.tags",["vip"]]}"""));
        PublishGuards.Report scalarWithAnyOf = check(oneRule("""
                {"anyOf":["in.topic",["refund"]]}"""));

        assertThat(arrayWithIn.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V3");
            assertThat(finding.message()).contains("anyOf");
        });
        assertThat(scalarWithAnyOf.findings()).anySatisfy(finding ->
                assertThat(finding.message()).contains("必须是数组字段"));
    }

    @Test
    @DisplayName("⚠️ V3：数值型 ctx 与字符串字面量比较 ⇒ 拒")
    void ctxTypeMustMatchLiteral() {
        PublishGuards.Report report = check(oneRule("""
                {"eq":["ctx.run.attempt","2"]}"""));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V3");
            assertThat(finding.message()).contains("ctx.run.attempt").contains("NUMBER");
        });
    }

    // ── V4 永假 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ V4：同一字段要求等于两个常量 ⇒ 该规则永假，拒")
    void contradictoryRuleIsRejected() {
        PublishGuards.Report report = check(oneRule("""
                {"and":[{"eq":["in.topic","refund"]},{"eq":["in.topic","policy"]}]}"""));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V4");
            assertThat(finding.message())
                    .as("要说清是哪一对照着改，而不是只说「永假」")
                    .contains("永假").contains("同时等于");
        });
        assertThat(report.publishable()).isFalse();
    }

    @Test
    @DisplayName("⚠️ V4：同一原子既正又反 ⇒ 也判永假（互补原子是另一类矛盾）")
    void complementaryAtomIsContradictory() {
        PublishGuards.Report report = check(oneRule("""
                {"and":[{"eq":["in.topic","refund"]},
                         {"not":{"eq":["in.topic","refund"]}}]}"""));

        assertThat(codes(report)).contains("V4");
    }

    // ── V5 遮蔽 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ V5：前条成立时后条必成立 ⇒ 后条永不执行，拒（「改了没效果」的物理解释）")
    void shadowedRuleIsRejected() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-broad","target":"refund-agent","when":{"eq":["in.topic","refund"]}},
                 {"ruleId":"r-narrow","target":"order-query",
                  "when":{"and":[{"eq":["in.topic","refund"]},{"startsWith":["in.orderId","SO-"]}]}}]
                """, "[]", null, Set.of("refund-agent", "order-query"), Set.of()));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V5");
            assertThat(finding.message())
                    .as("被遮蔽的那条要被点名：只有它才是死代码")
                    .contains("r-narrow").contains("遮蔽");
        });
    }

    @Test
    @DisplayName("✅ V5 不误报：前条自己永假时不构成遮蔽（它也不会命中）")
    void shadowingIsSkippedWhenEarlierRuleIsUnsatisfiable() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-dead","target":"refund-agent",
                  "when":{"and":[{"eq":["in.topic","refund"]},{"eq":["in.topic","policy"]}]}},
                 {"ruleId":"r-live","target":"order-query","when":{"eq":["in.topic","order_status"]}}]
                """, "[]", null, Set.of("refund-agent", "order-query"), Set.of()));

        assertThat(codes(report))
                .as("只报 V4（那条确实永假），不给同一条因再叠一条 V5")
                .contains("V4").doesNotContain("V5");
    }

    // ── V6 死目标 ───────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ V6：RULES_ONLY 下某个可委派目标无路可达 ⇒ 拒")
    void deadTargetUnderRulesOnlyIsRejected() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-refund","target":"refund-agent","when":{"eq":["in.topic","refund"]}}]
                """, "[]", null, Set.of("refund-agent", "order-query"), Set.of()));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V6");
            assertThat(finding.message()).contains("order-query");
        });
    }

    @Test
    @DisplayName("✅ V6 只在 RULES_ONLY 判：RULE_FIRST 下语义引擎能到达目标，不算死")
    void deadTargetIsNotReportedWhenSemanticsCanReachIt() {
        PublishGuards.Report report = check(policy("RULE_FIRST", """
                [{"ruleId":"r-refund","target":"refund-agent","when":{"eq":["in.topic","refund"]}}]
                """, "[]", "faq-answer", Set.of("refund-agent", "order-query", "faq-answer"),
                Set.of()));

        assertThat(codes(report)).doesNotContain("V6");
    }

    // ── V7 必选子覆盖（警告） ────────────────────────────────────────

    @Test
    @DisplayName("⚠️ V7：必选子没有规则覆盖 ⇒ <b>警告但不拦</b>（RULE_FIRST 下语义可达是合法的）")
    void requiredTargetWithoutRuleIsWarningOnly() {
        PublishGuards.Report report = check(policy("RULE_FIRST", """
                [{"ruleId":"r-refund","target":"refund-agent","when":{"eq":["in.topic","refund"]}}]
                """, """
                [{"label":"退款走退款子","input":{"topic":"refund"},"expectTarget":"refund-agent"}]
                """, "faq-answer", Set.of("refund-agent", "order-query", "faq-answer"),
                Set.of("order-query")));

        assertThat(report.warnings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V7");
            assertThat(finding.warning()).isTrue();
            assertThat(finding.message()).contains("order-query");
        });
        assertThat(report.violations())
                .as("警告不得混进拒绝：混了就只能靠「全都改成警告」来绕过，那是更坏的结果")
                .isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    // ── V8 样本回放 ─────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ V8：样本期望与引擎实际出口不符 ⇒ 拒（样本走的就是线上那台引擎）")
    void sampleExpectationMismatchIsRejected() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-status","target":"order-query",
                  "when":{"and":[{"eq":["in.topic","order_status"]},{"startsWith":["in.orderId","SO-"]}]}}]
                """, """
                [{"label":"以为能命中","input":{"topic":"order_status"},"expectTarget":"order-query"}]
                """, null, Set.of("order-query"), Set.of()));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V8");
            assertThat(finding.message())
                    .as("缺 orderId 让该条 ERROR→SKIP，实际出口是 NO_MATCH_REJECTED，报告要如实说")
                    .contains("以为能命中").contains("NO_MATCH_REJECTED");
        });
    }

    @Test
    @DisplayName("✅ V8：显式写 null 的样本字段能验「判缺失」的规则（null 不是解析错误）")
    void sampleCanDeclareExplicitNullForPresenceCheck() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-no-tags","target":"order-query","when":{"null":["in.tags"]}}]
                """, """
                [{"label":"没打标签的走查询子","input":{"topic":"refund","tags":null},
                  "expectTarget":"order-query"}]
                """, null, Set.of("order-query"), Set.of()));

        assertThat(report.findings())
                .as("null(...) 是全文法里唯一「缺失也算数」的算子，样本必须能表达它")
                .isEmpty();
    }

    // ── V9 样本覆盖率 ───────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ V9：有规则指向的目标却没有任何样本命中 ⇒ 拒（V4-V6 只保证不矛盾，不保证能命中）")
    void ruleTargetWithoutSampleIsRejected() {
        PublishGuards.Report report = check(policy("RULES_ONLY", """
                [{"ruleId":"r-refund","target":"refund-agent","when":{"eq":["in.topic","refund"]}},
                 {"ruleId":"r-status","target":"order-query","when":{"eq":["in.topic","order_status"]}}]
                """, """
                [{"label":"只验了退款","input":{"topic":"refund"},"expectTarget":"refund-agent"}]
                """, null, Set.of("refund-agent", "order-query"), Set.of()));

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("V9");
            assertThat(finding.message()).contains("order-query").doesNotContain("refund-agent");
        });
    }

    // ── V10 DNF 预算 ────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ V10：DNF 展开积超预算 ⇒ <b>报稳定码而不是抛异常</b>（降级判定比拒绝更坏）")
    void dnfOverBudgetIsReportedNotThrown() {
        // 3 个 17 项的 in 相与：展开积 17³=4913 > 4096。项必须互不相同，
        // 否则去重后根本不消耗预算 —— 那这条用例就只测了个「预算没用掉」。
        StringBuilder rules = new StringBuilder(
                "[{\"ruleId\":\"r-big\",\"target\":\"refund-agent\",\"when\":{\"and\":[");
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                rules.append(',');
            }
            rules.append("{\"in\":[\"in.f").append(group).append("\",[");
            for (int item = 0; item < 17; item++) {
                rules.append(item > 0 ? "," : "").append('"').append(group).append('-').append(item).append('"');
            }
            rules.append("]]}");
        }
        rules.append("]}}]");

        PublishGuards.Report report = check(policy("RULES_ONLY", rules.toString(), "[]", null,
                Set.of("refund-agent"), Set.of()));

        assertThat(codes(report)).as("超预算必须被 V10 兜住，而不是让守卫半路抛出去").contains("V10");
        assertThat(report.findings()).anySatisfy(finding ->
                assertThat(finding.message()).contains("DNF 展开积超过预算"));
    }

    // ── 边界：构造期已经守住的，不在守卫里重抄 ────────────────────────

    @Test
    @DisplayName("✅ V1/V2 在构造期：非法策略对象根本造不出来，守卫无需重抄")
    void illegalPolicyNeverReachesTheGuards() {
        assertThatThrownBy(() -> oneRule("{\"unknownop\":[\"in.topic\",\"refund\"]}"))
                .isInstanceOf(RoutingConfigException.class);
        assertThatThrownBy(() -> policy("RULES_ONLY", """
                [{"ruleId":"r","target":"not-declared-by-parent","when":{"const":true}}]
                """, "[]", null, Set.of("refund-agent"), Set.of()))
                .isInstanceOf(RoutingConfigException.class)
                .hasMessageContaining("可委派目标");
    }

    @Test
    @DisplayName("✅ 报告文本把稳定码放在最前（按码检索是排查的第一动作）")
    void renderPutsStableCodeFirst() {
        PublishGuards.Report report = check(oneRule("""
                {"and":[{"eq":["in.topic","refund"]},{"eq":["in.topic","policy"]}]}"""));

        assertThat(PublishGuards.render(report)).startsWith("[拒绝 V4]");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 只有一条规则（指向 refund-agent）的策略，用于逐条守卫的最小用例。 */
    private static @NonNull PolicySpec oneRule(@NonNull String when) {
        return policy("RULES_ONLY", """
                [{"ruleId":"r-one","target":"refund-agent","when":%s}]
                """.formatted(when), "[]", null, Set.of("refund-agent"), Set.of());
    }

    private static PublishGuards.@NonNull Report check(@NonNull PolicySpec policy) {
        return PublishGuards.check(policy, TENANT, DAY);
    }

    private static @NonNull List<String> codes(PublishGuards.@NonNull Report report) {
        return report.findings().stream().map(PublishGuards.Finding::code).toList();
    }

    /**
     * 组装策略文本。
     *
     * <p>{@code defaultTarget} 非空时 {@code noMatchAction} 取 {@code DEFAULT_TARGET}，
     * 为空时取 {@code REJECT} —— 两者本来就互斥，测试里不再给第二个自由度。
     */
    private static @NonNull PolicySpec policy(@NonNull String mode, @NonNull String rules,
                                              @NonNull String samples, @Nullable String defaultTarget,
                                              @NonNull Set<String> allowedTargets,
                                              @NonNull Set<String> requiredTargets) {
        StringBuilder json = new StringBuilder();
        json.append("{\"policyId\":\"guard-fixture\",\"version\":1,")
                .append("\"mode\":\"").append(mode).append("\",")
                .append("\"inputSchema\":").append(SCHEMA).append(',')
                .append("\"evaluation\":").append(rules).append(',');
        if (defaultTarget != null) {
            json.append("\"defaultTarget\":\"").append(defaultTarget).append("\",")
                    .append("\"noMatchAction\":\"DEFAULT_TARGET\",");
        } else {
            json.append("\"noMatchAction\":\"REJECT\",");
        }
        json.append("\"sampleSet\":").append(samples).append('}');
        return PolicyCodec.parse(json.toString(), allowedTargets, requiredTargets);
    }
}
