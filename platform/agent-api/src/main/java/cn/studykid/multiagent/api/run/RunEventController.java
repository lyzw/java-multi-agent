package cn.studykid.multiagent.api.run;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.core.run.RunState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 事件流端点（P2-D05 / FR-014）。
 *
 * <p><b>执行链不可跳过任何一步</b>（P0-D03 §4 红线 9）：
 * <pre>
 *   可信身份 → 授权(EVENT_SUBSCRIBE) → 存在性判定 → 读取事件 → 序列化
 * </pre>
 *
 * <p>⚠️ <b>授权在订阅时发生，而非仅在 Run 创建时</b>。
 * 一个曾经合法的订阅者，其权限可能在订阅期间被撤销
 * （监护人撤回同意、账号封禁、师生关系解除）。
 * 端点级授权只能覆盖「连接建立那一刻」，
 * 故本实现的续传模型天然要求<b>每次请求都重新授权</b> ——
 * 客户端断线重连时携带的 {@code Last-Event-ID} 必须重新过闸门。
 *
 * <p>⚠️ <b>为什么返回 404 而非 403</b>（P2-D05 §2 D-3）：
 * Run 不存在与 Run 属于他人，返回<b>完全相同</b>的 404。
 * 若区分，攻击者可用随机 runId 逐个试探，
 * 通过状态码差异<b>枚举出真实存在的 runId</b>。
 */
@RestController
public class RunEventController {

    private static final Logger log = LoggerFactory.getLogger(RunEventController.class);

    /**
     * 终态集合。
     *
     * <p>⚠️ <b>取自 {@link RunState} 而非文档描述</b>——
     * 终态由状态机定义，不是文档说了算。
     * 初稿曾漏 {@code TIMED_OUT}，漏判会导致连接永不关闭。
     */
    private static final java.util.Set<String> TERMINAL_STATES = java.util.Set.of(
            RunState.SUCCEEDED.name(),
            RunState.FAILED.name(),
            RunState.CANCELLED.name(),
            RunState.TIMED_OUT.name());

    /** 单次响应最多返回的事件数。 */
    private static final int MAX_EVENTS = 200;

    /**
     * ⚠️ <b>允许出现在 SSE 响应中的载荷字段白名单</b>。
     *
     * <p><b>为何是白名单而非黑名单</b>（P2-D05 实现期修正）：
     * 最初实现为「保留标量、丢弃嵌套」，看似合理，实测<b>被证伪</b>——
     * {@code studentName}、{@code idCard} 都是<b>字符串标量</b>，
     * 会原样透出。而字符串恰恰是敏感字段的主要形态。
     *
     * <p>黑名单在此场景不可能完备：新增字段时若忘记加黑名单，
     * 敏感数据即泄漏。白名单则默认拒绝，新增字段<b>不会</b>自动泄漏，
     * 这是 fail-closed 与 fail-open 的区别。
     *
     * <p>新增字段须<b>显式</b>加入本集合，并同时确认其不含学生可识别信息。
     */
    private static final java.util.Set<String> ALLOWED_PAYLOAD_FIELDS =
            java.util.Set.of("state", "attempt", "stepType", "toolName",
                    "operationState", "errorCode", "phase");

    private static boolean isScalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    /**
     * 终态集合（供 SSE 长连接推送器复用）。
     *
     * <p>⚠️ <b>取自 {@link RunState} 而非硬编码</b>——P2-D05 初稿曾漏
     * {@code TIMED_OUT}，漏判会导致连接永不关闭（P3-D02 已记录该教训）。
     */
    static java.util.Set<String> terminalStates() {
        return TERMINAL_STATES;
    }

    /**
     * 载荷字段是否在白名单内（P3-D10 §5.2 S-6）。
     *
     * <p>⚠️ <b>fail-closed</b>：未列入即丢弃。新增字段若忘记加白名单，
     * 默认<b>不会</b>流出——这与「黑名单」的默认放行正好相反。
     * P2-D05 的实现期教训：黑名单在此场景不可能完备，
     * {@code studentName}/{@code idCard} 都是字符串标量会原样透出。
     */
    public boolean isPayloadFieldAllowed(@NonNull String field) {
        return ALLOWED_PAYLOAD_FIELDS.contains(field);
    }

