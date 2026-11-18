package cn.studykid.multiagent.application.agentchataudit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 模型网关审计装饰器：记录送模型的 system prompt / messages，以及逐分片 delta / thinking。
 *
 * <p><b>⚠️ 分片只在「直连链」记录</b>：{@code /api/chat/stream} 这类直连链路没有编排器，
 * 分片只在这里出现一次。<b>编排链</b>的分片已经由
 * {@link AuditingAgentChatOrchestrator} 从 {@code delta} / {@code thinking} 帧记录，
 * 若这里再记一遍，同一段文本会出现两次（评估时 token 数与顺序全部失真）。
 * 判定依据是 {@link ChatAuditScope#orchestrated()}——编排链在自己的入口置位。
 *
 * <p><b>⚠️ 消息序列「后者覆盖前者」</b>：一条编排链会多次调用模型
 * （路由语义选择 → 正式回答 → 工具后续轮次），每次都会经过本装饰器。
 * V1 只保留<b>最后一次</b>送模型的消息，因为那才是「本轮真正决定的输入」；
 * 完整多轮轨迹由事件帧（{@code tool} / {@code route} / {@code usage}）还原。
 * 想连路由调用一起留，可开 {@code scope.includeRoutingModelCalls}（目前随配置记录，
 * 不额外区分调用类型——记录的是「确实送出去了什么」）。
 *
 * <p><b>⚠️ fail-open</b>：审计异常绝不影响模型调用与下游回调。
 */
public final class AuditingChatStreamGateway implements ChatStreamGateway {

    private static final Logger log = LoggerFactory.getLogger(AuditingChatStreamGateway.class);

    private final ChatStreamGateway delegate;

    public AuditingChatStreamGateway(@NonNull ChatStreamGateway delegate) {
        this.delegate = delegate;
    }

    /** 被装饰的真实网关（接线守卫用；见编排器装饰器同名方法的纪律说明）。 */
    public @NonNull ChatStreamGateway unwrap() {
        return delegate;
    }

    @Override
    public @NonNull ModelResponse streamChat(@NonNull ModelRequest request,
                                             @NonNull StreamDeltaConsumer consumer) {
        ChatAuditScope scope = ChatAuditContextHolder.current();
        if (scope == null) {
            return delegate.streamChat(request, consumer);
        }

        recordModelMessages(scope, request);
        final boolean recordChunks = !scope.orchestrated();
        Instant started = Instant.now();
        StringBuilder answer = new StringBuilder();

        StreamDeltaConsumer audited = new StreamDeltaConsumer() {
            @Override
            public void onDelta(@NonNull String token) {
                if (recordChunks) {
                    guarded(() -> scope.recordChunk("delta", token));
                    answer.append(token);
                }
                consumer.onDelta(token);
            }

            @Override
            public void onThinking(@NonNull String token) {
                if (recordChunks) {
                    scope.recordChunk("thinking", token);
                }
                consumer.onThinking(token);
            }
        };

        boolean converged = false;
        try {
            ModelResponse response = delegate.streamChat(request, audited);
            if (recordChunks) {
                scope.setResult(new Result(
                        response.content() == null || response.content().isBlank()
                                ? (answer.isEmpty() ? null : answer.toString())
                                : response.content(),
                        response.finishReason().name(),
                        0, 0,
                        response.usage().inputTokens() + response.usage().outputTokens(),
                        false));
            }
            return response;
        } catch (RuntimeException e) {
            // 直连链没有编排器兜底：错误在这里收敛，否则记录会停在「只写了 request」
            if (recordChunks) {
                scope.setResult(new Result(
                        answer.isEmpty() ? null : answer.toString(), null, 0, 0, 0, false));
                scope.finish("FAILED", new ErrorInfo("model", kindOf(e), safeMessage(e)));
                converged = true;
            }
            throw e;
        } finally {
            // 成功路径的收敛放这里：无论 return 还是异常，都要保证至少一次收敛尝试。
            // ⚠️ 用局部标志而非「依赖会话幂等」来表达意图——两者都对，
            //    但读代码的人不该需要先知道会话是幂等的。
            if (recordChunks && !converged) {
                scope.finish("SUCCEEDED", null);
                recordElapsed(scope, started);
            }
        }
    }

    /** 消息序列与 system prompt（多轮以 messages 为准，与 ModelRequest 的语义一致）。 */
    private static void recordModelMessages(@NonNull ChatAuditScope scope,
                                            @NonNull ModelRequest request) {
        try {
            List<Turn> turns = new ArrayList<>();
            List<ModelMessage> messages = request.messages();
            if (messages == null || messages.isEmpty()) {
                turns.add(new Turn("user", request.userContent()));
            } else {
                for (ModelMessage message : messages) {
                    turns.add(new Turn(message.role().name().toLowerCase(java.util.Locale.ROOT),
                            message.content()));
                }
            }
            scope.recordModelMessages(request.systemPrompt(), turns);
        } catch (RuntimeException e) {
            log.warn("审计记录模型消息失败（已忽略）", e);
        }
    }

    private static void recordElapsed(@NonNull ChatAuditScope scope, @NonNull Instant started) {
        // 仅作日志线索：耗时以 envelope 的 durationMs 为准
        long ms = java.time.Duration.between(started, Instant.now()).toMillis();
        if (ms > 60_000L) {
            log.info("直连对话流式耗时较长: auditId={}, {}ms", scope.auditId(), ms);
        }
    }

    private static void guarded(@NonNull Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("审计分片记录失败（已忽略）", e);
        }
    }

    private static @NonNull String kindOf(@NonNull RuntimeException e) {
        String name = e.getClass().getSimpleName();
        return name.isBlank() ? "MODEL_ERROR" : name;
    }

    private static @NonNull String safeMessage(@Nullable Throwable e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
