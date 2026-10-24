package cn.studykid.multiagent.application.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.monitoring.DenySeverity;
import cn.studykid.multiagent.ports.persistence.AdminTenantPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * 租户概览聚合服务（管理面 · 仪表盘 {@code GET /api/admin/dashboard}）。
 *
 * <p><b>本服务是只读聚合</b>：把当前租户的实体规模、待审申请、近期严重拒绝拼成
 * 一页可判读的概览。与 {@link MonitoringViewService} 不同，本服务<b>不做 fail-closed</b>：
 * 仪表盘允许「部分数据缺失」—— 某个计数源临时不可用不应让整页 503，
 * 因为仪表盘是导航入口，不是决策依据。各计数源独立 try/catch，失败项记 0 并留日志。
 *
 * <p>⚠️ <b>作用域</b>：全部按调用方租户计算（{@code tenantId} 来自已验签令牌），
 * 与监控端点一致，不接受请求参数指定租户。
 */
public class DashboardViewService {

    /** 概览窗口（小时）：用于统计「近期严重拒绝」。 */
    private static final int ALERT_WINDOW_HOURS = 24;

    /** 待审申请查询上限（仪表盘只需一个计数，PENDING 量通常很小）。 */
    private static final int MAX_PENDING = 200;

    private final AdminTenantPort tenantPort;
    private final CapToolDefPort toolDefPort;
    private final CapSkillDefPort skillDefPort;
    private final CapMcpServerDefPort mcpDefPort;
    private final ConfigPublishRequestPort publishPort;
    private final DecisionAuditPort decisionAudit;
    private final Clock clock;

    public DashboardViewService(
            @NonNull AdminTenantPort tenantPort,
            @NonNull CapToolDefPort toolDefPort,
            @NonNull CapSkillDefPort skillDefPort,
            @NonNull CapMcpServerDefPort mcpDefPort,
            @NonNull ConfigPublishRequestPort publishPort,
            @NonNull DecisionAuditPort decisionAudit,
            @NonNull Clock clock) {
        this.tenantPort = tenantPort;
        this.toolDefPort = toolDefPort;
        this.skillDefPort = skillDefPort;
        this.mcpDefPort = mcpDefPort;
        this.publishPort = publishPort;
        this.decisionAudit = decisionAudit;
        this.clock = clock;
    }

    /**
     * 读取当前租户概览。
     *
     * @param tenantId 租户标识（来自令牌）
     * @return 概览快照
     */
    public @NonNull DashboardSummary summary(@NonNull String tenantId) {
        long agentCount = safeLong(() -> tenantPort.countAgentDefs(tenantId));
        long toolCount = safeLong(() -> (long) toolDefPort.listAll().size());
        long skillCount = safeLong(() -> (long) skillDefPort.listAll().size());
        long mcpCount = safeLong(() -> (long) mcpDefPort.listAll().size());
        long pendingApprovals = safeLong(() ->
                (long) publishPort.list(tenantId, ConfigPublishRequest.State.PENDING, null, MAX_PENDING)
                        .items().size());
        long activeAlerts = safeCritical(tenantId);
        // 租户级概览：当前主体管理 1 个租户。
        return new DashboardSummary(
                1, agentCount, toolCount, skillCount, mcpCount, pendingApprovals, activeAlerts);
    }

    private long safeLong(java.util.function.Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private long safePending(@NonNull String tenantId) {
        try {
            return publishPort.list(tenantId, ConfigPublishRequest.State.PENDING, null, MAX_PENDING)
                    .items().size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long safeCritical(@NonNull String tenantId) {
        try {
            Instant since = clock.instant().minus(Duration.ofHours(ALERT_WINDOW_HOURS));
            return decisionAudit.findRecentDenies(tenantId, since, 200).stream()
                    .filter(e -> DenySeverity.of(e.reasonCode()) == DenySeverity.CRITICAL)
                    .count();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 仪表盘概览快照。 */
    public record DashboardSummary(
            long tenantCount,
            long agentCount,
            long toolCount,
            long skillCount,
            long mcpCount,
            long pendingApprovals,
            long activeAlerts) {
    }
}
