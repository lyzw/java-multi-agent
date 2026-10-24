package cn.studykid.multiagent.core.monitoring;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;

/**
 * 租户运行健康快照（管理面 P4 运行监控）。
 *
 * <p>本记录是监控首页与仪表盘的<b>单一聚合口径</b>：把「授权版本水位」、
 * 「近期拒绝告警」与「决策日志审计覆盖率」三处来源聚合成一个可判定的健康状态。
 *
 * <p>⚠️ <b>健康状态是「三源合并」的结果，不来自任何单一指标</b>：
 * 单看拒绝数无法区分「有攻击」与「配置坏了」；
 * 单看审计覆盖无法发现授权已经陈旧；
 * 单看版本水位无法发现有人在撞权限边界。合并后才具备分诊价值。
 *
 * <p>⚠️ <b>状态判定是「最差项优先」（fail-severity）</b>：
 * 任一维度达到 {@link Status#UNAVAILABLE} 则整体不可用；
 * 否则任一维度为 {@link Status#DEGRADED} 则整体降级。
 * 这样「某个数据源读不到」不会被其他正常维度掩盖 ——
 * 这正是「拒绝伪装成健康」最危险的形态。
 *
 * @param status          整体健康状态
 * @param authzWatermark  授权版本水位
 * @param recentDenies    近期拒绝告警（按发生时刻<b>倒序</b>）
 * @param criticalCount   严重级别拒绝计数
 * @param warningCount    警告级别拒绝计数
 * @param infoCount       提示级别拒绝计数
 * @param coverage        决策日志审计覆盖率（08 §7 两条指标）
 * @param observedAt      观测时刻
 */
public record MonitoringHealth(
        @NonNull Status status,
        @NonNull AuthzWatermark authzWatermark,
        @NonNull List<DenyAlert> recentDenies,
        long criticalCount,
        long warningCount,
        long infoCount,
        @NonNull DecisionAuditCoverage coverage,
        @NonNull Instant observedAt) {

    /** 监控健康状态。 */
    public enum Status {
        /** 正常：无严重拒绝，审计覆盖达标。 */
        HEALTHY,
        /** 降级：存在严重拒绝或审计覆盖不达标。 */
        DEGRADED,
        /** 不可用：关键数据源读取失败（fail-closed，<b>不返回空数据冒充正常</b>）。 */
        UNAVAILABLE
    }

    public MonitoringHealth {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (authzWatermark == null) {
            throw new IllegalArgumentException("authzWatermark 不能为空");
        }
        if (recentDenies == null) {
            throw new IllegalArgumentException("recentDenies 不能为空");
        }
        if (criticalCount < 0 || warningCount < 0 || infoCount < 0) {
            throw new IllegalArgumentException("各档拒绝计数不得为负");
        }
        if (coverage == null) {
            throw new IllegalArgumentException("coverage 不能为空");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt 不能为空");
        }
    }

    /**
     * @return 近期拒绝总数
     */
    public long totalDenyCount() {
        return criticalCount + warningCount + infoCount;
    }

    /**
     * @return 是否存在需要立即处置的严重拒绝
     */
    public boolean hasCritical() {
        return criticalCount > 0;
    }
}
