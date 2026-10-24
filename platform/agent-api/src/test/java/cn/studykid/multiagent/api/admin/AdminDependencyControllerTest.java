package cn.studykid.multiagent.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.DependencyGraphService;
import cn.studykid.multiagent.core.cap.DependencyEntityType;
import cn.studykid.multiagent.core.cap.DependencyGraph;
import cn.studykid.multiagent.core.cap.DependencyGraph.EdgeKind;
import cn.studykid.multiagent.core.cap.DependencyGraph.NodeType;
import cn.studykid.multiagent.core.cap.ImpactReport;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 依赖关系端点测试（P3 · 管理面 M7 只读端点）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>{@code GET /graph}：200 + 服务按<b>调用方租户</b>取数（防越租户）；</li>
 *   <li>{@code GET /impact}：200 + 参数校验 400 + 实体不存在 404；</li>
 *   <li>鉴权与限流口径：未授权抛错（不触达服务）、429；</li>
 *   <li>与 {@code CapabilityCatalogController} 同信任级别：{@code AGENT_MANAGE}。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：图谱服务以 Mockito 桩替代，聚焦<b>端点契约与闸门口径</b>。
 */
@DisplayName("P3 AdminDependencyController · 依赖关系端点")
class AdminDependencyControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PRINCIPAL = "principal-1";

    private final DependencyGraphService dependencies = mock(DependencyGraphService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private AdminDependencyController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext ctx = new TenantContext(TENANT, PRINCIPAL, Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
        return new AdminDependencyController(dependencies, authorization, rateLimiter,
                currentPrincipal);
    }

    private static DependencyGraph sampleGraph() {
        return new DependencyGraph(TENANT,
                List.of(new DependencyGraph.Node(
                        "tenant:" + TENANT, NodeType.TENANT, TENANT, TENANT, "租户",
                        true, true, 0)),
                List.of(), false);
    }

    private static ImpactReport sampleImpact() {
        return new ImpactReport("TOOL", "t-1", "t-1-name", true,
                List.of(), List.of(
                        new ImpactReport.Ref("SKILL", "sk-1", "sk-1-name",
                                EdgeKind.DECLARED_TOOL)),
                List.of(), List.of(), List.of());
    }

    // ── graph ─────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 图谱 200：按调用方租户取数（不穿透其他租户）")
    void graph_ok() {
        AdminDependencyController c = controller();
        when(dependencies.graph(TENANT)).thenReturn(sampleGraph());

        ResponseEntity<DependencyGraph> resp = c.graph();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().tenantId()).isEqualTo(TENANT);
        // ⚠️ 必须用令牌里的租户，而非任何请求参数
        verify(dependencies).graph(TENANT);
    }

    @Test
    @DisplayName("⛔ 图谱未授权 → 直接抛错（不触达图谱服务）")
    void graph_unauthorized() {
        AdminDependencyController c = controller();
        doThrow(new RuntimeException("denied"))
                .when(authorization).requireAuthorized(any(), any(), any());

        assertThatThrownBy(c::graph)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("denied");
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("✅ 图谱限流 → 429")
    void graph_rateLimited() {
        AdminDependencyController c = controller();
        // ⚠️ 必须在 controller() 之后桩：helper 内已桩 ALLOWED，后桩覆盖先桩
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.REJECTED);

        assertThatThrownBy(c::graph)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoServiceCall();
    }

    // ── impact ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 影响分析 200：透传服务报告")
    void impact_ok() {
        AdminDependencyController c = controller();
        when(dependencies.impact(eq(TENANT), eq(DependencyEntityType.TOOL), eq("t-1")))
                .thenReturn(sampleImpact());

        ResponseEntity<ImpactReport> resp = c.impact("tool", "t-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().entityExists()).isTrue();
        assertThat(resp.getBody().dependents()).hasSize(1);
    }

    @Test
    @DisplayName("✅ 影响分析兼容 mcp 别名 → MCP_SERVER")
    void impact_mcpAlias() {
        AdminDependencyController c = controller();
        when(dependencies.impact(eq(TENANT), eq(DependencyEntityType.MCP_SERVER), eq("s-1")))
                .thenReturn(sampleImpact());

        assertThat(c.impact("mcp", "s-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("⛔ 影响分析 type 非法 → 400")
    void impact_badType() {
        AdminDependencyController c = controller();

        assertThatThrownBy(() -> c.impact("widget", "t-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("⛔ 影响分析 id 空白 → 400")
    void impact_blankId() {
        AdminDependencyController c = controller();

        assertThatThrownBy(() -> c.impact("tool", "   "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("✅ 影响分析实体不存在 → 404（不泄露存在性细节）")
    void impact_notFound() {
        AdminDependencyController c = controller();
        when(dependencies.impact(eq(TENANT), eq(DependencyEntityType.TOOL), eq("ghost")))
                .thenReturn(ImpactReport.notFound("tool", "ghost"));

        assertThatThrownBy(() -> c.impact("tool", "ghost"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("✅ 影响分析限流 → 429")
    void impact_rateLimited() {
        AdminDependencyController c = controller();
        // ⚠️ 必须在 controller() 之后桩：helper 内已桩 ALLOWED，后桩覆盖先桩
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.FAIL_CLOSED);

        assertThatThrownBy(() -> c.impact("tool", "t-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoServiceCall();
    }

    private void verifyNoServiceCall() {
        verify(dependencies, org.mockito.Mockito.never()).graph(any());
        verify(dependencies, org.mockito.Mockito.never())
                .impact(any(), any(), any());
    }
}
