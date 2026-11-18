package cn.studykid.multiagent.application.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.Session;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;

/**
 * 模型网关审计装饰器的行为验证。
 *
 * <p><b>核心断言是「不重复记分片」</b>：编排链的分片已经由事件帧记录，
 * 若网关再记一遍，同一段文本会出现两次——评估时 token 数与到达顺序全部失真，
 * 而且这种失真在页面上完全看不出来。反向验证：删掉 {@code recordChunks} 的
 * {@code orchestrated()} 判定，第二条测试立刻红。
 */
class AuditingChatStreamGatewayTest {

    private final RecordingSession session = new RecordingSession();
    private final ChatAuditScope scope = new ChatAuditScope(
            session, ChatAuditConfig.defaults(), "audit-2", "trace-2",
            "/api/chat/stream", Instant.now());

    @AfterEach
    void clearHolder() {
        ChatAuditContextHolder.clear();
    }

    private static ModelRequest request() {
        return new ModelRequest("MiniMax-M2.5", "你是老师", "",
                Double.valueOf(0.3d), Integer.valueOf(1024), Duration.ofSeconds(30), Map.of(),
                List.of(ModelMessage.user("1+1=?")));
    }

    private static ModelResponse response(String content) {
        return new ModelResponse(content, Map.of(),
                new ModelResponse.ModelUsage(10, 5), FinishReason.STOP);
    }

    @Test
    @DisplayName("直连链：逐分片 + 模型消息 + 结果全部记录，并收敛 SUCCEEDED")
    void directChainRecordsChunksAndFinishes() {
        ChatAuditContextHolder.bind(scope);

        ChatStreamGateway delegate = (req, consumer) -> {
            consumer.onDelta("1+1");
            consumer.onDelta("=2");
            consumer.onThinking("想一下");
            return response("1+1=2");
        };

        ModelResponse out = new AuditingChatStreamGateway(delegate)
                .streamChat(request(), new ChatStreamGateway.StreamDeltaConsumer() {
                    @Override public void onDelta(@NonNull String token) { }
                });

        assertThat(out.content()).isEqualTo("1+1=2");
        assertThat(session.chunks).containsExactly("delta:1+1", "delta:=2", "thinking:想一下");
        assertThat(session.modelMessages).hasSize(1);
        assertThat(session.systemPrompt).isEqualTo("你是老师");
        assertThat(session.result).isNotNull();
        assertThat(session.result.finalAnswer()).isEqualTo("1+1=2");
        assertThat(session.terminalState).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("⛔ 编排链内不重复记分片（分片由事件帧负责）")
    void orchestratedChainDoesNotDuplicateChunks() {
        scope.markOrchestrated();
        ChatAuditContextHolder.bind(scope);

        ChatStreamGateway delegate = (req, consumer) -> {
            consumer.onDelta("x");
            return response("x");
        };

        new AuditingChatStreamGateway(delegate)
                .streamChat(request(), new ChatStreamGateway.StreamDeltaConsumer() {
                    @Override public void onDelta(@NonNull String token) { }
                });

        assertThat(session.chunks).isEmpty();
        // 但模型消息仍要记（编排链的帧里没有「送模型的 messages」）
        assertThat(session.modelMessages).hasSize(1);
    }

    @Test
    @DisplayName("模型失败：直连链自己收敛 FAILED，并把异常继续抛给调用方")
    void modelFailureIsRecordedAndRethrown() {
        ChatAuditContextHolder.bind(scope);

        ChatStreamGateway delegate = (req, consumer) -> {
            throw new IllegalStateException("供应商 502");
        };

        assertThatThrownBy(() -> new AuditingChatStreamGateway(delegate)
                .streamChat(request(), new ChatStreamGateway.StreamDeltaConsumer() {
                    @Override public void onDelta(@NonNull String token) { }
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(session.terminalState).isEqualTo("FAILED");
        assertThat(session.error).isNotNull();
        assertThat(session.error.phase()).isEqualTo("model");
    }

    @Test
    @DisplayName("无审计作用域时原样委托（不产生任何记录）")
    void noScopeDelegates() {
        ChatAuditContextHolder.clear();
        ChatStreamGateway delegate = (req, consumer) -> response("ok");

        ModelResponse out = new AuditingChatStreamGateway(delegate)
                .streamChat(request(), new ChatStreamGateway.StreamDeltaConsumer() {
                    @Override public void onDelta(@NonNull String token) { }
                });

        assertThat(out.content()).isEqualTo("ok");
        assertThat(session.chunks).isEmpty();
        assertThat(session.finished).isFalse();
    }

    /** 记录型会话替身。 */
    private static final class RecordingSession implements Session {
        private final List<String> chunks = new ArrayList<>();
        private final List<Turn> modelMessages = new ArrayList<>();
        private @Nullable String systemPrompt;
        private @Nullable Result result;
        private @Nullable ErrorInfo error;
        private @Nullable String terminalState;
        private boolean finished;

        @Override public void identify(@NonNull String domain, @Nullable String tenantId,
                                       @Nullable String principalId) { }
        @Override public void recordRequest(@Nullable RequestInfo request, boolean t) { }
        @Override public void setRawRequestBody(@Nullable String body, boolean t) { }
        @Override public void recordModelMessages(@Nullable String sp,
                                                 @NonNull List<Turn> messages) {
            this.systemPrompt = sp;
            this.modelMessages.clear();
            this.modelMessages.addAll(messages);
        }
        @Override public void recordFrame(@NonNull String name, @Nullable String data) { }
        @Override public void recordChunk(@NonNull String kind, @NonNull String text) {
            chunks.add(kind + ":" + text);
        }
        @Override public void setResult(@Nullable Result r) { this.result = r; }
        @Override public void setHttpStatus(int status) { }
        @Override public void markAnswerTruncated() { }
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
