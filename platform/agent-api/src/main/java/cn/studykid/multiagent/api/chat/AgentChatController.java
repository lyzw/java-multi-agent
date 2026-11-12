package cn.studykid.multiagent.api.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 通用 Agent 编排对话的双端点 SSE 控制器。
 *
 * <p><b>两个身份域，两条前缀，绝不混用</b>：
 * <ul>
 *   <li>{@code POST /api/agent/chat/stream} —— 平台链（aud=admin-console-api），
 *       由 {@code admin-console} 使用；<b>必须</b>显式给出目标租户；
 *       允许下发模型原始推理草稿 {@code event: thinking}；</li>
 *   <li>{@code POST /api/knowledge/agent/chat/stream} —— 租户链（aud=agent-platform），
 *       由 {@code frontend} 使用；租户取自已验签令牌；<b>恒丢弃</b>思考草稿。</li>
 * </ul>
 * 两个端点共享同一个编排器、同一份 13 类事件协议与同一套状态机语义，
 * 只是身份来源与推理草稿可见性不同。
 *
 * <p><b>身份不入参</b>：tenantId / principalId / Bearer 令牌全部由
 * {@link AgentChatIdentityResolver} 从已验签请求属性与请求头解析；
 * 请求体的 {@code tenantId} 仅作为平台链的<b>目标</b>租户，必须合法且存在，
 * 租户链出现该字段直接 400（防越权）。
 *
 * <p><b>流式实现</b>：{@code SseEmitter} + 虚拟线程，帧用
 * {@code SseEmitter.event().name(...).data(...)} 发送，<b>绝不手工拼 event:/data:</b>。
 * 请求头带 {@code X-Accel-Buffering: no} 关闭反代缓冲；
 * Security 链放行 {@code DispatcherType.ASYNC}，否则终止块会被二次授权拦下。
 *
 * <p><b>失败口径</b>：建连前失败（身份/参数/限流）走 HTTP 4xx；
 * 编排中失败走 {@code event: error} + {@code event: done}，且审计仍收敛 FAILED。
 *
 * <p><b>压缩态边界</b>：本控制器对 {@code contextState} 只做逐字透传，不验签 / 不解码 /
 * 不因它拒绝请求；六项不可信校验统一放在能取得已裁决策略指纹的编排边界（D49）。
 */
