package cn.studykid.multiagent.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * dry-run 用例测试（10 号设计 §7 债 5 · 管理台 A7-1b 的后端半边）。
 *
 * <p><b>本类验证的三件事</b>：
 * <ol>
 *   <li><b>同一份实现</b>：草稿文本走 {@link PolicyCodec#parseStandalone} +
 *       {@link PublishGuards} + {@link RoutingEngine}，
 *       结论与发布校验、运行期决策同源（fixture 也刻意<b>只</b>用文本，不手工造对象）；</li>
 *   <li><b>不合法的东西不会被静默放过</b>：策略解析不过 → 只有 {@code policyIssue}；
 *       探针列表不合法 → 只有那一条 issue，而不是「少跑几条、看起来还是绿的」；</li>
 *   <li><b>身份敞口不重开</b>：探针声明 {@code ctx.tenantId} 必须被拒（G-6 同口径）。</li>
 * </ol>
 */
class RoutingDryRunTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final String TENANT = "t-a";

    /** 与引擎测试同款 fixture：三条规则 + 兜底 faq-answer。 */
    private static final String POLICY = """
            {
              "policyId": "orders-router",
              "version": 3,
              "mode": "MODE",
              "inputSchema": {
                "type": "object", "required": ["topic"], "additionalProperties": "false",
                "properties": {
                  "topic": {"type": "string", "enum": ["order_status", "refund", "policy"]},
                  "orderId": {"type": "string"},
                  "tags": {"type": "array", "items": {"type": "string"}}
                }
              },
              "evaluation": [
                {"ruleId": "r-order-status", "target": "order-query",
                 "when": {"and": [{"eq": ["in.topic", "order_status"]},
                                   {"startsWith": ["in.orderId", "SO-"]}]}},
                {"ruleId": "r-refund", "target": "refund-agent",
                 "when": {"eq": ["in.topic", "refund"]}},
                {"ruleId": "r-any", "target": "order-query",
                 "when": {"null": ["in.tags"]}}
              ],
              "defaultTarget": "faq-answer",
              "noMatchAction": "NOMATCH",
              "onConflict": "CONFLICT",
              "sampleSet": []
            }
            """;

    private static String policy(String mode, String noMatch, String conflict) {
        return POLICY.replace("\"MODE\"", '"' + mode + '"')
                .replace("\"NOMATCH\"", '"' + noMatch + '"')
                .replace("\"CONFLICT\"", '"' + conflict + '"');
    }

    private static RoutingDryRun.Outcome run(String definitionJson, String probesJson) {
        return RoutingDryRun.run(definitionJson, TENANT, DAY, probesJson);
    }

    @Test
    @DisplayName("✅ 探针过的是运行期同一个引擎：命中哪条规则、走哪个目标、轨迹怎么记")
    void probeHitsTheSameEngineAsRuntime() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), """
                [{"label": "查订单", "input": {"topic": "order_status", "orderId": "SO-1"}}]
                """);

        assertThat(outcome.parsed()).isTrue();
        assertThat(outcome.probes()).hasSize(1);
        RoutingDryRun.ProbeOutcome probe = outcome.probes().getFirst();
        assertThat(probe.decided()).isTrue();
        assertThat(probe.decision().resolution()).isEqualTo(Resolution.RULE_MATCH);
        assertThat(probe.decision().finalTarget()).isEqualTo("order-query");
        assertThat(probe.decision().matchedRuleId()).isEqualTo("r-order-status");
        assertThat(probe.decision().trace()).singleElement()
                .extracting(EvaluationTraceEntry::ruleId).isEqualTo("r-order-status");
    }

    @Test
    @DisplayName("✅ 回显<b>投影后</b>的输入：字段名写错不报错，但它会悄悄改走别的规则")
    void probeEchoesProjectedInputOnly() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), """
                [{"label": "字段名拼错", "input": {"topicc": "refund", "orderId": "SO-1"}}]
                """);

        Map<String, Object> projected = outcome.probes().getFirst().projectedInput();
        assertThat(projected)
                .as("topicc 不在词汇表里，不进求值视野 —— 回显的是投影<b>之后</b>的那几个字段")
                .doesNotContainKey("topicc")
                .containsEntry("orderId", "SO-1");
        RoutingDecision decision = outcome.probes().getFirst().decision();
        assertThat(decision.matchedRuleId())
                .as("本想命中退款规则，实际被「tags 为空」那条兜底规则接走 —— "
                        + "字段名写错<b>不报错</b>，这正是 V3 与投影回显要治的形态")
                .isEqualTo("r-any");
        assertThat(decision.finalTarget()).isEqualTo("order-query");
        assertThat(decision.trace()).extracting(EvaluationTraceEntry::ruleId)
                .containsExactly("r-order-status", "r-refund", "r-any");
    }

    @Test
    @DisplayName("✅ 探针可模拟模型候选，越出策略点名的目标集时给出 TARGET_NOT_ALLOWED（C38-2）")
    void probeCanSimulateModelCandidate() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "REJECT", "RULE_WINS"), """
                [{"label": "模型提议拓扑内、策略外的目标",
                  "input": {"topic": "policy", "tags": ["vip"]},
                  "modelCandidate": "escalation-agent"}]
                """);

        RoutingDecision decision = outcome.probes().getFirst().decision();
        assertThat(decision.resolution()).isEqualTo(Resolution.TARGET_NOT_ALLOWED);
        assertThat(decision.hasTarget()).isFalse();
        assertThat(decision.candidateFromModel()).isEqualTo("escalation-agent");
        assertThat(decision.error()).contains("escalation-agent");
    }

    @Test
    @DisplayName("⛔ 策略文本解析不过：只给 policyIssue，守卫与探针一律不跑")
    void unparsablePolicyYieldsNoVerdicts() {
        RoutingDryRun.Outcome outcome = run(
                POLICY.replace("noMatchAction", "noMatch"), """
                [{"label": "任意", "input": {"topic": "refund"}}]
                """);

        assertThat(outcome.parsed()).isFalse();
        assertThat(outcome.publishable()).isFalse();
        assertThat(outcome.policyIssue().message()).contains("未定义的键");
        assertThat(outcome.guards().findings())
                .as("没有词汇表就没有守卫可跑 —— 对一份根本不合法的草稿给出「命中」结论是骗人")
                .isEmpty();
        assertThat(outcome.probes()).isEmpty();
    }

    @Test
    @DisplayName("⛔ 探针含未知键：整列表拒绝并回显原因，而不是默默少跑几条")
    void probeWithUnknownKeyIsRejected() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), """
                [{"label": "写错字段", "input": {"topic": "refund"}, "expectTarget": "refund-agent"}]
                """);

        RoutingDryRun.ProbeOutcome probe = outcome.probes().getFirst();
        assertThat(outcome.probes()).hasSize(1);
        assertThat(probe.decided()).isFalse();
        assertThat(probe.issue().message()).contains("未定义的键");
        assertThat(outcome.parsed())
                .as("探针不合法不牵连守卫报告 —— 「能不能发布」与「这条输入走到哪」是两个问题")
                .isTrue();
    }

    @Test
    @DisplayName("⛔ 探针不得自带租户：dry-run 也是租户身份可达的入口，G-6 不在这里重开")
    void probeCannotDeclareItsOwnTenant() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), """
                [{"label": "伪造租户", "input": {"topic": "refund"},
                  "ctx": {"tenantId": "t-victim"}}]
                """);

        assertThat(outcome.probes().getFirst().decided()).isFalse();
        assertThat(outcome.probes().getFirst().issue().message())
                .contains("ctx.tenantId");
    }

    @Test
    @DisplayName("⛔ 探针条数有界：超限整列表拒绝，不做「截断前 N 条」那种静默降级")
    void probeCountIsBounded() {
        StringBuilder probes = new StringBuilder("[");
        IntStream.range(0, RoutingDryRun.MAX_PROBES + 1).forEach(i -> {
            if (i > 0) {
                probes.append(',');
            }
            probes.append("{\"input\": {\"topic\": \"refund\"}}");
        });
        probes.append(']');

        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), probes.toString());

        assertThat(outcome.probes()).hasSize(1);
        assertThat(outcome.probes().getFirst().issue().message())
                .contains("最多 " + RoutingDryRun.MAX_PROBES);
    }

    @Test
    @DisplayName("✅ 缺省标签由序号补出：面板不必强制每条探针都起名")
    void probeLabelDefaultsToIndex() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"), """
                [{"input": {"topic": "refund"}}, {"input": {"topic": "policy"}}]
                """);

        assertThat(outcome.probes()).extracting(RoutingDryRun.ProbeOutcome::label)
                .containsExactly("探针#1", "探针#2");
        assertThat(outcome.probes().get(1).decision().finalTarget())
                .as("第 3 条规则要求 tags 为 null；这里没给 tags，判空成立")
                .isEqualTo("order-query");
    }

    @Test
    @DisplayName("✅ 守卫报告与试跑同时给出：没样本覆盖规则目标时 V9 拦住发布")
    void guardReportIsGivenAlongsideProbes() {
        RoutingDryRun.Outcome outcome = run(
                policy("RULES_ONLY", "REJECT", "RULE_WINS"), """
                [{"label": "查订单", "input": {"topic": "order_status", "orderId": "SO-1"}}]
                """);

        assertThat(outcome.publishable())
                .as("sampleSet 是空的：V9 判「有规则指向但从未被样本命中」")
                .isFalse();
        assertThat(outcome.guards().violations()).extracting(PublishGuards.Finding::code)
                .contains("V9");
        assertThat(outcome.probes().getFirst().decision().resolution())
                .as("探针照样给结论 —— dry-run 的价值正是「提交之前就知道」")
                .isEqualTo(Resolution.RULE_MATCH);
    }

    @Test
    @DisplayName("✅ 回显回放用的 UTC 日：引用 ctx.now.utcDate 的策略结论取决于它")
    void replayDateIsEchoed() {
        RoutingDryRun.Outcome outcome = RoutingDryRun.run(
                policy("RULES_ONLY", "REJECT", "RULE_WINS"), TENANT,
                LocalDate.of(2026, 3, 1), "[]");

        assertThat(outcome.replayDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(outcome.probes())
                .as("没有探针时只出守卫报告，不编造结论")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ ctx 覆盖项在探针里可用：固定渠道即可复现按渠道分流的规则")
    void probeCanFixContext() {
        String byChannel = """
                {
                  "policyId": "channel-router", "version": 1, "mode": "RULES_ONLY",
                  "inputSchema": {"type": "object", "additionalProperties": "false",
                                  "properties": {"topic": {"type": "string"}}},
                  "evaluation": [
                    {"ruleId": "r-app", "target": "app-agent",
                     "when": {"eq": ["ctx.channel", "app"]}},
                    {"ruleId": "r-other", "target": "web-agent",
                     "when": {"null": ["ctx.channel"]}}
                  ],
                  "noMatchAction": "REJECT", "sampleSet": []
                }
                """;

        RoutingDryRun.Outcome outcome = run(byChannel, """
                [{"label": "渠道=app", "input": {"topic": "x"}, "ctx": {"channel": "app"}},
                 {"label": "没给渠道", "input": {"topic": "x"}}]
                """);

        assertThat(outcome.probes()).extracting(p -> p.decision().finalTarget())
                .containsExactly("app-agent", "web-agent");
        assertThat(outcome.probes().stream().map(RoutingDryRun.ProbeOutcome::decided)
                .toList()).isEqualTo(List.of(true, true));
    }
}
