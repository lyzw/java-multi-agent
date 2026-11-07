package cn.studykid.multiagent.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * {@link ConsoleChatController} 契约测试（直接构造 + 替身，不启 Spring 上下文，
 * 与 {@code RunEventControllerTest} 同源风格）。
 *
 * <p>⚠️ 断言的是「不可绕过的性质」：增量逐帧、推理草稿不外泄、错误归一为
 * {@code event: error} 帧、空消息与限流 fail-closed。
 *
 * <p><b>⚠️ 2026-11-07</b>：控制器按请求 URI 判定是否允许 thinking 帧，
 * 故所有用例默认注入「平台控制台」请求上下文（等价于真实 HTTP 链路）。
 * 租户前台通道另有专门用例覆盖「不下发推理草稿」。
 */
class ConsoleChatControllerTest {

    @BeforeEach
    void useConsoleChatRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/chat/stream")));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ── S1：逐帧增量 + 终帧 ──────────────────────────────────────────────

    @Test
    void streamEmitsDeltaThenDone() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer -> {
            consumer.onDelta("你好");
            consumer.onDelta("世界");
            return new ModelResponse("你好世界", Map.of(),
                    new ModelResponse.ModelUsage(5L, 3L), ModelResponse.FinishReason.STOP);
        });
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        String all = String.join("", emitter.frames);
        int i1 = all.indexOf("data:你好");
        int i2 = all.indexOf("data:世界");
        int iDone = all.indexOf("event:done");
        assertTrue(i1 >= 0, "第一个增量必须发出");
        assertTrue(i2 >= 0, "第二个增量必须发出");
        assertTrue(iDone >= 0, "必须发送 done 终帧");
        assertTrue(i1 < i2 && i2 < iDone, "顺序必须为 你好 → 世界 → done");
        assertTrue(all.contains("\"finishReason\":\"STOP\""), "终帧含 STOP 终态");
        assertTrue(all.contains("\"inputTokens\":5"), "终帧含用量");
        assertTrue(emitter.completed, "流必须完成");
    }

    // ── S1c：帧必须是原生 SSE 事件（防「data:event:」双重包裹回归）────────

    @Test
    void framesAreNativeSseEventsNotDoubleWrappedData() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer -> {
            consumer.onDelta("第一行\n第二行");
            return new ModelResponse("第一行\n第二行", Map.of(),
                    new ModelResponse.ModelUsage(5L, 3L), ModelResponse.FinishReason.STOP);
        });
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        assertEquals(List.of(), emitter.rawStringSends,
                "禁止 emitter.send(String)：Spring 7 会把整帧包成一条 data 值，事件名全丢，"
                        + "前端按 event 分派时一帧都匹配不上（对话框永远停在生成中）");
        String first = emitter.frames.get(0);
        assertTrue(first.startsWith("event:delta\n"),
                "帧必须以原生 event 行开头，实际: " + first);
        assertFalse(first.contains("data:event:"),
                "出现 data:event: 说明帧被双重包裹，实际: " + first);
        assertTrue(first.contains("data:第一行\ndata:第二行"),
                "data 内换行必须规范为多行 data: 续行，实际: " + first);
        assertTrue(emitter.frames.stream().anyMatch(f -> f.startsWith("event:done\n")),
                "终帧必须是原生 done 事件");
    }

    // ── S1b：思考过程帧（event: thinking）被前向转发 ──────────────────────

    @Test
    void streamForwardsThinkingFrames() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer -> {
            consumer.onThinking("让我先算一下判别式");
            consumer.onDelta("你好");
            consumer.onThinking("再确认根的情况");
            consumer.onDelta("世界");
            return new ModelResponse("你好世界", Map.of(),
                    new ModelResponse.ModelUsage(5L, 3L), ModelResponse.FinishReason.STOP);
        });
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        String all = String.join("", emitter.frames);
        assertTrue(all.contains("event:thinking"), "思考帧必须发出");
        int t1 = all.indexOf("data:让我先算一下判别式");
        int d1 = all.indexOf("data:你好");
        int t2 = all.indexOf("data:再确认根的情况");
        int d2 = all.indexOf("data:世界");
        assertTrue(t1 >= 0 && d1 >= 0 && t2 >= 0 && d2 >= 0, "思考与答案帧都应存在");
        assertTrue(t1 < d1 && d1 < t2 && t2 < d2, "顺序须为 思考1 → 答案1 → 思考2 → 答案2");
        assertTrue(all.contains("\"finishReason\":\"STOP\""), "终帧含 STOP 终态");
        assertTrue(emitter.completed, "流必须完成");
    }

    // ── S1d：租户前台通道不下发推理草稿，正文链路不变 ──────────────────────

    @Test
    void tenantGeneralChatSuppressesThinkingButKeepsDeltaAndDone() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/knowledge/chat/stream")));
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer -> {
            consumer.onThinking("这段推理草稿不得下发到学生侧");
            consumer.onDelta("通用知识回答");
            return new ModelResponse("通用知识回答", Map.of(),
                    new ModelResponse.ModelUsage(5L, 3L), ModelResponse.FinishReason.STOP);
        });
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        String all = String.join("", emitter.frames);
        assertFalse(all.contains("event:thinking"),
                "推理草稿按设计仅展示给平台管理员，租户通道必须不下发");
        assertFalse(all.contains("这段推理草稿不得下发到学生侧"),
                "推理草稿正文也不得出现在租户通道的字节里");
        assertTrue(all.contains("data:通用知识回答"), "租户通道仍须逐 token 下发正文");
        assertTrue(all.contains("event:done"), "租户通道仍须有应用层终帧");
        assertTrue(emitter.completed, "流必须完成");
    }

    // ── S2：空消息 fail-closed（400）──────────────────────────────────────

    @Test
    void emptyMessagesRejected() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer ->
                new ModelResponse("", Map.of(), new ModelResponse.ModelUsage(0, 0),
                        ModelResponse.FinishReason.STOP));
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                        null, null, null, null, List.of())));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), "空消息必须 400");
    }

    // ── S3：建连限流（429）───────────────────────────────────────────────

    @Test
    void rateLimitedConnectionReturns429() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer ->
                new ModelResponse("", Map.of(), new ModelResponse.ModelUsage(0, 0),
                        ModelResponse.FinishReason.STOP));
        ConsoleChatController controller = controller(emitter, gate, DENIED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                        null, null, null, null,
                        List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi")))));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode(), "限流必须 429");
    }

    // ── S3b：限流分桶键取自已验签身份，不取请求体 / 不伪装租户 ────────────

    @Test
    void rateLimitBucketUsesVerifiedCallerKey() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer ->
                new ModelResponse("", Map.of(), new ModelResponse.ModelUsage(0, 0),
                        ModelResponse.FinishReason.STOP));
        java.util.List<String> keys = new java.util.ArrayList<>();
        RateLimitPort recording = new RateLimitPort() {
            @Override
            public Decision check(String callerKey, String bucketKey) {
                keys.add(callerKey);
                assertEquals(RateLimitPort.Buckets.SSE_CONNECT, bucketKey);
                return Decision.ALLOWED;
            }

            @Override
            public long currentCount(String callerKey, String bucketKey) {
                return 0L;
            }
        };
        Executor sync = command -> command.run();
        ConsoleChatController controller = new ConsoleChatController(
                gate, () -> PLATFORM_KEY, recording, "MiniMax-M2.5", sync, () -> emitter);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        assertEquals(List.of(PLATFORM_KEY), keys,
                "限流键必须原样来自已验签的调用方标识，不能取请求体或默认租户");
    }

    // ── S4：网关错误归一为 error 帧（不裸抛异常）─────────────────────────

    @Test
    void gatewayErrorNormalizedToErrorEvent() {
        RecordingEmitter emitter = new RecordingEmitter();
        FakeStreamGateway gate = new FakeStreamGateway(consumer -> {
            throw new ModelGatewayException(ModelErrorKind.RATE_LIMITED, "rate limited by provider");
        });
        ConsoleChatController controller = controller(emitter, gate, ALLOWED);

        controller.streamChat(new ConsoleChatController.ChatStreamRequest(
                null, null, null, null,
                List.of(new ConsoleChatController.ChatStreamRequest.ChatMessage("user", "hi"))));

        String all = String.join("", emitter.frames);
        assertTrue(all.contains("event:error"), "错误必须走 error 事件，而非裸抛");
        assertTrue(all.contains("RATE_LIMITED"), "错误帧须携带归一化 kind");
        assertTrue(emitter.completed, "错误后仍须结束流");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private ConsoleChatController controller(RecordingEmitter emitter,
                                            ChatStreamGateway gate,
                                            RateLimitPort rate) {
        // ⚠️ 同步执行器：流式逻辑在当前线程内跑完，便于直接断言帧序列
        Executor sync = command -> command.run();
        return new ConsoleChatController(gate, CALLER, rate, "MiniMax-M2.5", sync, () -> emitter);
    }

    /**
     * 记录每一帧渲染后的 SSE 文本与完成/错误状态。
     *
     * <p>⚠️ 刻意覆写<b>两个</b> send 重载：{@code send(SseEventBuilder)} 是正确通路，
     * 把 builder 的片段按序拼回帧文本；{@code send(Object)} 只记录、不转发。
     * 生产代码一旦回退到手工拼帧字符串，就会落进后者，被守卫用例判红 ——
     * 那种写法在 Spring 7 下会被包成一条 data 值，浏览器收不到事件名。
     */
    private static final class RecordingEmitter extends SseEmitter {
        final java.util.List<String> frames = new java.util.ArrayList<>();
        final java.util.List<String> rawStringSends = new java.util.ArrayList<>();
        volatile boolean completed = false;
        final java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();

        RecordingEmitter() {
            super(0L); // 0 = 无超时
        }

        @Override
        public void send(Object object) throws IOException {
            rawStringSends.add(String.valueOf(object));
            // ⚠️ 不调用 super（测试无真实响应通道）
        }

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
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
            error.set(t);
        }
    }

    /** 可控的流式网关替身。 */
    private static final class FakeStreamGateway implements ChatStreamGateway {
        private final Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> handler;

        FakeStreamGateway(Function<ChatStreamGateway.StreamDeltaConsumer, ModelResponse> handler) {
            this.handler = handler;
        }

        @Override
        public ModelResponse streamChat(ModelRequest request,
                                       ChatStreamGateway.StreamDeltaConsumer consumer) {
            return handler.apply(consumer);
        }
    }

    /**
     * ⚠️ 直接对话是平台身份域：注入的是限流分桶键（合法 UUID），不是 TenantContext。
     * 平台主体用派生 v3 UUID，与真实租户 v4 UUID 不碰撞。
     */
    private static final String PLATFORM_KEY = "00000000-0000-3000-8000-000000000001";

    private static final ConsoleChatController.ChatCaller CALLER = () -> PLATFORM_KEY;

    private static final RateLimitPort ALLOWED = new RateLimitPort() {
        @Override
        public Decision check(String tenantId, String bucketKey) {
            return Decision.ALLOWED;
        }

        @Override
        public long currentCount(String tenantId, String bucketKey) {
            return 0L;
        }
    };

    private static final RateLimitPort DENIED = new RateLimitPort() {
        @Override
        public Decision check(String tenantId, String bucketKey) {
            return Decision.REJECTED;
        }

        @Override
        public long currentCount(String tenantId, String bucketKey) {
            return 1L;
        }
    };
}
