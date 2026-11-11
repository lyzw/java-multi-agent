package cn.studykid.multiagent.worker.agentchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditHandle;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditStep;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.BeginRecord;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.FinishRecord;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagResult;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagUnavailableException;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ToolCallRequest;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler;
import cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler.AgentModelSettings;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate;
import cn.studykid.multiagent.worker.execute.ToolInvocationResultFixture;

/**
 * 请求级 SSE 编排器的<b>行为守卫</b>（从编排入口进，不直连内部端口）。
 *
 * <p><b>反向验证口径</b>：每条关键守卫都对应一个「撤掉修复点必红」的动作，例如
 * <ul>
 *   <li>把 RAG 围栏从 {@code wrapToolResult} 改成裸拼 → {@code ragContextIsUntrustedUserMessage} 红；</li>
 *   <li>把租户 thinking 分支删掉 → {@code tenantChainNeverEmitsThinking} 红；</li>
 *   <li>把 RAG 失败 catch 改成静默 {@code return null} → {@code ragFailureIsComponentLevelVisible} 红；</li>
 *   <li>把 answer 改成用 {@code ModelResponse.content()} 拼接 → {@code answerEqualsStreamedDeltas} 红；</li>
 *   <li>把 {@code audit.finish} 从 SinkClosed 分支删掉 → {@code clientDisconnectStillConvergesAudit} 红；</li>
 *   <li>把 resultPayload 加上回答正文 → {@code auditStoresFactsOnlyNeverBodyText} 红。</li>
 * </ul>
 *
 * <p><b>挤出压力</b>：模型的 {@code ModelResponse.content()} 刻意与 delta 序列<b>不同</b>，
 * 使「answer 是否真的是用户看到的增量拼接」成为可证伪断言，而不是恒真的同义反复。
 * RAG 命中 / 工具输出 / 用户问题 / 回答各带唯一标记，确保「审计不存正文」能抓到任一漏点。
 */
class AgentChatOrchestratorTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    /** 故意与 Context 不同的「请求体租户」：证明身份只从 Context 取。 */
    private static final String SPOOF_TENANT = "99999999-9999-9999-9999-999999999999";
    private static final String AGENT = "agent-1";
    private static final String AGENT_NAME = "辅导助手";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String TOKEN = "signed-bearer-jwt";
    private static final String RAG_MARKER = "RAG_MARKER_外部语料片段";
    private static final String TOOL_MARKER = "TOOL_MARKER_工具输出";
    private static final String ANSWER_MARKER = "ANSWER_MARKER_可见正文";
    private static final String QUESTION_MARKER = "QUESTION_MARKER_怎么移项";
    private static final String SYSTEM_MARKER = "SYS_PROMPT_MARKER_可信指令";
    private static final String THINKING_SECRET = "推理草稿_绝不可下发租户";

    // ── 主链路 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 完整协议序 + 两段式审计：begin 先于 run.start，finish 是最后一步")
    void happyPathEmitsProtocolOrderAndTwoPhaseAudit() {
        Harness h = new Harness();
        h.rag.result = ragWith(RAG_HIT);
        h.toolResult = ToolInvocationResultFixture.succeeded("lookup", TOOL_MARKER);
        h.gateway
                .then(toolRound("前", "call-1", "lookup"))
                .then(answer("后"));

        h.orchestrator.run(h.platform(), h.request(SPOOF_TENANT), h.sink);

        // 协议序锁死：工具轮先吐 delta，再发 tool.call/tool.result；最后一轮吐 delta 后收口。
        // ⚠️ 用 containsExactly 而非 "有一行" 式弱断言：任何阶段顺序漂移都会立刻变红。
        assertThat(h.sink.names).containsExactly(
                "run.start", "route", "rag.query", "rag.hits",
                "skill", "skill",
                "delta", "tool", "tool",
                "delta", "answer", "usage", "done");

        // runId 必须来自真实落库行（run.start 在 audit.begin 之后）
        assertThat(h.order).contains("audit.begin").doesNotContainNull();
        assertThat(h.order.indexOf("audit.begin"))
                .as("run.start 的 runId 必须是已落库的行，不能先推帧再补写")
                .isLessThan(h.order.indexOf("sink:run.start"));
        assertThat(h.order.indexOf("audit.begin"))
                .as("审计占位必须先于工具调用（tool_operation_run_fk）")
                .isLessThan(h.order.indexOf("tool.invokeAll"));
        assertThat(h.order.indexOf("tool.invokeAll"))
                .isLessThan(h.order.indexOf("audit.finish"));
        assertThat(h.order.indexOf("audit.finish"))
                .as("finish 必须在 done 之前完成，客户端收到终帧时审计已收敛")
                .isLessThan(h.order.indexOf("sink:done"));

        // 两条独立防重线：SSE 专用 owner + 不写 Outbox（此处由仓储 IT 落库验证）
        assertThat(h.audit.beginRecord.fencingToken()).isZero();
        assertThat(h.audit.finishRecord.state()).isEqualTo("SUCCEEDED");
        assertThat(h.audit.finishCount).isEqualTo(1);
        assertThat(h.audit.handle.runId()).isEqualTo(firstOf(h.sink, AgentChatEvent.RunStart.class).runId());

        // 工具执行必须带显式 ExecutionScope（固定版本 + 闭包指纹）
        assertThat(h.scopes).singleElement().satisfies(scope -> {
            assertThat(scope.agentId()).isEqualTo(AGENT);
            assertThat(scope.agentVersion()).isEqualTo(1);
            assertThat(scope.expectedFingerprint()).isNotBlank();
        });

        // 最后一轮强制无工具 Schema，保证收敛出自然语言终答。
        assertThat(h.gateway.requests.get(0).toolSchemas()).isNotEmpty();
        assertThat(h.gateway.requests.get(1).toolSchemas()).isEmpty();
    }

    @Test
    @DisplayName("⛔ answer 是用户实际看到的 delta 拼接，不是 ModelResponse.content() 的拼接")
    void answerEqualsStreamedDeltas() {
        Harness h = new Harness();
        h.gateway.then(toolRound("前", "call-1", "lookup")).then(answer("后"));
        h.toolResult = ToolInvocationResultFixture.succeededSilently("lookup");

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        String streamed = allOf(h.sink, AgentChatEvent.Delta.class).stream()
                .map(AgentChatEvent.Delta::text).reduce("", String::concat);
        assertThat(streamed).isEqualTo("前后");
        assertThat(firstOf(h.sink, AgentChatEvent.Answer.class).text())
                .as("answer 必须与逐帧 delta 同前缀；用 ModelResponse.content() 拼会变成「整段正文-非增量」")
                .isEqualTo("前后")
                .doesNotContain("整段正文-非增量");
    }

    // ── RAG 语义 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 命中以不可信 USER 消息围栏注入，绝不进 systemPrompt")
    void ragContextIsUntrustedUserMessage() {
        Harness h = new Harness();
        h.rag.result = ragWith(RAG_HIT);
        h.gateway.then(answer(ANSWER_MARKER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        ModelRequest first = h.gateway.requests.get(0);
        assertThat(first.systemPrompt()).doesNotContain(RAG_MARKER);
        assertThat(first.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ModelMessage.Role.USER);
            assertThat(m.content()).contains(RAG_MARKER)
                    .contains(PromptGuard.TOOL_RESULT_OPEN)
                    .contains(PromptGuard.TOOL_RESULT_CLOSE);
        });
        assertThat(firstOf(h.sink, AgentChatEvent.RagHits.class).injected()).isTrue();
        // 已验签令牌只用于服务间鉴权，绝不进事件 / 模型上下文
        assertThat(h.sink.events.stream().map(AgentChatEvent::data).reduce("", String::concat))
                .doesNotContain(TOKEN);
        assertThat(first.messages().stream().map(ModelMessage::content).reduce("", String::concat))
                .doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("零命中是合法 info：不注入空围栏、不发组件错误、整轮仍成功")
    void zeroHitsAreLegalWithoutEmptyFence() {
        Harness h = new Harness();
        h.rag.result = new RagResult(List.of(), false, false, null, 0);
        h.gateway.then(answer(ANSWER_MARKER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.RagHits.class).injected()).isFalse();
        assertThat(firstOf(h.sink, AgentChatEvent.RagHits.class).hits()).isEmpty();
        assertThat(h.sink.names).doesNotContain("error");
        assertThat(h.sink.events).noneMatch(e -> e instanceof AgentChatEvent.Skill s
                && "rag".equals(s.skillId()) && "error".equals(s.phase()));
        assertThat(h.gateway.requests.get(0).messages().stream()
                .map(ModelMessage::content).reduce("", String::concat))
                .doesNotContain(PromptGuard.TOOL_RESULT_OPEN);
        assertThat(h.audit.finishRecord.state()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("⛔ RAG 不可用是组件级失败：skill.error 可见，绝不伪造命中/静默当零命中")
    void ragFailureIsComponentLevelVisible() {
        Harness h = new Harness();
        h.rag.failure = new RagUnavailableException("RAG 服务不可达 503");
        h.gateway.then(answer(ANSWER_MARKER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(h.sink.events).anyMatch(e -> e instanceof AgentChatEvent.Skill s
                && "rag".equals(s.skillId())
                && "error".equals(s.phase())
                && s.status() == AgentChatEvent.StepStatus.ERROR
                && s.error().contains("RAG 服务不可达"));
        assertThat(firstOf(h.sink, AgentChatEvent.RagHits.class).hits()).isEmpty();
        assertThat(firstOf(h.sink, AgentChatEvent.RagHits.class).injected()).isFalse();
        // 组件级失败后仍继续生成，整轮不算失败
        assertThat(h.sink.names).doesNotContain("error").endsWith("done");
        assertThat(h.audit.finishRecord.state()).isEqualTo("SUCCEEDED");
        assertThat(h.audit.finishRecord.events()).anyMatch(e -> "RAG_UNAVAILABLE".equals(e.eventType()));
    }

    // ── 身份域 / 推理草稿 ────────────────────────────────────────────

    @Test
    @DisplayName("平台链可下发 thinking，正文链路不变")
    void platformChainEmitsThinking() {
        Harness h = new Harness();
        h.gateway.then(thinkingThenAnswer(THINKING_SECRET, ANSWER_MARKER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Thinking.class).text()).isEqualTo(THINKING_SECRET);
        assertThat(h.sink.names).containsSubsequence("thinking", "delta", "done");
    }

    @Test
    @DisplayName("⛔ 租户链恒丢弃推理草稿（含正文），但保留组件过程与终答")
    void tenantChainNeverEmitsThinking() {
        Harness h = new Harness();
        h.gateway.then(thinkingThenAnswer(THINKING_SECRET, ANSWER_MARKER));

        h.orchestrator.run(h.tenant(), h.request(null), h.sink);

        assertThat(h.sink.events).noneMatch(e -> e instanceof AgentChatEvent.Thinking);
        assertThat(h.sink.events.stream().map(AgentChatEvent::data).reduce("", String::concat))
                .doesNotContain(THINKING_SECRET);
        assertThat(h.sink.names).contains("rag.query", "rag.hits", "delta", "answer", "done");
        // 审计上下文仍来自 Context，租户链请求体 tenantId 被忽略
        assertThat(h.audit.beginRecord.tenantId()).isEqualTo(TENANT);
    }

    // ── 工具组件级失败 ────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 工具被拒是组件级失败：tool.error 可见且失败原因回灌，整轮仍生成终答")
    void rejectedToolIsComponentLevelAndFeedbackIsReinjected() {
        Harness h = new Harness();
        h.toolResult = ToolInvocationResultFixture.rejected("lookup", "策略拒绝：未获授权");
        h.gateway.then(toolRound("前", "call-1", "lookup")).then(answer("后"));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(h.sink.events).anyMatch(e -> e instanceof AgentChatEvent.Tool t
                && "error".equals(t.phase())
                && t.status() == AgentChatEvent.StepStatus.ERROR
                && t.error().contains("策略拒绝：未获授权"));
        assertThat(h.sink.names).doesNotContain("error").endsWith("done");
        // 失败也回灌：下一轮模型必须看到「被拒（未执行）」，否则会换个姿势重试同一动作
        assertThat(h.gateway.requests.get(1).messages())
                .anySatisfy(m -> assertThat(m.content()).contains("被拒绝（未执行"));
        assertThat(h.audit.finishRecord.events()).anyMatch(e -> "TOOL_ROUND".equals(e.eventType()));
        assertThat(h.audit.finishRecord.state()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("工具结果回灌成「assistant 带调用 + tool 同标识」不可信消息对")
    void toolOutcomeReinjectedAsUntrustedPairedMessages() {
        Harness h = new Harness();
        h.toolResult = ToolInvocationResultFixture.succeeded("lookup", TOOL_MARKER);
        h.gateway.then(toolRound("前", "call-1", "lookup")).then(answer("后"));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        List<ModelMessage> round2 = h.gateway.requests.get(1).messages();
        assertThat(round2).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ModelMessage.Role.ASSISTANT);
            assertThat(m.toolCalls()).singleElement()
                    .satisfies(echo -> assertThat(echo.callId()).isEqualTo("call-1"));
        });
        assertThat(round2).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ModelMessage.Role.TOOL);
            assertThat(m.toolCallId()).isEqualTo("call-1");
            assertThat(m.content()).contains(TOOL_MARKER)
                    .contains(PromptGuard.TOOL_RESULT_OPEN);
        });
    }

    @Test
    @DisplayName("委派工具触发 handoff 事件（模型请求委派子 Agent）")
    void delegationToolEmitsHandoff() {
        Harness h = new Harness();
        h.toolResult = ToolInvocationResultFixture.rejected(DelegationToolSchema.TOOL_NAME, "未授权委派");
        h.gateway.then(toolRound("前", "call-1", DelegationToolSchema.TOOL_NAME)).then(answer("后"));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Handoff.class).from()).isEqualTo(AGENT);
        assertThat(h.sink.names).containsSubsequence("tool", "handoff", "delta");
    }

    // ── fail-closed ───────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 路由不确定立即 fail-closed：error + done，绝不随便选一个 / 不伪造 RAG")
    void ambiguousRoutingFailsClosed() {
        Harness h = new Harness();
        h.router.failure = new AgentRouter.RoutingException("多候选且模型未给出合法目标");

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Error.class).kind()).isEqualTo("ROUTE_FAILED");
        assertThat(h.sink.names).doesNotContain("run.start", "rag.query", "delta", "answer")
                .endsWith("error", "done");
        assertThat(h.audit.beginRecord).as("路由失败未建立 Run，不应有半条审计").isNull();
        assertThat(h.audit.finishCount).isZero();
    }

    @Test
    @DisplayName("⛔ Agent 结构化配置缺失 fail-closed（CONFIG_UNAVAILABLE）")
    void missingModelConfigFailsClosed() {
        Harness h = new Harness();
        when(h.modelConfig.assemble(anyString(), anyString(), anyInt())).thenReturn(Optional.empty());

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Error.class).kind()).isEqualTo("CONFIG_UNAVAILABLE");
        assertThat(h.sink.names).doesNotContain("run.start", "rag.query").endsWith("error", "done");
    }

    @Test
    @DisplayName("⛔ 无工具轮次仍请求工具 → fail-closed，不做无限工具循环")
    void toolCallsOnFinalRoundFailClosed() {
        Harness h = new Harness();
        h.gateway
                .then(toolRound("前", "call-1", "lookup"))
                .then(toolRound("后", "call-2", "lookup"));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Error.class).kind())
                .isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(h.sink.names).endsWith("error", "done").doesNotContain("answer");
        assertThat(h.scopes).as("第二轮没有工具 Schema，闸门不得再被调用").hasSize(1);
        assertThat(h.gateway.requests.get(1).toolSchemas()).isEmpty();
        assertThat(h.audit.finishRecord.state()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("⛔ 内容安全拦截 fail-closed（CONTENT_BLOCKED）")
    void contentFilterBlocksWholeRun() {
        Harness h = new Harness();
        h.gateway.then(consumer -> new ModelResponse("", Map.of(),
                new ModelUsage(1, 0), FinishReason.CONTENT_FILTER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(firstOf(h.sink, AgentChatEvent.Error.class).kind()).isEqualTo("CONTENT_BLOCKED");
        assertThat(h.sink.names).endsWith("error", "done").doesNotContain("answer");
        assertThat(h.audit.finishRecord.state()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("⛔ 模型异常归一：error.kind=TIMEOUT，不泄漏供应商报文原文")
    void modelErrorNormalizedWithoutLeakingProviderText() {
        Harness h = new Harness();
        h.gateway.then(consumer -> {
            throw new ModelGatewayException(ModelErrorKind.TIMEOUT,
                    "PROVIDER_SECRET_DETAIL upstream raw payload");
        });

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        AgentChatEvent.Error error = firstOf(h.sink, AgentChatEvent.Error.class);
        assertThat(error.kind()).isEqualTo("TIMEOUT");
        assertThat(error.message()).doesNotContain("PROVIDER_SECRET_DETAIL");
        assertThat(h.sink.names).contains("run.start", "rag.hits").endsWith("error", "done");
        assertThat(h.audit.finishRecord.state()).isEqualTo("FAILED");
        assertThat(h.audit.finishRecord.errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("⛔ 客户端断开：不再推任何帧，但审计仍收敛 FAILED / CLIENT_DISCONNECTED")
    void clientDisconnectStillConvergesAudit() {
        Harness h = new Harness();
        h.gateway.then(thinkingThenAnswer(THINKING_SECRET, ANSWER_MARKER));
        h.sink.onEvent = event -> {
            if (event instanceof AgentChatEvent.Delta) {
                throw new AgentChatOrchestratorPort.SinkClosedException("SSE 客户端已断开");
            }
        };

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        assertThat(h.sink.names).doesNotContain("delta", "answer", "usage", "error", "done");
        assertThat(h.audit.finishCount).isEqualTo(1);
        assertThat(h.audit.finishRecord.state()).isEqualTo("FAILED");
        assertThat(h.audit.finishRecord.errorCode()).isEqualTo("CLIENT_DISCONNECTED");
        assertThat(h.audit.finishRecord.resultPayload()).contains("\"finishReason\":\"FAILED\"");
    }

    @Test
    @DisplayName("⛔ 非法请求 fail-closed：四种畸形输入都发 INVALID_REQUEST + done")
    void invalidRequestsFailClosed() {
        List<AgentChatRequest> bad = List.of(
                new AgentChatRequest("  ", List.of(), null, null, false, null, null),
                new AgentChatRequest("x".repeat(32_001), List.of(), null, null, false, null, null),
                new AgentChatRequest("q", List.of(new AgentChatRequest.Turn("system", "越权")),
                        null, null, false, null, null),
                new AgentChatRequest("q", java.util.stream.IntStream.range(0, 101)
                        .mapToObj(i -> new AgentChatRequest.Turn("user", "m" + i)).toList(),
                        null, null, false, null, null));

        for (AgentChatRequest request : bad) {
            Harness h = new Harness();
            h.orchestrator.run(h.platform(), request, h.sink);
            assertThat(firstOf(h.sink, AgentChatEvent.Error.class).kind())
                    .isEqualTo("INVALID_REQUEST");
            assertThat(h.sink.names).endsWith("error", "done").doesNotContain("run.start");
            assertThat(h.audit.beginRecord).isNull();
        }
    }

    // ── 审计"不存正文" ──────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 审计只存标量事实：用户问题 / RAG / 工具输出 / 回答 / 推理草稿一律不落库")
    void auditStoresFactsOnlyNeverBodyText() {
        Harness h = new Harness();
        h.rag.result = ragWith(RAG_HIT);
        h.toolResult = ToolInvocationResultFixture.succeeded("lookup", TOOL_MARKER);
        // 第 1 轮思考+工具调用，第 2 轮终答；两种正文标记都要出现，才能验证都没落库。
        h.gateway.then(thinkingToolRound(THINKING_SECRET, "前", "call-1", "lookup"))
                .then(answer(ANSWER_MARKER));

        h.orchestrator.run(h.platform(), h.request(null), h.sink);

        String beginPayload = String.valueOf(h.audit.beginRecord.inputPayload());
        String finishPayload = h.audit.finishRecord.resultPayload();
        String eventPayloads = h.audit.finishRecord.events().stream()
                .map(AuditEvent::payload).map(String::valueOf).reduce("", String::concat);
        String stepPayloads = h.audit.finishRecord.steps().stream()
                .map(AuditStep::stepType).reduce("", String::concat);

        for (String blob : List.of(beginPayload, finishPayload, eventPayloads, stepPayloads)) {
            assertThat(blob)
                    .as("审计载荷只含摘要/计数/状态码，不得出现任何对话或外部数据正文")
                    .doesNotContain(QUESTION_MARKER, RAG_MARKER, TOOL_MARKER,
                            ANSWER_MARKER, THINKING_SECRET, SYSTEM_MARKER, TOKEN);
        }
        // 事实字段必须在：数字算对了，不只是"有一行"
        assertThat(finishPayload).contains("\"ragHits\":1").contains("\"toolRounds\":1")
                .contains("\"modelRounds\":2").contains("\"ragInjected\":true");
        assertThat(h.audit.beginRecord.inputDigest()).hasSize(64);
        assertThat(h.audit.beginRecord.inputPayload()).containsEntry("scenario", "algebra");
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 每次测试独立的编排器与可观测替身；全部通过 {@code run()} 入口驱动。 */
    private static final class Harness {
        final List<String> order = new ArrayList<>();
        final RecordingSink sink = new RecordingSink(order);
        final RecordingAudit audit = new RecordingAudit(order);
        final ScriptedRag rag = new ScriptedRag();
        final ScriptedGateway gateway = new ScriptedGateway();
        final FixedRouter router = new FixedRouter();
        final FixedAgents agents = new FixedAgents();
        final FixedClosures closures = new FixedClosures();
        final AgentModelConfigAssembler modelConfig = mock(AgentModelConfigAssembler.class);
        final ToolInvocationGate toolGate = mock(ToolInvocationGate.class);
        final List<ExecutionScope> scopes = new ArrayList<>();
        ToolInvocationGate.ToolInvocationResult toolResult = ToolInvocationResultFixture.empty();
        final AgentChatOrchestrator orchestrator;

        Harness() {
            router.decision = new AgentRouter.Decision(AGENT, "单候选直选",
                    List.of(new AgentChatEvent.Candidate(AGENT, AGENT_NAME, null, null)));
            agents.items = List.of(new AgentQueryPort.AgentItem(
                    AGENT, AGENT_NAME, "初中数学辅导", 1, 1, "{}", Instant.now()));
            closures.closure = closureWithSkill();
            rag.result = new RagResult(List.of(), false, false, null, 0);
            when(modelConfig.assemble(anyString(), anyString(), anyInt()))
                    .thenReturn(Optional.of(new AgentModelSettings("model-x", SYSTEM_MARKER,
                            0.7, 2048, Duration.ofSeconds(30),
                            Map.of("lookup", Map.of("type", "function")))));
            doAnswer(invocation -> {
                order.add("tool.invokeAll");
                scopes.add(invocation.getArgument(3, ExecutionScope.class));
                return toolResult;
            }).when(toolGate).invokeAll(anyString(), any(RunClaimPort.LeasedTask.class),
                    org.mockito.ArgumentMatchers.<String, ToolCallRequest>anyMap(),
                    nullable(ExecutionScope.class));

            orchestrator = new AgentChatOrchestrator(
                    audit, rag, gateway, router, agents,
                    (tenantId, agentId) -> Optional.of(
                            new ExecutionSnapshotPort.SnapshotRef(SNAPSHOT, 1)),
                    closures, modelConfig, toolGate,
                    /* maxToolRounds */ 1, /* defaultTopK */ 5,
                    /* lease */ Duration.ofMinutes(1));
        }

        Context platform() {
            return new Context(TENANT, "principal-1", Context.Domain.PLATFORM, true, TOKEN);
        }

        Context tenant() {
            return new Context(TENANT, "principal-1", Context.Domain.TENANT, false, TOKEN);
        }

        AgentChatRequest request(String spoofTenant) {
            return new AgentChatRequest(QUESTION_MARKER, List.of(), null, 7, true,
                    spoofTenant, "algebra");
        }
    }

    private static EffectiveClosure closureWithSkill() {
        EffectiveClosure.SkillBinding skill = new EffectiveClosure.SkillBinding(
                "skill-solve", 1, CapabilityRiskLevel.READ, "fp-skill",
                List.of("lookup"), "{}");
        return new EffectiveClosure(AGENT, 1, List.of(skill), List.of(), List.of());
    }

    private static RagResult ragWith(AgentChatEvent.Hit hit) {
        return new RagResult(List.of(hit), false, false, "数学", 1);
    }

    private static final AgentChatEvent.Hit RAG_HIT =
            new AgentChatEvent.Hit("c-1", RAG_MARKER, 0.91, "第3页", "数学", "example", "语义命中");

    /** 一轮工具调用：先吐一段 delta，再返回 tool_calls。content 刻意与 delta 不同。 */
    private static Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> toolRound(
            String delta, String callId, String tool) {
        return consumer -> {
            if (!delta.isEmpty()) {
                consumer.onDelta(delta);
            }
            return new ModelResponse("整段正文-工具轮", Map.of(callId,
                    new ToolCallRequest(tool, Map.of("q", "x"), callId)),
                    new ModelUsage(10, 2), FinishReason.TOOL_CALLS);
        };
    }

    /** 一轮纯文本收口：content 刻意与 delta 不同，用于挤出「answer 取自谁」的假绿。 */
    private static Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> answer(String delta) {
        return consumer -> {
            consumer.onDelta(delta);
            return new ModelResponse("整段正文-非增量", Map.of(),
                    new ModelUsage(7, 3), FinishReason.STOP);
        };
    }

    private static Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> thinkingThenAnswer(
            String thinking, String delta) {
        return consumer -> {
            consumer.onThinking(thinking);
            consumer.onDelta(delta);
            return new ModelResponse("整段正文-非增量", Map.of(),
                    new ModelUsage(7, 3), FinishReason.STOP);
        };
    }

    /** 第一轮既出思考草稿又发起工具调用（用于「所有正文都不落审计」的挤出测试）。 */
    private static Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> thinkingToolRound(
            String thinking, String delta, String callId, String tool) {
        return consumer -> {
            consumer.onThinking(thinking);
            consumer.onDelta(delta);
            return new ModelResponse("整段正文-工具轮", Map.of(callId,
                    new ToolCallRequest(tool, Map.of("q", "x"), callId)),
                    new ModelUsage(10, 2), FinishReason.TOOL_CALLS);
        };
    }

    // ── 可观测替身 ────────────────────────────────────────────────────

    /** 事件汇聚：记录帧名与 data 字节，并可注入「客户端断开」。 */
    private static final class RecordingSink implements AgentChatOrchestratorPort.Sink {
        final List<AgentChatEvent> events = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> order;
        Consumer<AgentChatEvent> onEvent;

        RecordingSink(List<String> order) {
            this.order = order;
        }

        @Override
        public void accept(@NonNull AgentChatEvent event) {
            if (onEvent != null) {
                onEvent.accept(event); // 先断开，再决定是否记录（贴近真实 send 失败）
            }
            events.add(event);
            names.add(event.eventName());
            order.add("sink:" + event.eventName());
        }

        String allData() {
            return events.stream().map(AgentChatEvent::data).reduce("", String::concat);
        }
    }

    /** 两段式审计替身：记录 begin/finish 的真实入参与调用顺序。 */
    private static final class RecordingAudit implements AgentChatAuditPort {
        final List<String> order;
        BeginRecord beginRecord;
        FinishRecord finishRecord;
        AuditHandle handle;
        int finishCount;

        RecordingAudit(List<String> order) {
            this.order = order;
        }

        @Override
        public @NonNull AuditHandle begin(@NonNull BeginRecord record) {
            order.add("audit.begin");
            beginRecord = record;
            handle = new AuditHandle(record.runId(), record.tenantId(), record.fencingToken());
            return handle;
        }

        @Override
        public void finish(@NonNull AuditHandle h, @NonNull FinishRecord record) {
            order.add("audit.finish");
            finishCount++;
            finishRecord = record;
        }
    }

    /** RAG 替身：可注入不可用，并记录真实调用参数。 */
    private static final class ScriptedRag implements RagRetrievalPort {
        RagResult result = new RagResult(List.of(), false, false, null, 0);
        RuntimeException failure;
        String seenTenant;
        String seenToken;
        String seenQuery;
        int seenTopK;

        @Override
        public @NonNull RagResult retrieve(@NonNull String tenantId, @NonNull String bearerToken,
                                           @NonNull String query, int topK) {
            seenTenant = tenantId;
            seenToken = bearerToken;
            seenQuery = query;
            seenTopK = topK;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** 模型替身：按脚本回放每轮响应，并捕获每次 ModelRequest 以断言上下文形状。 */
    private static final class ScriptedGateway implements ChatStreamGateway {
        final Deque<Function<StreamDeltaConsumer, ModelResponse>> script = new ArrayDeque<>();
        final List<ModelRequest> requests = new ArrayList<>();

        ScriptedGateway then(Function<StreamDeltaConsumer, ModelResponse> step) {
            script.add(step);
            return this;
        }

        @Override
        public @NonNull ModelResponse streamChat(@NonNull ModelRequest request,
                                                 @NonNull StreamDeltaConsumer consumer) {
            requests.add(request);
            Function<StreamDeltaConsumer, ModelResponse> step = script.poll();
            if (step == null) {
                throw new AssertionError("意外的模型调用（第 " + requests.size() + " 轮）");
            }
            return step.apply(consumer);
        }
    }

    /** 路由替身：可注入不确定结论。 */
    private static final class FixedRouter implements AgentRouter {
        Decision decision;
        RuntimeException failure;

        @Override
        public @NonNull Decision route(@NonNull String tenantId, @NonNull String message,
                                       String explicitAgentId,
                                       @NonNull List<AgentQueryPort.AgentItem> candidates) {
            if (failure != null) {
                throw failure;
            }
            return decision;
        }
    }

    /** Agent 查询替身（3 个抽象方法，必须实现类）。 */
    private static final class FixedAgents implements AgentQueryPort {
        List<AgentItem> items = List.of();

        @Override
        public @NonNull Page<AgentItem> listAgents(@NonNull String tenantId, String cursor,
                                                   int limit) {
            return Page.of(items, null);
        }

        @Override
        public AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId) {
            return items.stream().filter(i -> i.agentId().equals(agentId)).findFirst().orElse(null);
        }

        @Override
        public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                            @NonNull String agentId) {
            return List.of();
        }
    }

    /** 闭包解析替身（2 个抽象方法）。 */
    private static final class FixedClosures implements ClosureResolver {
        EffectiveClosure closure;

        @Override
        public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                                 @NonNull String agentId, int agentVersion) {
            return closure;
        }

        @Override
        public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                        @NonNull String agentId) {
            return closure;
        }
    }

    private static <T extends AgentChatEvent> T firstOf(RecordingSink sink, Class<T> type) {
        return sink.events.stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError("缺少事件：" + type.getSimpleName()
                        + "，实际=" + sink.names));
    }

    private static <T extends AgentChatEvent> List<T> allOf(RecordingSink sink, Class<T> type) {
        return sink.events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
