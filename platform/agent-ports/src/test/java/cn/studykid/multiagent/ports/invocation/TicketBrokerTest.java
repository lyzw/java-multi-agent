package cn.studykid.multiagent.ports.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketBrokerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    @DisplayName("✅ 代理票声明绑定父主体 / 能力 / 入口 / 作用域")
    void delegationClaimsBindsAllDimensions() {
        TicketBroker.DelegationSpec spec = new TicketBroker.DelegationSpec(
                "parent-user", InvocationContext.Domain.PLATFORM, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), Channel.AGENT_ORCH,
                "write:ingest", "corr-1");

        TicketClaims claims = TicketBroker.delegationClaims(spec, "jti-d", NOW.plusSeconds(60));

        assertThat(claims.jti()).isEqualTo("jti-d");
        assertThat(claims.principalId()).isEqualTo("parent-user");
        assertThat(claims.domain()).isEqualTo(TicketClaims.Domain.PLATFORM);
        assertThat(claims.capability()).isEqualTo(Capability.RAG_CORPUS_INGEST.id());
        assertThat(claims.channel()).isEqualTo("AGENT_ORCH");
        assertThat(claims.scope()).isEqualTo("TENANT");
        assertThat(claims.declaresScope()).isTrue();
    }

    @Test
    @DisplayName("⛔ Q3 代理票：TENANT_SESSION 入口强制 TENANT 域，不接受平台主体提权")
    void delegationForcesTenantDomainOnTenantSession() {
        TicketBroker.DelegationSpec spec = new TicketBroker.DelegationSpec(
                "platform-user", InvocationContext.Domain.PLATFORM, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), Channel.TENANT_SESSION,
                "read:ingest", "corr-2");

        TicketClaims claims = TicketBroker.delegationClaims(spec, "jti-t", NOW.plusSeconds(60));

        assertThat(claims.domain()).as("租户会话入口不得签出平台域代理票").isEqualTo(TicketClaims.Domain.TENANT);
    }

    @Test
    @DisplayName("✅ match 与 TicketClaims.mismatch 同语义（同一判定入口）")
    void matchAliasesMismatch() {
        TicketClaims claims = new TicketClaims("j", TicketClaims.Domain.PLATFORM, "p",
                "TENANT", Capability.RAG_CORPUS_INGEST.id(), "PLATFORM_DIRECT", NOW.plusSeconds(60));
        InvocationContext ctx = new InvocationContext("p", InvocationContext.Domain.PLATFORM,
                Channel.PLATFORM_DIRECT, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", "c", true, claims);

        assertThat(TicketBroker.match(claims, ctx, NOW)).isNull();
        assertThat(TicketBroker.match(claims, ctx, NOW.plusSeconds(61))).isEqualTo("TICKET_EXPIRED");
    }
}
