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
 *       目标租户由请求体显式给出；{@code thinkingAllowed=true}。</li>
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

    public RequestAgentChatIdentityResolver(@NonNull TenantLifecycleProbe lifecycleProbe) {
        this.lifecycleProbe = lifecycleProbe;
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
            String target = activeTenant(requestedTenantId, "平台链必须显式指定目标租户");
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
