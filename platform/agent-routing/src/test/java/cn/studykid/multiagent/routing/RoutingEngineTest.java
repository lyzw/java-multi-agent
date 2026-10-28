package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.RoutingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 决策引擎测试（10 号设计 §6 的五个阶段中 ②③④ 的行为证明）。
 *
 * <p><b>策略 fixture 走 {@link PolicyCodec}</b>而不是手工拼装：
 * 引擎读的是解析结果，如果 fixture 绕过解析器，就验不出
 * 「策略文本 → 对象」这一步与运行期用的是同一条路径。
 */
class RoutingEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final String TENANT = "t-a";

    /** 三条规则：状态查询（需订单号）、退款、渠道兜底；默认目标 faq-answer。 */
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
                 "when": {"eq": ["in.topic", "refund"]}, "onError": "REJECT"},
                {"ruleId": "r-any", "target": "order-query",
                 "when": {"null": ["in.tags"]}}
              ],
              "defaultTarget": "faq-answer",
              "noMatchAction": "NOMATCH",
              "onConflict": "CONFLICT",
              "sampleSet": []
            }
            """;

    // ── 阶段② 规则命中 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ 规则按 ordinal 升序求值，首个 TRUE 即命中并短路")
    void firstTrueRuleWins() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "order_status", "orderId", "SO-000001"));

        assertThat(decision.resolution()).isEqualTo(Resolution.RULE_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("order-query");
        assertThat(decision.matchedRuleId()).isEqualTo("r-order-status");
        assertThat(decision.trace())
                .as("短路后未执行的规则不得进轨迹，否则重放报告会谎称「判过」")
                .containsExactly(new EvaluationTraceEntry(1, "r-order-status",
                        EvaluationTraceEntry.OUTCOME_MATCH, null));
    }

    @Test
    @DisplayName("✅ 未命中的规则在轨迹里留 NO_MATCH，可回答「为什么没走这条」")
    void missedRulesAreTraced() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "refund"));

        assertThat(decision.matchedRuleId()).isEqualTo("r-refund");
        assertThat(decision.trace()).extracting(EvaluationTraceEntry::outcome)
                .containsExactly(EvaluationTraceEntry.OUTCOME_NO_MATCH,
                        EvaluationTraceEntry.OUTCOME_MATCH);
    }

    @Test
    @DisplayName("✅ 全部未命中 + DEFAULT_TARGET ⇒ 走兜底，且轨迹记满")
    void noMatchFallsBackToDefault() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "policy", "orderId", "X-1", "tags", List.of("vip")));

        assertThat(decision.resolution()).isEqualTo(Resolution.DEFAULT_TARGET);
        assertThat(decision.finalTarget()).isEqualTo("faq-answer");
        assertThat(decision.trace()).hasSize(3);
    }

    @Test
    @DisplayName("✅ 全部未命中 + REJECT ⇒ 失败且<b>没有</b>目标")
    void noMatchRejectsWhenConfigured() {
        RoutingDecision decision = decide(policy("RULES_ONLY", "REJECT", "RULE_WINS"),
                Map.of("topic", "policy", "orderId", "X-1", "tags", List.of("vip")));

        assertThat(decision.resolution()).isEqualTo(Resolution.NO_MATCH_REJECTED);
        assertThat(decision.hasTarget()).isFalse();
    }

    // ── 阶段③ 语义引擎 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ RULE_FIRST 且规则无命中时，才采纳模型候选")
    void ruleFirstUsesModelCandidateOnlyOnNoMatch() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "policy", "orderId", "X-1", "tags", List.of("vip")),
                "faq-answer");

        assertThat(decision.resolution()).isEqualTo(Resolution.SEMANTIC_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("faq-answer");
    }

    @Test
    @DisplayName("⚠️⚠️ RULES_ONLY 不消费模型候选：Schema 都不发 candidates，引擎也不听")
    void rulesOnlyIgnoresModelCandidate() {
        RoutingDecision decision = decide(policy("RULES_ONLY", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "refund"), "some-other-agent");

        assertThat(decision.resolution()).isEqualTo(Resolution.RULE_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("refund-agent");
        assertThat(decision.candidateFromModel())
                .as("候选记为 null：该模式下模型<b>没有</b>选择权，不能留下「它选了但没采纳」的暧昧记录")
                .isNull();
    }

    @Test
    @DisplayName("✅ SEMANTIC 模式不判规则，目标即模型候选")
    void semanticModeSkipsRules() {
        // SEMANTIC 下 evaluation 允许为空，这里用只带兜底的形态验证「规则不参与判定」。
        PolicySpec spec = policy("SEMANTIC", "REJECT", "RULE_WINS");
        RoutingDecision decision = decide(spec, Map.of("topic", "order_status",
                "orderId", "SO-000001"), "refund-agent");

        assertThat(decision.resolution()).isEqualTo(Resolution.SEMANTIC_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("refund-agent");
        assertThat(decision.trace()).as("SEMANTIC 不跑规则引擎，轨迹必须为空")
                .isEmpty();
    }

    // ── 阶段④ 冲突裁决 ──────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ RULE_WINS：规则命中<b>不被</b>模型候选改写，只记冲突")
    void ruleWinsKeepsDecisionAndRecordsConflict() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "refund"), "faq-answer");

        assertThat(decision.resolution()).isEqualTo(Resolution.RULE_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("refund-agent");
        assertThat(decision.conflict())
                .as("冲突必须留痕：规则与语义长期不一致是配置缺陷的信号，不是常态")
                .isTrue();
    }

    @Test
    @DisplayName("✅ CANDIDATE_WINS：采纳模型侧，但轨迹仍是规则的真实判定")
    void candidateWinsAdoptsModel() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "CANDIDATE_WINS"),
                Map.of("topic", "refund"), "faq-answer");

        assertThat(decision.resolution()).isEqualTo(Resolution.SEMANTIC_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("faq-answer");
        assertThat(decision.conflict()).isTrue();
        assertThat(decision.trace()).isNotEmpty();
    }

    @Test
    @DisplayName("✅ 候选与规则一致时不算冲突")
    void sameTargetIsNotConflict() {
        RoutingDecision decision = decide(policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS"),
                Map.of("topic", "refund"), "refund-agent");

        assertThat(decision.conflict()).isFalse();
    }

    // ── 策略收窄（MA-T05a）──────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ MA-T05a：候选在拓扑内、策略外 ⇒ {@code TARGET_NOT_ALLOWED}，且<b>不产生目标</b>")
    void candidateOutsidePolicyWorkbookIsRejected() {
        RoutingDecision decision = decide(
                policyWithExtraTarget("RULE_FIRST", "REJECT", "RULE_WINS"),
                Map.of("topic", "policy", "orderId", "X-1", "tags", List.of("vip")),
                "escalation-agent");

        assertThat(decision.resolution()).isEqualTo(Resolution.TARGET_NOT_ALLOWED);
        assertThat(decision.hasTarget())
                .as("不拦的代价是「委派给一个策略从没点名的 Agent」，而它照样在父声明里、过得了准入")
                .isFalse();
        assertThat(decision.candidateFromModel()).isEqualTo("escalation-agent");
        assertThat(decision.resolution().reasonCode())
                .as("失败码必须映射到对外原因码，否则委派会带着失败结论继续跑")
                .isEqualTo(ReasonCode.ROUTING_TARGET_NOT_ALLOWED);
        assertThat(Resolution.TARGET_NOT_ALLOWED.isFailure()).isTrue();
    }

    @Test
    @DisplayName("✅ 采纳出口只有一个：{@code CANDIDATE_WINS} 的改判同样过策略点名集")
    void candidateWinsGoesThroughTheSameNarrowing() {
        // r-refund 命中（目标 refund-agent），候选越出策略。判据落在采纳的收口点上，
        // 所以「哪一侧赢」不影响「赢的那一侧要不要在策略里」—— 两个调用点不会各判各的。
        RoutingDecision decision = decide(
                policyWithExtraTarget("RULE_FIRST", "DEFAULT_TARGET", "CANDIDATE_WINS"),
                Map.of("topic", "refund"), "escalation-agent");

        assertThat(decision.resolution()).isEqualTo(Resolution.TARGET_NOT_ALLOWED);
        assertThat(decision.conflict())
                .as("冲突事实照常留痕：被拒的是候选，不是规则命中这件事")
                .isTrue();
        assertThat(decision.matchedRuleId())
                .as("最终目标不是那条规则给的，matchedRuleId 必须为空")
                .isNull();
    }

    @Test
    @DisplayName("✅ 策略没点名任何目标（SEMANTIC 且无兜底）⇒ 不设收窄，只剩拓扑一道关")
    void policyWithoutDeclaredTargetsDoesNotNarrow() {
        PolicySpec spec = PolicyCodec.parse(MAPPER.readTree("""
                {
                  "policyId": "open-triage", "version": 1, "mode": "SEMANTIC",
                  "inputSchema": {"type": "object", "additionalProperties": "false",
                                  "properties": {"text": {"type": "string"}}},
                  "evaluation": [], "noMatchAction": "REJECT", "onConflict": "RULE_WINS",
                  "sampleSet": []
                }
                """), Set.of("order-query", "refund-agent"), Set.of());
        RoutingDecision decision = RoutingEngine.decide(spec,
                RoutingInput.of(spec.inputSchema(), Map.of("text", "任意问题"),
                        TENANT, null, null, 1, 0, DAY),
                "order-query");

        assertThat(spec.declaredTargets()).as("没规则也没兜底 ⇒ 没有可收窄的集合").isEmpty();
        assertThat(decision.resolution()).isEqualTo(Resolution.SEMANTIC_MATCH);
        assertThat(decision.finalTarget()).isEqualTo("order-query");
    }

    // ── 失败语义 ────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ onError=SKIP：缺失字段跳过本条继续判，且轨迹记 ERROR_SKIPPED")
    void skipKeepsEvaluatingAndRecordsError() {
        RoutingDecision decision = decide(policy("RULES_ONLY", "REJECT", "RULE_WINS"),
                // topic 必须先满足第 1 条的 eq，and 才会走到「缺 orderId」的 startsWith 上；
                // 换成别的 topic 会短路成 FALSE，这条用例就变成「测了一个不存在的 ERROR」。
                Map.of("topic", "order_status"));

        assertThat(decision.trace().get(0).outcome())
                .isEqualTo(EvaluationTraceEntry.OUTCOME_ERROR_SKIPPED);
        assertThat(decision.trace().get(0).error()).contains("in.orderId");
        assertThat(decision.resolution()).isEqualTo(Resolution.RULE_MATCH);
        assertThat(decision.matchedRuleId()).isEqualTo("r-any");
    }

    @Test
    @DisplayName("⚠️⚠️ onError=REJECT：求值异常中止整次路由，<b>不触发子执行</b>")
    void rejectStopsTheWholeDecision() {
        // 第 1 条要求 in.orderId 前缀匹配；输入没有 orderId ⇒ 求值 ERROR，而该条被配成 REJECT。
        PolicySpec spec = withOnError("REJECT");
        RoutingDecision decision = decide(spec, Map.of("topic", "order_status"));

        assertThat(decision.resolution()).isEqualTo(Resolution.EVAL_ERROR_REJECTED);
        assertThat(decision.matchedRuleId()).isEqualTo("r-order-status");
        assertThat(decision.hasTarget())
                .as("中止时必须<b>没有</b>目标：带着兜底目标继续跑等于把失败读成成功")
                .isFalse();
        assertThat(decision.trace()).hasSize(1);
        assertThat(decision.trace().get(0).outcome())
                .isEqualTo(EvaluationTraceEntry.OUTCOME_ERROR_REJECTED);
    }

    @Test
    @DisplayName("⚠️ onError=REJECT 的中止点在<b>出错的那条</b>：后面的规则不再求值")
    void rejectDoesNotEvaluateLaterRules() {
        PolicySpec spec = withOnError("REJECT");
        RoutingDecision decision = decide(spec, Map.of("topic", "order_status"));

        assertThat(decision.trace()).extracting(EvaluationTraceEntry::ordinal).containsExactly(1);
    }

    // ── 失败语义之外：结果不是异常 ──────────────────────────────────

    @Test
    @DisplayName("✅ 无命中且 REJECT 是<b>业务结果</b>（稳定码），引擎不抛异常")
    void engineNeverThrowsForValidatedPolicy() {
        PolicySpec spec = policy("RULES_ONLY", "REJECT", "RULE_WINS");
        RoutingDecision decision = decide(spec, Map.of("topic", "policy",
                "orderId", "X-1", "tags", List.of("vip")));

        assertThat(decision.resolution()).isEqualTo(Resolution.NO_MATCH_REJECTED);
        assertThat(decision.error()).contains("no_match_action=REJECT");
    }

    @Test
    @DisplayName("⚠️⚠️ {@code ERROR} 永不等于 {@code MATCH}：ERROR_SKIPPED 不会让规则命中")
    void errorNeverCountsAsMatch() {
        // 第 1 条 ERROR 被 SKIP，第 2、3 条 FALSE ⇒ 绝不能把这条记成 r-order-status 命中。
        PolicySpec spec = policy("RULES_ONLY", "REJECT", "RULE_WINS");
        RoutingDecision decision = decide(spec,
                Map.of("topic", "order_status", "tags", List.of("vip")));

        assertThat(decision.trace().get(0).outcome())
                .isEqualTo(EvaluationTraceEntry.OUTCOME_ERROR_SKIPPED);
        assertThat(decision.matchedRuleId())
                .as("跳过的规则不是命中规则：快照里若留下 matchedRuleId，重放会把「没判成」读成「判成了」")
                .isNull();
        assertThat(decision.resolution()).isEqualTo(Resolution.NO_MATCH_REJECTED);
        assertThat(decision.hasTarget()).isFalse();
        assertThat(Resolution.RULE_MATCH).isNotEqualTo(Resolution.EVAL_ERROR_SKIPPED);
    }

    @Test
    @DisplayName("⛔ 成功决策不带空目标、失败决策不带目标（构造期即拒）")
    void decisionShapeIsEnforced() {
        assertThatThrownBy(() -> new RoutingDecision(RoutingMode.RULES_ONLY,
                Resolution.RULE_MATCH, null, null, null, null, false, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成功决策必须带目标");

        assertThatThrownBy(() -> new RoutingDecision(RoutingMode.RULES_ONLY,
                Resolution.NO_MATCH_REJECTED, "faq-answer", null, null, null, false, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("失败决策不得带目标");
    }

    // ── 重放不变式 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 同 (策略, 规范化输入) 重放两次 ⇒ 同一目标、同一轨迹")
    void replayIsDeterministic() {
        PolicySpec spec = policy("RULE_FIRST", "DEFAULT_TARGET", "RULE_WINS");
        Map<String, Object> raw = Map.of("topic", "policy", "orderId", "X-9");

        RoutingDecision first = decide(spec, raw);
        RoutingDecision second = decide(spec, raw);

        assertThat(second).isEqualTo(first);
        assertThat(first.finalTarget()).isEqualTo(second.finalTarget());
    }

    @Test
    @DisplayName("✅ 日粒度时间词汇：同一自然日内可重放；<b>跨日可以不同</b>（§4.4 的取舍）")
    void dateAwareRuleIsDayReplayable() {
        PolicySpec spec = parse(POLICY
                .replace("\"MODE\"", "\"RULES_ONLY\"")
                .replace("\"NOMATCH\"", "\"REJECT\"")
                .replace("\"CONFLICT\"", "\"RULE_WINS\"")
                .replace("{\"ruleId\": \"r-order-status\"",
                        "{\"ruleId\": \"r-order-status\", \"onError\": \"REJECT\"")
                .replace("{\"eq\": [\"in.topic\", \"order_status\"]},",
                        "{\"eq\": [\"in.topic\", \"order_status\"]},"
                                + " {\"eq\": [\"ctx.now.utcDate\", \"" + DAY + "\"]},"),
                Set.of("order-query", "refund-agent", "faq-answer"));
        Map<String, Object> raw = Map.of("topic", "order_status", "orderId", "SO-1",
                "tags", List.of("vip"));

        RoutingDecision sameDayTwice = RoutingEngine.decide(spec,
                RoutingInput.of(spec.inputSchema(), raw, TENANT, null, null, 1, 0, DAY), null);
        RoutingDecision nextDay = RoutingEngine.decide(spec,
                RoutingInput.of(spec.inputSchema(), raw, TENANT, null, null, 1, 0,
                        DAY.plusDays(1)), null);

        assertThat(RoutingEngine.decide(spec,
                RoutingInput.of(spec.inputSchema(), raw, TENANT, null, null, 1, 0, DAY), null))
                .as("同一份 (策略, 输入, 自然日) 两次求值必须给出同一结论")
                .isEqualTo(sameDayTwice);
        assertThat(sameDayTwice.matchedRuleId()).isEqualTo("r-order-status");
        assertThat(nextDay.finalTarget())
                .as("引用了 ctx.now.utcDate 的规则跨日结论可以不同 —— 所以该字段必须进决策快照")
                .isNotEqualTo(sameDayTwice.finalTarget());
        assertThat(nextDay.resolution()).isEqualTo(Resolution.NO_MATCH_REJECTED);
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static PolicySpec policy(String mode, String noMatch, String conflict) {
        return parse(POLICY
                .replace("\"MODE\"", '"' + mode + '"')
                .replace("\"NOMATCH\"", '"' + noMatch + '"')
                .replace("\"CONFLICT\"", '"' + conflict + '"'),
                Set.of("order-query", "refund-agent", "faq-answer"));
    }

    /**
     * MA-T05a 的形态：父声明里<b>多一个策略没点名的目标</b>（{@code escalation-agent}）。
     *
     * <p>它必须只在 {@code allowedTargets} 里、不在 {@code evaluation} 里 ——
     * 两边都写就等于把「策略比拓扑窄」这个前提抹掉了，用例只会恒真通过。
     */
    private static PolicySpec policyWithExtraTarget(String mode, String noMatch, String conflict) {
        return parse(POLICY
                .replace("\"MODE\"", '"' + mode + '"')
                .replace("\"NOMATCH\"", '"' + noMatch + '"')
                .replace("\"CONFLICT\"", '"' + conflict + '"'),
                Set.of("order-query", "refund-agent", "faq-answer", "escalation-agent"));
    }

    /** 把第 1 条规则的 {@code onError} 改掉（其余不动），用于构造 REJECT 分支。 */
    private static PolicySpec withOnError(String onError) {
        return parse(POLICY
                .replace("\"MODE\"", "\"RULES_ONLY\"")
                .replace("\"NOMATCH\"", "\"REJECT\"")
                .replace("\"CONFLICT\"", "\"RULE_WINS\"")
                .replace("{\"ruleId\": \"r-order-status\"",
                        "{\"ruleId\": \"r-order-status\", \"onError\": \"" + onError + '"'),
                Set.of("order-query", "refund-agent", "faq-answer"));
    }

    private static PolicySpec parse(String json, Set<String> allowedTargets) {
        return PolicyCodec.parse(MAPPER.readTree(json), allowedTargets, Set.of());
    }

    private static RoutingDecision decide(PolicySpec spec, Map<String, Object> rawInput) {
        return decide(spec, rawInput, null);
    }

    private static RoutingDecision decide(PolicySpec spec, Map<String, Object> rawInput,
                                          String candidate) {
        return RoutingEngine.decide(spec, RoutingInput.of(spec.inputSchema(), rawInput, TENANT,
                null, null, 1, 0, DAY), candidate);
    }
}
