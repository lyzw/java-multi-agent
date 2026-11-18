package cn.studykid.multiagent.application.agentchataudit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditDirectoryRetentionPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate;

/**
 * 对话审计保留期清理服务（PIPL §7）。
 *
 * <p><b>为什么这个类必须存在</b>：{@code 05_chat_audit.sql} 建的表存
 * <b>完整原文</b>（用户问题、历史、模型回答、thinking、逐分片、RAG 命中、工具入参与返回），
 * 而目录里还有同一份原文的第二个拷贝。没有本类时，
 * 配置页面上写着「保留 30 天」，库里和盘上却无限期留存，
 * 且系统<b>没有任何异常信号</b>——这正是本项目反复治理的「存在但不生效」形态。
 *
 * <p><b>⚠️ 窗口来源唯一：{@code config.retention().days()}</b>
 * （前端「系统配置 → 对话审计配置」可改，默认 30 天，与 {@code message-days} 对齐）。
 * 刻意<b>不</b>复用 yml 的 {@code RetentionSettings}：那会让
 * 「页面写 7 天、实际按 yml 的 90 天删」成为一个无法从任何单一位置解释的偏差。
 *
 * <p><b>⚠️ 配置读不出来时<b>整轮跳过</b>，不猜窗口</b>：
 * 删除不可逆，用一个猜出来的天数去删比「这一轮没删」危险得多。
 * 该情形计入 {@link Report#failedCategories()} 并打 WARN，绝不静默。
 *
 * <p><b>⚠️ 两类失败各自独立计数</b>（与 {@code RetentionPurgeService} 同纪律）：
 * DB 清理失败不应让文件清理一起跳过——那会让「库里删了、盘上还在」变成常态。
 *
 * <p><b>⚠️ 凭证口径的诚实边界</b>：{@code erasure_certificate} 是<b>按租户</b>的
 * （唯一键含 {@code tenantId}），而目录结构
 * {@code {dir}/{yyyy-MM-dd}/audit-*.json} <b>没有租户维度</b>。
 * 因此凭证只登记 DB 行数，文件删除数只进日志与计数——
 * 把全局的文件数摊给某个租户，等于伪造一条举证材料。
 * 若将来需要文件侧凭证，正确做法是改目录布局带上 tenant（V2），而不是在这里分摊。
 */
