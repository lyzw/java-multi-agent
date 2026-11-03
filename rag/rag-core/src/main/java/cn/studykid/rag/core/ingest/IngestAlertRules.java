package cn.studykid.rag.core.ingest;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 摄取链告警规则（V1.0.2 §7.3 / S4-1）。
 *
 * <p><b>⚠️ 本类只做「该不该告警」的判定，不做通知发送</b>：
 * 通知通道复用平台既有 {@code agent.alerts.webhook-url}，本类不引入第二套通道。
 *
 * <p><b>⚠️ 阈值取值（T-6，方案建议默认）与理由</b>：
 * <ul>
 *   <li><b>失败率 &gt; 20%</b>：正常录入的失败应是个位数百分比。20% 足以过滤偶发失败，
 *       又能在大面积故障时立刻触发。⚠️ <b>必须剔除 {@code SOURCE_REVOKED}</b>——
 *       那是法务撤回的正常结果，计入会让「撤回一批教材」直接打爆告警（N2）。</li>
 *   <li><b>积压 &gt; 50 且持续 15 分钟</b>：单册处理可能要数分钟，
 *       阈值太低会在正常批量导入时误报。</li>
 *   <li><b>Sweeper 接管 &gt; 3 次/小时</b>：接管本身是兜底，频繁接管说明 worker 不稳定，
 *       这是积压发生**之前**的前兆信号。</li>
 *   <li><b>同来源连续失败 ≥ 3 次</b>：几乎可以断定是文件问题而非系统问题，
 *       应提示换文件，而不是继续重试。</li>
 * </ul>
 */
public final class IngestAlertRules {

    private IngestAlertRules() {
    }

    /** 失败率阈值（0.20）。 */
    public static final double FAILURE_RATE_THRESHOLD = 0.20;

    /** 积压条数阈值。 */
    public static final int BACKLOG_THRESHOLD = 50;

    /** 积压持续时长阈值（分钟）。 */
    public static final int BACKLOG_MINUTES = 15;

    /** Sweeper 接管频发阈值（次/小时）。 */
    public static final int SWEEPER_TAKEOVER_THRESHOLD = 3;

    /** 同来源连续失败阈值。 */
    public static final int REPEATED_FAILURE_THRESHOLD = 3;

    /**
     * 告警级别。
     */
    public enum Level {
        /** 需立即处置。 */
        CRITICAL,
        /** 需关注，尚未影响用户。 */
        WARNING
    }

    /**
     * 一条告警。
     *
     * @param level   级别
     * @param metric  指标名
     * @param message 可读信息
     * @param value   当前值
     * @param threshold 阈值
     */
    public record Alert(Level level, String metric, String message,
                        double value, double threshold) {
    }

    /**
     * 评估失败率。
     *
     * <p>⚠️ 分母是「已结束的 job」而非「全部 job」：还在跑的任务既不算成功也不算失败，
     * 计入分母会让刚上传一批时的失败率被稀释成 0。
     *
     * @param failed       失败数（不含撤回取消）
     * @param finished     已结束总数（含成功/失败/取消）
     * @return 告警（不触发则返回 null）
     */
    public static Alert evaluateFailureRate(int failed, int finished) {
        if (finished <= 0) {
            return null;
        }
        double rate = failed / (double) finished;
        if (rate > FAILURE_RATE_THRESHOLD) {
            return new Alert(Level.CRITICAL, "ingest.failure_rate",
                    String.format("摄取失败率 %.1f%% 超过阈值 %.0f%%（%d/%d）",
                            rate * 100, FAILURE_RATE_THRESHOLD * 100, failed, finished),
                    rate, FAILURE_RATE_THRESHOLD);
        }
        return null;
    }

    /**
     * 评估积压。
     *
     * @param pendingCount 待处理数
     * @param pendingMinutes 最老待处理任务的等待分钟数
     * @return 告警（不触发则返回 null）
     */
    public static Alert evaluateBacklog(int pendingCount, long pendingMinutes) {
        if (pendingCount > BACKLOG_THRESHOLD && pendingMinutes > BACKLOG_MINUTES) {
            return new Alert(Level.WARNING, "ingest.backlog",
                    "待处理摄取任务 " + pendingCount + " 条，最久等待 " + pendingMinutes
                            + " 分钟（阈值 " + BACKLOG_THRESHOLD + " 条 / "
                            + BACKLOG_MINUTES + " 分钟）——请检查 worker 是否在运行",
                    pendingCount, BACKLOG_THRESHOLD);
        }
        return null;
    }

    /**
     * 评估 Sweeper 接管频次。
     *
     * @param takeoversLastHour 近 1 小时接管次数
     * @return 告警（不触发则返回 null）
     */
    public static Alert evaluateSweeperTakeovers(int takeoversLastHour) {
        if (takeoversLastHour > SWEEPER_TAKEOVER_THRESHOLD) {
            return new Alert(Level.WARNING, "ingest.sweeper_takeover",
                    "近 1 小时 Sweeper 接管 " + takeoversLastHour
                            + " 次（阈值 " + SWEEPER_TAKEOVER_THRESHOLD + " 次）"
                            + "——worker 可能不稳定，通常是大面积积压的前兆",
                    takeoversLastHour, SWEEPER_TAKEOVER_THRESHOLD);
        }
        return null;
    }

    /**
     * 评估同来源连续失败。
     *
     * @param sourceId          来源 ID
     * @param consecutiveFails  连续失败次数
     * @return 告警（不触发则返回 null）
     */
    public static Alert evaluateRepeatedFailure(String sourceId, int consecutiveFails) {
        if (consecutiveFails >= REPEATED_FAILURE_THRESHOLD) {
            return new Alert(Level.WARNING, "ingest.repeated_failure",
                    "来源 " + sourceId + " 连续失败 " + consecutiveFails
                            + " 次——大概率是文件本身的问题，建议换文件而非继续重试",
                    consecutiveFails, REPEATED_FAILURE_THRESHOLD);
        }
        return null;
    }

    /**
     * 汇总评估：收集所有触发的告警。
     *
     * @param failed           失败数（已剔除撤回取消）
     * @param finished         已结束总数
     * @param pendingCount     待处理数
     * @param pendingMinutes   最老待处理等待分钟
     * @param takeoversLastHour Sweeper 近 1 小时接管次数
     * @return 触发的告警列表（可能为空）
     */
    public static List<Alert> evaluateAll(int failed, int finished, int pendingCount,
                                          long pendingMinutes, int takeoversLastHour) {
        List<Alert> alerts = new java.util.ArrayList<>();
        addIfPresent(alerts, evaluateFailureRate(failed, finished));
        addIfPresent(alerts, evaluateBacklog(pendingCount, pendingMinutes));
        addIfPresent(alerts, evaluateSweeperTakeovers(takeoversLastHour));
        return alerts;
    }

    private static void addIfPresent(@NonNull List<Alert> out, Alert a) {
        if (a != null) {
            out.add(a);
        }
    }
}
