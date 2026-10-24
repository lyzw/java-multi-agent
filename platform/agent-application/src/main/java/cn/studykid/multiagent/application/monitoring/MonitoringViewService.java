package cn.studykid.multiagent.application.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 租户运行监控聚合服务（管理面 P4 运行监控）。
 *
 * <p><b>本服务是「只读聚合层」</b>：它把三处独立来源拼成运维可判定的一张图 ——
 * <ul>
 *   <li>授权版本水位（{@link AuthzVersionPort#watermark}）→ 「谁被撤过权」；</li>
 *   <li>近期拒绝（{@link DecisionAuditPort#findRecentDenies}）→ 「刚刚拒绝了什么」；</li>
 *   <li>审计覆盖率（{@link DecisionAuditPort#coverage}）→ 「这些拒绝记录本身可不可信」。</li>
 * </ul>
 *
 * <p>⚠️ <b>fail-closed：本服务在数据源不可用时抛异常，绝不返回空快照</b>。
 * 理由很直接：监控页的默认状态是「一切正常」。
 * 若在数据源故障时返回「0 个拒绝、0 个主体」，运维看到的是<b>绿色</b> ——
 * 而真相是「我什么都看不到」。
 * 这与「拒绝伪装成健康」是同一种危险，故此处选择 503 而不是降级为空。
 *
 * <p>⚠️ <b>为什么不用缓存</b>：监控数据的价值在于「现在」，
 * 缓存会让「刚刚发生的越权尝试」在缓存窗口内不可见 ——
 * 恰好是运维最需要看到的那一类。本服务不设缓存。
 */
public class MonitoringViewService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringViewService.class);

    /** 默认监控窗口（小时）。 */
    public static final int DEFAULT_WINDOW_HOURS = 24;

    /** 窗口上限（小时）：防止传入超大窗口导致全表扫描。 */
    public static final int MAX_WINDOW_HOURS = 168;

    /** 单次返回的拒绝条数上限。 */
    public static final int MAX_ALERTS = 200;

    private final AuthzVersionPort authzVersions;
    private final DecisionAuditPort decisionAudit;
    private final Clock clock;

    public MonitoringViewService(@NonNull AuthzVersionPort authzVersions,
                                 @NonNull DecisionAuditPort decisionAudit,
                                 @NonNull Clock clock) {
        this.authzVersions = authzVersions;
        this.decisionAudit = decisionAudit;
        this.clock = clock;
    }

    /**
     * 读取租户运行健康快照。
     *
     * @param tenantId    租户标识
     * @param windowHours 监控窗口（小时）；须在 {@code [1, MAX_WINDOW_HOURS]} 内
     * @return 健康快照
     * @throws MonitoringUnavailableException 关键数据源不可用（fail-closed，映射为 503）
     * @throws IllegalArgumentException 窗口参数非法
     */
    public @NonNull MonitoringHealth health(@NonNull String tenantId, int windowHours) {
        if (windowHours <= 0 || windowHours > MAX_WINDOW_HOURS) {
            throw new IllegalArgumentException(
                    "windowHours 必须在 1~" + MAX_WINDOW_HOURS + " 之间: " + windowHours);
        }
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(windowHours));

        AuthzWatermark watermark = readWatermark(tenantId);
        List<DecisionAuditEntry> denies = readDenies(tenantId, since);
        DecisionAuditCoverage coverage = readCoverage(tenantId);

        List<DenyAlert> alerts = new ArrayList<>(denies.size());
        long critical = 0;
        long warning = 0;
        long info = 0;
        for (DecisionAuditEntry entry : denies) {
            DenySeverity severity = DenySeverity.of(entry.reasonCode());
            switch (severity) {
                case CRITICAL -> critical++;
                case WARNING -> warning++;
                case INFO -> info++;
            }
            alerts.add(new DenyAlert(
                    severity,
                    entry.reasonCode(),
                    entry.principalId(),
                    entry.toolId(),
                    entry.runId(),
                    entry.invocationId(),
                    entry.createdAt()));
        }

        MonitoringHealth.Status status = decideStatus(critical, coverage);
        return new MonitoringHealth(
                status, watermark, List.copyOf(alerts),
                critical, warning, info, coverage, now);
    }

    /**
     * 读取近期拒绝告警列表（不聚合健康状态）。
     *
     * <p>⚠️ <b>与 {@link #health} 分开</b>：健康快照是「一页看完」的聚合视图，
     * 告警列表是「逐条排查」的下钻视图。两者对「空列表」的含义不同 ——
     * 健康快照为空可能是「确实没有拒绝」，告警列表为空也可能是「窗口内没有」，
     * 混用一个端点会让前端在两种空态间做无意义的区分。
     *
     * @param tenantId    租户标识
     * @param windowHours 监控窗口（小时）
     * @param limit       最大条数
     * @return 告警列表（按发生时刻倒序）
     * @throws MonitoringUnavailableException 数据源不可用
     */
    public @NonNull List<DenyAlert> denies(@NonNull String tenantId,
                                           int windowHours,
                                           int limit) {
        if (windowHours <= 0 || windowHours > MAX_WINDOW_HOURS) {
            throw new IllegalArgumentException(
                    "windowHours 必须在 1~" + MAX_WINDOW_HOURS + " 之间: " + windowHours);
        }
        if (limit <= 0 || limit > MAX_ALERTS) {
            throw new IllegalArgumentException(
                    "limit 必须在 1~" + MAX_ALERTS + " 之间: " + limit);
        }
        Instant since = clock.instant().minus(Duration.ofHours(windowHours));
        List<DecisionAuditEntry> entries = readDenies(tenantId, since);
        List<DenyAlert> alerts = new ArrayList<>(Math.min(entries.size(), limit));
        for (DecisionAuditEntry entry : entries) {
            if (alerts.size() >= limit) {
                break;
            }
            alerts.add(new DenyAlert(
                    DenySeverity.of(entry.reasonCode()),
                    entry.reasonCode(),
                    entry.principalId(),
                    entry.toolId(),
                    entry.runId(),
                    entry.invocationId(),
                    entry.createdAt()));
        }
        return List.copyOf(alerts);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 读授权水位，失败即抛（fail-closed）。
     */
    private @NonNull AuthzWatermark readWatermark(@NonNull String tenantId) {
        try {
            return authzVersions.watermark(tenantId);
        } catch (RuntimeException e) {
            log.error("授权版本水位读取失败（监控不可用）: tenant={}, err={}", tenantId, e.getMessage(), e);
            throw new MonitoringUnavailableException(
                    "授权版本水位不可读，无法提供运行监控视图", e);
        }
    }

    /**
     * 读近期拒绝，失败即抛（fail-closed）。
     */
    private @NonNull List<DecisionAuditEntry> readDenies(@NonNull String tenantId,
                                                          @NonNull Instant since) {
        try {
            return decisionAudit.findRecentDenies(tenantId, since, MAX_ALERTS);
        } catch (RuntimeException e) {
            log.error("近期拒绝记录读取失败（监控不可用）: tenant={}, err={}",
                    tenantId, e.getMessage(), e);
            throw new MonitoringUnavailableException(
                    "决策日志不可读，无法提供运行监控视图", e);
        }
    }

    /**
     * 读审计覆盖率，失败即抛（fail-closed）。
     */
    private @NonNull DecisionAuditCoverage readCoverage(@NonNull String tenantId) {
        try {
            return decisionAudit.coverage(tenantId);
        } catch (RuntimeException e) {
            log.error("审计覆盖率读取失败（监控不可用）: tenant={}, err={}",
                    tenantId, e.getMessage(), e);
            throw new MonitoringUnavailableException(
                    "审计覆盖率不可读，无法提供运行监控视图", e);
        }
    }

    /**
     * 健康状态判定（最差项优先）。
     *
     * <p>⚠️ <b>审计覆盖不达标也会降级</b>：若四字段完整率或非笼统 DENY 占比
     * 不为 100%（08 §7），那这批拒绝记录本身<b>不可作为追责依据</b> ——
     * 此时展示「一切正常」是危险的，因为它让运维以为拒绝记录是完整的。
     */
    private static MonitoringHealth.Status decideStatus(
            long criticalCount,
            @NonNull DecisionAuditCoverage coverage) {
        if (criticalCount > 0) {
            return MonitoringHealth.Status.DEGRADED;
        }
        if (!coverage.isComplete()) {
            return MonitoringHealth.Status.DEGRADED;
        }
        return MonitoringHealth.Status.HEALTHY;
    }

    /**
     * 监控数据源不可用。
     *
     * <p>⚠️ <b>与「拒绝」严格区分</b>：本异常表示「我看不到」，
     * 而「拒绝」表示「我看到了并拒绝了」。前者映射为 503（服务不可用），
     * 后者映射为 403（权限不足）。混用会让监控不可用被误读成「你在越权」。
     */
    public static class MonitoringUnavailableException extends RuntimeException {

        public MonitoringUnavailableException(@NonNull String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 原因码的展示名（供上层做只读投影时参考）。
     *
     * <p>⚠️ <b>不引入新的展示映射</b>：原因码名本身即稳定契约（§5.7），
     * 前端直接展示 {@code reasonCode().name()} 即可。
     * 这里只做一次显式暴露，避免调用方各自拼字符串。
     */
    public static String displayName(@NonNull ReasonCode code) {
        return code.name();
    }
}