public class ChatAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditRetentionService.class);

    private final @Nullable ChatAuditRetentionPort databasePort;
    private final @Nullable ChatAuditDirectoryRetentionPort directoryPort;
    private final Supplier<ChatAuditConfig> configSupplier;
    private final @Nullable ErasurePort erasurePort;
    private final String actor;
    private final Clock clock;

    /** 单轮每租户上限：防止一个大租户的积压把整轮拉成长事务。 */
    private final int batchSize;

    /** 「无清理凭据」只告警一次：每轮刷一条 WARN 会让日志变成第二个故障。 */
    private final AtomicBoolean warnedNoCredential = new AtomicBoolean(false);

    public ChatAuditRetentionService(@Nullable ChatAuditRetentionPort databasePort,
                                      @Nullable ChatAuditDirectoryRetentionPort directoryPort,
                                      @NonNull Supplier<ChatAuditConfig> configSupplier,
                                      @Nullable ErasurePort erasurePort,
                                      @NonNull String actor,
                                      int batchSize,
                                      @NonNull Clock clock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("对话审计清理单轮上限必须 > 0: " + batchSize);
        }
        if (actor.isBlank()) {
            // ⚠️ fail-fast：actor 是删除行为的唯一责任人（与 P4-D04 D-1 同一纪律）。
            throw new IllegalArgumentException(
                    "对话审计清理必须指定 actor：没有责任人就不得删除审计原文");
        }
        this.databasePort = databasePort;
        this.directoryPort = directoryPort;
        this.configSupplier = configSupplier;
        this.erasurePort = erasurePort;
        this.actor = actor;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /**
     * 执行一轮清理。
     *
     * @param tenantIds 要清理的租户；⚠️ 逐租户执行，绝不跨租户批量删
     */
    public @NonNull Report runOnce(@NonNull List<String> tenantIds) {
        ChatAuditConfig config;
        try {
            config = configSupplier.get();
        } catch (RuntimeException e) {
            // ⚠️ 整轮跳过：用一个猜出来的保留天数去删不可逆，比「这轮没删」危险得多。
            log.warn("读取对话审计配置失败，本轮保留期清理整体跳过"
                    + "（不猜窗口，避免按错误天数删除；配置恢复后自动继续）: {}", e.toString());
            return new Report(0, 0, 0, 1);
        }
        int days = config.retention().days();
        if (days < 1) {
            // 校验层已挡住，这里兜底：0 天会让「删除全部历史」成为默认行为。
            log.error("对话审计保留期配置非法（days={}），本轮跳过；"
                    + "请检查配置页「保留天数」（合法区间 1~180）", days);
            return new Report(0, 0, 0, 1);
        }

        Instant now = clock.instant();
        Instant cutoff = now.minus(days, ChronoUnit.DAYS);
        LocalDate cutoffDate = cutoff.atZone(ZoneOffset.UTC).toLocalDate();

        int failed = 0;

        // ── 类别 1：库内原文 ──
        Map<String, Integer> rowsByTenant = new LinkedHashMap<>();
        if (databasePort == null) {
            if (warnedNoCredential.compareAndSet(false, true)) {
                log.error("⚠️ 对话审计保留期清理未接入清理凭据（agent_retention 数据源），"
                        + "库内原文不会被删除：app_agent 对 chat_audit_record 只有 "
                        + "SELECT/INSERT（DELETE 已在 03_platform_roles.sql 撤销）。"
                        + "执行 deploy/sql/platform/04_retention_role.sql 并配置 "
                        + "agent.retention.datasource.* 后重启生效。");
            }
            failed++;
        } else {
            for (String tenantId : tenantIds) {
                try {
                    int deleted = databasePort.purgeExpired(tenantId, cutoff, batchSize);
                    if (deleted > 0) {
                        rowsByTenant.put(tenantId, deleted);
                    }
                } catch (RuntimeException e) {
                    // ⚠️ 单租户失败不终止整轮：一个租户的问题不应让其余租户同步违约。
                    failed++;
                    log.error("对话审计原文清理失败（继续处理其余租户）: tenant={}", tenantId, e);
                }
            }
            if (!rowsByTenant.isEmpty()) {
                writeCertificates(rowsByTenant, now, days);
            }
        }

        // ── 类别 2：目录文件 ──
        int files = 0;
        if (directoryPort == null) {
            // 文件清理未接入不是「失败」而是一种明确的关闭态（例如纯 DB 部署）。
            log.debug("对话审计目录清理未接入，跳过文件类别");
        } else {
            try {
                files = directoryPort.purgeOlderThan(config.sinks().file().directory(), cutoffDate);
            } catch (RuntimeException e) {
                failed++;
                log.error("对话审计目录清理失败（文件侧原文仍在磁盘上）: dir={}",
                        config.sinks().file().directory(), e);
            }
        }

        int rows = 0;
        for (Integer n : rowsByTenant.values()) {
            rows += n;
        }
        return new Report(rows, files, days, failed);
    }

    /**
     * 写删除凭证（按租户，幂等）。
     *
     * <p>⚠️ <b>每张凭证只登记该租户自己的行数</b>，不含其它租户、也不含文件数 ——
     * 把总量摊给每个租户等于伪造举证材料；文件侧的理由见类注释
     * 「凭证口径的诚实边界」。
     */
    private void writeCertificates(@NonNull Map<String, Integer> rowsByTenant,
                                   @NonNull Instant now,
                                   int days) {
        if (erasurePort == null) {
            log.warn("对话审计清理已删除 {} 行但未写删除凭证"
                    + "（ErasurePort 未装配：保留期清理总开关关闭？）: days={}",
                    rowsByTenant.values().stream().mapToInt(Integer::intValue).sum(), days);
            return;
        }
        String windowKey = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC).format(now);
        for (Map.Entry<String, Integer> entry : rowsByTenant.entrySet()) {
            try {
                erasurePort.recordCertificate(entry.getKey(), new ErasureCertificate(
                        ErasureCertificate.REASON_RETENTION_EXPIRED,
                        actor,
                        now,
                        windowKey,
                        ErasureCertificate.SUBJECT_TENANT_WIDE,
                        Map.of("chat_audit_record", entry.getValue())));
            } catch (RuntimeException e) {
                // ⚠️ 凭证写失败不能让已完成的删除「假装置于未发生」，但也绝不能静默。
                log.error("对话审计删除凭证写入失败（删除已生效，需人工补登）: tenant={}",
                        entry.getKey(), e);
            }
        }
    }

    /**
     * 一轮清理结果。
     *
     * <p>⚠️ {@code failedCategories} 必须被上层消费（由 {@code ChatAuditRetentionJob}
     * 累计并打 WARN）——若无人看，它就退化为装饰性字段。
     */
    public record Report(int rowsDeleted,
                         int filesDeleted,
                         int retentionDays,
                         int failedCategories) {

        /** 本轮是否真的清理了东西。 */
        public boolean hasChanges() {
            return rowsDeleted > 0 || filesDeleted > 0;
        }
    }
}
