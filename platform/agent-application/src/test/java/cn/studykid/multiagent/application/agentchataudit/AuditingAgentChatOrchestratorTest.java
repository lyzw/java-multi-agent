package cn.studykid.multiagent.application.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.Session;

/**
 * 编排链审计装饰器的行为验证。
 *
 * <p><b>覆盖四件事</b>：① 每一帧都被记录且<b>顺序与转发一致</b>；
 * ② 逐分片按到达顺序编号；③ 终态收敛（成功 / error 帧 / 客户端断开 / 静默结束）；
 * ④ 转发对象与客户端收到的<b>完全一致</b>（装饰器不改写任何帧）。
 *
 * <p><b>反向验证</b>：把 {@code collector.capture(...)} 移到 {@code sink.accept(...)} 之后，
 * 「客户端断开时最后已发帧必须已记录」这条断言会红。
 */
class AuditingAgentChatOrchestratorTest {

    private final RecordingSession session = new RecordingSession();
    private final ChatAuditScope scope = new ChatAuditScope(
            session, ChatAuditConfig.defaults(), "audit-1", "trace-1",
            "/api/agent/chat/stream", Instant.now());

    @AfterEach
    void clearHolder() {
        ChatAuditContextHolder.clear();
    }

    private static AgentChatRequest.Context context() {
        return new AgentChatRequest.Context(
                "11111111-1111-4111-8111-111111111111", "user-1",
                AgentChatRequest.Context.Domain.TENANT, true);
    }

    private static AgentChatRequest request() {
        return new AgentChatRequest("为什么 1+1=2", List.of(), null, 5, true, null,
                "tutoring", null);
    }

    @Test
    @DisplayName("✅ 成功链路：帧顺序/分片序/终态/结果全部落审计")
    void recordsFramesChunksAndSuccessState() {
        ChatAuditContextHolder.bind(scope);
        List<AgentChatEvent> forwarded = new ArrayList<>();

        AgentChatOrchestratorPort delegate = (ctx, req, sink) -> {
            sink.accept(new AgentChatEvent.RunStart("run-1"));
            sink.accept(new AgentChatEvent.Thinking("先想"));
            sink.accept(new AgentChatEvent.Delta("1+1"));
            sink.accept(new AgentChatEvent.Delta("=2"));
            sink.accept(new AgentChatEvent.Answer("1+1=2"));
            sink.accept(new AgentChatEvent.Usage(10, 3));
            sink.accept(new AgentChatEvent.Done(false));
        };

        new AuditingAgentChatOrchestrator(delegate)
                .run(context(), request(), forwarded::add);

        assertThat(session.frames).containsExactly(
                "run.start", "thinking", "delta", "delta", "answer", "usage", "done");
        // thinking 也是「到达顺序」的一部分：逐分片记录不区分类型先后（决策 4）
        assertThat(session.chunks).containsExactly("thinking:先想", "delta:1+1", "delta:=2");
        assertThat(session.terminalState).isEqualTo("SUCCEEDED");
        assertThat(session.error).isNull();
        assertThat(session.result).isNotNull();
        assertThat(session.result.finalAnswer()).isEqualTo("1+1=2");
        assertThat(session.result.totalTokens()).isEqualTo(13);
        // 转发条数必须与记录条数一致：装饰器不得吞帧、不得加帧
        assertThat(forwarded).hasSize(7);
    }

    @Test
    @DisplayName("⛔ error 帧 → FAILED，且错误码/消息进审计")
    void errorFrameYieldsFailed() {
        ChatAuditContextHolder.bind(scope);
        AgentChatOrchestratorPort delegate = (ctx, req, sink) -> {
            sink.accept(new AgentChatEvent.Delta("部分"));
            sink.accept(new AgentChatEvent.Error("PROVIDER_ERROR", "模型不可用"));
            sink.accept(new AgentChatEvent.Done(false));
        };

        new AuditingAgentChatOrchestrator(delegate).run(context(), request(), e -> { });

        assertThat(session.terminalState).isEqualTo("FAILED");
        assertThat(session.error).isNotNull();
        assertThat(session.error.phase()).isEqualTo("answer");
        assertThat(session.error.code()).isEqualTo("PROVIDER_ERROR");
        assertThat(session.error.message()).isEqualTo("模型不可用");
    }