    private final RunEventQueryPort eventQuery;
    private final AuthorizationService authorization;
    private final cn.studykid.multiagent.api.run.RunController.CurrentPrincipal currentPrincipal;

    /**
     * 限流器（P3-D10 / D-4，收口 P2-D05 §6 L7）。
     *
     * <p>⚠️ 长连接比短请求更危险——它<b>长期占用</b>服务端资源。
     * 未装配时用「永不拒绝」桩，保持既有行为。
     */
    private final RateLimitPort rateLimiter;

    public RunEventController(@NonNull RunEventQueryPort eventQuery,
                              @NonNull AuthorizationService authorization,
                              cn.studykid.multiagent.api.run.RunController.CurrentPrincipal currentPrincipal) {
        this(eventQuery, authorization, currentPrincipal, NEVER_LIMIT,
                new RunEventStreamer(500L, 30 * 60 * 1000L));
    }

    /** 「限流已装配、推送器用默认参数」的便捷构造器（单测用）。 */
    public RunEventController(@NonNull RunEventQueryPort eventQuery,
                              @NonNull AuthorizationService authorization,
                              cn.studykid.multiagent.api.run.RunController.CurrentPrincipal currentPrincipal,
                              @NonNull RateLimitPort rateLimiter) {
        this(eventQuery, authorization, currentPrincipal, rateLimiter,
                new RunEventStreamer(500L, 30 * 60 * 1000L));
    }

    /**
     * ⚠️ 本类被组件扫描注册且有<b>两个</b>构造器时，Spring 无法选择
     * → {@code No default constructor found} → 上下文启动失败。
     * 这是 F-6 的<b>第三次</b>发生（D7 {@code JdbcRunClaimRepository}、
     * D8 {@code RunController}、D10 {@code RunEventController}）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RunEventController(@NonNull RunEventQueryPort eventQuery,
                              @NonNull AuthorizationService authorization,
                              cn.studykid.multiagent.api.run.RunController.CurrentPrincipal currentPrincipal,
                              @NonNull RateLimitPort rateLimiter,
                              @NonNull RunEventStreamer streamer) {
        this.eventQuery = eventQuery;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
        this.rateLimiter = rateLimiter;
        this.streamer = streamer;
    }

    /** SSE 长连接推送器（P3-D10 / FR-014 部分）。 */
    private final RunEventStreamer streamer;

    /** 默认限流桩：永不拒绝（限流未装配时保持既有行为）。 */
    private static final RateLimitPort NEVER_LIMIT = new RateLimitPort() {
        @Override
        public Decision check(String tenantId, String bucketKey) {
            return Decision.ALLOWED;
        }

        @Override
        public long currentCount(String tenantId, String bucketKey) {
            return 0L;
        }
    };

