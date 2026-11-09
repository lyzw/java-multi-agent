package cn.studykid.multiagent.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code scenario} 生产方契约（2026-11-09 补）。
 *
 * <p><b>⚠️ 为什么需要本类</b>：此前「scenario 从 {@code input_payload.scenario} 读」
 * 只活在采集侧的 SQL 注释里，调用方没有任何契约可依 ⇒ 真实流量下
 * {@code agent_trace_metric.scenario} 恒为 NULL，看板「按场景拆分」长期只有
 * 「未标注」组。现在 {@link CreateRunRequest#SCENARIO_KEY} 是唯一键名，
 * 显式 {@code scenario} 字段是推荐入口。
 *
 * <p><b>⚠️ 走 Jackson 绑定而不是直接 new</b>：契约的边界是 HTTP 请求体，
 * 只测构造函数会漏掉「JSON 里根本没这个字段 / 字段名写错被当成未知字段」。
 */
class CreateRunRequestScenarioTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PREFIX =
            "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                    + "\"input\":{\"question\":\"q\"}";

    @Test
    @DisplayName("✅ 显式 scenario 是合法字段（不再被当成未知字段拒收）")
    void scenarioIsDeclaredField() {
        String body = PREFIX + ",\"scenario\":\"数学:一元二次方程\"}";

        var request = JSON.readValue(body, CreateRunRequest.class);

        assertThat(request.scenario()).isEqualTo("数学:一元二次方程");
        // ⚠️ 必须写回 input_payload —— 采集侧 JdbcTraceFactRepository 从那里读
        assertThat(request.input())
                .containsEntry(CreateRunRequest.SCENARIO_KEY, "数学:一元二次方程");
    }

    @Test
    @DisplayName("✅ 不传 scenario = 未标注（input 里不凭空塞一个键）")
    void absentScenarioIsUnlabeled() {
        var request = JSON.readValue(PREFIX + "}", CreateRunRequest.class);

        assertThat(request.scenario()).isNull();
        assertThat(request.input()).doesNotContainKey(CreateRunRequest.SCENARIO_KEY);
    }

    @Test
    @DisplayName("⚠️ 显式 scenario 覆盖 input 里的同名键（不允许两个真相）")
    void explicitScenarioWinsOverInputKey() {
        String body = "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                + "\"input\":{\"question\":\"q\",\"scenario\":\"数学:一元二次方程\"},"
                + "\"scenario\":\"英语:完形填空\"}";

        var request = JSON.readValue(body, CreateRunRequest.class);

        assertThat(request.scenario()).isEqualTo("英语:完形填空");
        assertThat(request.input())
                .containsEntry(CreateRunRequest.SCENARIO_KEY, "英语:完形填空");
    }

    @Test
    @DisplayName("⚠️⚠️ 非法 scenario 在创建期直接拒绝（不是静默降级为未标注）")
    void invalidScenarioRejectedAtCreation() {
        // 空格 + 自然语言：会被 ScenarioTag 白名单挡住
        String body = PREFIX + ",\"scenario\":\"这道题到底怎么做 请详细讲解\"}";

        assertThatThrownBy(() -> JSON.readValue(body, CreateRunRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("不是合法的场景标签");
    }

    @Test
    @DisplayName("⚠️ 显式 scenario 前后空白被 trim，标签本身仍是合法分类码")
    void scenarioIsTrimmed() {
        String body = PREFIX + ",\"scenario\":\"  数学:一元二次方程  \"}";

        var request = JSON.readValue(body, CreateRunRequest.class);

        assertThat(request.scenario()).isEqualTo("数学:一元二次方程");
    }

    @Test
    @DisplayName("✅ 直接构造（不经过 JSON）也走同一套校验")
    void directConstructorEnforcesSameContract() {
        assertThatThrownBy(() -> new CreateRunRequest(
                "idem-1", "cs-router", Map.of("question", "q"),
                null, null, null, null, "带 空格的非法标签"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法的场景标签");
    }

    @Test
    @DisplayName("✅ 旧 7 参兼容构造仍可用（scenario = 未标注）")
    void legacyConstructorStillWorks() {
        var request = new CreateRunRequest(
                "idem-1", "cs-router", Map.of("question", "q"),
                null, null, null, null);

        assertThat(request.scenario()).isNull();
    }
}
