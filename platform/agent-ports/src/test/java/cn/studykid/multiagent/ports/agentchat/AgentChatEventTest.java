package cn.studykid.multiagent.ports.agentchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.ContextCheckpoint;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.Done;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.StepStatus;

/**
 * 14 类 SSE 事件的线协议守卫（v1.1，M6 contract-only）。
 *
 * <p><b>为什么必须有它</b>：字段名 / 事件名是与两个前端
 * （{@code admin-console/src/api/agentChatApi.ts}、
 * {@code frontend/src/api/agentChatApi.ts}）逐字对齐的<b>契约</b>。
 * 改一个字母前端就会 fail-closed 丢帧，表现为「连接 200 但对话框没有内容」，
 * 且没有任何服务端报错。本测试把线格式钉死，并额外冻结 v1 旧字节：
 * 新增第 14 事件与 {@code Done.contextStateVersion} <b>只能追加，不能改写旧帧</b>。
 */
class AgentChatEventTest {

    private static final Map<String, String> EXPECTED_WIRE_NAMES = Map.ofEntries(
            Map.entry("runStart", "run.start"),
            Map.entry("route", "route"),
            Map.entry("ragQuery", "rag.query"),
            Map.entry("ragHits", "rag.hits"),
            Map.entry("skill", "skill"),
            Map.entry("tool", "tool"),
            Map.entry("handoff", "handoff"),
            Map.entry("thinking", "thinking"),
            Map.entry("delta", "delta"),
            Map.entry("answer", "answer"),
            Map.entry("usage", "usage"),
            Map.entry("error", "error"),
            Map.entry("done", "done"),
            Map.entry("contextCheckpoint", "context.checkpoint"));

