package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;
import cn.studykid.multiagent.worker.retention.ChatAuditRetentionJob;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 对话审计插件指标（P4「监控 {@code sinkErrorTotal} / {@code droppedTotal}」）。
 *
 * <p><b>为什么必须暴露这几个数</b>：
 * 审计插件刻意做成 <b>fail-open</b>——队列满就丢、sink 失败就吞。
 * 这是正确的取舍（业务不能因审计变慢），但它带来一个副作用：
 * <b>「审计在丢数据」与「审计一切正常」在外部表现上完全一样</b>。
 * 三个计数（提交 / 丢弃 / sink 失败）是唯一能区分二者的证据。
 *
 * <p><b>⚠️ 为什么放在 {@code platform-bootstrap}</b>：
 * {@code agent-ports} / {@code agent-application} 不依赖 Micrometer，
 * 指标出口放在同时可见端口与 {@code MeterRegistry} 的组合根，模块边界保持干净
 * （与 {@link CapEnforcementMetrics} 同一理由）。
 *
 * <p><b>采样语义</b>：{@code Gauge} 是<b>拉模型</b>——Prometheus 每次抓取时调一次取值函数，
 * 因此这里注册的是「读当前累计值」的引用，而不是在写入侧主动累加。
 * 这样即使写入器实例在运行期被替换，指标也不会与真实计数漂移。
 */
public final class ChatAuditMetrics {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditMetrics.class);

    /** 已提交条数（含后续被丢弃的）。 */
    public static final String SUBMITTED = "chat.audit.submitted";

    /** 队列满被丢弃条数。⚠️ 持续 >0 表示有效容量不足或写入侧被堵住。 */
    public static final String DROPPED = "chat.audit.dropped";

    /** sink 失败次数。⚠️ 持续 >0 表示 DB 断连 / 目录只读。 */
    public static final String SINK_ERRORS = "chat.audit.sink_errors";

    /** 至少一个 sink 写成功的条数。 */
    public static final String WRITTEN = "chat.audit.written";

    /** 采集总开关状态：1=开 / 0=关 / -1=配置读不到。 */
    public static final String ENABLED = "chat.audit.enabled";

    /** 清理任务累计删除行数。 */
    public static final String RETENTION_ROWS = "chat.audit.retention.rows_deleted";

    /** 清理任务累计删除文件数。 */
    public static final String RETENTION_FILES = "chat.audit.retention.files_deleted";

    /** 清理任务累计失败类别。⚠️ 持续 >0 = 合规清理没在履约。 */
    public static final String RETENTION_FAILURES = "chat.audit.retention.failed_categories";

    /**
     * @param registry     Micrometer 注册表；为 {@code null} 时不注册（未启用 actuator 的部署形态）
     * @param writer       写入器（计数来源）
     * @param configPort   配置端口（开关状态来源）
     * @param retentionJob 清理任务；未接入清理时传 {@code null}，只缺清理三项
     */
    public ChatAuditMetrics(@Nullable MeterRegistry registry,
                            @NonNull ChatAuditWriterPort writer,
                            @NonNull ChatAuditConfigPort configPort,
                            @Nullable ChatAuditRetentionJob retentionJob) {
        if (registry == null) {
            // ⚠️ 不是失败：没有 actuator 的环境本就没有指标出口。
            //    但必须说出来——否则「面板上没有审计指标」会被当成面板配置错。
            log.warn("未装配 MeterRegistry，对话审计指标未注册"
                    + "（审计仍正常工作，但「丢了多少」无法从指标观测）");
            return;
        }
        Gauge.builder(SUBMITTED, writer, ChatAuditWriterPort::submittedTotal)
                .description("Chat audit records submitted to the writer")
                .register(registry);
        Gauge.builder(DROPPED, writer, ChatAuditWriterPort::droppedTotal)
                .description("Chat audit records dropped because the queue was full")
                .register(registry);
        Gauge.builder(SINK_ERRORS, writer, ChatAuditWriterPort::sinkErrorTotal)
                .description("Chat audit sink write failures (fail-open: never breaks business)")
                .register(registry);
        Gauge.builder(WRITTEN, writer, ChatAuditWriterPort::writtenTotal)
                .description("Chat audit records written to at least one sink")
                .register(registry);
        Gauge.builder(ENABLED, configPort, ChatAuditMetrics::enabledValue)
                .description("Chat audit enabled (1=on, 0=off, -1=config unreadable)")
                .register(registry);
        if (retentionJob != null) {
            Gauge.builder(RETENTION_ROWS, retentionJob, ChatAuditRetentionJob::totalRowsDeleted)
                    .description("Chat audit rows deleted by the retention job")
                    .register(registry);
            Gauge.builder(RETENTION_FILES, retentionJob, ChatAuditRetentionJob::totalFilesDeleted)
                    .description("Chat audit files deleted by the retention job")
                    .register(registry);
            Gauge.builder(RETENTION_FAILURES, retentionJob,
                            ChatAuditRetentionJob::totalFailedCategories)
                    .description("Chat audit retention failures (non-zero = compliance risk)")
                    .register(registry);
        }
        log.info("对话审计指标已注册: 写入侧 5 项{}", retentionJob == null
                ? "（清理指标未注册：未接入清理任务）" : " + 清理侧 3 项");
    }

    /**
     * 采集开关的指标取值（纯函数，便于单测钉死三态映射）。
     *
     * <p>⚠️ 读配置失败返回 {@code -1} 而不是 {@code 0}：把「读不到」与「明确关闭」分开，
     * 否则配置表故障会伪装成「运维主动关掉了审计」，排障方向直接走偏。
     */
    public static double enabledValue(@NonNull ChatAuditConfigPort configPort) {
        try {
            return configPort.get().config().enabled() ? 1d : 0d;
        } catch (RuntimeException e) {
            return -1d;
        }
    }
}
