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
    private final cn.studykid.multiagent.application.cap.ConfigLifecycleService lifecycle =
            mock(cn.studykid.multiagent.application.cap.ConfigLifecycleService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private CapabilityCatalogController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext ctx = new TenantContext("tenant-1", "principal-1", Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
        return new CapabilityCatalogController(toolDefs, skillDefs, serverDefs,
                lifecycle, authorization, rateLimiter, currentPrincipal);
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

    // ── 关系查询（第三十四批）────────────────────────────────────────

    @Test
    @DisplayName("✅ Skill 关系：声明的 Tool / MCP 与「声明了但不存在」分列")
    void skillRelationsSeparateMissingOnes() {
        CapabilityCatalogController c = controller();
        when(skillDefs.findLatestPublished("skill-1")).thenReturn(Optional.of(
                new cn.studykid.multiagent.core.cap.CapSkillDef(
                        "skill-1", 1, "技能", "描述",
                        List.of("tool-1", "tool-missing"),
                        List.of(new cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration(
                                "mcp-1", null),
                                new cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration(
                                        "mcp-missing", List.of())),
                        CapabilityRiskLevel.READ, "{}", List.of(),
                        "fp", 1, Instant.now(), Instant.now())));
        when(toolDefs.findLatestPublished("tool-1")).thenReturn(Optional.of(
                new cn.studykid.multiagent.core.cap.CapToolDef(
                        "tool-1", 1, "工具", cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                        CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp", 1,
                        Instant.now(), Instant.now())));
        when(toolDefs.findLatestPublished("tool-missing")).thenReturn(Optional.empty());
        when(serverDefs.findLatestPublished("mcp-1")).thenReturn(Optional.of(sampleServer()));
        when(serverDefs.findLatestPublished("mcp-missing")).thenReturn(Optional.empty());

        var resp = c.skillRelations("skill-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = resp.getBody();
        assertThat(body.tools()).hasSize(1);
        assertThat(body.mcpServers()).hasSize(1);
        // ⚠️ 「声明了但对象不存在」必须**显式列出** ——
        //    悄悄从清单里消失会让运维以为「没有这个依赖」。
        assertThat(body.missingTools()).containsExactly("tool-missing");
        assertThat(body.missingMcpServers()).containsExactly("mcp-missing");
    }

    @Test
    @DisplayName("✅ Tool 关系：kind=native 的 Tool 没有绑定 MCP（合法状态，不是 404）")
    void nativeToolHasNoMcpBinding() {
        CapabilityCatalogController c = controller();
        when(toolDefs.findLatestPublished("tool-native")).thenReturn(Optional.of(
                new cn.studykid.multiagent.core.cap.CapToolDef(
                        "tool-native", 1, "原生工具",
                        cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                        CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp", 1,
                        Instant.now(), Instant.now())));

        var resp = c.toolRelations("tool-native");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().mcpServer())
                .as("⚠️ 原生工具不绑定 MCP 是**合法状态**，不得报成「资源不存在」")
                .isNull();
    }

    @Test
    @DisplayName("✅ MCP 关系：允许集（exposedToolAllowlist）与实际登记（tools）分列，不合并")
    void mcpRelationsKeepAllowlistAndToolsApart() {
        CapabilityCatalogController c = controller();
        when(serverDefs.findLatestPublished(SERVER_ID)).thenReturn(Optional.of(sampleServer()));
        when(toolDefs.listByMcpServer(SERVER_ID)).thenReturn(List.of(
                new cn.studykid.multiagent.core.cap.CapToolDef(
                        "tool-1", 1, "远端工具",
                        cn.studykid.multiagent.core.cap.CapToolKind.MCP,
                        CapabilityRiskLevel.READ, SERVER_ID, "remote-op",
                        "{}", "{}", "fp", 1, Instant.now(), Instant.now())));

        var resp = c.mcpRelations(SERVER_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().tools()).hasSize(1);
        // ⚠️ sampleServer 的 exposedToolAllowlist 是 null（= 仅连接级授权）
        assertThat(resp.getBody().exposedToolAllowlist())
                .as("⚠️ null（未配置）与 []（显式空清单）语义不同，不得归一化")
                .isNull();
    }

    @Test
    @DisplayName("✅ 目录视图带生命周期状态（停用是 CAS 迁移，前端需要真实原状态）")
    void catalogCarriesLifecycleState() {
        CapabilityCatalogController c = controller();
        when(serverDefs.listAll()).thenReturn(List.of(sampleServer()));
        when(lifecycle.stateOf("mcp_server", null, SERVER_ID, 1))
                .thenReturn(Optional.of(
                        cn.studykid.multiagent.core.cap.ConfigLifecycleState.PUBLISHED));

        var resp = c.listMcps();
        assertThat(resp.getBody().get(0).lifecycleState())
                .as("⛔ 目录视图没有生命周期状态：前端停用时只能硬编码 "
                        + "expectedFrom=PUBLISHED，对象早就是 DEPRECATED 时"
                        + "会得到「原状态不符」这种像并发竞争的报错")
                .isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("✅ 生命周期状态读不出来 → 该字段为 null，目录仍可读（不整页失败）")
    void lifecycleStateFailureDegradesToNull() {
        CapabilityCatalogController c = controller();
        when(serverDefs.listAll()).thenReturn(List.of(sampleServer()));
        when(lifecycle.stateOf("mcp_server", null, SERVER_ID, 1))
                .thenThrow(new IllegalStateException("生命周期状态读不到"));

        var resp = c.listMcps();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get(0).lifecycleState())
                .as("⚠️ 生命周期状态读不出来**不得**让整个目录页挂掉；"
                        + "前端据此禁用停用按钮即可（fail-closed 在 UI 层）")
                .isNull();
    }
}