@RestController
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    /** 平台链路径（与 admin-console AGENT_LIVE_STREAM_PATH 对齐）。 */
    public static final String PLATFORM_STREAM_PATH = "/api/agent/chat/stream";
    /** 租户链路径（与 frontend AGENT_CHAT_PATH 对齐）。 */
    public static final String TENANT_STREAM_PATH = "/api/knowledge/agent/chat/stream";

    /** SSE 整体超时：须大于两前端的 120s / 150s 请求超时，否则会先被服务端截断。 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final AgentChatOrchestratorPort orchestrator;
    private final AgentChatIdentityResolver identityResolver;
    private final RateLimitPort rateLimiter;
    private final Executor executor;
    private final Supplier<SseEmitter> emitterFactory;

    /**
     * 生产构造（组合根装配）。⚠️ 另有测试用全参构造，故显式标注生产入口。
     */
    @Autowired
    public AgentChatController(@NonNull AgentChatOrchestratorPort orchestrator,
                               @NonNull AgentChatIdentityResolver identityResolver,
                               @NonNull RateLimitPort rateLimiter) {
        this(orchestrator, identityResolver, rateLimiter,
                Executors.newVirtualThreadPerTaskExecutor(),
                () -> new SseEmitter(SSE_TIMEOUT_MS));
    }

    /** 全参构造（测试注入同步执行器与可捕获的 emitter）。 */
    AgentChatController(@NonNull AgentChatOrchestratorPort orchestrator,
                        @NonNull AgentChatIdentityResolver identityResolver,
                        @NonNull RateLimitPort rateLimiter,
                        @NonNull Executor executor,
                        @NonNull Supplier<SseEmitter> emitterFactory) {
        this.orchestrator = orchestrator;
        this.identityResolver = identityResolver;
        this.rateLimiter = rateLimiter;
        this.executor = executor;
        this.emitterFactory = emitterFactory;
    }

    /** 平台控制台：平台令牌 + 显式目标租户。 */
    @PostMapping(value = PLATFORM_STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamPlatform(@RequestBody AgentChatStreamRequest body) {
        return stream(body, true);
    }

    /** 租户前台：租户令牌，租户从令牌取。 */
    @PostMapping(value = TENANT_STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamTenant(@RequestBody AgentChatStreamRequest body) {
        return stream(body, false);
    }

    private ResponseEntity<SseEmitter> stream(@RequestBody AgentChatStreamRequest body,
                                              boolean platformChain) {
        AgentChatStreamRequest safe = body == null
                ? new AgentChatStreamRequest(null, List.of(), null, null, false, null, null, null)
                : body;
        if (safe.message() == null || safe.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        if (!platformChain && safe.tenantId() != null && !safe.tenantId().isBlank()) {
            // 租户链不接受请求体指定租户：身份只能来自已验签令牌。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "租户链不得在请求体中指定 tenantId");
        }

        Context context;
        try {
            context = identityResolver.resolve(platformChain, safe.tenantId());
        } catch (AgentChatIdentityResolver.UnauthorizedAgentChatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        } catch (AgentChatIdentityResolver.InvalidAgentChatTargetException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // SSE 长连接是贵资源：建连前过限流，桶与控制台直接对话同源，
        // 但桶键按身份域分叉，避免平台工具挤占租户问答。
        String rateLimitKey = rateLimitKey(context);
        if (rateLimiter.check(rateLimitKey, RateLimitPort.Buckets.SSE_CONNECT)
                != RateLimitPort.Decision.ALLOWED) {
            log.warn("Agent 编排 SSE 建连被限流: domain={}, key={}",
                    context.domain(), rateLimitKey);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "对话连接过于频繁");
        }

        AgentChatRequest request = new AgentChatRequest(
                safe.message(),
                turns(safe.history()),
                blankToNull(safe.agentId()),
                safe.topK(),
                safe.thinkingMode(),
                platformChain ? blankToNull(safe.tenantId()) : null,
                blankToNull(safe.scenario()),
                // ⚠️ 控制器只透传原始串；六项不可信校验在能取得已裁决策略指纹的
                //    编排边界执行（D49）。这里做 trim / 解码会掩盖超长与篡改。
                safe.contextState());

        SseEmitter emitter = emitterFactory.get();
        emitter.onCompletion(() -> log.debug("Agent 编排 SSE 完成: domain={}", context.domain()));
        emitter.onTimeout(() -> {
            log.warn("Agent 编排 SSE 超时: domain={}, key={}", context.domain(), rateLimitKey);
            emitter.complete();
        });

        AgentChatOrchestratorPort.Sink sink = event -> sendFrame(emitter, event);
        executor.execute(() -> {
            try {
                orchestrator.run(context, request, sink);
                emitter.complete();
            } catch (RuntimeException e) {
                // 编排器内部已收敛审计与 error 帧；这里只兜底「编排器自身也未料到」的异常。
                log.error("Agent 编排执行器异常: domain={}, key={}, cause={}",
                        context.domain(), rateLimitKey, e.toString());
                try {
                    sendFrame(emitter, new AgentChatEvent.Error(
                            "SERVER_ERROR", "编排服务暂时不可用（SERVER_ERROR）"));
                } catch (RuntimeException ignored) {
                    // 客户端已断开
                }
            } finally {
                emitter.complete();
            }
        });

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-store")
                .body(emitter);
    }

    /**
     * 发送单帧。⚠️ 必须走 {@link SseEmitter#event()}：Spring Framework 7 把
     * {@code emitter.send(String)} 视为一条 data 值，手工拼帧会写出
     * {@code data:event: delta} 这种废帧，前端一个事件都匹配不上。
     */
    private static void sendFrame(SseEmitter emitter, @NonNull AgentChatEvent event) {
        String data = event.data() == null ? "" : event.data();
        String safe = data.replace("\r\n", "\n").replace('\r', '\n');
        try {
            emitter.send(SseEmitter.event().name(event.eventName()).data(safe));
        } catch (java.io.IOException | IllegalStateException e) {
            throw new AgentChatOrchestratorPort.SinkClosedException("SSE 客户端已断开", e);
        }
    }

    /** 平台主体不属任何租户，按 userId 派生确定性 UUID 才能写 RLS 限流桶。 */
    private static @NonNull String rateLimitKey(@NonNull Context context) {
        if (context.domain() == Context.Domain.TENANT) {
            return context.tenantId();
        }
        return UUID.nameUUIDFromBytes(
                ("platform-agent-chat:" + context.principalId())
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static @NonNull List<AgentChatRequest.Turn> turns(
            @Nullable List<AgentChatTurn> raw) {
        if (raw == null) {
            return List.of();
        }
        List<AgentChatRequest.Turn> result = new ArrayList<>(raw.size());
        for (AgentChatTurn turn : raw) {
            if (turn == null || turn.content() == null) {
                continue;
            }
            result.add(new AgentChatRequest.Turn(
                    turn.role() == null ? "user" : turn.role(), turn.content()));
        }
        return List.copyOf(result);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 请求体：字段名与两个前端的 {@code AgentChatRequest} 逐字对齐。
     *
     * <p>⚠️ {@code contextState} 是<b>不透明原始串</b>：缺失 / 空白表示本轮无压缩态；
     * 控制器不验签、不解码、不 trim，逐字下传给编排边界统一按 D49 六项校验。
     * 租户链只禁止请求体指定 {@code tenantId}，压缩态对两条身份域同样放开。
     */
    public record AgentChatStreamRequest(
            @Nullable String message,
            @Nullable List<AgentChatTurn> history,
            @Nullable String agentId,
            @Nullable Integer topK,
            boolean thinkingMode,
            @Nullable String tenantId,
            @Nullable String scenario,
            @Nullable String contextState) {
    }

    /** 单轮历史；role 只允许 user / assistant，其余由编排器 fail-closed 拒绝。 */
    public record AgentChatTurn(@Nullable String role, @Nullable String content) {
    }
}
