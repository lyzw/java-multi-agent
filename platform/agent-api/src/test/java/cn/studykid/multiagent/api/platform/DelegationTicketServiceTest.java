package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.ports.invocation.Capability;
import cn.studykid.multiagent.ports.invocation.Channel;
import cn.studykid.multiagent.ports.invocation.PolicyGate;
import cn.studykid.multiagent.ports.invocation.Scope;
import cn.studykid.multiagent.ports.invocation.TicketBroker;
import cn.studykid.multiagent.ports.invocation.TicketClaims;

/**
 * 代理票签发的范围纪律测试（双入口方案 §4.2 / B.5#3 增量）。
 *
 * ⚠️ 这些断言守的是**防提权语义**而不是签发细节：
 *    · Agent 自报租户 / 自报父主体 → 签发前就拒（§5 反诱导）；
 *    · 未知能力 / PolicyGate 拒绝的能力 → 拒（声明式安全，不靠 Agent 自觉）；
 *    · Q1 全局写禁令：AGENT_GLOBAL_WRITE_DENIED 在签发路径同样生效；
 *    · 签出的 JWT 复用执行票结构 + capability/channel/scope 绑定声明（Q3），
 *      下游按 TicketClaims 三元组匹配。
 */
class DelegationTicketServiceTest {

    private static final String ISSUER = "test-issuer";
    private static final String TENANT_AUDIENCE = "agent-platform";
    private static final String TENANT_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String OTHER_TENANT = "a1111111-1111-1111-1111-111111111111";
    private static final String PARENT_ID = "b2222222-2222-2222-2222-222222222222";

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static TenantContext parent() {
        return new TenantContext(TENANT_ID, PARENT_ID, Set.of("TICKET_ROLE"), 1L);
    }

    private static TicketBroker.DelegationSpec spec(String capability) {
        return new TicketBroker.DelegationSpec(
                PARENT_ID,
                cn.studykid.multiagent.ports.invocation.InvocationContext.Domain.TENANT,
                TENANT_ID,
                capability,
                Channel.AGENT_ORCH,
                "录入租户语料",
                "corr-1");
    }

