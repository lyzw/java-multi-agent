package cn.studykid.multiagent.api.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import tools.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 控制台「直接对话」端点（真·LLM 多轮流式 · 非 RAG 语料问答）。
 *
 * <p><b>与红线 R6 的边界</b>：本端点是<b>平台管理员自己</b>与模型的多轮对话
 * （类似一个内置于控制台的聊天工具），<b>不是</b>回放/展示某个租户 Agent Run 的
 * 运行输出正文。它不读取任何租户 Run 数据，输入仅来自管理员本次键入的内容
 * 与系统提示词，故不触碰「平台控制台不展示运行输出」的红线。
 *
 * <p><b>流式实现</b>：使用 {@link SseEmitter} 长连接，每收到一个内容增量即
 * 向客户端推送一个 {@code event: delta} 帧；推理草稿（思考过程）推 {@code event: thinking}
 * 帧；终态再推 {@code event: done}（含完整正文与用量）。这与
 * {@code RunEventController} 的「短连接 + 续传」模型不同——对话需要逐 token 实时呈现，
 * 必须用长连接。思考过程仅展示给平台管理员，不回落到面向学生的通道。
 *
 * <p><b>沿用既有铁律</b>：
 * <ul>
 *   <li>租户来自验签令牌 {@link CurrentPrincipal#context()}，<b>绝不</b>取请求体；</li>
 *   <li>建连前过 {@link RateLimitPort}（SSE 长连接是贵资源，与 RunEventController 同源纪律）；</li>
 *   <li>凭据（MiniMax API Key）只存在于网关层，本控制器与日志均不接触。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ConsoleChatController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleChatController.class);

    /** SSE 长连接整体超时（与单次模型调用超时解耦）。 */
    private static final long SSE_TIMEOUT_MS = 120_000L;

    /** 单次模型调用超时（增量 delivery 仍受此约束）。 */
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

    /** 终帧/错误帧的 JSON 序列化器（仅用于结构化载荷，不接触凭据）。 */
    private static final ObjectMapper SSE_JSON = new ObjectMapper();

    private final ChatStreamGateway chatGateway;
    private final CurrentPrincipal currentPrincipal;
    private final RateLimitPort rateLimiter;
    private final String defaultModel;
    /** 流式调用执行器（生产用虚拟线程，测试可注入同步执行器）。 */
    private final Executor executor;
    /** SSE 发射器工厂（生产用标准超时 SseEmitter，测试可注入可捕获替身）。 */
    private final Supplier<SseEmitter> emitterFactory;

    /**
     * 默认构造：虚拟线程执行器 + 标准超时 SSE 发射器（生产装配用）。
     *
     * <p>⚠️ <b>必须 {@code @Autowired}</b>：本类另有一个测试用包级全参构造，
     * 组件扫描下多构造且无 {@code @Autowired} 时 Spring 回落无参构造并抛
     * {@code No default constructor found}（启动期故障，编译期发现不了）。
     */
    @Autowired
    public ConsoleChatController(
                                 @NonNull @Qualifier("chatStreamGateway")
                                 ChatStreamGateway chatGateway,
                                 @NonNull CurrentPrincipal currentPrincipal,
                                 @NonNull RateLimitPort rateLimiter,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${agent.model.minimax.default-model:MiniMax-M2.5}")
                                 String defaultModel) {
        this(chatGateway, currentPrincipal, rateLimiter, defaultModel,
                Executors.newVirtualThreadPerTaskExecutor(),
                () -> new SseEmitter(SSE_TIMEOUT_MS));
    }

    /** 全参构造（测试可注入同步执行器与可捕获的发射器工厂）。 */
    ConsoleChatController(@NonNull ChatStreamGateway chatGateway,
                          @NonNull CurrentPrincipal currentPrincipal,
                          @NonNull RateLimitPort rateLimiter,
                          String defaultModel,
                          @NonNull Executor executor,
                          @NonNull Supplier<SseEmitter> emitterFactory) {
        this.chatGateway = chatGateway;
        this.currentPrincipal = currentPrincipal;
        this.rateLimiter = rateLimiter;
        this.defaultModel = defaultModel == null || defaultModel.isBlank()
                ? "MiniMax-M2.5" : defaultModel;
        this.executor = executor;
        this.emitterFactory = emitterFactory;
    }

    /**
     * 流式对话。
     *
     * @param body 请求体（多轮以 {@code messages} 全量历史为准）
     * @return SSE 长连接；逐帧推送 {@code delta} / 终帧 {@code done} / 异常 {@code error}
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@RequestBody ChatStreamRequest body) {
        // 1) 可信身份：唯一来源是已验签令牌
        TenantContext context = currentPrincipal.context();

        // 2) 建连限流（SSE 长连接长期占用资源，与 RunEventController 同源纪律）
        RateLimitPort.Decision limit = rateLimiter.check(
                context.tenantId(), RateLimitPort.Buckets.SSE_CONNECT);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            log.warn("对话 SSE 建连被限流拒绝: tenant={}, decision={}", context.tenantId(), limit);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "对话连接过于频繁");
        }

        // 3) 组装模型请求（fail-closed：消息为空即拒）
        ModelRequest request = buildRequest(body);

        SseEmitter emitter = emitterFactory.get();
        emitter.onCompletion(() -> log.debug("对话 SSE 完成: tenant={}", context.tenantId()));
        emitter.onTimeout(() -> {
            log.warn("对话 SSE 超时: tenant={}", context.tenantId());
            emitter.complete();
        });

        // 4) 在执行器里跑流式调用，避免阻塞 Tomcat 平台线程
        executor.execute(() -> {
            try {
                StringBuilder full = new StringBuilder();
                ModelResponse response = chatGateway.streamChat(request,
                        new ChatStreamGateway.StreamDeltaConsumer() {
                            @Override
                            public void onDelta(String token) {
                                if (token == null || token.isEmpty()) {
                                    return;
                                }
                                full.append(token);
                                emitDelta(emitter, token);
                            }

                            @Override
                            public void onThinking(String token) {
                                if (token == null || token.isEmpty()) {
                                    return;
                                }
                                emitThinking(emitter, token);
                            }
                        });
                emitDone(emitter, full.toString(), response);
                emitter.complete();
            } catch (ChatClientGoneException gone) {
                emitter.complete();
            } catch (ModelGatewayException e) {
                log.warn("对话流式调用失败: tenant={}, kind={}", context.tenantId(), e.kind());
                emitError(emitter, e.kind(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("对话流式调用异常: tenant={}", context.tenantId(), e);
                emitError(emitter, ModelErrorKind.PROVIDER_ERROR, "模型调用失败");
            }
        });

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-store")
                .body(emitter);
    }

    /** 发送单个增量帧（content 内的换行会被规范为多行 data，SSE 兼容）。 */
    private static void emitDelta(SseEmitter emitter, String token) {
        sendFrame(emitter, "delta", token);
    }

    /** 发送单个思考过程（推理草稿）增量帧；前端折叠展示，仅对管理员可见。 */
    private static void emitThinking(SseEmitter emitter, String token) {
        sendFrame(emitter, "thinking", token);
    }

    /** 发送终帧（含完整正文与用量，供前端兜底展示/计量）。 */
    private static void emitDone(SseEmitter emitter, String fullContent, ModelResponse response) {
        try {
            String json = SSE_JSON.writeValueAsString(new ChatDonePayload(fullContent, response));
            sendFrame(emitter, "done", json);
        } catch (Exception e) {
            emitError(emitter, ModelErrorKind.PROVIDER_ERROR, "序列化终帧失败");
        }
    }

    /** 发送错误帧（kind 归一，便于前端按分类处理/重试），随后结束流。 */
    private static void emitError(SseEmitter emitter, ModelErrorKind kind, String message) {
        try {
            sendFrame(emitter, "error", errorJson(kind, message));
        } catch (ChatClientGoneException gone) {
            // 客户端已断开，错误帧丢失无妨
        } finally {
            emitter.complete();
        }
    }

    private static String errorJson(ModelErrorKind kind, String message) {
        try {
            return SSE_JSON.writeValueAsString(new ChatErrorPayload(kind.name(), message));
        } catch (Exception e) {
            return "{\"kind\":\"" + kind.name() + "\"}";
        }
    }

    /**
     * 以原始 SSE 帧格式推送（{@code event: <name>\\ndata: <data>\\n\\n}）。
     *
     * <p>⚠️ 不用 Spring 的 {@code SseEmitter.event()} 构造器，便于在测试中
     * 以纯字符串断言帧结构；且 data 内的换行被规范为续行 {@code data:}，
     * 避免破坏 SSE 帧边界。
     */
    private static void sendFrame(SseEmitter emitter, String name, String data) {
        String safe = data.replace("\r\n", "\n").replace("\n", "\ndata: ");
        try {
            emitter.send("event: " + name + "\ndata: " + safe + "\n\n");
        } catch (java.io.IOException e) {
            // 客户端断开：停止推送即可
            throw new ChatClientGoneException();
        }
    }

    /** 由请求体组装 {@link ModelRequest}（多轮以 messages 为准）。 */
    private ModelRequest buildRequest(ChatStreamRequest body) {
        if (body == null || body.messages() == null || body.messages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 不能为空");
        }
        List<ModelMessage> messages = new ArrayList<>(body.messages().size());
        for (ChatStreamRequest.ChatMessage m : body.messages()) {
            if (m == null || m.content() == null) {
                continue;
            }
            ModelMessage.Role role = parseRole(m.role());
            messages.add(switch (role) {
                case ModelMessage.Role.ASSISTANT -> ModelMessage.assistant(m.content());
                case ModelMessage.Role.TOOL ->
                        ModelMessage.toolResult("tool", m.content());
                default -> ModelMessage.user(m.content());
            });
        }
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 无有效内容");
        }
        String modelId = (body.modelId() == null || body.modelId().isBlank())
                ? defaultModel : body.modelId();
        String system = (body.systemPrompt() == null) ? "" : body.systemPrompt();
        Double temperature = (body.temperature() == null) ? 0.7 : body.temperature();
        Integer maxTokens = (body.maxTokens() == null) ? 1024 : body.maxTokens();
        return new ModelRequest(modelId, system, "", temperature, maxTokens,
                MODEL_TIMEOUT, Map.of(), messages);
    }

    private static ModelMessage.Role parseRole(String role) {
        if (role == null) {
            return ModelMessage.Role.USER;
        }
        try {
            return ModelMessage.Role.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ModelMessage.Role.USER;
        }
    }

    /** 客户端已断开的轻量信号（不冒泡为 ModelGatewayException）。 */
    private static final class ChatClientGoneException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 对话请求体。 */
    public record ChatStreamRequest(
            String modelId,
            String systemPrompt,
            Double temperature,
            Integer maxTokens,
            List<ChatMessage> messages) {
        /** 单条消息。 */
        public record ChatMessage(String role, String content) {
        }
    }

    /** 终帧载荷（含完整正文与用量，供前端兜底展示/计量）。 */
    public record ChatDonePayload(String content, ModelUsage usage, String finishReason) {
        public ChatDonePayload(String content, ModelResponse response) {
            this(content,
                    new ModelUsage(response.usage().inputTokens(),
                            response.usage().outputTokens()),
                    response.finishReason().name());
        }

        /** 用量（与 {@link ModelResponse.ModelUsage} 同构，避免前端耦合端口类型）。 */
        public record ModelUsage(long inputTokens, long outputTokens) {
        }
    }

    /** 错误帧载荷。 */
    public record ChatErrorPayload(String kind, String message) {
    }
}
