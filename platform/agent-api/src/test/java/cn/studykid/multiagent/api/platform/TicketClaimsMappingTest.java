package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.invocation.Capability;
import cn.studykid.multiagent.ports.invocation.Channel;
import cn.studykid.multiagent.ports.invocation.InvocationContext;
import cn.studykid.multiagent.ports.invocation.PolicyGate;
import cn.studykid.multiagent.ports.invocation.Scope;
import cn.studykid.multiagent.ports.invocation.TicketClaims;

/**
 * 第 2 刀 R3：两张既有票证的 toClaims() 归一 + 交叉误用反证。
 *
 * <p>只构造公开 record，不触碰两个 Service 的复杂假依赖；验证的是"票 → 统一声明"
 * 这条映射的语义正确性与 PolicyGate 的交叉误用拦截。
 */
class TicketClaimsMappingTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String PLATFORM_USER = "plat-user-1";
    private static final String PRINCIPAL = "tenant-principal-1";
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    private static PolicyGate gate() {
        return new PolicyGate(Capability.defaults(), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("✅ 治理票映射为平台域 + PLATFORM 档 + RAG 录入能力 + 平台直录入口")
    void governanceTicketMapsToUnifiedClaims() {
        var ticket = new PlatformRagGovernanceTicketService.GovernanceTicket(
                "token", "PLATFORM", PLATFORM_USER, NOW.plusSeconds(300), "jti-g");

        TicketClaims claims = ticket.toClaims();

        assertThat(claims.domain()).isEqualTo(TicketClaims.Domain.PLATFORM);
        assertThat(claims.principalId()).isEqualTo(PLATFORM_USER);
        assertThat(claims.scope()).isEqualTo("PLATFORM");
        assertThat(claims.capability()).isEqualTo(Capability.RAG_CORPUS_INGEST.id());
        assertThat(claims.channel()).isEqualTo(Channel.PLATFORM_DIRECT.name());
    }

    @Test
    @DisplayName("✅ 执行票映射为租户域 + TENANT 档 + Run 执行能力 + 不限入口")
    void executionTicketMapsToUnifiedClaims() {
        var ticket = new PlatformExecutionTicketService.ExecutionTicket(
                "token", TENANT, PRINCIPAL, "student-1", Set.of("AGENT_AUTHOR"),
                NOW.plusSeconds(60), "jti-e");

        TicketClaims claims = ticket.toClaims();

        assertThat(claims.domain()).isEqualTo(TicketClaims.Domain.TENANT);
        assertThat(claims.principalId()).isEqualTo(PRINCIPAL);
        assertThat(claims.scope()).isEqualTo("TENANT");
        assertThat(claims.capability()).isEqualTo(Capability.RUN_EXECUTE.id());
        assertThat(claims.channel()).isNull();
    }

    @Test
    @DisplayName("⛔ R3 执行票当平台治理票用 → TICKET_DOMAIN_MISMATCH（闸门拒）")
    void executionTicketCannotActAsGovernanceTicket() {
        var ticket = new PlatformExecutionTicketService.ExecutionTicket(
                "token", TENANT, PRINCIPAL, "student-1", Set.of("AGENT_AUTHOR"),
                NOW.plusSeconds(60), "jti-e");
        TicketClaims execClaims = ticket.toClaims();

        InvocationContext ctx = new InvocationContext(PRINCIPAL,
                InvocationContext.Domain.PLATFORM, Channel.PLATFORM_DIRECT, Scope.PLATFORM, null,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", "corr", true, execClaims);

        assertThat(gate().decide(ctx).reasonCode()).isEqualTo("TICKET_DOMAIN_MISMATCH");
    }

    @Test
    @DisplayName("⛔ R3 治理票当作 T 档 Agent 代录用 → SCOPE/CHANNEL 不匹配被拒")
    void governanceTicketCannotActAsAgentDelegation() {
        var ticket = new PlatformRagGovernanceTicketService.GovernanceTicket(
                "token", "PLATFORM", PLATFORM_USER, NOW.plusSeconds(300), "jti-g");
        TicketClaims govClaims = ticket.toClaims();

        InvocationContext ctx = new InvocationContext(PLATFORM_USER,
                InvocationContext.Domain.PLATFORM, Channel.AGENT_ORCH, Scope.TENANT, TENANT,
                Capability.RAG_CORPUS_INGEST.id(), "write:ingest", "corr", true, govClaims);

        String decision = gate().decide(ctx).reasonCode();
        assertThat(decision).isIn("TICKET_SCOPE_MISMATCH", "TICKET_CHANNEL_MISMATCH");
    }
}