    private static DelegationTicketService service(boolean enabled) {
        var keyPair = JwtTokenService.generateKeyPair();
        JwtTokenService tokens = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                ISSUER, TENANT_AUDIENCE, Duration.ofSeconds(900), "k-test");
        return new DelegationTicketService(tokens,
                new PolicyGate(Capability.defaults()), enabled);
    }

    private static Map<String, Object> payloadOf(String token) {
        String payload = token.split("\\.")[1];
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) new tools.jackson.databind.ObjectMapper()
                .readValue(new String(DECODER.decode(payload), StandardCharsets.UTF_8),
                        Map.class);
        return claims;
    }

    @Test
    @DisplayName("✅ 合规代写：签出租户域 JWT，capability/channel/scope 三绑定 + 60s TTL")
    void issuesBoundShortLivedToken() {
        DelegationTicketService service = service(true);

        DelegationTicketService.DelegationTicket ticket =
                service.issue(parent(), spec(Capability.RAG_CORPUS_INGEST.id()));

        Map<String, Object> payload = payloadOf(ticket.token());
        assertThat(payload.get("aud")).isEqualTo(TENANT_AUDIENCE);
        assertThat(payload.get("tenant_id")).isEqualTo(TENANT_ID);
        assertThat(payload.get("sub")).isEqualTo(PARENT_ID);
        assertThat(payload.get("capability")).isEqualTo(Capability.RAG_CORPUS_INGEST.id());
        assertThat(payload.get("channel")).isEqualTo(Channel.AGENT_ORCH.name());
        assertThat(payload.get("scope")).isEqualTo(Scope.TENANT.auditValue());
        assertThat(payload.get("jti")).isEqualTo(ticket.jti());
        // TTL 60 秒：代理票与租户口令等价可用，必须短时
        long iat = ((Number) payload.get("iat")).longValue();
        long exp = ((Number) payload.get("exp")).longValue();
        assertThat(exp - iat).isEqualTo(60);
        // 声明视图与令牌一致：jti / capability / channel / scope
        assertThat(ticket.claims().jti()).isEqualTo(ticket.jti());
        assertThat(ticket.claims().capability()).isEqualTo(Capability.RAG_CORPUS_INGEST.id());
        assertThat(ticket.claims().channel()).isEqualTo(Channel.AGENT_ORCH.name());
        assertThat(ticket.claims().scope()).isEqualTo(Scope.TENANT.auditValue());
        assertThat(ticket.correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("⛔ Agent 自报其它租户 → DELEGATION_TENANT_MISMATCH，签发前拒绝")
    void rejectsForeignTenant() {
        DelegationTicketService service = service(true);

        TicketBroker.DelegationSpec foreign = new TicketBroker.DelegationSpec(
                PARENT_ID,
                cn.studykid.multiagent.ports.invocation.InvocationContext.Domain.TENANT,
                OTHER_TENANT,
                Capability.RAG_CORPUS_INGEST.id(),
                Channel.AGENT_ORCH,
                "把语料写到别的租户",
                "corr-2");

        assertThatThrownBy(() -> service.issue(parent(), foreign))
                .isInstanceOf(DelegationTicketService.DelegationRejectedException.class)
                .hasMessageContaining(OTHER_TENANT)
                .extracting(e -> ((DelegationTicketService.DelegationRejectedException) e).reasonCode())
                .isEqualTo("DELEGATION_TENANT_MISMATCH");
    }

    @Test
    @DisplayName("⛔ 自报父主体不一致 → DELEGATION_SUBJECT_MISMATCH（不可冒用他人身份）")
    void rejectsForeignParent() {
        DelegationTicketService service = service(true);

        TicketBroker.DelegationSpec forged = new TicketBroker.DelegationSpec(
                UUID.randomUUID().toString(),
                cn.studykid.multiagent.ports.invocation.InvocationContext.Domain.TENANT,
                TENANT_ID,
                Capability.RAG_CORPUS_INGEST.id(),
                Channel.AGENT_ORCH,
                "冒用他人",
                "corr-3");

        assertThatThrownBy(() -> service.issue(parent(), forged))
                .isInstanceOf(DelegationTicketService.DelegationRejectedException.class)
                .extracting(e -> ((DelegationTicketService.DelegationRejectedException) e).reasonCode())
                .isEqualTo("DELEGATION_SUBJECT_MISMATCH");
    }

    @Test
    @DisplayName("⛔ 未知能力 → CAPABILITY_UNKNOWN（fail-closed，不靠 Agent 自觉）")
    void rejectsUnknownCapability() {
        DelegationTicketService service = service(true);

        assertThatThrownBy(() -> service.issue(parent(), spec("rag.corpus.drop-all")))
                .isInstanceOf(DelegationTicketService.DelegationRejectedException.class)
                .extracting(e -> ((DelegationTicketService.DelegationRejectedException) e).reasonCode())
                .isEqualTo("CAPABILITY_UNKNOWN");
    }

    @Test
    @DisplayName("⛔ PolicyGate 拒绝（Q1 全局写禁令 / 不在 allowedChannels）→ 透传稳定 reasonCode")
    void rejectsWhenPolicyGateDenies() {
        DelegationTicketService service = service(true);
        // agent.chat.orchestrate 不允许 AGENT_ORCH 入口（只允许直录/租户会话）
        assertThatThrownBy(() -> service.issue(parent(), spec(Capability.AGENT_CHAT_ORCHESTRATE.id())))
                .isInstanceOf(DelegationTicketService.DelegationRejectedException.class)
                .extracting(e -> ((DelegationTicketService.DelegationRejectedException) e).reasonCode())
                .isEqualTo("CHANNEL_NOT_ALLOWED");
    }

    @Test
    @DisplayName("⛔ 通道关闭 → CHANNEL_DISABLED，绝不静默降级为父令牌直通")
    void failsClosedWhenDisabled() {
        DelegationTicketService service = service(false);

        assertThatThrownBy(() -> service.issue(parent(), spec(Capability.RAG_CORPUS_INGEST.id())))
                .isInstanceOf(DelegationTicketService.DelegationRejectedException.class)
                .extracting(e -> ((DelegationTicketService.DelegationRejectedException) e).reasonCode())
                .isEqualTo("CHANNEL_DISABLED");
    }

    @Test
    @DisplayName("✅ 签出的声明视图与父上下文匹配（TicketClaims.mismatch = null）")
    void claimsMatchParentInvocationContext() {
        DelegationTicketService service = service(true);

        DelegationTicketService.DelegationTicket ticket =
                service.issue(parent(), spec(Capability.RAG_CORPUS_INGEST.id()));

        cn.studykid.multiagent.ports.invocation.InvocationContext ctx =
                new cn.studykid.multiagent.ports.invocation.InvocationContext(
                        PARENT_ID,
                        cn.studykid.multiagent.ports.invocation.InvocationContext.Domain.TENANT,
                        Channel.AGENT_ORCH,
                        Scope.TENANT,
                        TENANT_ID,
                        Capability.RAG_CORPUS_INGEST.id(),
                        "录入租户语料",
                        "corr-1",
                        true,
                        ticket.claims());
        assertThat(TicketBroker.match(ticket.claims(), ctx, Instant.now())).isNull();
    }
}
