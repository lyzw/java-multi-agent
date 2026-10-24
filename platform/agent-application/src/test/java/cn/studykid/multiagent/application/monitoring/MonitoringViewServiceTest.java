package cn.studykid.multiagent.application.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.monitoring.MonitoringViewService.MonitoringUnavailableException;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.monitoring.DenyAlert;
import cn.studykid.multiagent.core.monitoring.DenySeverity;
import cn.studykid.multiagent.core.monitoring.MonitoringHealth;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * 运行监控聚合服务测试（P4 运行监控 · 应用层）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>三源聚合的计数与分档正确；</li>
 *   <li><b>fail-closed</b>：任一数据源不可用 → 抛不可用异常，<b>绝不返回空快照</b>；</li>
 *   <li>健康状态判定（最差项优先）：严重拒绝 / 审计覆盖不达标 → 降级；</li>
 *   <li>窗口与 limit 的参数边界。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：两个端口以 Mockito 桩替代，聚焦<b>聚合与闸门口径</b>。
 *
 * <p>⚠️ <b>桩的顺序约定</b>：{@link #service()} 内部已给出「默认成功」桩，
 * 因此每个用例必须<b>先取 service 再覆盖桩</b>（Mockito 后桩覆盖先桩）。
 * 反过来写（先桩后取 service）会让默认值把用例的桩冲掉，
 * 表现为「用例断言了一个从未生效的场景」——
 * 那比用例失败更危险，因为它会伪装成「实现没问题」。
 */
@DisplayName("P4 MonitoringViewService · 运行监控聚合")
class MonitoringViewServiceTest {

    private static final String TENANT = "tenant-1";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private final AuthzVersionPort authzVersions = mock(AuthzVersionPort.class);
    private final DecisionAuditPort decisionAudit = mock(DecisionAuditPort.class);

    /**
     * 构造服务并给出「一切正常」的默认桩。
     *
     * <p>⚠️ <b>只桩成功路径</b>：失败的用例自己覆盖对应桩。
     */
    private MonitoringViewService service() {
        when(authzVersions.watermark(TENANT)).thenReturn(watermark(3, 1, 5));
        when(decisionAudit.coverage(TENANT)).thenReturn(new DecisionAuditCoverage(10, 8, 3, 0, 0));
        when(decisionAudit.findRecentDenies(eq(TENANT), any(), anyInt()))
                .thenReturn(List.of());
        return new MonitoringViewService(authzVersions, decisionAudit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthzWatermark watermark(long subjects, long revoked, long maxVersion) {
        return new AuthzWatermark(TENANT, subjects, revoked, maxVersion, NOW.minusSeconds(60), NOW);
    }

    private static DecisionAuditEntry deny(ReasonCode code, String invocationId) {
        return new DecisionAuditEntry(
                TENANT, "run-1", invocationId,
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                AuthorizationDecision.Ledger.PENDING,
                code, 3L, "fp-1", null, null, "p-1", "t-1",
                DecisionAuditEntry.Kind.INVOCATION, code.phase(), NOW.minusSeconds(30));
    }

    // ── 健康快照 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 无严重拒绝 + 审计覆盖达标 → HEALTHY")
    void healthIsHealthy() {
        MonitoringViewService service = service();

        MonitoringHealth health = service.health(TENANT, 24);

        assertThat(health.status()).isEqualTo(MonitoringHealth.Status.HEALTHY);
        assertThat(health.criticalCount()).isZero();
        assertThat(health.totalDenyCount()).isZero();
        assertThat(health.authzWatermark().subjectCount()).isEqualTo(3);
        assertThat(health.observedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("✅ 存在严重拒绝 → DEGRADED（最差项优先）")
    void healthDegradedByCriticalDeny() {
        MonitoringViewService service = service();
        when(decisionAudit.findRecentDenies(eq(TENANT), any(), anyInt()))
                .thenReturn(List.of(
                        deny(ReasonCode.AUTHZ_STALE, "inv-1"),
                        deny(ReasonCode.SKILL_NOT_ENABLED, "inv-2"),
                        deny(ReasonCode.BUDGET_EXCEEDED, "inv-3")));

        MonitoringHealth health = service.health(TENANT, 24);

        assertThat(health.status()).isEqualTo(MonitoringHealth.Status.DEGRADED);
        assertThat(health.criticalCount()).isEqualTo(1);
        assertThat(health.warningCount()).isEqualTo(1);
        assertThat(health.infoCount()).isEqualTo(1);
        assertThat(health.totalDenyCount()).isEqualTo(3);
        assertThat(health.hasCritical()).isTrue();
    }

    @Test
    @DisplayName("✅ 审计覆盖不达标 → DEGRADED（拒绝记录本身不可信，不能显示正常）")
    void healthDegradedByIncompleteCoverage() {
        MonitoringViewService service = service();
        when(decisionAudit.coverage(TENANT))
                .thenReturn(new DecisionAuditCoverage(10, 8, 3, 1, 2));

        MonitoringHealth health = service.health(TENANT, 24);

        assertThat(health.status()).isEqualTo(MonitoringHealth.Status.DEGRADED);
        assertThat(health.criticalCount()).isZero();
        assertThat(health.coverage().isComplete()).isFalse();
    }

    @Test
    @DisplayName("✅ 告警按严重级别投影，且携带上下文标识")
    void healthProjectsAlerts() {
        MonitoringViewService service = service();
        when(decisionAudit.findRecentDenies(eq(TENANT), any(), anyInt()))
                .thenReturn(List.of(deny(ReasonCode.AUTHZ_ROLE_MISSING, "inv-9")));

        MonitoringHealth health = service.health(TENANT, 24);

        assertThat(health.recentDenies()).hasSize(1);
        DenyAlert alert = health.recentDenies().get(0);
        assertThat(alert.severity()).isEqualTo(DenySeverity.CRITICAL);
        assertThat(alert.reasonCode()).isEqualTo(ReasonCode.AUTHZ_ROLE_MISSING);
        assertThat(alert.principalId()).isEqualTo("p-1");
        assertThat(alert.toolId()).isEqualTo("t-1");
        assertThat(alert.runId()).isEqualTo("run-1");
        assertThat(alert.invocationId()).isEqualTo("inv-9");
    }

    // ── fail-closed ───────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 水位读不到 → 抛不可用异常（绝不返回空快照冒充正常）")
    void healthFailsClosedWhenWatermarkUnavailable() {
        MonitoringViewService service = service();
        when(authzVersions.watermark(TENANT)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.health(TENANT, 24))
                .isInstanceOf(MonitoringUnavailableException.class);
    }

    @Test
    @DisplayName("⛔ 决策日志读不到 → 抛不可用异常")
    void healthFailsClosedWhenAuditUnavailable() {
        MonitoringViewService service = service();
        when(decisionAudit.findRecentDenies(eq(TENANT), any(), anyInt()))
                .thenThrow(new RuntimeException("table missing"));

        assertThatThrownBy(() -> service.health(TENANT, 24))
                .isInstanceOf(MonitoringUnavailableException.class);
    }

    @Test
    @DisplayName("⛔ 覆盖率读不到 → 抛不可用异常")
    void healthFailsClosedWhenCoverageUnavailable() {
        MonitoringViewService service = service();
        when(decisionAudit.coverage(TENANT)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.health(TENANT, 24))
                .isInstanceOf(MonitoringUnavailableException.class);
    }

    // ── 参数边界 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 窗口越界 → 参数异常（防全表扫描）")
    void healthRejectsInvalidWindow() {
        MonitoringViewService service = service();

        assertThatThrownBy(() -> service.health(TENANT, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowHours");
        assertThatThrownBy(() -> service.health(TENANT,
                MonitoringViewService.MAX_WINDOW_HOURS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowHours");
    }

    @Test
    @DisplayName("⛔ limit 越界 → 参数异常")
    void deniesRejectsInvalidLimit() {
        MonitoringViewService service = service();

        assertThatThrownBy(() -> service.denies(TENANT, 24, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.denies(TENANT, 24,
                MonitoringViewService.MAX_ALERTS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    // ── 告警列表 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 告警列表按 limit 截断（不静默返回全量）")
    void deniesRespectsLimit() {
        MonitoringViewService service = service();
        when(decisionAudit.findRecentDenies(eq(TENANT), any(), anyInt()))
                .thenReturn(List.of(
                        deny(ReasonCode.AUTHZ_STALE, "inv-1"),
                        deny(ReasonCode.AUTHZ_STALE, "inv-2"),
                        deny(ReasonCode.AUTHZ_STALE, "inv-3")));

        List<DenyAlert> alerts = service.denies(TENANT, 24, 2);

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).invocationId()).isEqualTo("inv-1");
    }

    @Test
    @DisplayName("✅ 告警列表：窗口非法同样拒绝")
    void deniesRejectsInvalidWindow() {
        MonitoringViewService service = service();

        assertThatThrownBy(() -> service.denies(TENANT, 999, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowHours");
    }
}
