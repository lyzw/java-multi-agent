package cn.studykid.multiagent.bootstrap.config;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.invocation.Channel;
import cn.studykid.multiagent.ports.invocation.InvocationContext;
import cn.studykid.multiagent.ports.invocation.TicketClaims;
import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 请求级 Agent 对话的可信身份解析器（{@link AgentChatIdentityResolver} 的生产实现）。
 *
 * <p><b>唯一可信来源</b>：Spring Security 过滤器在<b>验签成功后</b>写入的请求属性，
 * 加上已验签的 {@code Authorization} 头。请求体里的 {@code tenantId} 只是平台链的
 * <b>目标</b>，必须经 UUID 形态 + 生命周期双重校验；租户链出现该字段直接拒绝。
 *
 * <p><b>两条链的身份域</b>：
 * <ul>
 *   <li>平台链 {@code /api/agent/chat/stream}：{@link SecurityConfiguration#PLATFORM_PRINCIPAL_ATTRIBUTE}
 *       必须是 {@link PlatformPrincipal}（{@code aud=admin-console-api}，自身不持租户）；
 *       目标租户由请求体显式给出，<b>未给出时回落到配置化默认租户</b>
 *       （{@code agent.chat.default-tenant-id}，供 AI 实验室通用对话使用，未配置则 fail-closed）；
 *       {@code thinkingAllowed=true}。</li>
 *   <li>租户链 {@code /api/knowledge/agent/chat/stream}：
 *       {@link SecurityConfiguration#TENANT_CONTEXT_ATTRIBUTE} 必须是 {@link TenantContext}
 *       （{@code aud=agent-platform}）；租户从令牌取；{@code thinkingAllowed=false}。</li>
 * </ul>
 * 任一端点若拿到<b>对域</b>的主体属性（如租户令牌打平台链），一律 fail-closed 抛 401，
 * 绝不做「身份域自动转换」——那等于把令牌层隔离在应用层拆掉。
 *
 * <p><b>租户生命周期再验证</b>：两条链路都经 {@link TenantLifecycleProbe} 读
 * {@code public.tenant.status}（<b>全局表、无 RLS</b>，不依赖任何租户上下文），
 * 只有 {@link TenantLifecycle#ACTIVE} 才承接新请求。平台暂停 / 退役租户后，
 * 即便旧令牌仍在 15 分钟有效期内，也不能再开一条新的 Agent 对话。
 *
 * <p><b>Bearer 令牌透传</b>：从已验签请求头取原始令牌（不含 {@code Bearer } 前缀），
 * 交给编排器做 platform → rag 的服务间鉴权。⛔ <b>绝不打印 / 落库 / 进 SSE 事件</b>。
 * 租户链 rag 侧强制 {@code tenant_id claim == X-Tenant-Id}；平台链 rag 允许合法跨租户。
 *
 * <p><b>线程边界</b>：本解析器只在控制器<b>请求线程</b>调用。编排器跑在虚拟线程池，
 * {@code RequestContextHolder} 不会自动传播，故所有请求属性与令牌都在此一次性取好，
 * 以不可变 {@link Context} 带进异步执行体。
 */
public final class RequestAgentChatIdentityResolver implements AgentChatIdentityResolver {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TenantLifecycleProbe lifecycleProbe;
    /** 平台链未显式指定租户时的兜底目标；null/空白 = 不启用兜底，保持 fail-closed。 */
    private final @Nullable String defaultTenantId;

    public RequestAgentChatIdentityResolver(@NonNull TenantLifecycleProbe lifecycleProbe) {
        this(lifecycleProbe, null);
    }

    public RequestAgentChatIdentityResolver(@NonNull TenantLifecycleProbe lifecycleProbe,
                                             @Nullable String defaultTenantId) {
        this.lifecycleProbe = lifecycleProbe;
        this.defaultTenantId = defaultTenantId;
    }

    @Override
    public @NonNull Context resolve(boolean platformChain,
                                    @Nullable String requestedTenantId) {
        HttpServletRequest request = currentRequest();
        // 令牌在任何身份分支之前取：两条链路都要用它调用 rag。
        String bearerToken = bearerToken(request);

        if (platformChain) {
            Object verified = request.getAttribute(
                    SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE);
            if (!(verified instanceof PlatformPrincipal principal)) {
                throw new UnauthorizedAgentChatException(
                        "平台对话端点需要平台令牌（aud=admin-console-api）");
            }
            // 平台管理员的通用对话不需要绑定具体租户：未指定时回落到配置化默认租户。
            // ⚠️ 回落值仍走 activeTenant 的 UUID + 生命周期校验——配置错（非法/不存在/已暂停）
            //    同样 fail-closed，绝不因为「是默认的」就跳过校验。
            String targetTenant = requestedTenantId == null || requestedTenantId.isBlank()
                    ? defaultTenantId
                    : requestedTenantId;
            String target = activeTenant(targetTenant, "平台链必须显式指定目标租户，且未配置默认租户");
            return new Context(target, principal.userId(),
                    Context.Domain.PLATFORM, true, bearerToken);
        }

        Object verified = request.getAttribute(SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE);
        if (!(verified instanceof TenantContext tenant)) {
            throw new UnauthorizedAgentChatException(
                    "租户对话端点需要租户令牌（aud=agent-platform）");
        }
        if (requestedTenantId != null && !requestedTenantId.isBlank()) {
            // 控制器已拒绝过一次；这里守第二道，防止将来有人绕过控制器直调端口。
            throw new InvalidAgentChatTargetException("租户链不得在请求体中指定 tenantId");
        }
        activeTenant(tenant.tenantId(), "目标租户不存在");
        return new Context(tenant.tenantId(), tenant.principalId(),
                Context.Domain.TENANT, false, bearerToken);
    }

    /**
     * 统一调用上下文解析（第 1 刀 · 收口判定的落点）。
     *
     * <p>在既有 {@link #resolve(boolean, String)} 完成验签 / UUID / 生命周期校验之后，
     * 把结果归一为 {@link InvocationContext}，供 {@code PolicyGate} 统一裁决。
     * <b>不改动旧契约</b>：旧的 {@code resolve} 与其 8 处调用点保持逐字兼容。
     *
     * <p><b>R1 关闭点</b>：{@code scopeExplicit} 严格等于"请求体是否显式给出目标租户"。
     * 平台链未给租户而回落到配置默认租户时，本值为 false，PolicyGate 对该上下文的
     * <b>写意图一律拒绝</b>（{@code SCOPE_IMPLICIT_WRITE_DENIED}），只读视图仍可用。
     * 租户链的租户来自已验签令牌，恒为显式。
     *
     * <p><b>R2 关闭点</b>：本方法不接收 thinkingAllowed 入参；该值由
     * {@link InvocationContext#thinkingAllowed()} 从 {@link Channel} 派生，
     * 草稿可见性不再随上下文 / 票据跨渠道流转。
     *
     * @param platformChain 是否平台链（与 resolve 的首参同义）
     * @param requestedTenantId 请求体显式租户（平台链可为 null = 回落默认租户）
     * @param capability 能力标识（由控制器按端点声明，如 agent.chat.orchestrate）
     * @param purpose 目的串（"write:" 前缀 = 写意图；其余为读）
     * @param correlationId 链路关联 id
     * @param ticket 已验签票据声明（无票据传 null）
     */
    public @NonNull InvocationContext resolveInvocation(
            boolean platformChain,
            @Nullable String requestedTenantId,
            @NonNull String capability,
            @NonNull String purpose,
            @NonNull String correlationId,
            @Nullable TicketClaims ticket) {
        Context trusted = resolve(platformChain, requestedTenantId);
        boolean explicit = !platformChain
                || (requestedTenantId != null && !requestedTenantId.isBlank());
        InvocationContext.Domain domain = platformChain
                ? InvocationContext.Domain.PLATFORM
                : InvocationContext.Domain.TENANT;
        Channel channel = platformChain ? Channel.PLATFORM_DIRECT : Channel.TENANT_SESSION;
        return new InvocationContext(
                trusted.principalId(), domain, channel,
                cn.studykid.multiagent.ports.invocation.Scope.TENANT,
                trusted.tenantId(), capability, purpose, correlationId, explicit, ticket);
    }

    /**
     * 校验目标租户：UUID 形态 + 存在 + {@code ACTIVE}。
     *
     * <p>⚠️ 状态值来自 DB 而不是请求参数 / 令牌自述。租户不存在或已暂停/退役时
     * 抛 {@link InvalidAgentChatTargetException}（控制器映射 400），
     * <b>不</b>抛 401 —— 后者会让前端误判为「登录过期」并清会话跳登录页，
     * 而真实原因是租户被平台停用，重登一百次也没用。
     */
    private @NonNull String activeTenant(@Nullable String raw,
                                          @NonNull String missingMessage) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAgentChatTargetException(missingMessage);
        }
        final String tenantId;
        try {
            tenantId = UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidAgentChatTargetException("目标租户不是合法 UUID");
        }
        String state = lifecycleProbe.lifecycleState(tenantId).orElse(null);
        if (state == null) {
            throw new InvalidAgentChatTargetException("目标租户不存在: " + tenantId);
        }
        if (!TenantLifecycle.isActive(state)) {
            throw new InvalidAgentChatTargetException(
                    "目标租户不可承接新请求（状态=" + state + "）");
        }
        return tenantId;
    }

    /** 无 Web 请求上下文 = 单测直调或非 HTTP 误用，fail-closed。 */
    private static @NonNull HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        throw new UnauthorizedAgentChatException("无 HTTP 请求上下文");
    }

    /**
     * 提取原始 Bearer 令牌。
     *
     * <p>兼容两种头形态：{@code Bearer xxx} 与裸 {@code xxx}（与平台过滤器
     * {@code PlatformBearerTokenFilter} 的宽松口径一致）。<b>只取字符串，不解析内容</b>——
     * 验签是过滤器的唯一职责，这里再做一遍会形成两处实现、语义迟早分叉。
     */
    private static @NonNull String bearerToken(@NonNull HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || header.isBlank()) {
            throw new UnauthorizedAgentChatException("缺少已验签的 Bearer 令牌");
        }
        String token = header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : header.trim();
        if (token.isEmpty()) {
            throw new UnauthorizedAgentChatException("Bearer 令牌为空");
        }
        return token;
    }
}
