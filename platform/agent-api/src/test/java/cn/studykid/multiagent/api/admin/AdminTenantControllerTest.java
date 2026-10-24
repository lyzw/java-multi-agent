package cn.studykid.multiagent.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.core.tenant.TenantRegistry.PrincipalRow;
import cn.studykid.multiagent.core.tenant.TenantRegistry.TenantRow;
import cn.studykid.multiagent.ports.persistence.AdminTenantPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 租户管理端点测试（P0.5 · 作用域闸门）。
 *
 * <p><b>本类验证什么</b>（每条都能被证伪）：
 * <ul>
 *   <li>平台级端点（list / detail / provision / updateStatus / rlsVerify）对<b>非 PLATFORM_ADMIN</b>
 *       主体一律返回 <b>403</b>（默认拒绝，而非默认放行）；</li>
 *   <li>租户内端点（selfSummary / selfPrincipals）对<b>任意已认证租户主体</b>可用，
 *       且数据严格限定在调用方所属租户；</li>
 *   <li>平台级端点在持有 PLATFORM_ADMIN 时正常返回（不抛 403）。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：仓储与限流均以 Mockito 桩替代，聚焦于<b>安全闸门</b>逻辑本身。
 */
@DisplayName("P0.5 AdminTenantController · 作用域闸门")
class AdminTenantControllerTest {

    private static final String TENANT_ID = "tenant-1";

    private final AdminTenantPort tenants = mock(AdminTenantPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);

    private AdminTenantController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        return new AdminTenantController(tenants, currentPrincipal, rateLimiter);
    }

    private void stubContext(Set<String> roles) {
        TenantContext ctx = new TenantContext(TENANT_ID, "principal-1", roles, 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── 默认拒绝：非 PLATFORM_ADMIN 访问平台级端点 ──

    @Test
    @DisplayName("非 PLATFORM_ADMIN 列出全部租户 → 403")
    void list_forbidden_when_not_platform_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        controller();
        assertForbidden(() -> controller().list(null, null, null));
    }

    @Test
    @DisplayName("非 PLATFORM_ADMIN 查看租户详情 → 403")
    void detail_forbidden_when_not_platform_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        controller();
        assertForbidden(() -> controller().detail("any-id"));
    }

    @Test
    @DisplayName("非 PLATFORM_ADMIN 开通租户 → 403")
    void provision_forbidden_when_not_platform_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        controller();
        assertForbidden(() -> controller().provision(
                new AdminTenantController.ProvisionRequest("slug", "name")));
    }

    @Test
    @DisplayName("非 PLATFORM_ADMIN 置租户状态 → 403")
    void updateStatus_forbidden_when_not_platform_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        controller();
        assertForbidden(() -> controller().updateStatus("any-id",
                new AdminTenantController.StatusRequest("SUSPENDED")));
    }

    @Test
    @DisplayName("非 PLATFORM_ADMIN 执行 RLS 校验 → 403")
    void rlsVerify_forbidden_when_not_platform_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        controller();
        assertForbidden(() -> controller().rlsVerify("any-id"));
    }

    // ── 租户内端点：任意已认证租户主体可用 ──

    @Test
    @DisplayName("selfSummary 对 TENANT_ADMIN 返回当前租户概要")
    void selfSummary_ok_for_tenant_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        AdminTenantController c = controller();
        TenantRow row = new TenantRow(TENANT_ID, "slug-1", "我的租户", "ACTIVE", null, null);
        when(tenants.findTenant(TENANT_ID)).thenReturn(row);
        when(tenants.countPrincipals(TENANT_ID)).thenReturn(3L);
        when(tenants.countAgentDefs(TENANT_ID)).thenReturn(2L);
        when(tenants.countOverrides(TENANT_ID)).thenReturn(1L);

        var resp = c.selfSummary();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().tenant().tenantId()).isEqualTo(TENANT_ID);
        assertThat(resp.getBody().principalsCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("selfPrincipals 对 TENANT_ADMIN 返回当前租户主体")
    void selfPrincipals_ok_for_tenant_admin() {
        stubContext(Set.of("TENANT_ADMIN"));
        AdminTenantController c = controller();
        PrincipalRow p = new PrincipalRow("p-1", "alice", "Alice", "ACTIVE", 1L, List.of("TENANT_ADMIN"));
        when(tenants.listPrincipals(TENANT_ID)).thenReturn(List.of(p));

        var resp = c.selfPrincipals();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).loginName()).isEqualTo("alice");
    }

    // ── 正向：持有 PLATFORM_ADMIN 时平台级端点不抛 403 ──

    @Test
    @DisplayName("PLATFORM_ADMIN 列出全部租户 → 200（不抛 403）")
    void list_ok_for_platform_admin() {
        stubContext(Set.of("PLATFORM_ADMIN"));
        AdminTenantController c = controller();
        TenantRow row = new TenantRow(TENANT_ID, "slug-1", "租户一", "ACTIVE", null, null);
        when(tenants.listTenants(null, 50)).thenReturn(List.of(row));

        var resp = c.list(null, null, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().items()).hasSize(1);
    }
}
