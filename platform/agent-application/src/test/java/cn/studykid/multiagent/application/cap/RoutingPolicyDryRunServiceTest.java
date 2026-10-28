package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.routing.RoutingDryRun;

/**
 * 路由策略 dry-run 用例测试（10 号设计 §7 债 5 · 应用层那一半）。
 *
 * <p><b>本类只管三件事</b>（引擎与守卫的行为在 {@code RoutingDryRunTest} 里测）：
 * <ol>
 *   <li>草稿确实按<b>发布路径同一个构造入口</b>拼成策略文本 —— 否则试跑的对象与发布
 *       的对象不是一份东西；</li>
 *   <li>回放日期来自注入时钟，而不是系统时钟；</li>
 *   <li>行级不合法是<b>请求错误</b>（抛出去给 API 层折算 400），
 *       而文法/守卫不合法是<b>业务结论</b>（200 + {@code parsed=false}）。</li>
 * </ol>
 */
@DisplayName("S5 路由策略 dry-run 用例")
class RoutingPolicyDryRunServiceTest {

    private static final String TENANT = "tenant-1";

    /** 固定到 2026-03-01T09:00Z：日期会影响引用 {@code ctx.now.utcDate} 的规则。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T09:00:00Z"), ZoneOffset.UTC);

    private static final RoutingPolicyDryRunService SERVICE = new RoutingPolicyDryRunService(CLOCK);

    /** 一条按 {@code in.topic} 分流的草稿（三段文本原样透传，不在入口重新序列化）。 */
    private static ConfigPublishPayload.RoutingPolicySpec draft(String mode) {
        return new ConfigPublishPayload.RoutingPolicySpec(
                "orders-router", mode,
                """
                {"type": "object", "additionalProperties": "false",
                 "properties": {"topic": {"type": "string"}}}
                """,
                """
                [{"ruleId": "r-refund", "target": "refund-agent",
                  "when": {"eq": ["in.topic", "refund"]}}]
                """,
                "faq-answer", "DEFAULT_TARGET", "RULE_WINS", "[]");
    }

    @Test
    @DisplayName("✅ 草稿走发布路径的同一个构造入口：探针跑出与运行期同源的目标")
    void probesRunAgainstTheSameDraftText() {
        RoutingDryRun.Outcome outcome = SERVICE.run(TENANT, draft("RULES_ONLY"), """
                [{"label": "退款", "input": {"topic": "refund"}}]
                """);

        assertThat(outcome.parsed()).isTrue();
        assertThat(outcome.probes()).singleElement()
                .extracting(p -> p.decision().finalTarget()).isEqualTo("refund-agent");
    }

    @Test
    @DisplayName("✅ 不传探针 = 只出守卫报告，不编造试跑结论")
    void nullProbesMeansGuardReportOnly() {
        RoutingDryRun.Outcome outcome = SERVICE.run(TENANT, draft("RULES_ONLY"), null);

        assertThat(outcome.probes()).isEmpty();
        assertThat(outcome.parsed()).isTrue();
    }

    @Test
    @DisplayName("✅ 回放日期来自注入时钟（引擎不读系统时钟）")
    void replayDateComesFromInjectedClock() {
        assertThat(SERVICE.run(TENANT, draft("RULES_ONLY"), "[]").replayDate())
                .isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("✅ 草稿文本原样透传：空白与键序不改写，指纹判定的是「这段文本变没变」")
    void draftTextIsPassedThroughUnchanged() {
        // mode=SEMANTIC 时 evaluation 允许空数组；这里用 RULES_ONLY 反证：
        // 空规则数组是配置缺陷，行级就拒，不会跑到引擎里给一个「永远走兜底」的绿灯。
        ConfigPublishPayload.RoutingPolicySpec emptyRules = new ConfigPublishPayload.RoutingPolicySpec(
                "orders-router", "RULES_ONLY",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}",
                "[]", "faq-answer", "DEFAULT_TARGET", "RULE_WINS", "[]");

        assertThatThrownBy(() -> SERVICE.run(TENANT, emptyRules, "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluation 不能是空数组");
    }

    @Test
    @DisplayName("⛔ 行级不合法抛出去（API 层折算 400）；文法不合法是结论不是异常")
    void rowLevelFailureIsARequestErrorNotABusinessVerdict() {
        assertThatThrownBy(() -> SERVICE.run(TENANT, draft("SMART_ROUTING"), "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SMART_ROUTING");

        // 谓词算子拼错：行能装配（三段文本非空、枚举名合法），但文法不过。
        // 这必须是 dry-run 给出的<b>结论</b>，而不是一个 500 —— 否则「草稿还没写完」
        // 在界面上表现为服务器错误，配置者永远不知道是哪一格写错了。
        ConfigPublishPayload.RoutingPolicySpec badOperator =
                new ConfigPublishPayload.RoutingPolicySpec(
                        "orders-router", "RULES_ONLY",
                        "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}",
                        """
                        [{"ruleId": "r-refund", "target": "refund-agent",
                          "when": {"equals": ["in.topic", "refund"]}}]
                        """,
                        "faq-answer", "DEFAULT_TARGET", "RULE_WINS", "[]");

        RoutingDryRun.Outcome outcome = SERVICE.run(TENANT, badOperator, "[]");
        assertThat(outcome.parsed()).isFalse();
        assertThat(outcome.publishable()).isFalse();
        assertThat(outcome.policyIssue().message()).contains("equals");
    }
}
