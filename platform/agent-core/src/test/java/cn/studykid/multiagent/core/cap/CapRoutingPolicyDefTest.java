package cn.studykid.multiagent.core.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;

/**
 * 路由策略定义行的<b>行级</b>领域约束（10 号设计 §4.2）。
 *
 * <p>⚠️ <b>本类只判「这一行自不自洽」，不判规则文法</b>：
 * 谓词是否合法、DNF 是否越界、V3~V10 全在 {@code agent-routing}。
 * 领域层若也判一遍，「策略里能写什么」就有了第二个真相，
 * 而第二个真相迟早和引擎不一致 —— 症状是「库里存得下、引擎跑不通」。
 * 所以这里刻意<b>只做三件与 DDL CHECK 同口径的事</b>：
 * 文本非空、有规则的模式不许空数组、兜底动作必须指名兜底目标。
 */
class CapRoutingPolicyDefTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}";
    private static final String RULES = "[{\"ordinal\":1,\"ruleId\":\"r-1\","
            + "\"target\":\"order-query\",\"when\":{\"const\":true}}]";

    private CapRoutingPolicyDef def(RoutingMode mode, String evaluation,
                                    String defaultTarget, NoMatchAction noMatchAction) {
        return new CapRoutingPolicyDef("t-1", "orders-router", 1, mode, SCHEMA, evaluation,
                defaultTarget, noMatchAction, ConflictPolicy.RULE_WINS, "[]",
                "fp-1", 1, NOW, NOW, null);
    }

    private CapRoutingPolicyDef rulesOnly() {
        return def(RoutingMode.RULES_ONLY, RULES, null, NoMatchAction.REJECT);
    }

    // ── 行级一致性 ────────────────────────────────────────────────

    @Test
    @DisplayName("三段结构文本不得为空：空文本 ≠ 空集合")
    void blankJsonSectionsRejected() {
        assertThatThrownBy(() -> new CapRoutingPolicyDef("t-1", "p", 1, RoutingMode.RULES_ONLY,
                "  ", RULES, null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp", 1, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空文本");

        assertThatThrownBy(() -> new CapRoutingPolicyDef("t-1", "p", 1, RoutingMode.RULES_ONLY,
                SCHEMA, RULES, null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, null,
                "fp", 1, NOW, NOW, null))
                .as("无样本要显式写 []，留空是「这行没写」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleSet");
    }

    @Test
    @DisplayName("RULES_ONLY / RULE_FIRST 不许空规则数组；SEMANTIC 允许")
    void emptyRulesRejectedForRuleModesOnly() {
        assertThatThrownBy(() -> def(RoutingMode.RULES_ONLY, "[]", null, NoMatchAction.REJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能是空数组");

        assertThatThrownBy(() -> def(RoutingMode.RULE_FIRST, "[ ]", null, NoMatchAction.REJECT))
                .as("空白变体也得拦住，否则 [ ] 就成了绕过")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> def(RoutingMode.SEMANTIC, "[]", "faq-answer",
                NoMatchAction.DEFAULT_TARGET)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no_match_action=DEFAULT_TARGET 必须声明 default_target（与 DDL 同口径）")
    void defaultTargetRequiredWithItsAction() {
        assertThatThrownBy(() -> def(RoutingMode.RULES_ONLY, RULES, null,
                NoMatchAction.DEFAULT_TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须声明 default_target");

        assertThatThrownBy(() -> def(RoutingMode.RULES_ONLY, RULES, "   ",
                NoMatchAction.DEFAULT_TARGET))
                .as("空白字符串同样是「没写」")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("版本号必须为正数")
    void nonPositiveVersionRejected() {
        assertThatThrownBy(() -> new CapRoutingPolicyDef("t-1", "p", 0, RoutingMode.RULES_ONLY,
                SCHEMA, RULES, null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp", 1, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version 必须为正数");
    }

    @Test
    @DisplayName("三个模式枚举缺一即拒：得由代码猜的行为不在任何策略文本里")
    void nullEnumsRejected() {
        assertThatThrownBy(() -> new CapRoutingPolicyDef("t-1", "p", 1, null,
                SCHEMA, RULES, null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp", 1, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    // ── 引用与发布语义 ────────────────────────────────────────────

    @Test
    @DisplayName("固定版本引用形如 id@version；草稿不可被引用")
    void refAndPublishedSemantics() {
        CapRoutingPolicyDef published = rulesOnly();
        assertThat(published.ref()).isEqualTo("orders-router@1");
        assertThat(published.isPublished()).isTrue();

        CapRoutingPolicyDef draft = new CapRoutingPolicyDef("t-1", "orders-router", 1,
                RoutingMode.RULES_ONLY, SCHEMA, RULES, null, NoMatchAction.REJECT,
                ConflictPolicy.RULE_WINS, "[]", "fp", 1, NOW, null, null);
        assertThat(draft.isPublished())
                .as("草稿不可被引用（先引用再发布是绕过发布流程的后门）")
                .isFalse();
    }

    @Test
    @DisplayName("DEPRECATED 与「未记录」是两件事")
    void deprecatedSemantics() {
        assertThat(rulesOnly().isDeprecated()).isFalse();
        assertThat(new CapRoutingPolicyDef("t-1", "orders-router", 1, RoutingMode.RULES_ONLY,
                SCHEMA, RULES, null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp", 1, NOW, NOW, ConfigLifecycleState.DEPRECATED).isDeprecated()).isTrue();
    }

    // ── definitionJson：行字段 → 引擎输入 ─────────────────────────

    @Test
    @DisplayName("definitionJson 由行字段拼回 §4.1 对象，不另存一份原文")
    void definitionJsonCarriesEverySection() {
        String json = rulesOnly().definitionJson();
        assertThat(json)
                .contains("\"policyId\":\"orders-router\"")
                .contains("\"mode\":\"RULES_ONLY\"")
                .contains("\"noMatchAction\":\"REJECT\"")
                .contains("\"onConflict\":\"RULE_WINS\"")
                .contains(SCHEMA)
                .contains(RULES);
    }

    @Test
    @DisplayName("无兜底目标时不写 defaultTarget 键（空串会被引擎读成目标名为空）")
    void definitionJsonOmitsAbsentDefaultTarget() {
        assertThat(rulesOnly().definitionJson()).doesNotContain("defaultTarget");
        assertThat(def(RoutingMode.RULES_ONLY, RULES, "faq-answer",
                NoMatchAction.DEFAULT_TARGET).definitionJson())
                .contains("\"defaultTarget\":\"faq-answer\"");
    }

    @Test
    @DisplayName("policyId 里的引号被转义：拼出来的必须还是合法 JSON")
    void definitionJsonEscapesIdentifiers() {
        CapRoutingPolicyDef odd = new CapRoutingPolicyDef("t-1", "a\"b", 1, RoutingMode.SEMANTIC,
                SCHEMA, "[]", null, NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                "fp", 1, NOW, NOW, null);
        assertThat(odd.definitionJson()).contains("\"policyId\":\"a\\\"b\"");
    }
}