    /** v1 的 13 类事件；其 {@code data()} 字节永久冻结。 */
    private static final List<AgentChatEvent> LEGACY_EVENTS = List.of(
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

    /** v1 的 13 帧原始字节；任何改动都会让旧客户端/快照比对失败。 */
    private static final List<String> LEGACY_DATA = List.of(
            "{\"runId\":\"run-1\"}",
            "{\"agentId\":\"a-1\",\"agentName\":\"数学助手\",\"reason\":\"显式指定\","
                    + "\"candidates\":[{\"agentId\":\"a-1\",\"name\":\"数学助手\"}]}",
            "{\"query\":\"怎么解方程\",\"topK\":5}",
            "{\"hits\":[{\"chunkId\":\"c-1\",\"text\":\"先移项\",\"score\":0.9,"
                    + "\"locator\":\"第 10 页\",\"edition\":\"数学\",\"role\":\"example\","
                    + "\"reason\":\"语义命中\"}],\"injected\":true}",
            "{\"skillId\":\"skill-1\",\"skillName\":\"解题\",\"phase\":\"load\","
                    + "\"status\":\"pending\",\"message\":\"已装载\"}",
            "{\"toolCallId\":\"call-1\",\"toolName\":\"get_hint\",\"phase\":\"call\","
                    + "\"status\":\"pending\",\"args\":\"{}\"}",
            "{\"from\":\"a-1\",\"note\":\"委派至子 Agent\"}",
            "先移项",
            "你好",
            "{\"text\":\"你好，世界\"}",
            "{\"inputTokens\":10,\"outputTokens\":20}",
            "{\"kind\":\"ROUTE_FAILED\",\"message\":\"没有可执行 Agent\"}",
            "{\"truncated\":false}");

    private static List<AgentChatEvent> allEvents() {
        List<AgentChatEvent> events = new ArrayList<>(LEGACY_EVENTS);
        events.add(checkpoint());
        return List.copyOf(events);
    }

    private static ContextCheckpoint checkpoint() {
        return new ContextCheckpoint("eyJnb2FsIjoieCJ9", 12, "cp_v1_8f3", 2, false, "sha256:abc");
    }

    @Test
    @DisplayName("v1.1 的 14 类事件与冻结的 WIRE_NAMES 一一对应")
    void wireNamesCoverAllFourteenEvents() {
        List<AgentChatEvent> events = allEvents();
        assertThat(events).hasSize(14);

        Map<String, String> wire = AgentChatEvent.WIRE_NAMES;
        assertThat(wire).hasSize(14).containsAllEntriesOf(EXPECTED_WIRE_NAMES);
        for (AgentChatEvent event : events) {
            assertThat(wire).containsValue(event.eventName());
        }
        assertThat(events.stream().map(AgentChatEvent::eventName).distinct())
                .as("事件名不得重复，否则前端 switch 会串台")
                .hasSize(14);
    }

    @Test
    @DisplayName("旧 13 帧 data 字节逐字冻结，v1.1 只能追加不能改写")
    void legacyThirteenEventsKeepByteIdenticalData() {
        assertThat(LEGACY_EVENTS.stream().map(AgentChatEvent::data).toList())
                .containsExactlyElementsOf(LEGACY_DATA);
    }

    @Test
    @DisplayName("Done 新增可选 contextStateVersion，缺省/空串仍产出 v1 旧字节")
    void doneOptionalVersionKeepsV1BytesWhenAbsent() {
        assertThat(new Done(false).data()).isEqualTo("{\"truncated\":false}");
        assertThat(new Done(true).data()).isEqualTo("{\"truncated\":true}");
        assertThat(new Done(false, null).data()).isEqualTo("{\"truncated\":false}");
        assertThat(new Done(false, "  ").data()).isEqualTo("{\"truncated\":false}");
        assertThat(new Done(false, "cp_v1_8f3").data())
                .isEqualTo("{\"truncated\":false,\"contextStateVersion\":\"cp_v1_8f3\"}");
        assertThat(new Done(true, "cp_v1_8f3").data())
                .isEqualTo("{\"truncated\":true,\"contextStateVersion\":\"cp_v1_8f3\"}");
        assertThat(Done.class.getDeclaredConstructors())
                .as("保留 v1 单参兼容构造，但所有路径必须收敛到全参构造")
                .hasSize(2);
    }

    @Test
    @DisplayName("context.checkpoint 载荷字段与顺序冻结，且拒绝空 state/digest/非法水位")
    void contextCheckpointPayloadIsFrozenAndFailClosed() {
        ContextCheckpoint event = checkpoint();
        assertThat(event.eventName()).isEqualTo("context.checkpoint");
        assertThat(event.data()).isEqualTo(
                "{\"state\":\"eyJnb2FsIjoieCJ9\",\"upToTurn\":12,"
                        + "\"policyVersion\":\"cp_v1_8f3\",\"tier\":2,"
                        + "\"fallback\":false,\"digest\":\"sha256:abc\"}");

        ContextCheckpoint noTurn = new ContextCheckpoint(
                "eyJnb2FsIjoieCJ9", null, "cp_v1", 3, true, "sha256:def");
        assertThat(noTurn.data())
                .isEqualTo("{\"state\":\"eyJnb2FsIjoieCJ9\",\"policyVersion\":\"cp_v1\","
                        + "\"tier\":3,\"fallback\":true,\"digest\":\"sha256:def\"}")
                .doesNotContain("upToTurn");
        assertThat(ContextCheckpoint.class.getDeclaredConstructors()).hasSize(1);

        assertThatThrownBy(() -> new ContextCheckpoint("", 1, "cp", 2, false, "sha256:abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("state");
        assertThatThrownBy(() -> new ContextCheckpoint("s", 1, " ", 2, false, "sha256:abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("policyVersion");
        assertThatThrownBy(() -> new ContextCheckpoint("s", 1, "cp", 2, false, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
        assertThatThrownBy(() -> new ContextCheckpoint("s", 1, "cp", 0, false, "sha256:abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier");
        assertThatThrownBy(() -> new ContextCheckpoint("s", 1, "cp", 4, false, "sha256:abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier");
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
        assertThat(checkpoint().data())
                .contains("\"state\"", "\"upToTurn\"", "\"policyVersion\"")
                .contains("\"tier\"", "\"fallback\"", "\"digest\"");
        assertThat(new Done(true, "cp_v1").data()).contains("\"contextStateVersion\":\"cp_v1\"");
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
        assertThat(new ContextCheckpoint(evil, null, "cp_v1", 2, false, "sha256:abc").data())
                .doesNotContain("\n", "\r");
    }
}
