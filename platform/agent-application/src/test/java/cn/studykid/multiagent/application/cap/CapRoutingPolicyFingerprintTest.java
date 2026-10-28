package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;

/**
 * 路由策略的<b>内容指纹</b>（10 号设计 §4.2 · 取代第十四批的「策略枚举 + 目标列表」）。
 *
 * <p><b>⚠️ 为什么 {@code config_fingerprint} 必须由服务端算</b>：
 * 它的唯一用途是证明「这一行内容没变」。若让调用方提供这个值，
 * 它就成了一个可以随便填的字符串 —— 客户端填 A、实际内容是 B，
 * 闭包指纹仍会自洽，于是「内容变了但指纹没变」这件事再也检测不出来。
 *
 * <p><b>⚠️ 「全都拒」不能算通过</b>：本类同时钉住「内容变了 → 指纹必须变」
 * 与「内容没变 → 指纹必须不变」两侧，只有一侧的话用例形同虚设。
 *
 * <p><b>⚠️ 本类最要紧的一条是 JSON 列的规范化口径</b>：
 * PG 的 {@code jsonb} 存进去再读出来会<b>重排对象键、压缩空白，但保留数组顺序</b>。
 * 于是「同一份内容写入前后指纹不同」是一个<b>看起来正常、实际每次发布都被读成变更</b>
 * 的缺陷 —— 它不报错，只是每次都说「你改过」。
 * 而规则次序又恰好是路由语义（首个命中即止），所以<b>不能</b>简单地把两边都排序。
 */
class CapRoutingPolicyFingerprintTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}";

    /** 目标列表 → 规则数组：每目标一条可满足条件（{@code in.topic == case-N}），目标顺序即策略顺序。 */
    private static String rulesOf(List<String> targets) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"ordinal\":").append(i + 1).append(",\"ruleId\":\"r-").append(i + 1)
                    .append("\",\"target\":\"").append(targets.get(i))
                    .append("\",\"when\":{\"eq\":[\"in.topic\",\"")
                    .append("case-").append(i + 1).append("\"]}}");
        }
        return sb.append(']').toString();
    }

    private static CapRoutingPolicyDef policy(String policyId, int version, RoutingMode mode,
                                              NoMatchAction noMatchAction, List<String> targets,
                                              String evaluation, Instant publishedAt) {
        return new CapRoutingPolicyDef("t-1", policyId, version, mode, SCHEMA, evaluation,
                noMatchAction == NoMatchAction.DEFAULT_TARGET && !targets.isEmpty()
                        ? targets.get(0) : null,
                noMatchAction, ConflictPolicy.RULE_WINS, "[]",
                "placeholder", 1, NOW, publishedAt, null);
    }

    private static CapRoutingPolicyDef policy(String policyId, int version, RoutingMode mode,
                                              NoMatchAction noMatchAction, List<String> targets,
                                              Instant publishedAt) {
        return policy(policyId, version, mode, noMatchAction, targets, rulesOf(targets),
                publishedAt);
    }

    private static String fingerprintOf(CapRoutingPolicyDef def) {
        return CapObjectFingerprint.of(def);
    }

    @Test
    @DisplayName("内容相同 → 指纹相同（否则每次发布都被读成内容变化）")
    void sameContentSameFingerprint() {
        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a", "agent-b"), NOW)))
                .isEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of("agent-a", "agent-b"), NOW)));
    }

    @Test
    @DisplayName("发布时间不参与指纹（草稿与发布态是同一份内容）")
    void publishedAtDoesNotAffectFingerprint() {
        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), null)))
                .as("时间戳不参与计算，否则每次发布都会让指纹变化")
                .isEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of("agent-a"), NOW)));
    }

    @Test
    @DisplayName("JSON 列的键顺序与空白不参与指纹（jsonb 会重排，指纹不能跟着抖）")
    void jsonObjectKeyOrderDoesNotAffectFingerprint() {
        String spaced = "[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"agent-a\","
                + "\"when\":{\"const\":false}}]";
        String reshuffled = "[{\"when\":{\"const\":false},\"target\":\"agent-a\","
                + "\"ruleId\":\"r-1\",\"ordinal\":1}]";
        String minified = "[ {  \"when\" : { \"const\" : false } , \"target\" : \"agent-a\" ,"
                + " \"ruleId\" : \"r-1\" , \"ordinal\" : 1 } ]";

        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of(), reshuffled, NOW)))
                .as("对象键被 jsonb 重排后必须还是同一份内容")
                .isEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of(), spaced, NOW)));
        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of(), minified, NOW)))
                .as("空白同样不该改指纹")
                .isEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of(), spaced, NOW)));
    }

    @Test
    @DisplayName("规则次序参与指纹（数组顺序就是路由语义，不能被规范化抹平）")
    void ruleOrderAffectsFingerprint() {
        String first = "[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"agent-a\","
                + "\"when\":{\"const\":false}},{\"ordinal\":2,\"ruleId\":\"r-2\","
                + "\"target\":\"agent-b\",\"when\":{\"const\":false}}]";
        String swapped = "[{\"ordinal\":1,\"ruleId\":\"r-2\",\"target\":\"agent-b\","
                + "\"when\":{\"const\":false}},{\"ordinal\":2,\"ruleId\":\"r-1\","
                + "\"target\":\"agent-a\",\"when\":{\"const\":false}}]";

        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of(), swapped, NOW)))
                .as("同一组规则换个次序就是另一种路由行为，指纹必须能区分")
                .isNotEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of(), first, NOW)));
    }

    @Test
    @DisplayName("mode / noMatchAction 变化 → 指纹变化（三种路由行为互不相同）")
    void enumsAffectFingerprint() {
        String base = fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW));

        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.SEMANTIC,
                NoMatchAction.REJECT, List.of("agent-a"), NOW)))
                .as("RULES_ONLY 与 SEMANTIC 是两种行为")
                .isNotEqualTo(base);
        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.DEFAULT_TARGET, List.of("agent-a"), NOW)))
                .as("拒绝与兜底是两种行为")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("词汇表变化 → 指纹变化（V3 的判据变了，策略就变了）")
    void inputSchemaAffectsFingerprint() {
        CapRoutingPolicyDef stringTopic = policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW);
        CapRoutingPolicyDef integerTopic = new CapRoutingPolicyDef("t-1", "orders-router", 1,
                RoutingMode.RULES_ONLY, "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"integer\"}}}",
                stringTopic.evaluation(), null, NoMatchAction.REJECT,
                ConflictPolicy.RULE_WINS, "[]", "placeholder", 1, NOW, NOW, null);

        assertThat(fingerprintOf(integerTopic)).isNotEqualTo(fingerprintOf(stringTopic));
    }

    @Test
    @DisplayName("目标集合变化 → 指纹变化（策略内容变了必须能被检出）")
    void targetsAffectFingerprint() {
        assertThat(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a", "agent-b"), NOW)))
                .isNotEqualTo(fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                        NoMatchAction.REJECT, List.of("agent-a"), NOW)));
    }

    @Test
    @DisplayName("策略标识与版本变化 → 指纹变化")
    void identityAffectsFingerprint() {
        String base = fingerprintOf(policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW));

        assertThat(fingerprintOf(policy("refund-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW))).isNotEqualTo(base);
        assertThat(fingerprintOf(policy("orders-router", 2, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("内容指纹不含自身（否则占位值永远算不出终值）")
    void fingerprintExcludesItself() {
        CapRoutingPolicyDef draft = policy("orders-router", 1, RoutingMode.RULES_ONLY,
                NoMatchAction.REJECT, List.of("agent-a"), NOW);
        String computed = CapObjectFingerprint.of(draft);
        CapRoutingPolicyDef withRealFp = new CapRoutingPolicyDef("t-1", "orders-router", 1,
                RoutingMode.RULES_ONLY, SCHEMA, draft.evaluation(), null,
                NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                computed, 1, NOW, NOW, null);

        assertThat(CapObjectFingerprint.of(withRealFp))
                .as("把算出来的指纹塞回行里，再算一次必须还是它自己")
                .isEqualTo(computed);
    }
}
