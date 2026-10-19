package cn.studykid.multiagent.application.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate;
import cn.studykid.multiagent.ports.persistence.ErasurePort.PendingWithdrawal;

/**
 * 保留期清理服务（P4-D04）。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li><b>撤回优先</b> —— 监护人撤回有 30 天硬期限（FR-019），
 *       逾期即违约；而保留期清理晚跑一轮只是多存一天。
 *       因此 {@link #runOnce} 先处理撤回，再处理到期清理。</li>
 *   <li><b>单类失败不终止整轮</b>（与 D03「坏消息不终止循环」同纪律）——
 *       画像清理失败不应让 Trace 清理一起跳过，否则四类数据会同步违约。
 *       ⚠️ 但「吞掉异常」必须有补偿：失败项须计入 {@link PurgeReport#failedCategories()}，
 *       否则运维看到「清理成功」却不知道有一类没跑。</li>
 *   <li><b>时间判定用 UTC 而非本机时区</b> —— 清理是否发生必须可复现；
 *       换一台机器跑出不同结论会让「是否按承诺删除」无法举证。</li>
 * </ol>
 */
public class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    /** 清理窗口键的时区。⚠️ 刻意固定为 UTC，不随部署环境变化。 */
    private static final ZoneId WINDOW_ZONE = ZoneOffset.UTC;

    private static final DateTimeFormatter WINDOW_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(WINDOW_ZONE);

    private final ErasurePort erasurePort;
    private final RetentionSettings settings;
    private final String actor;
    private final Clock clock;

    public RetentionPurgeService(@NonNull ErasurePort erasurePort,
                                 @NonNull RetentionSettings settings,
                                 @NonNull String actor,
                                 @NonNull Clock clock) {
        if (actor.isBlank()) {
            // ⚠️ fail-fast：actor 是删除行为的唯一责任人，空主体 = R-1 失效。
            throw new IllegalArgumentException(
                    "清理服务必须指定 actor：没有责任人就不得删除未成年人数据");
        }
        this.erasurePort = erasurePort;
        this.settings = settings;
        this.actor = actor;
        this.clock = clock;
    }

    /**
     * 对一个租户执行一轮完整清理。
     *
     * @param tenantId 租户标识（清理范围由本参数与 RLS 共同界定）
     */
    public PurgeReport runOnce(@NonNull String tenantId) {
        Instant now = clock.instant();

        int withdrawalsErased = 0;
        int withdrawalsFailed = 0;
        try {
            withdrawalsErased = processWithdrawals(tenantId, now);
        } catch (RuntimeException e) {
            // ⚠️ 不重抛：撤回失败不应让到期清理也跳过。
            //    但必须 ERROR 级——这是 FR-019 的履约失败信号。
            log.error("撤回同意删除失败（FR-019 履约风险）: tenant={}", tenantId, e);
            withdrawalsFailed = 1;
        }

        Map<String, Integer> detail = new LinkedHashMap<>();
        int failed = withdrawalsFailed;

        int profiles = runCategory("画像", detail, () -> erasurePort.purgeExpiredProfiles(
                tenantId, monthsAgo(now, settings.profileMonths()), settings.batchSize()));
        if (profiles < 0) {
            failed++;
        }

        int prompts = runCategory("Prompt", detail, () -> erasurePort.blankExpiredPrompts(
                tenantId, now.minus(settings.promptDays(), ChronoUnit.DAYS), settings.batchSize()));
        if (prompts < 0) {
            failed++;
        }

        int traces = runCategory("Trace", detail, () -> erasurePort.purgeExpiredTraces(
                tenantId, now.minus(settings.traceDays(), ChronoUnit.DAYS), settings.batchSize()));
        if (traces < 0) {
            failed++;
        }

        int audits = runCategory("审计", detail, () -> erasurePort.purgeExpiredAudits(
                tenantId, now.minus(settings.auditDays(), ChronoUnit.DAYS),
                settings.batchSize(), actor));
        if (audits < 0) {
            failed++;
        }

        int affectedRows = 0;
        for (Integer n : detail.values()) {
            if (n != null && n > 0) {
                affectedRows += n;
            }
        }

        // ⚠️ 只在实际变更时写凭证：vacuous 的凭证会让「清理了什么」无法判读，
        //    且每日堆积空凭证会让举证材料迅速失去信噪比。
        if (affectedRows > 0) {
            erasurePort.recordCertificate(tenantId, new ErasureCertificate(
                    ErasureCertificate.REASON_RETENTION_EXPIRED,
                    actor,
                    now,
                    WINDOW_KEY.format(now),
                    ErasureCertificate.SUBJECT_TENANT_WIDE,
                    detail));
        }

        return new PurgeReport(
                Math.max(profiles, 0),
                Math.max(prompts, 0),
                Math.max(traces, 0),
                Math.max(audits, 0),
                withdrawalsErased,
                failed);
    }

    /** 处理已到期的撤回登记。 */
    private int processWithdrawals(String tenantId, Instant now) {
        List<PendingWithdrawal> due = erasurePort.findDueWithdrawals(tenantId, now);
        if (due.isEmpty()) {
            return 0;
        }
        int erased = 0;
        for (PendingWithdrawal w : due) {
            try {
                int rows = erasurePort.eraseWithdrawnStudent(tenantId, w.studentId(), actor);
                erasurePort.markWithdrawalErased(tenantId, w.studentId(), now);
                erasurePort.recordCertificate(tenantId, new ErasureCertificate(
                        ErasureCertificate.REASON_CONSENT_WITHDRAWN,
                        actor,
                        now,
                        WINDOW_KEY.format(now),
                        w.studentId(),
                        Map.of("student_profile", rows)));
                erased++;
                log.info("撤回同意删除完成: tenant={}, student={}, rows={}",
                        tenantId, w.studentId(), rows);
            } catch (RuntimeException e) {
                // ⚠️ 单个学生失败必须继续处理其余学生：
                //    否则一个坏数据会拖过其余人的 30 天期限 → 集体违约。
                erasurePort.markWithdrawalFailed(tenantId, w.studentId());
                log.error("单个撤回删除失败（已标记 FAILED，继续处理其余）: tenant={}, student={}",
                        tenantId, w.studentId(), e);
            }
        }
        return erased;
    }

    /**
     * 执行单个清理类别并记账。
     *
     * @return 成功时返回 ≥0 的行数；失败返回 -1（并计入 {@code failed} 判定）
     */
    private int runCategory(String label, Map<String, Integer> detail, Cleanup action) {
        try {
            int rows = action.run();
            if (rows > 0) {
                detail.put(labelKey(label), rows);
            }
            return rows;
        } catch (RuntimeException e) {
            log.error("清理类别失败: category={}", label, e);
            return -1;
        }
    }

    /**
     * 按「月」回退 cutoff。
     *
     * <p>⚠️⚠️ <b>实测缺陷 F-12</b>：{@code Instant.minus(months, ChronoUnit.MONTHS)}
     * 会抛 {@code UnsupportedTemporalTypeException: Unsupported unit: Months}——
     * {@code Instant} 只支持基于<b>固定时长</b>的单位，不支持「月」这种
     * 长度随日历变化的单位。
     *
     * <p><b>失败形态极其隐蔽</b>：异常发生在 {@code runCategory} 内部并转成 ERROR 日志，
     * 于是「画像清理」这一类别<b>永远为 0 行、永远不生效</b>，
     * 而其余三类正常、任务无退出码异常、健康检查全绿。
     * 这与本项目治理的「存在但不生效」是同一形态。
     *
     * <p>必须先转到带日历语义的类型（此处用 {@link #WINDOW_ZONE}），
     * 再回退到 {@code Instant}。
     */
    private static Instant monthsAgo(Instant now, int months) {
        return now.atZone(WINDOW_ZONE).minus(months, ChronoUnit.MONTHS).toInstant();
    }

    private static String labelKey(String label) {
        return switch (label) {
            case "画像" -> "student_profile";
            case "Prompt" -> "prompt_payload";
            case "Trace" -> "run_event";
            case "审计" -> "audit_record";
            default -> label;
        };
    }

    @FunctionalInterface
    private interface Cleanup {
        int run();
    }

    /**
     * 保留期设置。
     *
     * <p>⚠️ 本 record 的构造<b>不校验</b>取值区间——
     * 校验发生在配置解析阶段（{@code RetentionPolicy}，fail-closed）。
     * 这里只做传输，避免两处校验口径不一致。
     */
    public record RetentionSettings(int profileMonths,
                                    int promptDays,
                                    int auditDays,
                                    int traceDays,
                                    int withdrawalGraceDays,
                                    int batchSize) {
    }

    /**
     * 一轮清理的结果。
     *
     * <p>⚠️ <b>{@code failedCategories()} 必须被上层消费</b>：
     * 若无人看这个值，它就退化为一个「装饰性字段」——
     * 而本项目治理的正是这类「字段存在但没人判断」的缺陷。
     * {@code RetentionJob} 在它大于 0 时打 WARN。
     */
    public record PurgeReport(int profilesDeleted,
                              int promptsBlanked,
                              int tracesDeleted,
                              int auditsDeleted,
                              int withdrawalsErased,
                              int failedCategories) {

        /** 本轮是否有任何数据被删除/清理。 */
        public boolean hasChanges() {
            return profilesDeleted + promptsBlanked + tracesDeleted
                    + auditsDeleted + withdrawalsErased > 0;
        }
    }
}
