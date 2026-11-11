package cn.studykid.multiagent.ports.agentchat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.StepStatus;

/**
 * 13 类 SSE 事件的线协议守卫。
 *
 * <p><b>为什么必须有它</b>：字段名 / 事件名是与两个前端
 * （{@code admin-console/src/api/agentChatApi.ts}、
 * {@code frontend/src/api/agentChatApi.ts}）逐字对齐的<b>契约</b>。
 * 改一个字母前端就会 fail-closed 丢帧，表现为「连接 200 但对话框没有内容」，
 * 且没有任何服务端报错。本测试把线格式钉死。
 */
class AgentChatEventTest {

    private static List<AgentChatEvent> allEvents() {
        return List.of(
                new AgentChatEvent.RunStart("run-1"),
                new AgentChatEvent.Route("a-1", "数学助手", "显式指定",
                        List.of(new AgentChatEvent.Candidate("a-1", "数学助手", null, null))),
                new AgentChatEvent.RagQuery("怎么解方程", 5),
                new AgentChatEvent.RagHits(List.of(new AgentChatEvent.Hit(
                        "c-1", "先移项", 0.9, "第 10 页", "数学", "example", "语义命中")), true),
                new AgentChatEvent.Skill("skill-1", "解题", "load", StepStatus.PENDING,
                        "已装载", null, null),
                new AgentChatEvent.Tool("call-1", "get_hint", "call", StepStatus.PENDING,
                        "{}", null, null),
                new AgentChatEvent.Handoff("a-1", "委派至子 Agent"),
                new AgentChatEvent.Thinking("先移项"),
                new AgentChatEvent.Delta("你好"),
                new AgentChatEvent.Answer("你好，世界"),
                new AgentChatEvent.Usage(10, 20),
                new AgentChatEvent.Error("ROUTE_FAILED", "没有可执行 Agent"),
                new AgentChatEvent.Done(false));
    }

    @Test
    @DisplayName("13 类事件的 eventName 与冻结的 WIRE_NAMES 完全一一对应")
    void wireNamesCoverAllThirteenEvents() {
        List<AgentChatEvent> events = allEvents();
        assertThat(events).hasSize(13);

        Map<String, String> wire = AgentChatEvent.WIRE_NAMES;
        assertThat(wire).hasSize(13);
        for (AgentChatEvent event : events) {
            assertThat(wire).containsValue(event.eventName());
        }
        assertThat(events.stream().map(AgentChatEvent::eventName).distinct())
                .as("事件名不得重复，否则前端 switch 会串台")
                .hasSize(13);
    }

    @Test
    @DisplayName("thinking / delta 的 data 是裸文本而非 JSON 包装")
    void reasoningAndDeltaCarryRawText() {
        assertThat(new AgentChatEvent.Thinking("草稿 <think> 内容").data())
                .isEqualTo("草稿 <think> 内容");
        assertThat(new AgentChatEvent.Delta("正文片段").data())
                .isEqualTo("正文片段");
    }

    @Test
    @DisplayName("结构化事件是合法 JSON 且字段名与前端读取一致")
    void structuredEventsAreJsonWithFrozenFieldNames() {
        AgentChatEvent.Route route = new AgentChatEvent.Route("a-1", "助手", "理由",
                List.of(new AgentChatEvent.Candidate("a-1", "助手", 0.5, "匹配")));
        assertThat(route.data())
                .contains("\"agentId\"", "\"agentName\"", "\"reason\"", "\"candidates\"")
                .contains("\"name\"", "\"score\"");

        AgentChatEvent.RagHits hits = new AgentChatEvent.RagHits(List.of(
                new AgentChatEvent.Hit("c-1", "文本", 0.8, "定位", "版次", "role", "reason")), true);
        assertThat(hits.data())
                .contains("\"chunkId\"", "\"text\"", "\"score\"", "\"locator\"")
                .contains("\"edition\"", "\"role\"", "\"reason\"", "\"injected\"");

        assertThat(new AgentChatEvent.Tool("c1", "t", "call", StepStatus.PENDING,
                "{\"x\":1}", null, null).data())
                .contains("\"toolCallId\"", "\"toolName\"", "\"phase\"", "\"status\"", "\"args\"");
        assertThat(new AgentChatEvent.Skill("s", "名", "error", StepStatus.ERROR,
                null, null, "失败原因").data())
                .contains("\"skillId\"", "\"skillName\"", "\"phase\"", "\"error\"");
        assertThat(new AgentChatEvent.Error("KIND", "msg").data())
                .contains("\"kind\"", "\"message\"");
        assertThat(new AgentChatEvent.Done(true).data()).contains("\"truncated\":true");
    }

    @Test
    @DisplayName("注入控制字符 / 引号不会破坏 JSON，也不会产生 SSE 非法换行")
    void controlCharactersAreEscaped() {
        String evil = "a\"b\\c\n\r\td\u0001";
        @NonNull String quoted = AgentChatEvent.quote(evil);
        assertThat(quoted)
                .startsWith("\"").endsWith("\"")
                .contains("\\\"", "\\\\", "\\n", "\\r", "\\t", "\\u0001")
                .doesNotContain("\n", "\r");
        assertThat(new AgentChatEvent.Error("X", evil).data()).doesNotContain("\n", "\r");
    }
}
