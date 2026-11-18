package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver.InvalidAgentChatTargetException;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver.UnauthorizedAgentChatException;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.invocation.Channel;
import cn.studykid.multiagent.ports.invocation.InvocationContext;
import cn.studykid.multiagent.ports.invocation.Scope;
import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 双端点身份域隔离的守卫。
 *
 * <p><b>反向验证锚点</b>：把租户链的 {@code thinkingAllowed} 改成 {@code true}，
 * {@link #tenantChainUsesTokenTenantAndNeverAllowsReasoning()} 立即红；
 * 把平台链 matcher 从 SecurityConfiguration 删除，
 * {@link #crossDomainPrincipalIsRejectedOnBothChains()} 对应的装配守卫也会红。
 */
class RequestAgentChatIdentityResolverTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String DEFAULT_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String PRINCIPAL = "student-1";
    private static final String PLATFORM_USER = "plat-user-1";
    private static final String TOKEN = "signed.jwt.value";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest platformRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE,
                new PlatformPrincipal(PLATFORM_USER, Set.of("PLATFORM_SUPER_ADMIN"), 1L, true));
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static MockHttpServletRequest tenantRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE,
                new TenantContext(TENANT, PRINCIPAL, Set.of("STUDENT"), 1L));
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static RequestAgentChatIdentityResolver resolver(String state) {
        return resolver(state, null);
    }

    /** defaultTenant=null 表示「未配置默认租户」，必须保持旧的 fail-closed 行为。 */
    private static RequestAgentChatIdentityResolver resolver(String state, String defaultTenant) {
        TenantLifecycleProbe probe = mock(TenantLifecycleProbe.class);
        when(probe.lifecycleState(TENANT)).thenReturn(Optional.ofNullable(state));
        when(probe.lifecycleState(DEFAULT_TENANT)).thenReturn(Optional.ofNullable(state));
        return new RequestAgentChatIdentityResolver(probe, defaultTenant);
    }

    @Test
    @DisplayName("平台链：平台主体 + 显式 ACTIVE 租户 → PLATFORM 域且允许推理草稿")
    void platformChainUsesPlatformPrincipalAndExplicitTarget() {
        bind(platformRequest());

        Context ctx = resolver(TenantLifecycle.ACTIVE).resolve(true, TENANT);

        assertThat(ctx.tenantId()).isEqualTo(TENANT);
        assertThat(ctx.principalId()).isEqualTo(PLATFORM_USER);
        assertThat(ctx.domain()).isEqualTo(Context.Domain.PLATFORM);
        assertThat(ctx.thinkingAllowed()).isTrue();
        assertThat(ctx.bearerToken()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("⛔ 租户链：令牌租户 + TENANT 域 + 恒不下发推理草稿")
    void tenantChainUsesTokenTenantAndNeverAllowsReasoning() {
        bind(tenantRequest());

        Context ctx = resolver(TenantLifecycle.ACTIVE).resolve(false, null);

        assertThat(ctx.tenantId()).isEqualTo(TENANT);
        assertThat(ctx.principalId()).isEqualTo(PRINCIPAL);
        assertThat(ctx.domain()).isEqualTo(Context.Domain.TENANT);
        assertThat(ctx.thinkingAllowed()).isFalse();
        assertThat(ctx.bearerToken()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("⛔ 跨域主体一律 401：租户令牌打平台链、平台令牌打租户链")
    void crossDomainPrincipalIsRejectedOnBothChains() {
        MockHttpServletRequest wrongForPlatform = platformRequest();
        wrongForPlatform.removeAttribute(SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE);
        wrongForPlatform.setAttribute(SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE,
                new TenantContext(TENANT, PRINCIPAL, Set.of(), 1L));
        bind(wrongForPlatform);
        assertThatThrownBy(() -> resolver(TenantLifecycle.ACTIVE).resolve(true, TENANT))
                .isInstanceOf(UnauthorizedAgentChatException.class);

        MockHttpServletRequest wrongForTenant = tenantRequest();
        wrongForTenant.removeAttribute(SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE);
        wrongForTenant.setAttribute(SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE,
                new PlatformPrincipal(PLATFORM_USER, Set.of(), 1L, true));
        bind(wrongForTenant);
        assertThatThrownBy(() -> resolver(TenantLifecycle.ACTIVE).resolve(false, null))
                .isInstanceOf(UnauthorizedAgentChatException.class);
    }

    @Test
    @DisplayName("⛔ 目标租户必须 ACTIVE：暂停 / 不存在直接拒（旧令牌也不能续命）")
    void targetTenantMustBeActiveAndExisting() {
        bind(platformRequest());
        assertThatThrownBy(() -> resolver(TenantLifecycle.SUSPENDED).resolve(true, TENANT))
                .isInstanceOf(InvalidAgentChatTargetException.class)
                .hasMessageContaining("SUSPENDED");
        assertThatThrownBy(() -> resolver(null).resolve(true, TENANT))
                .isInstanceOf(InvalidAgentChatTargetException.class)
                .hasMessageContaining("不存在");

        bind(tenantRequest());
        assertThatThrownBy(() -> resolver(TenantLifecycle.SUSPENDED).resolve(false, null))
                .isInstanceOf(InvalidAgentChatTargetException.class);
    }

    @Test
    @DisplayName("⛔ 平台链缺/非法目标租户一律 400；租户链出现 tenantId 一律拒")
    void tenantTargetRulesAreFailClosed() {
        bind(platformRequest());
        RequestAgentChatIdentityResolver active = resolver(TenantLifecycle.ACTIVE);
        assertThatThrownBy(() -> active.resolve(true, null))
                .isInstanceOf(InvalidAgentChatTargetException.class);
        assertThatThrownBy(() -> active.resolve(true, "this-is-not-a-uuid"))
                .isInstanceOf(InvalidAgentChatTargetException.class);

        bind(tenantRequest());
        assertThatThrownBy(() -> active.resolve(false, TENANT))
                .isInstanceOf(InvalidAgentChatTargetException.class);
    }

    @Test
    @DisplayName("平台链未指定租户 + 已配置默认租户 → 用默认租户，仍经 UUID/生命周期校验")
    void platformChainFallsBackToConfiguredDefaultTenant() {
        bind(platformRequest());

        Context ctx = resolver(TenantLifecycle.ACTIVE, DEFAULT_TENANT).resolve(true, null);

        assertThat(ctx.tenantId())
                .as("显式租户缺失时必须用配置默认租户，不能再抛「平台链必须显式指定目标租户」")
                .isEqualTo(DEFAULT_TENANT);
        assertThat(ctx.domain()).isEqualTo(Context.Domain.PLATFORM);
        assertThat(ctx.thinkingAllowed()).isTrue();
    }

    @Test
    @DisplayName("⛔ 默认租户仍走 ACTIVE/存在性校验：配置成已暂停租户照样拒")
    void configuredDefaultTenantMustBeActive() {
        bind(platformRequest());

        assertThatThrownBy(() -> resolver(TenantLifecycle.SUSPENDED, DEFAULT_TENANT)
                .resolve(true, null))
                .isInstanceOf(InvalidAgentChatTargetException.class)
                .hasMessageContaining("SUSPENDED");
        assertThatThrownBy(() -> resolver(null, DEFAULT_TENANT).resolve(true, null))
                .isInstanceOf(InvalidAgentChatTargetException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("✅ 显式 tenantId 优先于默认租户（默认租户只兜底，不抢已有选择）")
    void explicitTenantWinsOverDefault() {
        bind(platformRequest());

        Context ctx = resolver(TenantLifecycle.ACTIVE, DEFAULT_TENANT).resolve(true, TENANT);

        assertThat(ctx.tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("⛔ 无令牌 / 无 Web 请求上下文一律 401，绝不编造默认身份")
    void missingTokenOrContextFailsClosed() {
        MockHttpServletRequest noToken = platformRequest();
        noToken.removeHeader("Authorization");
        bind(noToken);
        assertThatThrownBy(() -> resolver(TenantLifecycle.ACTIVE).resolve(true, TENANT))
                .isInstanceOf(UnauthorizedAgentChatException.class);

        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> resolver(TenantLifecycle.ACTIVE).resolve(true, TENANT))
                .isInstanceOf(UnauthorizedAgentChatException.class)
                .hasMessageContaining("无 HTTP 请求上下文");
    }

    @Test
    @DisplayName("第1刀 R1：平台链显式租户 → 统一上下文 scopeExplicit=true")
    void resolveInvocationPlatformExplicitIsScopeExplicit() {
        bind(platformRequest());

        InvocationContext ctx = resolver(TenantLifecycle.ACTIVE)
                .resolveInvocation(true, TENANT, "agent.chat.orchestrate", "read:chat", "corr-1", null);

        assertThat(ctx.tenantId()).isEqualTo(TENANT);
        assertThat(ctx.scope()).isEqualTo(Scope.TENANT);
        assertThat(ctx.channel()).isEqualTo(Channel.PLATFORM_DIRECT);
        assertThat(ctx.domain()).isEqualTo(InvocationContext.Domain.PLATFORM);
        assertThat(ctx.scopeExplicit()).isTrue();
        assertThat(ctx.capability()).isEqualTo("agent.chat.orchestrate");
    }

    @Test
    @DisplayName("⛔ 第1刀 R1：平台链默认租户回落 → scopeExplicit=false（PolicyGate 据此拒写）")
    void resolveInvocationDefaultTenantIsNotScopeExplicit() {
        bind(platformRequest());

        InvocationContext ctx = resolver(TenantLifecycle.ACTIVE, DEFAULT_TENANT)
                .resolveInvocation(true, null, "rag.corpus.ingest", "write:ingest", "corr-2", null);

        assertThat(ctx.tenantId()).isEqualTo(DEFAULT_TENANT);
        assertThat(ctx.scopeExplicit()).isFalse();
        assertThat(ctx.isWrite()).isTrue();
    }

    @Test
    @DisplayName("⛔ 第1刀 R2：推理草稿可见性只由入口派生，租户链恒 false")
    void resolveInvocationThinkingDerivesFromChannel() {
        bind(platformRequest());
        InvocationContext platformCtx = resolver(TenantLifecycle.ACTIVE)
                .resolveInvocation(true, TENANT, "agent.chat.orchestrate", "read:chat", "c1", null);
        assertThat(platformCtx.thinkingAllowed()).isTrue();

        bind(tenantRequest());
        InvocationContext tenantCtx = resolver(TenantLifecycle.ACTIVE)
                .resolveInvocation(false, null, "agent.chat.orchestrate", "read:chat", "c2", null);
        assertThat(tenantCtx.thinkingAllowed()).isFalse();
        assertThat(tenantCtx.channel()).isEqualTo(Channel.TENANT_SESSION);
        assertThat(tenantCtx.scopeExplicit()).isTrue();
    }

    @Test
    @DisplayName("裸令牌头亦可解析（与平台过滤器宽松口径一致，不解析令牌内容）")
    void bareTokenHeaderIsAccepted() {
        MockHttpServletRequest request = platformRequest();
        request.removeHeader("Authorization");
        request.addHeader("Authorization", TOKEN);
        bind(request);

        assertThat(resolver(TenantLifecycle.ACTIVE).resolve(true, TENANT).bearerToken())
                .isEqualTo(TOKEN);
    }
}
