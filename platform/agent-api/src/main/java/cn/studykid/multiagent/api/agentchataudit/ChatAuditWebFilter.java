package cn.studykid.multiagent.api.agentchataudit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import cn.studykid.multiagent.api.error.RequestBodies;
import cn.studykid.multiagent.application.agentchataudit.ChatAuditContextHolder;
import cn.studykid.multiagent.application.agentchataudit.ChatAuditScope;
import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.BeginContext;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.Session;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 对话审计的 HTTP 入口：建立审计作用域、记录 HTTP 元数据与原始 body、收敛建连前失败。
 *
 * <p><b>⚠️ 位置很关键：{@code HIGHEST_PRECEDENCE + 2}</b>
 * <ul>
 *   <li>{@code HIGHEST_PRECEDENCE} 是 traceId 过滤器（要最先拿到链路号）；</li>
 *   <li>{@code + 1} 是请求体缓存过滤器（必须早于任何读取 body 的组件）；</li>
 *   <li>本过滤器在 {@code + 2}：既拿得到 traceId、又拿得到缓存 body，
 *       且<b>早于安全链</b>——早于安全链才记得到 401/429 这类「身份还没成立就失败」的请求。</li>
 * </ul>
 *
 * <p><b>⚠️ 身份是「后补」的</b>：进入时安全链还没跑，拿不到可信身份。
 * 因此本过滤器在 {@code chain.doFilter} 返回后从请求属性补写身份
 * （Bearer 过滤器把已验签主体挂在请求上，属性不会随 SecurityContext 清理而消失）。
 * 平台链的目标租户只在请求体里，由编排器装饰器从已验签上下文补写——
 * 两处写同一作用域，<b>后写覆盖</b>，以最接近业务事实的来源为准。
 *
 * <p><b>⚠️ SSE 不在这里收敛成功</b>：控制器把编排/流式调用甩到虚拟线程，
 * 本过滤器的 {@code finally} 在流还没开始时就执行完了。此时只做「补身份 + 记 body + 清绑定」，
 * 终态由编排器 / 网关装饰器收敛；这里额外挂一个 {@link AsyncListener} 兜底
 * （异步结束时幂等收口，避免记录永远停在半截）。
 *
 * <p><b>⚠️ 配置读库有代价，所以先做静态端点判定</b>：配置端点恒为
 * {@link ChatAuditConfig#ALLOWED_ENDPOINTS} 的子集（写入时校验），
 * 因此不在白名单里的请求<b>不必读库</b>——否则每个 HTTP 请求都多一次配置查询。
 *
 * <p><b>⚠️ fail-open</b>：任何审计异常只 WARN，绝不影响请求本身。
 */
public class ChatAuditWebFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditWebFilter.class);

    private final ChatAuditConfigPort configPort;
    private final ChatAuditRecorderPort recorder;
    private final boolean emergencyStop;

    public ChatAuditWebFilter(@NonNull ChatAuditConfigPort configPort,
                              @NonNull ChatAuditRecorderPort recorder,
                              boolean emergencyStop) {
        this.configPort = configPort;
        this.recorder = recorder;
        this.emergencyStop = emergencyStop;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // 只在 REQUEST 派发工作：ERROR / ASYNC 二次派发会重复建作用域（同一次请求记两条）
        if (emergencyStop || request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        // 静态白名单先行：不属于任何可审计端点的请求，连配置都不读
        return matchEndpoint(ChatAuditConfig.ALLOWED_ENDPOINTS, request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        ChatAuditConfig config;
        long configVersion;
        try {
            ChatAuditConfig.State state = configPort.get();
            config = state.config();
            configVersion = state.version();
        } catch (RuntimeException e) {
            log.warn("读取对话审计配置失败，本次不记录: {}", e.toString());
            chain.doFilter(request, response);
            return;
        }
        if (!config.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String endpoint = matchEndpoint(config.scope().endpoints(), request.getRequestURI());
        if (endpoint == null) {
            // 该端点被配置从白名单里摘掉了（例如只想审计平台链）
            chain.doFilter(request, response);
            return;
        }

        Instant startedAt = Instant.now();
        String auditId = UUID.randomUUID().toString();
        String traceId = resolveTraceId(auditId);
        String domain = domainOf(request.getRequestURI());

        Session session;
        try {
            session = recorder.begin(new BeginContext(
                    auditId, traceId, endpoint, domain, null, null, startedAt,
                    request.getMethod(), request.getRequestURI(),
                    pickedHeaders(config, request), config, configVersion));
        } catch (RuntimeException e) {
            log.warn("对话审计会话创建失败（本次不记录）: {}", e.toString());
            chain.doFilter(request, response);
            return;
        }
        ChatAuditScope scope = new ChatAuditScope(
                session, config, auditId, traceId, endpoint, startedAt);
        ChatAuditContextHolder.bind(scope);

        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            try {
                scope.setHttpStatus(response.getStatus());
                boolean tenantResolved = fillIdentity(scope, request);
                // body 只有下游读过之后才拿得到（请求流是一次性的），故在链尾取
                RequestBodies.cachedBody(request).ifPresent(body ->
                        scope.setRawRequestBody(body, RequestBodies.isTruncated(request)));
                if (request.isAsyncStarted()) {
                    registerAsyncFallback(request, scope);
                } else {
                    convergeSync(scope, response.getStatus(), failed, tenantResolved,
                            request.getMethod());
                }
            } catch (RuntimeException e) {
                log.warn("对话审计收尾失败（已忽略）: auditId={}", auditId, e);
            } finally {
                // ⚠️ 无条件清理：工作线程会复用，漏清会把本次会话带进下一个请求
                ChatAuditContextHolder.clear();
            }
        }
    }

    /** 同步请求在这条路径上收敛终态。 */
    private void convergeSync(@NonNull ChatAuditScope scope, int status, boolean failed,
                              boolean tenantResolved, @NonNull String httpMethod) {
        boolean rejectedBeforeStream = !failed && status >= 400;
        if (rejectedBeforeStream && !tenantResolved) {
            // 建连前失败且没有可信租户 → 只记元数据（原文归属不明，绝不落原文）
            recordRejected(scope, status, httpMethod);
            return;
        }
        String terminal = terminalOf(status, failed);
        ErrorInfo error = "SUCCEEDED".equals(terminal)
                ? null : new ErrorInfo("preconnection", "HTTP_" + status, "HTTP " + status);
        scope.finish(terminal, error);
    }

    /**
     * 异步（SSE）兜底：装饰器没收敛时按结束/超时/错误收口。
     *
     * <p>⚠️ 不在这里判成功：SSE 的终态取决于 {@code done} 帧，只有装饰器看得到。
     * 这里只在「异步结束」兜底，{@code finish} 幂等，与装饰器不会打架
     * （装饰器先收敛时，这里的调用被忽略）。
     */
    private void registerAsyncFallback(@NonNull HttpServletRequest request,
                                       @NonNull ChatAuditScope scope) {
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    scope.finish("SUCCEEDED", null);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    scope.finish("TIMED_OUT",
                            new ErrorInfo("stream", "SSE_TIMEOUT", "SSE 超时"));
                }

                @Override
                public void onError(AsyncEvent event) {
                    scope.finish("FAILED",
                            new ErrorInfo("stream", "SSE_ERROR", "SSE 传输失败"));
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // 二次异步：沿用同一作用域
                }
            });
        } catch (RuntimeException e) {
            // 容器已收尾时拿不到 AsyncContext：不是致命问题（记录已由装饰器收敛）
            log.debug("无法注册审计异步兜底: auditId={}, cause={}", scope.auditId(), e.toString());
        }
    }

    /** 建连前失败：只写未归属元数据表。 */
    private void recordRejected(@NonNull ChatAuditScope scope, int status,
                                @NonNull String httpMethod) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("uri", scope.endpoint());
        metadata.put("durationMs", String.valueOf(
                Duration.between(scope.startedAt(), Instant.now()).toMillis()));
        recorder.recordRejected(new ChatAuditRejected(
                scope.auditId(), scope.traceId(), scope.startedAt(), scope.endpoint(),
                httpMethod, status, reasonOf(status), metadata), scope.config());
    }

    private static @NonNull String resolveTraceId(@NonNull String auditId) {
        String traceId = LogContext.traceId();
        return traceId == null || traceId.isBlank() ? auditId : traceId;
    }

    private static @NonNull String reasonOf(int status) {
        return switch (status) {
            case 400 -> "参数不合法";
            case 401 -> "未认证";
            case 403 -> "无权限";
            case 404 -> "端点不存在";
            case 429 -> "建连被限流";
            default -> status >= 500 ? "服务端错误" : "请求被拒绝";
        };
    }

    /** HTTP 状态 → 审计终态（与 DDL 的 CHECK 白名单逐字一致）。 */
    static @NonNull String terminalOf(int status, boolean failed) {
        if (failed || status >= 500) {
            return "FAILED";
        }
        return status >= 400 ? "REJECTED" : "SUCCEEDED";
    }

    /**
     * 从已验签请求属性补写身份。
     *
     * @return 是否已解析出租户（平台主体不算「有租户」，但平台域可用 nil UUID 占位）
     */
    private static boolean fillIdentity(@NonNull ChatAuditScope scope,
                                        @NonNull HttpServletRequest request) {
        Object tenantAttr = request.getAttribute(
                IdentityDomains.TENANT_CONTEXT_REQUEST_ATTRIBUTE);
        if (tenantAttr instanceof TenantContext tenant) {
            scope.identify("TENANT", tenant.tenantId(), tenant.principalId());
            return true;
        }
        Object platformAttr = request.getAttribute(
                IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE);
        if (platformAttr instanceof PlatformPrincipal principal) {
            // 平台主体不持有租户：目标租户由编排器装饰器补写（或落 nil UUID 占位）
            scope.identify("PLATFORM", null, principal.userId());
            return true;
        }
        return false;
    }

    /** 端点白名单匹配：精确或「前缀 + /」边界，避免 /api/runs 命中 /api/runsXyz。 */
    static @Nullable String matchEndpoint(@NonNull List<String> endpoints,
                                          @Nullable String uri) {
        String path = uri == null ? "" : uri;
        for (String candidate : endpoints) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.endsWith("/")
                    ? candidate.substring(0, candidate.length() - 1) : candidate;
            if (path.equals(normalized) || path.startsWith(normalized + "/")) {
                return normalized;
            }
        }
        return null;
    }

    /** 域判定：{@code /api/knowledge/**} 是租户链，其余（平台链）为 PLATFORM。 */
    static @NonNull String domainOf(@Nullable String uri) {
        return uri != null && uri.startsWith("/api/knowledge/") ? "TENANT" : "PLATFORM";
    }

    /** 只记录配置里点名的 Header（凭据类无论配不配都会被脱敏器强制掩码）。 */
    private static @NonNull Map<String, String> pickedHeaders(
            @NonNull ChatAuditConfig config, @NonNull HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> wanted = config.capture().headers();
        if (wanted == null || wanted.isEmpty()) {
            return out;
        }
        for (String name : wanted) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = request.getHeader(name);
            if (value != null) {
                out.put(name.toLowerCase(Locale.ROOT), value);
            }
        }
        return out;
    }
}
