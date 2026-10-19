package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 数据保留与删除端口（P4-D04）。
 *
 * <p><b>为什么所有的清理方法都必须显式带 {@code tenantId}</b>（D-4）：
 * 批量删除是本系统中<b>单次影响面最大</b>的操作。若允许「不带租户、删全表」，
 * 那么一次参数传错就会跨越 ADR-006 的租户边界，
 * 且事后无法从错误信息判断删了谁的数据。
 *
 * <p>因此本端口<b>刻意不提供</b>任何无租户参数的删除方法——
 * 「不方便」是有意的：DELETE 的代价不可逆，便捷性必须让位于安全性。
 *
 * <p><b>⚠️ 四类数据的处置方式不同且不可互换</b>（见 {@code P4-D04 §3}）：
 * <ul>
 *   <li>画像 /  Trace / 审计 → <b>删行</b>；</li>
 *   <li>Prompt 与工具结果 → <b>置空字段</b>，不删行
 *       （删 run 行会经 {@code ON DELETE CASCADE} 级联删除学生薄弱点）；</li>
 *   <li>幂等键 → 本片<b>不清理</b>（删键等于解除重复执行保护）。</li>
 * </ul>
 */
public interface ErasurePort {

    /**
     * 清理到期的学生画像（含 {@code weakness_record}）。
     *
     * @param tenantId 租户标识，必填
     * @param cutoff   早于该时点的画像视为到期
     * @param limit    单轮上限，防止长事务锁表
     * @return 实际删除的画像行数
     */
    int purgeExpiredProfiles(@NonNull String tenantId,
                             @NonNull Instant cutoff,
                             int limit);

    /**
     * 将到期的 Prompt 与工具结果<b>置空</b>（不删行）。
     *
     * <p>⚠️ 返回的是<b>被置空的行数合计</b>，不是删除行数。
     *
     * @return 被置空的 run 行数
     */
    int blankExpiredPrompts(@NonNull String tenantId,
                            @NonNull Instant cutoff,
                            int limit);

    /** 清理到期的 Trace（{@code run_event}）。 */
    int purgeExpiredTraces(@NonNull String tenantId,
                           @NonNull Instant cutoff,
                           int limit);

    /**
     * 清理到期的审计记录。
     *
     * <p>⚠️ <b>{@code actor} 不可为空</b>（D-1）：
     * {@code audit_record} 的删除必须经「受控清理通道」，
     * 而通道的开关就是会话是否声明了操作主体。
     * 传入空值会让 DB 触发器直接拒绝，此处提前 fail-fast 以便给出可读错误。
     */
    int purgeExpiredAudits(@NonNull String tenantId,
                           @NonNull Instant cutoff,
                           int limit,
                           @NonNull String actor);

    /** 登记监护人撤回同意。重复登记同一学生应幂等，不抛异常。 */
    void registerWithdrawal(@NonNull String tenantId,
                            @NonNull String studentId,
                            @NonNull Instant withdrawnAt,
                            @NonNull Instant dueAt);

    /** 查询已到期但尚未完成删除的撤回登记。 */
    @NonNull List<PendingWithdrawal> findDueWithdrawals(@NonNull String tenantId,
                                                        @NonNull Instant now);

    /**
     * 删除已撤回同意学生的全部数据（画像 + 薄弱点）。
     *
     * @return 被删除的画像行数（0 或 1）
     */
    int eraseWithdrawnStudent(@NonNull String tenantId,
                              @NonNull String studentId,
                              @NonNull String actor);

    void markWithdrawalErased(@NonNull String tenantId,
                              @NonNull String studentId,
                              @NonNull Instant erasedAt);

    void markWithdrawalFailed(@NonNull String tenantId,
                              @NonNull String studentId);

    /**
     * 写入删除凭证。
     *
     * <p>⚠️ <b>幂等</b>（D-6）：同一 {@code (tenantId, reason, windowKey, subjectKey)}
     * 只允许一张凭证，重复写入应被静默忽略而非抛出异常——
     * 清理任务是周期性的，「凭证已存在」不是错误而是预期状态。
     */
    void recordCertificate(@NonNull String tenantId,
                           @NonNull ErasureCertificate certificate);

    /** 按租户与原因查询凭证（举证与外部抽检用）。 */
    @NonNull List<ErasureCertificate> findCertificates(@NonNull String tenantId,
                                                       @NonNull String reason,
                                                       int limit);

    /**
     * 待处理的撤回登记。
     *
     * <p>⚠️ JSpecify 约束：嵌套类型不可标注 {@code @NonNull}
     * （历史上因此报「存取方法无效」），故本 record 不携带 JSpecify 注解。
     */
    record PendingWithdrawal(String tenantId, String studentId, Instant dueAt) {
    }

    /**
     * 删除凭证。
     *
     * <p>⚠️ 本 record <b>刻意不含 {@code dryRun}</b>：本片未实现预演语义，
     * 留下一个恒为 false 的字段就等于制造一个「看起来支持、实际从未接线的开关」。
     */
    record ErasureCertificate(String reason,
                              String actor,
                              Instant executedAt,
                              String windowKey,
                              String subjectKey,
                              Map<String, Integer> detail) {

        /** 保留期到期清理（租户级）。 */
        public static final String REASON_RETENTION_EXPIRED = "RETENTION_EXPIRED";

        /** 监护人撤回同意（学生级）。 */
        public static final String REASON_CONSENT_WITHDRAWN = "CONSENT_WITHDRAWN";

        /** 租户级清理的 {@code subjectKey} 取值。 */
        public static final String SUBJECT_TENANT_WIDE = "tenant-wide";
    }
}