    /**
     * 读取 Run 事件（支持断线续传）。
     *
     * <p>⚠️ <b>无 tenantId 参数</b>：租户来自已验签令牌。
     * 这是 D1 部署阻断的同一条铁律，在本端点同样成立。
     *
     * @param runId       Run 标识
     * @param lastEventId 上次消费到的序号（可选）；格式非法时返回 400
     */
    @GetMapping(value = "/api/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> streamEvents(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        // 1) 可信身份：唯一来源是已验签的令牌
        TenantContext context = currentPrincipal.context();

        // 2) 授权（红线 9）—— 每次请求都重新校验，不信任上次订阅
        authorization.requireAuthorized(
                context, AuthorizationService.Actions.EVENT_SUBSCRIBE, runId);

        // 2b) ⚠️ 建连限流（P3-D10 D-4）：长连接长期占用资源，比短请求更危险。
        //     收口 P2-D05 §6 L7「SSE 无每租户连接数限流」。
        var limit = rateLimiter.check(context.tenantId(), RateLimitPort.Buckets.SSE_CONNECT);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            log.warn("SSE 建连被限流拒绝: tenant={}, runId={}, decision={}",
                    context.tenantId(), runId, limit);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "事件流连接过于频繁");
        }

        // 3) 游标解析：非法游标必须显式拒绝
        long cursor = parseCursor(lastEventId);

        // 4) 存在性判定：⚠️ 「不存在」与「他人租户」返回相同 404
        String state = eventQuery.findState(context.tenantId(), runId);
        if (state == null) {
            // ⚠️ 不区分「不存在」与「无权限」—— 区分即提供存在性探测通道
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }

        // 5) 读取续传起点之后的事件
        List<RunEventQueryPort.RunEvent> events =
                eventQuery.findAfter(context.tenantId(), runId, cursor, MAX_EVENTS);

        // 6) 序列化为 SSE 帧
        StringBuilder body = new StringBuilder();
        for (RunEventQueryPort.RunEvent event : events) {
            appendFrame(body, event);
        }

        // 7) 终态 Run 发送完成标记后结束流
        //    ⚠️ 非终态也结束本次响应：当前是「短连接 + 续传」模型（P2-D05 §3.3），
        //    客户端凭 Last-Event-ID 重新请求。保持长连接会造成连接池耗尽。
        if (TERMINAL_STATES.contains(state) && events.size() < MAX_EVENTS) {
            appendComment(body, "run-terminal");
        }

        if (log.isDebugEnabled()) {
            log.debug("事件流返回: runId={}, cursor={}, 事件={} 条, 状态={}",
                    runId, cursor, events.size(), state);
        }

        // ⚠️ X-Accel-Buffering: no —— 反向代理（Nginx）默认会缓冲 SSE，
        //    导致事件迟迟不到。这是 SSE 部署最常见的「不推送」原因。
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-store")
                .body(body.toString());
    }

    /**
     * 解析 {@code Last-Event-ID} 游标。
     *
     * <p>⚠️ <b>格式非法必须 400，不能静默当作 0</b>：
     * 静默归零会让客户端收到全量历史，
     * 既浪费带宽又可能重复处理已完成的动作。
     *
     * @return 游标；未提供时为 0（从头开始）
     * @throws ResponseStatusException 格式非法
     */
    static long parseCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        String trimmed = lastEventId.trim();
        try {
            long value = Long.parseLong(trimmed);
            if (value < 0) {
                throw new NumberFormatException("负数游标");
            }
            // ⚠️ 客户端游标远超实际最大值时不报错：
            //    可能是客户端读到过已回滚的数据。
            //    视为「无新事件」，返回空即可。
            return value;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Last-Event-ID 格式非法");
        }
    }

    /**
     * 追加一帧 SSE。
     *
     * <p>⚠️ <b>载荷只含状态与序号</b>：不含 Prompt、不含学生数据、
     * 不含工具参数。事件写入时已最小化，此处不得「帮忙」扩展。
     */
    private static void appendFrame(StringBuilder out,
                                   cn.studykid.multiagent.ports.persistence.RunEventQueryPort.RunEvent event) {
        out.append("id: ").append(event.sequenceNo()).append('\n');
        out.append("event: ").append(event.eventType()).append('\n');

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sequenceNo", event.sequenceNo());
        data.put("at", event.createdAt().toString());

        // ⚠️⚠️ 白名单透传，**不是**黑名单过滤。
        //   早期实现用「只保留标量」的规则，结果 studentName / idCard
        //   这类字符串被原样透出 —— 而字符串正是敏感字段的主要形态。
        //   「过滤数字和布尔」挡不住「张三」「110101199001011234」。
        //   白名单是唯一可靠的写法：未列入的字段一律不出现在响应中。
        event.payload().forEach((k, v) -> {
            if (ALLOWED_PAYLOAD_FIELDS.contains(k) && isScalar(v)) {
                data.put(k, v);
            }
        });

        out.append("data: ").append(toJson(data)).append("\n\n");
    }

    private static void appendComment(StringBuilder out, String text) {
        out.append(": ").append(text).append("\n\n");
    }

    /**
     * 最小化 JSON 序列化。
     *
     * <p>⚠️ 必须转义引号与换行：载荷含未转义引号会破坏 SSE 帧结构，
     * 使后续事件被客户端丢弃。
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}