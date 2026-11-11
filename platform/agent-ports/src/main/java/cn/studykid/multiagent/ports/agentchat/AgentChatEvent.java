package cn.studykid.multiagent.ports.agentchat;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 通用 Agent 编排对话的流式事件协议（平台侧契约）。
 *
 * <p><b>与前端逐字段对齐</b>：{@code admin-console/src/api/agentChatApi.ts} 与
 * {@code frontend/src/api/agentChatApi.ts} 的 {@code decodeAgentFrame} 是本契约的镜像。
 * 字段名（含 {@code error.kind}、{@code route.candidates[].name}、{@code skill.phase} 等）
 * 一经改动会让前端 fail-closed 丢弃该帧，故必须与两份前端同步修改。
 *
 * <p><b>线格式</b>：{@code thinking}/{@code delta} 的 data 是裸文本；其余事件的 data 是 JSON 对象；
 * {@code done} 是应用层终帧（客户端收到即收口，不等 TCP EOF）。
 *
 * <p><b>语义</b>：{@code error} 表示整轮失败；{@code skill}/{@code tool} 的
 * {@code phase=error} 表示<b>组件级</b>失败，Agent 可降级继续；RAG 零命中是合法
 * {@code info}，不是 {@code error}。
 */
public sealed interface AgentChatEvent permits
        AgentChatEvent.RunStart,
        AgentChatEvent.Route,
        AgentChatEvent.RagQuery,
        AgentChatEvent.RagHits,
        AgentChatEvent.Skill,
        AgentChatEvent.Tool,
        AgentChatEvent.Handoff,
        AgentChatEvent.Thinking,
        AgentChatEvent.Delta,
        AgentChatEvent.Answer,
        AgentChatEvent.Usage,
        AgentChatEvent.Error,
        AgentChatEvent.Done {

    /** SSE 帧名（前端 {@code switch(name)} 的键）。 */
    @NonNull String eventName();

    /** data 载荷：{@code null} 表示无 data（本协议无此情形，保留给未来心跳）。 */
    @Nullable String data();

    /** 组件步骤状态（对齐前端 AgentStepStatus）。 */
    enum StepStatus {
        PENDING("pending"), RUNNING("running"), SUCCESS("success"),
        ERROR("error"), INFO("info");

        private final String wire;

        StepStatus(String wire) {
            this.wire = wire;
        }

        public @NonNull String wire() {
            return wire;
        }
    }

    /** 路由候选（字段名 {@code name} 与前端 candidates() 读取一致）。 */
    record Candidate(@NonNull String agentId, @NonNull String name,
                     @Nullable Double score, @Nullable String reason) {
    }

    /** RAG 命中（七字段与 rag-api HitDto 同名，前端 hits() 逐字段读取）。 */
    record Hit(@NonNull String chunkId, @NonNull String text, double score,
               @NonNull String locator, @NonNull String edition,
               @NonNull String role, @NonNull String reason) {
    }

    /** ① run.start · data=JSON。 */
    record RunStart(@NonNull String runId) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "run.start";
        }

        @Override
        public @NonNull String data() {
            return "{\"runId\":" + quote(runId) + "}";
        }
    }

    /** ② route · data=JSON。 */
    record Route(@NonNull String agentId, @NonNull String agentName, @NonNull String reason,
                 @NonNull List<Candidate> candidates) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "route";
        }

        @Override
        public @NonNull String data() {
            StringBuilder sb = new StringBuilder("{\"agentId\":").append(quote(agentId))
                    .append(",\"agentName\":").append(quote(agentName))
                    .append(",\"reason\":").append(quote(reason))
                    .append(",\"candidates\":[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Candidate c = candidates.get(i);
                sb.append("{\"agentId\":").append(quote(c.agentId()))
                        .append(",\"name\":").append(quote(c.name()));
                if (c.score() != null) {
                    sb.append(",\"score\":").append(c.score());
                }
                if (c.reason() != null) {
                    sb.append(",\"reason\":").append(quote(c.reason()));
                }
                sb.append('}');
            }
            return sb.append("]}").toString();
        }
    }

    /** ③ rag.query · data=JSON。 */
    record RagQuery(@NonNull String query, int topK) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "rag.query";
        }

        @Override
        public @NonNull String data() {
            return "{\"query\":" + quote(query) + ",\"topK\":" + topK + "}";
        }
    }

    /** ④ rag.hits · data=JSON。零命中时 hits 为空且 injected=false（合法 info）。 */
    record RagHits(@NonNull List<Hit> hits, boolean injected) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "rag.hits";
        }

        @Override
        public @NonNull String data() {
            StringBuilder sb = new StringBuilder("{\"hits\":[");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Hit h = hits.get(i);
                sb.append("{\"chunkId\":").append(quote(h.chunkId()))
                        .append(",\"text\":").append(quote(h.text()))
                        .append(",\"score\":").append(h.score())
                        .append(",\"locator\":").append(quote(h.locator()))
                        .append(",\"edition\":").append(quote(h.edition()))
                        .append(",\"role\":").append(quote(h.role()))
                        .append(",\"reason\":").append(quote(h.reason()))
                        .append('}');
            }
            return sb.append("],\"injected\":").append(injected).append('}').toString();
        }
    }

    /** ⑤ skill · data=JSON。 */
    record Skill(@NonNull String skillId, @NonNull String skillName, @NonNull String phase,
                 @NonNull StepStatus status, @Nullable String message,
                 @Nullable String result, @Nullable String error) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "skill";
        }

        @Override
        public @NonNull String data() {
            StringBuilder sb = new StringBuilder("{\"skillId\":").append(quote(skillId))
                    .append(",\"skillName\":").append(quote(skillName))
                    .append(",\"phase\":").append(quote(phase))
                    .append(",\"status\":").append(quote(status.wire()));
            appendOptional(sb, "message", message);
            appendOptional(sb, "result", result);
            appendOptional(sb, "error", error);
            return sb.append('}').toString();
        }
    }

    /** ⑥ tool · data=JSON。 */
    record Tool(@NonNull String toolCallId, @NonNull String toolName, @NonNull String phase,
                @NonNull StepStatus status, @Nullable String args,
                @Nullable String result, @Nullable String error) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "tool";
        }

        @Override
        public @NonNull String data() {
            StringBuilder sb = new StringBuilder("{\"toolCallId\":").append(quote(toolCallId))
                    .append(",\"toolName\":").append(quote(toolName))
                    .append(",\"phase\":").append(quote(phase))
                    .append(",\"status\":").append(quote(status.wire()));
            appendOptional(sb, "args", args);
            appendOptional(sb, "result", result);
            appendOptional(sb, "error", error);
            return sb.append('}').toString();
        }
    }

    /** ⑦ handoff · data=JSON。 */
    record Handoff(@NonNull String from, @NonNull String note) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "handoff";
        }

        @Override
        public @NonNull String data() {
            return "{\"from\":" + quote(from) + ",\"note\":" + quote(note) + "}";
        }
    }

    /** ⑧ thinking · data=裸文本（推理草稿，仅平台链可见）。 */
    record Thinking(@NonNull String text) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "thinking";
        }

        @Override
        public @NonNull String data() {
            return text;
        }
    }

    /** ⑨ delta · data=裸文本（正文增量）。 */
    record Delta(@NonNull String text) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "delta";
        }

        @Override
        public @NonNull String data() {
            return text;
        }
    }

    /** ⑩ answer · data=JSON（终答兜底，绝不回撤已到达的 delta）。 */
    record Answer(@NonNull String text) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "answer";
        }

        @Override
        public @NonNull String data() {
            return "{\"text\":" + quote(text) + "}";
        }
    }

    /** ⑪ usage · data=JSON。 */
    record Usage(long inputTokens, long outputTokens) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "usage";
        }

        @Override
        public @NonNull String data() {
            return "{\"inputTokens\":" + inputTokens + ",\"outputTokens\":" + outputTokens + "}";
        }
    }

    /** ⑫ error · data=JSON（整轮失败；前端读 kind 字段）。 */
    record Error(@NonNull String kind, @NonNull String message) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "error";
        }

        @Override
        public @NonNull String data() {
            return "{\"kind\":" + quote(kind) + ",\"message\":" + quote(message) + "}";
        }
    }

    /** ⑬ done · data=JSON（应用层终帧）。 */
    record Done(boolean truncated) implements AgentChatEvent {
        @Override
        public @NonNull String eventName() {
            return "done";
        }

        @Override
        public @NonNull String data() {
            return "{\"truncated\":" + truncated + "}";
        }
    }

    /** JSON 字符串转义（含换行归一，SSE data 多行由 SseEmitter 规范处理）。 */
    static @NonNull String quote(@NonNull String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void appendOptional(StringBuilder sb, String key, @Nullable String value) {
        if (value != null) {
            sb.append(',').append(quote(key)).append(':').append(quote(value));
        }
    }

    /** 协议事件名集合（供架构守卫与测试避免依赖实现常量时对照）。 */
    static @NonNull Map<String, String> WIRE_NAMES = Map.ofEntries(
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
            Map.entry("done", "done"));
}
