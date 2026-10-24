package cn.studykid.multiagent.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.monitoring.MonitoringViewService;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.monitoring.DenyAlert;
import cn.studykid.multiagent.core.monitoring.DenySeverity;
import cn.studykid.multiagent.core.monitoring.MonitoringHealth;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 运行监控端点测试（P4 · 管理面运行监控只读端点）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>{@code GET /health}：200 + 服务按<b>调用方租户</b>取数（防越租户）；</li>
 *   <li>{@code GET /denies}：200 + 默认窗口/条数透传；</li>
 *   <li>鉴权与限流口径：未授权抛错（不触达服务）、429；</li>
 *   <li>与依赖图谱同信任级别：{@code AGENT_MANAGE}。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：监控服务以 Mockito 桩替代，聚焦<b>端点契约与闸门口径</b>。
 */
@DisplayName("P4 AdminMonitoringController · 运行监控端点")
class AdminMonitoringControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PRINCIPAL = "principal-1";

    private final MonitoringViewService monitoring = mock(MonitoringViewService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private AdminMonitoringController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext ctx = new TenantContext(TENANT, PRINCIPAL, Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
        return new AdminMonitoringController(monitoring, authorization, rateLimiter,
                currentPrincipal);
    }

    private static MonitoringHealth sampleHealth() {
        return new MonitoringHealth(
                MonitoringHealth.Status.HEALTHY,
                new AuthzWatermark(TENANT, 3L, 1L, 5L, Instant.now(), Instant.now()),
                List.of(), 0L, 0L, 0L,
                new DecisionAuditCoverage(10, 8, 0, 0, 0),
                Instant.now());
    }

    private static List<DenyAlert> sampleAlerts() {
        return List.of(new DenyAlert(
                DenySeverity.CRITICAL, ReasonCode.AUTHZ_STALE,
                "p-1", "t-1", "r-1", "inv-1", Instant.now()));
    }

    // ── health ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 健康快照 200：按调用方租户取数（不穿透其他租户）")
    void health_ok() {
        AdminMonitoringController c = controller();
        when(monitoring.health(TENANT, 24)).thenReturn(sampleHealth());

        ResponseEntity<MonitoringHealth> resp = c.health(null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(MonitoringHealth.Status.HEALTHY);
        // ⚠️ 必须用令牌里的租户，而非任何请求参数
        verify(monitoring).health(TENANT, 24);
    }

    @Test
    @DisplayName("✅ 健康快照 200：窗口参数透传")
    void health_passesWindow() {
        AdminMonitoringController c = controller();
        when(monitoring.health(TENANT, 72)).thenReturn(sampleHealth());

        ResponseEntity<MonitoringHealth> resp = c.health(72);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(monitoring).health(TENANT, 72);
    }

    @Test
    @DisplayName("⛔ 健康快照未授权 → 直接抛错（不触达监控服务）")
    void health_unauthorized() {
        AdminMonitoringController c = controller();
        doThrow(new RuntimeException("denied"))
                .when(authorization).requireAuthorized(any(), any(), any());

        assertThatThrownBy(() -> c.health(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("denied");
        verifyNoInteractions(monitoring);
    }

    @Test
    @DisplayName("✅ 健康快照限流 → 429")
    void health_rateLimited() {
        AdminMonitoringController c = controller();
        // ⚠️ 必须在 controller() 之后桩：helper 内已桩 ALLOWED，后桩覆盖先桩
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.REJECTED);

        assertThatThrownBy(() -> c.health(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(monitoring);
    }

    // ── denies ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 拒绝告警 200：默认窗口与默认条数")
    void denies_ok() {
        AdminMonitoringController c = controller();
        when(monitoring.denies(TENANT, 24, 50)).thenReturn(sampleAlerts());

        ResponseEntity<List<DenyAlert>> resp = c.denies(null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).severity()).isEqualTo(DenySeverity.CRITICAL);
        verify(monitoring).denies(TENANT, 24, 50);
    }

    @Test
    @DisplayName("✅ 拒绝告警 200：窗口与条数参数透传")
    void denies_passesParams() {
        AdminMonitoringController c = controller();
        when(monitoring.denies(TENANT, 48, 10)).thenReturn(List.of());

        ResponseEntity<List<DenyAlert>> resp = c.denies(48, 10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(monitoring).denies(TENANT, 48, 10);
    }

    @Test
    @DisplayName("⛔ 拒绝告警未授权 → 直接抛错（不触达监控服务）")
    void denies_unauthorized() {
        AdminMonitoringController c = controller();
        doThrow(new RuntimeException("denied"))
                .when(authorization).requireAuthorized(any(), any(), any());

        assertThatThrownBy(() -> c.denies(null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("denied");
        verifyNoInteractions(monitoring);
    }

    @Test
    @DisplayName("✅ 拒绝告警限流 → 429")
    void denies_rateLimited() {
        AdminMonitoringController c = controller();
        // ⚠️ 必须在 controller() 之后桩
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.REJECTED);

        assertThatThrownBy(() -> c.denies(null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(monitoring);
    }

    @Test
    @DisplayName("⛔ 参数非法由服务层拒绝 → 400（不在控制器静默兜底）")
    void denies_invalidWindowPropagates() {
        AdminMonitoringController c = controller();
        when(monitoring.denies(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("windowHours 必须在 1~168 之间"));

        assertThatThrownBy(() -> c.denies(999, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowHours");
    }
}
