package cn.studykid.multiagent.ports.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PolicyGateTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String CORR = "corr-1";
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    private static final Map<String, Capability> REGISTRY = Capability.defaults();

    private static PolicyGate gate() {
        return new PolicyGate(REGISTRY, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static TicketClaims ticket(String scope, String capability, String channel,
                                        InvocationContext.Domain domain, String principal,
                                        Instant exp) {
        return new TicketClaims("jti-1", domain == InvocationContext.Domain.PLATFORM
                ? TicketClaims.Domain.PLATFORM : TicketClaims.Domain.TENANT,
                principal, scope, capability, channel, exp);
    }

    private static InvocationContext ctx(Channel channel, Scope scope, String tenantId,
                                          String capability, String purpose,
                                          boolean scopeExplicit, TicketClaims ticket) {
        InvocationContext.Domain domain = channel == Channel.TENANT_SESSION
                ? InvocationContext.Domain.TENANT : InvocationContext.Domain.PLATFORM;
        return new InvocationContext("principal-1", domain, channel, scope, tenantId,
                capability, purpose, CORR, scopeExplicit, ticket);
    }

    @Test
    @DisplayName("✅ 平台直录 T 档写：显式作用域 + 合法票据 → ALLOW")
    void platformDirectTenantWriteAllowed() {
        InvocationContext c = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).allowed()).isTrue();
        assertThat(c.thinkingAllowed()).isTrue();
    }

    @Test
    @DisplayName("⛔ R1 隐式作用域写恒拒（默认租户兜底不得写）")
    void implicitScopeWriteDenied() {
        InvocationContext c = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", false,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).reasonCode()).isEqualTo("SCOPE_IMPLICIT_WRITE_DENIED");
    }

    @Test
    @DisplayName("⛔ R1 反证：同一隐式上下文只读仍放行（不把读也一刀切）")
    void implicitScopeReadStillAllowed() {
        InvocationContext c = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.AGENT_CHAT_ORCHESTRATE.id(), "read:chat", false, null);

        assertThat(gate().decide(c).allowed()).isTrue();
    }

    @Test
    @DisplayName("⛔ Q1 Agent 编排 + 平台全局桶 → 拒（G 档写只留平台直录）")
    void agentGlobalWriteDenied() {
        InvocationContext c = ctx(Channel.AGENT_ORCH, Scope.PLATFORM, null,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("PLATFORM", Capability.RAG_CORPUS_INGEST.id(), "AGENT_ORCH",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).reasonCode()).isEqualTo("AGENT_GLOBAL_WRITE_DENIED");
    }

    @Test
    @DisplayName("✅ Agent 编排 T 档代录：票据匹配 → ALLOW，且推理草稿恒不下发")
    void agentTenantWriteAllowed() {
        InvocationContext c = ctx(Channel.AGENT_ORCH, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "AGENT_ORCH",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).allowed()).isTrue();
        assertThat(c.thinkingAllowed()).isFalse();
    }

    @Test
    @DisplayName("⛔ R3 票据交叉误用：治理票(PLATFORM)打 T 档调用 → TICKET_SCOPE_MISMATCH")
    void ticketCrossScopeDenied() {
        InvocationContext c = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("PLATFORM", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).reasonCode()).isEqualTo("TICKET_SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("⛔ R3 执行票当平台域用 → TICKET_DOMAIN_MISMATCH")
    void ticketCrossDomainDenied() {
        InvocationContext c = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.TENANT, "principal-1", NOW.plusSeconds(60)));

        assertThat(gate().decide(c).reasonCode()).isEqualTo("TICKET_DOMAIN_MISMATCH");
    }

    @Test
    @DisplayName("⛔ 票据过期 → TICKET_EXPIRED；缺票 → TICKET_REQUIRED")
    void ticketExpiryAndMissingDenied() {
        InvocationContext expired = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.minusSeconds(1)));
        assertThat(gate().decide(expired).reasonCode()).isEqualTo("TICKET_EXPIRED");

        InvocationContext missing = ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true, null);
        assertThat(gate().decide(missing).reasonCode()).isEqualTo("TICKET_REQUIRED");
    }

    @Test
    @DisplayName("⛔ 未知能力 / 越权入口 / ALL 档写 → 各自稳定拒绝码")
    void structuralDenials() {
        assertThat(gate().decide(ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                "unknown.capability", "read:x", true, null)).reasonCode())
                .isEqualTo("CAPABILITY_UNKNOWN");
        assertThat(gate().decide(ctx(Channel.TENANT_SESSION, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "read:x", true, null)).reasonCode())
                .isEqualTo("CHANNEL_NOT_ALLOWED");
        assertThat(gate().decide(ctx(Channel.PLATFORM_DIRECT, Scope.ALL, null,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", true, null)).reasonCode())
                .isEqualTo("SCOPE_READ_ONLY");
    }

    @Test
    @DisplayName("⛔ 上下文不变量：TENANT 缺租户 / 非 TENANT 带租户 → 构造即拒")
    void contextInvariantsFailClosed() {
        assertThatThrownBy(() -> ctx(Channel.PLATFORM_DIRECT, Scope.TENANT, null,
                Capability.RAG_CORPUS_INGEST.id(), "read:x", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> ctx(Channel.PLATFORM_DIRECT, Scope.PLATFORM, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "read:x", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非 TENANT 档");
    }

    @Test
    @DisplayName("⛔ 票据主体冒用 → TICKET_SUBJECT_MISMATCH")
    void ticketSubjectMismatchDenied() {
        InvocationContext c = new InvocationContext("someone-else",
                InvocationContext.Domain.PLATFORM, Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", CORR, true,
                ticket("TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT",
                        InvocationContext.Domain.PLATFORM, "principal-1", NOW.plusSeconds(60)));
        assertThat(gate().decide(c).reasonCode()).isEqualTo("TICKET_SUBJECT_MISMATCH");
    }

    @Test
    @DisplayName("✅ Scope.parse 严格解析：非法值返回 null，不静默回落")
    void scopeParseStrict() {
        assertThat(Scope.parse("tenant")).isEqualTo(Scope.TENANT);
        assertThat(Scope.parse("  PLATFORM ")).isEqualTo(Scope.PLATFORM);
        assertThat(Scope.parse("BOGUS")).isNull();
        assertThat(Scope.parse(null)).isNull();
        assertThat(Scope.parse("")).isNull();
    }

    @Test
    @DisplayName("✅ 能力注册表冻结且可叠加自定义副本")
    void registryIsImmutable() {
        Map<String, Capability> defaults = Capability.defaults();
        assertThatThrownBy(() -> defaults.put("evil", Capability.RUN_EXECUTE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Capability.find(defaults, Capability.RAG_CORPUS_INGEST.id())).isPresent();
        assertThat(Capability.find(defaults, "nope")).isEmpty();
    }
}
