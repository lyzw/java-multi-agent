package cn.studykid.multiagent.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 双端点 SSE 控制器契约守卫（不启 Spring 上下文，直接构造 + 替身）。
 *
 * <p><b>守住的是「两个身份域绝不混用」</b>：平台链必须显式目标租户且可下发 thinking；
 * 租户链身份只能来自令牌，请求体出现 tenantId 直接 400。这两条一旦回归，
 * 表现不是报错，而是<b>跨租户越权</b>（最危险的静默失真）。
 *
 * <p><b>帧格式守卫</b>：Spring 7 下 {@code emitter.send(String)} 会被包成
 * {@code data:event: xxx} 的废帧，前端一个事件都匹配不上（对话框永远停在生成中），
 * 故锁死「必须走 {@code SseEmitter.event()} 原生事件」。
 *
 * <p><b>反向验证锚点</b>：删掉租户链 tenantId 的 400 分支 →
 * {@code tenantChainRejectsBodyTenantId} 立即红；把 sendFrame 改成手工拼帧 →
 * {@code framesAreNativeSseEvents} 立即红。
 */
class AgentChatControllerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SPOOF_TENANT = "99999999-9999-9999-9999-999999999999";
    private static final String PRINCIPAL = "principal-1";
    private static final String TOKEN = "signed-bearer-jwt";
    private static final String RAW_STATE = "RAW_CONTEXT_STATE_FROM_CLIENT";

    // ── 端点映射 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 两个端点路径与两前端逐字对齐，且都是 text/event-stream")
    void bothEndpointsMappedWithStreamingContentType() throws Exception {
        Method platform = AgentChatController.class.getMethod(
                "streamPlatform", AgentChatController.AgentChatStreamRequest.class);
        Method tenant = AgentChatController.class.getMethod(
                "streamTenant", AgentChatController.AgentChatStreamRequest.class);

        PostMapping pm = platform.getAnnotation(PostMapping.class);
        PostMapping tm = tenant.getAnnotation(PostMapping.class);
        assertThat(pm).isNotNull();
        assertThat(tm).isNotNull();
        assertThat(pm.value()).containsExactly("/api/agent/chat/stream");
        assertThat(tm.value()).containsExactly("/api/knowledge/agent/chat/stream");
        assertThat(pm.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(tm.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(AgentChatController.PLATFORM_STREAM_PATH).isEqualTo(pm.value()[0]);
        assertThat(AgentChatController.TENANT_STREAM_PATH).isEqualTo(tm.value()[0]);
        assertThat(pm.value()[0]).isNotEqualTo(tm.value()[0]);
    }

    // ── 平台链 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("平台链：验签身份 + 显式目标租户 + 反代不缓冲响应头")
    void platformStreamUsesVerifiedIdentityAndHeaders() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        h.orchestrator.emitted = List.of(
                new AgentChatEvent.RunStart("run-1"),
                new AgentChatEvent.Done(false));

        ResponseEntity<SseEmitter> response = h.controller().streamPlatform(
                request("怎么移项", TENANT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .isEqualTo("no-cache, no-store");

        assertThat(h.identity.seenPlatformChain).isTrue();
        assertThat(h.identity.seenRequestedTenantId).isEqualTo(TENANT);
        assertThat(h.orchestrator.seenContext).isSameAs(h.identity.context);
        assertThat(h.orchestrator.seenRequest.requestedTenantId()).isEqualTo(TENANT);
        assertThat(h.emitter.completed).isTrue();
    }

    @Test
    @DisplayName("请求体字段逐字下传：history / agentId / topK / thinkingMode / scenario / contextState")
    void requestBodyFieldsPassedThrough() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        AgentChatController.AgentChatStreamRequest body = new AgentChatController.AgentChatStreamRequest(
                "本轮问题",
                List.of(new AgentChatController.AgentChatTurn("user", "上一轮"),
                        new AgentChatController.AgentChatTurn("assistant", "回答")),
                "agent-7", 13, true, TENANT, "algebra", RAW_STATE);
        h.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));

        h.controller().streamPlatform(body);

        AgentChatRequest seen = h.orchestrator.seenRequest;
        assertThat(seen.message()).isEqualTo("本轮问题");
        assertThat(seen.history()).containsExactly(
                new AgentChatRequest.Turn("user", "上一轮"),
                new AgentChatRequest.Turn("assistant", "回答"));
        assertThat(seen.agentId()).isEqualTo("agent-7");
        assertThat(seen.topK()).isEqualTo(13);
        assertThat(seen.thinkingMode()).isTrue();
        assertThat(seen.scenario()).isEqualTo("algebra");
        assertThat(seen.requestedTenantId()).isEqualTo(TENANT);
        assertThat(seen.contextState())
                .as("控制器不验签 / 不解码 / 不 trim：原始字节逐字交给编排边界")
                .isEqualTo(RAW_STATE);
    }

    @Test
    @DisplayName("空 history / null 请求体归一为合法空列表，不 NPE")
    void nullHistoryNormalized() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        h.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));

        h.controller().streamPlatform(new AgentChatController.AgentChatStreamRequest(
                "q", null, null, null, false, TENANT, null, null));
        assertThat(h.orchestrator.seenRequest.history()).isEmpty();

        h.orchestrator.seenRequest = null;
        h.controller().streamPlatform(new AgentChatController.AgentChatStreamRequest(
                "q", List.of(), null, null, false, TENANT, null, null));
        assertThat(h.orchestrator.seenRequest.history()).isEmpty();
    }

    // ── 租户链 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 租户链不得在请求体指定 tenantId（身份只能来自令牌）")
    void tenantChainRejectsBodyTenantId() {
        Harness h = new Harness();
        h.identity.context = tenantContext();

        assertThatThrownBy(() -> h.controller().streamTenant(request("q", TENANT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(h.identity.seenPlatformChain)
                .as("租户越权字段必须在身份解析之前就被拒绝").isFalse();
        assertThat(h.orchestrator.seenRequest).isNull();
    }

    @Test
    @DisplayName("租户链：租户取自令牌，编排请求不带 requestedTenantId")
    void tenantChainUsesTokenIdentity() {
        Harness h = new Harness();
        h.identity.context = tenantContext();
        h.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));

        h.controller().streamTenant(request("q", null));

        assertThat(h.identity.seenPlatformChain).isFalse();
        assertThat(h.orchestrator.seenContext.domain()).isEqualTo(Context.Domain.TENANT);
        assertThat(h.orchestrator.seenContext.thinkingAllowed()).isFalse();
        assertThat(h.orchestrator.seenRequest.requestedTenantId()).isNull();
    }

    @Test
    @DisplayName("租户链同样逐字透传 contextState；禁止的只有请求体 tenantId")
    void tenantChainAlsoPassesRawContextState() {
        Harness h = new Harness();
        h.identity.context = tenantContext();
        h.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));

        h.controller().streamTenant(request("q", null, RAW_STATE));

        assertThat(h.orchestrator.seenRequest.contextState()).isEqualTo(RAW_STATE);
        assertThat(h.orchestrator.seenRequest.requestedTenantId()).isNull();
        assertThat(h.identity.seenPlatformChain).isFalse();
    }

    // ── 建连前的 fail-closed ──────────────────────────────────────────

    @Test
    @DisplayName("空消息在身份解析前就 400")
    void emptyMessageRejectedBeforeIdentityResolution() {
        Harness h = new Harness();
        h.identity.context = platformContext();

        assertThatThrownBy(() -> h.controller().streamPlatform(request("   ", TENANT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(h.identity.seenPlatformChain).isFalse();
    }

    @Test
    @DisplayName("未认证身份 401，非法目标租户 400（不裸抛内部异常）")
    void identityFailuresMappedToHttpStatus() {
        Harness unauthorized = new Harness();
        unauthorized.identity.failure =
                new AgentChatIdentityResolver.UnauthorizedAgentChatException("需要平台令牌");
        assertThatThrownBy(() -> unauthorized.controller().streamPlatform(request("q", TENANT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        Harness badTarget = new Harness();
        badTarget.identity.failure =
                new AgentChatIdentityResolver.InvalidAgentChatTargetException("目标租户不存在");
        assertThatThrownBy(() -> badTarget.controller().streamPlatform(request("q", SPOOF_TENANT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("建连限流 429：被拒时编排器一次都不跑")
    void rateLimitedConnectionReturns429AndSkipsOrchestrator() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        h.rateLimit.decision = RateLimitPort.Decision.REJECTED;

        assertThatThrownBy(() -> h.controller().streamPlatform(request("q", TENANT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(h.rateLimit.seenBucket).isEqualTo(RateLimitPort.Buckets.SSE_CONNECT);
        assertThat(h.orchestrator.seenRequest).isNull();
    }

    @Test
    @DisplayName("⛔ 限流键取自已验签身份，绝不取请求体租户（防伪装租户挤占）")
    void rateLimitKeyDerivedFromVerifiedIdentity() {
        Harness platformHarness = new Harness();
        platformHarness.identity.context = platformContext();
        platformHarness.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));
        platformHarness.controller().streamPlatform(request("q", SPOOF_TENANT));
        String expectedPlatformKey = UUID.nameUUIDFromBytes(
                ("platform-agent-chat:" + PRINCIPAL).getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(platformHarness.rateLimit.seenKey)
                .as("平台主体不属任何租户，必须用确定性派生 UUID，且不能采纳请求体租户")
                .isEqualTo(expectedPlatformKey)
                .isNotEqualTo(SPOOF_TENANT);

        Harness tenantHarness = new Harness();
        tenantHarness.identity.context = tenantContext();
        tenantHarness.orchestrator.emitted = List.of(new AgentChatEvent.Done(false));
        tenantHarness.controller().streamTenant(request("q", null));
        assertThat(tenantHarness.rateLimit.seenKey)
                .as("租户链按令牌租户分桶").isEqualTo(TENANT);
    }

    // ── SSE 帧格式 ────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 帧必须是原生 SSE 事件（data:event: 双重包裹 = 前端全丢帧）")
    void framesAreNativeSseEvents() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        h.orchestrator.emitted = List.of(
                new AgentChatEvent.Thinking("草稿"),
                new AgentChatEvent.Delta("正文"),
                new AgentChatEvent.Done(false));

        h.controller().streamPlatform(request("q", TENANT));

        assertThat(h.emitter.rawStringSends).isEmpty();
        assertThat(h.emitter.frames).hasSize(3);
        assertThat(h.emitter.frames.get(0)).startsWith("event:thinking\n");
        assertThat(h.emitter.frames.get(1)).startsWith("event:delta\n");
        assertThat(h.emitter.frames.get(2)).startsWith("event:done\n");
        assertThat(h.emitter.frames.stream().reduce("", String::concat))
                .doesNotContain("data:event:");
        assertThat(h.emitter.completed).isTrue();
    }

    @Test
    @DisplayName("编排器自身未料到的异常也归一为 error 帧并收口，不裸抛给容器")
    void unexpectedOrchestratorFailureEmitsErrorFrame() {
        Harness h = new Harness();
        h.identity.context = platformContext();
        h.orchestrator.failure = new IllegalStateException("编排器内部炸了");
        // 编排器没来得及发 error/done；控制器必须兜底
        h.orchestrator.emitted = List.of();

        h.controller().streamPlatform(request("q", TENANT));

        String frames = h.emitter.frames.stream().reduce("", String::concat);
        assertThat(frames).contains("event:error").contains("SERVER_ERROR");
        assertThat(frames).doesNotContain("编排器内部炸了");
        assertThat(h.emitter.completed).isTrue();
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static final class Harness {
        final RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        final FixedIdentity identity = new FixedIdentity();
        final RecordingRateLimit rateLimit = new RecordingRateLimit();
        final RecordingEmitter emitter = new RecordingEmitter();

        AgentChatController controller() {
            // 同步执行器：流式逻辑在当前线程跑完，便于直接断言帧序列
            return new AgentChatController(orchestrator, identity, rateLimit,
                    command -> command.run(), () -> emitter);
        }
    }

    private static AgentChatController.AgentChatStreamRequest request(String message,
                                                                      String tenantId) {
        return request(message, tenantId, null);
    }

    private static AgentChatController.AgentChatStreamRequest request(String message,
                                                                      String tenantId,
                                                                      String contextState) {
        return new AgentChatController.AgentChatStreamRequest(
                message, List.of(), null, null, false, tenantId, null, contextState);
    }

    private static Context platformContext() {
        return new Context(TENANT, PRINCIPAL, Context.Domain.PLATFORM, true, TOKEN);
    }

    private static Context tenantContext() {
        return new Context(TENANT, PRINCIPAL, Context.Domain.TENANT, false, TOKEN);
    }

    /** 编排器替身：捕获 Context / Request，并按脚本推帧（或抛异常）。 */
    private static final class RecordingOrchestrator implements AgentChatOrchestratorPort {
        Context seenContext;
        AgentChatRequest seenRequest;
        List<AgentChatEvent> emitted = List.of();
        RuntimeException failure;

        @Override
        public void run(@NonNull Context context, @NonNull AgentChatRequest request,
                        @NonNull Sink sink) {
            seenContext = context;
            seenRequest = request;
            for (AgentChatEvent event : emitted) {
                sink.accept(event);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** 身份解析器替身：锁定控制器从端口取身份，不碰 Security 请求属性。 */
    private static final class FixedIdentity implements AgentChatIdentityResolver {
        Context context;
        RuntimeException failure;
        boolean seenPlatformChain;
        String seenRequestedTenantId;

        @Override
        public @NonNull Context resolve(boolean platformChain, String requestedTenantId) {
            seenPlatformChain = platformChain;
            seenRequestedTenantId = requestedTenantId;
            if (failure != null) {
                throw failure;
            }
            return context;
        }
    }

    /** 限流替身：记录分桶键与桶名。 */
    private static final class RecordingRateLimit implements RateLimitPort {
        Decision decision = Decision.ALLOWED;
        String seenKey;
        String seenBucket;

        @Override
        public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
            seenKey = tenantId;
            seenBucket = bucketKey;
            return decision;
        }

        @Override
        public long currentCount(@NonNull String tenantId, @NonNull String bucketKey) {
            return 0L;
        }
    }

    /**
     * 记录每帧渲染后的 SSE 文本。
     *
     * <p>⚠️ 刻意覆写两个 send 重载：{@code send(SseEventBuilder)} 是正确通路，
     * 把 builder 片段按序拼回帧文本；{@code send(Object)} 只记录不转发 ——
     * 生产代码一旦回退到手工拼帧字符串，就会落进后者被守卫判红。
     */
    private static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        final List<String> rawStringSends = new ArrayList<>();
        volatile boolean completed = false;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(Object object) throws IOException {
            rawStringSends.add(String.valueOf(object));
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (var part : builder.build()) {
                sb.append(part.getData());
            }
            frames.add(sb.toString());
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable t) {
            completed = true;
        }
    }
}