    @Test
    @DisplayName("客户端断开 → CLIENT_DISCONNECTED，且断开前已转发的帧仍被记录")
    void clientGoneYieldsClientDisconnected() {
        ChatAuditContextHolder.bind(scope);
        AgentChatOrchestratorPort delegate = (ctx, req, sink) -> {
            sink.accept(new AgentChatEvent.Delta("一"));
            // 第二帧模拟「已记录、转发失败」
            throw new AgentChatOrchestratorPort.SinkClosedException("客户端断开");
        };

        assertThatThrownBy(() -> new AuditingAgentChatOrchestrator(delegate)
                .run(context(), request(), e -> {
                    if (e instanceof AgentChatEvent.Delta) {
                        throw new AgentChatOrchestratorPort.SinkClosedException("断开");
                    }
                }))
                .isInstanceOf(AgentChatOrchestratorPort.SinkClosedException.class);

        assertThat(session.terminalState).isEqualTo("CLIENT_DISCONNECTED");
        assertThat(session.frames).containsExactly("delta");
    }

    @Test
    @DisplayName("⛔ 没有 done 也没有异常 → FAILED（静默结束不得记成功）")
    void silentEndIsFailed() {
        ChatAuditContextHolder.bind(scope);
        AgentChatOrchestratorPort delegate = (ctx, req, sink) ->
                sink.accept(new AgentChatEvent.Delta("半截"));

        new AuditingAgentChatOrchestrator(delegate).run(context(), request(), e -> { });

        assertThat(session.terminalState).isEqualTo("FAILED");
        assertThat(session.error).isNotNull();
        assertThat(session.error.code()).isEqualTo("STREAM_ENDED_WITHOUT_DONE");
    }

    @Test
    @DisplayName("无审计作用域时原样委托（插件关闭零副作用）")
    void noScopeDelegates() {
        ChatAuditContextHolder.clear();
        List<AgentChatEvent> forwarded = new ArrayList<>();
        AgentChatOrchestratorPort delegate = (ctx, req, sink) ->
                sink.accept(new AgentChatEvent.Done(false));

        new AuditingAgentChatOrchestrator(delegate).run(context(), request(), forwarded::add);

        assertThat(forwarded).hasSize(1);
        assertThat(session.finished).isFalse();
    }

    /** 记录型会话替身：只收集，不做任何 I/O。 */
    private static final class RecordingSession implements Session {
        private final List<String> frames = new ArrayList<>();
        private final List<String> chunks = new ArrayList<>();
        private @Nullable Result result;
        private @Nullable ErrorInfo error;
        private @Nullable String terminalState;
        private boolean finished;

        @Override public void identify(@NonNull String domain, @Nullable String tenantId,
                                       @Nullable String principalId) { }
        @Override public void recordRequest(@Nullable RequestInfo request, boolean t) { }
        @Override public void setRawRequestBody(@Nullable String body, boolean t) { }
        @Override public void recordModelMessages(@Nullable String sp,
                                                 @NonNull List<Turn> messages) { }
        @Override public void recordFrame(@NonNull String name, @Nullable String data) {
            frames.add(name);
        }
        @Override public void recordChunk(@NonNull String kind, @NonNull String text) {
            chunks.add(kind + ":" + text);
        }
        @Override public void setResult(@Nullable Result r) { this.result = r; }
        @Override public void setHttpStatus(int status) { }
        @Override public void markAnswerTruncated() { }
        /** 测试替身不区分两阶段：seal 无副作用（真实实现在 flushIfReady 里等它）。 */
        @Override public void seal() {
        }

        @Override public void finish(@NonNull String state, @Nullable ErrorInfo e) {
            if (finished) {
                return;
            }
            this.terminalState = state;
            this.error = e;
            this.finished = true;
        }
    }
}
