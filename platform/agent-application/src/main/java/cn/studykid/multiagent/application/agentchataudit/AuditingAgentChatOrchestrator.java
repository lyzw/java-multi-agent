package cn.studykid.multiagent.application.agentchataudit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;

/**
 * 编排链审计装饰器：包住 {@link AgentChatOrchestratorPort}，记录全部 SSE 帧与逐分片。
 *
 * <p><b>⚠️ 只观察，不改写</b>：每个事件<b>先记后转发</b>，转发的是<b>原对象</b>。
 * 任何字段改动都会让审计看到的内容与客户端收到的不同——那是最坏的失真：
 * 记录看起来完整，却对不上线上事实。
 *
 * <p><b>⚠️ 为什么先记后转发</b>：转发可能抛 {@code SinkClosedException}（客户端断开），
 * 先记能保证「客户端已经收到的那一帧」一定在记录里。
 *
 * <p><b>⚠️ fail-open</b>：记录动作全部包在 try/catch 里，绝不因为审计异常改变业务流。
 *
 * <p><b>终态收敛</b>：{@code done} → SUCCEEDED；{@code error} 帧 → FAILED（带错误码与阶段）；
 * 客户端断开 → CLIENT_DISCONNECTED；其它异常 → FAILED。
 * <b>没有 {@code done} 也没有异常</b>的收尾也算 FAILED——「静悄悄结束」不能记成成功。
 */
public final class AuditingAgentChatOrchestrator implements AgentChatOrchestratorPort {

    private static final Logger log = LoggerFactory.getLogger(AuditingAgentChatOrchestrator.class);

    private final AgentChatOrchestratorPort delegate;

    public AuditingAgentChatOrchestrator(@NonNull AgentChatOrchestratorPort delegate) {
        this.delegate = delegate;
    }

    /**
     * 被装饰的真实编排器。
     *
     * <p>⚠️ 仅供<b>接线守卫</b>使用（{@code AgentChatConfigurationWiringTest} 要反射检查
     * 真实实现是否把每个依赖都注入了）。业务代码不接受「装饰器再取内部实例」的写法——
     * 那等于绕开审计，把「审计是否生效」变成调用方的自觉。
     */
    public @NonNull AgentChatOrchestratorPort unwrap() {
        return delegate;
    }

    @Override
    public void run(AgentChatRequest.@NonNull Context context,
                    @NonNull AgentChatRequest request,
                    @NonNull Sink sink) {
        ChatAuditScope scope = ChatAuditContextHolder.current();
        if (scope == null) {
            delegate.run(context, request, sink);
            return;
        }

        scope.markOrchestrated();
        // 身份的唯一可信来源是已验签上下文（请求体里的 tenantId 只作为「目标」）。
        scope.identify(context.domain().name(), context.tenantId(), context.principalId());
        scope.recordRequest(structured(request), false);

        Collector collector = new Collector();
        Instant started = Instant.now();
        Sink auditingSink = event -> {
            collector.capture(scope, event, started);
            sink.accept(event);
        };

        String terminalState = "FAILED";
        ErrorInfo error = null;
        try {
            delegate.run(context, request, auditingSink);
            if (collector.error != null) {
                terminalState = "FAILED";
                error = collector.error;
            } else if (collector.doneSeen) {
                terminalState = "SUCCEEDED";
            } else {
                // 没有 done 就返回：流被中断但没有异常信号，属于「不可解释的收尾」
                terminalState = "FAILED";
                error = new ErrorInfo("answer", "STREAM_ENDED_WITHOUT_DONE",
                        "流已结束但没有终帧（done）");
            }
        } catch (SinkClosedException e) {
            terminalState = "CLIENT_DISCONNECTED";
            throw e;
        } catch (RuntimeException e) {
            terminalState = "FAILED";
            error = new ErrorInfo("orchestrator", "ORCHESTRATOR_ERROR", safeMessage(e));
            throw e;
        } finally {
            try {
                scope.setResult(collector.result());
                scope.finish(terminalState, error);
            } catch (RuntimeException e) {
                // 收敛失败不能顶掉业务异常
                log.warn("编排链审计收敛失败（已忽略）: auditId={}", scope.auditId(), e);
            }
        }
    }

    /** 结构化请求事实（原始 body 由 HTTP 过滤器补）。 */
    private static @NonNull RequestInfo structured(@NonNull AgentChatRequest request) {
        List<Turn> history = new ArrayList<>(request.history().size());
        for (AgentChatRequest.Turn turn : request.history()) {
            history.add(new Turn(turn.role(), turn.content()));
        }
        return new RequestInfo(null, request.message(), history, request.agentId(),
                request.topK(), request.thinkingMode(), request.scenario(),
                request.contextState(), null, List.of());
    }

    private static @NonNull String safeMessage(@NonNull RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        // 异常消息可能含上游报文片段：只留前 200 字符
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    /** 事件收集器：把 14 类帧折算成「结果 + 错误」，并逐帧/逐分片落审计。 */
    private static final class Collector {

        private final StringBuilder answer = new StringBuilder();
        private int frameCount;
        private long firstTokenMs;
        private long totalTokens;
        private boolean doneSeen;
        private boolean truncated;
        private ErrorInfo error;

        void capture(@NonNull ChatAuditScope scope,
                     @NonNull AgentChatEvent event,
                     @NonNull Instant started) {
            try {
                frameCount++;
                scope.recordFrame(event.eventName(), event.data());
                switch (event) {
                    case AgentChatEvent.Delta delta -> {
                        markFirstToken(scope, started);
                        answer.append(delta.text());
                        scope.recordChunk("delta", delta.text());
                    }
                    case AgentChatEvent.Thinking thinking -> {
                        markFirstToken(scope, started);
                        scope.recordChunk("thinking", thinking.text());
                    }
                    case AgentChatEvent.Answer finalAnswer -> {
                        // answer 是终答兜底：以它为准（delta 可能不完整）
                        if (finalAnswer.text() != null && !finalAnswer.text().isBlank()) {
                            answer.setLength(0);
                            answer.append(finalAnswer.text());
                        }
                    }
                    case AgentChatEvent.Usage usage -> totalTokens =
                            usage.inputTokens() + usage.outputTokens();
                    case AgentChatEvent.Error err -> error =
                            new ErrorInfo("answer", err.kind(), err.message());
                    case AgentChatEvent.Done done -> {
                        doneSeen = true;
                        truncated = done.truncated();
                    }
                    default -> {
                        // 其余事件只需帧本身（route / rag / skill / tool / handoff / checkpoint）
                    }
                }
            } catch (RuntimeException e) {
                log.warn("审计采集事件失败（已忽略该帧）: event={}", event.eventName(), e);
            }
        }

        private void markFirstToken(@NonNull ChatAuditScope scope, @NonNull Instant started) {
            if (firstTokenMs == 0L) {
                firstTokenMs = Math.max(0L,
                        java.time.Duration.between(started, Instant.now()).toMillis());
            }
        }

        @Nullable Result result() {
            return new Result(
                    answer.isEmpty() ? null : answer.toString(),
                    truncated ? "TRUNCATED" : (doneSeen ? "STOP" : null),
                    frameCount, firstTokenMs, totalTokens, false);
        }
    }
}
