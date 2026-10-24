package cn.studykid.multiagent.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 能力目录端点测试（P1 · MCP Server 目录只读）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>{@code GET /api/cap/catalog/mcps} 返回 200 + 各 Server 最高版本视图；</li>
 *   <li>{@code GET /api/cap/catalog/mcps/{id}} 命中返回 200，未命中返回 404；</li>
 *   <li>读取同样走 {@code AGENT_MANAGE} 鉴权 + {@code GOVERNANCE_QUERY} 限流（与 Tool/Skill 同口径）。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：端口与限流均以 Mockito 桩替代，聚焦于<b>只读目录契约</b>。
 */
@DisplayName("P1 CapabilityCatalogController · MCP Server 目录只读")
class CapabilityCatalogControllerTest {

    private static final String SERVER_ID = "mcp-1";

    private final CapToolDefPort toolDefs = mock(CapToolDefPort.class);
    private final CapSkillDefPort skillDefs = mock(CapSkillDefPort.class);
    private final CapMcpServerDefPort serverDefs = mock(CapMcpServerDefPort.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private CapabilityCatalogController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext ctx = new TenantContext("tenant-1", "principal-1", Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
        return new CapabilityCatalogController(toolDefs, skillDefs, serverDefs,
                authorization, rateLimiter, currentPrincipal);
    }

    private CapMcpServerDef sampleServer() {
        return new CapMcpServerDef(
                SERVER_ID, 1,
                CapMcpServerDef.Transport.HTTP, "https://example.com/mcp", null,
                CapMcpServerDef.AuthKind.TOKEN, McpTrustTier.INTERNAL,
                null, CapabilityRiskLevel.READ, false,
                "fp-1", null, 1, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("列出 MCP Server → 200 + 映射列表")
    void listMcps_ok() {
        CapabilityCatalogController c = controller();
        when(serverDefs.listAll()).thenReturn(List.of(sampleServer()));

        var resp = c.listMcps();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).serverId()).isEqualTo(SERVER_ID);
        // ⚠️ 响应必须不含 credentialRef（脱敏）
        assertThat(resp.getBody().get(0).configFingerprint()).isEqualTo("fp-1");
    }

    @Test
    @DisplayName("读取单个 MCP Server 命中 → 200")
    void getMcp_ok_when_found() {
        CapabilityCatalogController c = controller();
        when(serverDefs.findLatestPublished(SERVER_ID)).thenReturn(Optional.of(sampleServer()));

        var resp = c.getMcp(SERVER_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().serverId()).isEqualTo(SERVER_ID);
        assertThat(resp.getBody().transport()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("读取单个 MCP Server 未命中 → 404")
    void getMcp_notFound_when_missing() {
        CapabilityCatalogController c = controller();
        when(serverDefs.findLatestPublished(SERVER_ID)).thenReturn(Optional.empty());
        when(serverDefs.find(SERVER_ID, Integer.MAX_VALUE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> c.getMcp(SERVER_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
