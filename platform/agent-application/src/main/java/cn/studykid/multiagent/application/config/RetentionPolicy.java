package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.Nullable;

/**
 * 数据保留期配置（P4-D04 / D-5）。
 *
 * <p><b>⚠️ 缺失或非法一律拒绝启动</b>（fail-closed）：
 * 对未成年人数据而言，保留期**没有安全的默认值**——
 * <ul>
 *   <li>过短 = 违规删除（破坏审计举证能力，等于自行削弱 R-1/R-3）；</li>
 *   <li>过长 = 违规留存（PIPL 超期留存，数据泄露影响面随时间线性增长）。</li>
 * </ul>
 * 两个方向都不是「保守」，因此退回默认值在语义上站不住脚。
 * 与 D4 {@code BudgetPolicy}、D8 {@code RateLimitPolicy}、D03 {@code WorkerPolicy} 同一取舍。
 *
 * <p><b>⚠️ 上下限同样是 fail-closed 的一部分</b>：
 * 只校验「为正数」不足以阻止「配了 0 天」或「配了 1000 天」，
 * 前者等于立刻清空所有数据，后者等于永久留存。
 * 因此本类对每一项都规定了合理的取值区间，超出即拒绝。
 */
public final class RetentionPolicy {

    /** 学习画像保留月数（student_profile / weakness_record）。 */
    public static final String KEY_PROFILE_MONTHS = "agent.retention.profile-months";

    /** Prompt 与工具结果保留天数（置空，不删行）。 */
    public static final String KEY_PROMPT_DAYS = "agent.retention.prompt-days";

    /** 审计日志保留天数。 */
    public static final String KEY_AUDIT_DAYS = "agent.retention.audit-days";

    /** Trace（run_event）保留天数。 */
    public static final String KEY_TRACE_DAYS = "agent.retention.trace-days";

    /** 撤回同意后的删除宽限天数（FR-019 要求上限 30 天）。 */
    public static final String KEY_WITHDRAWAL_GRACE_DAYS = "agent.retention.withdrawal-grace-days";

    /** 单表单轮清理的最大行数。 */
    public static final String KEY_BATCH_SIZE = "agent.retention.batch-size";

    /** 清理任务轮询间隔毫秒。 */
    public static final String KEY_INTERVAL_MS = "agent.retention.interval-ms";

    // ⚠️ 上限为什么存在：FR-019 明文要求「撤回后 30 日内删除」。
    //    配置值若大于 30 就是直接违反验收条款，必须在启动期拦住，
    //    而不是等到合规审计时被发现。
    private static final int MAX_GRACE_DAYS = 30;

    private RetentionPolicy() {
    }

    public static int requireProfileMonths(@Nullable Object raw) {
        long v = requireInRange(raw, KEY_PROFILE_MONTHS, 1, 120);
        return (int) v;
    }

    public static int requirePromptDays(@Nullable Object raw) {
        long v = requireInRange(raw, KEY_PROMPT_DAYS, 1, 3650);
        return (int) v;
    }

    public static int requireAuditDays(@Nullable Object raw) {
        // ⚠️ 下限为什么是 30：审计是 R-1/R-3 的举证载体，
        //    保留期短于一个bug追溯周期，等于让 Sev-0 事件的 evidence 先于结论消失。
        long v = requireInRange(raw, KEY_AUDIT_DAYS, 30, 3650);
        return (int) v;
    }

    public static int requireTraceDays(@Nullable Object raw) {
        long v = requireInRange(raw, KEY_TRACE_DAYS, 1, 3650);
        return (int) v;
    }

    public static int requireWithdrawalGraceDays(@Nullable Object raw) {
        long v = requireInRange(raw, KEY_WITHDRAWAL_GRACE_DAYS, 0, MAX_GRACE_DAYS);
        return (int) v;
    }

    public static int requireBatchSize(@Nullable Object raw) {
        // ⚠️ 上限 10000：批量删除持有行锁，批次过大会长时间锁表，
        //    影响同租户的在线请求；过小则清理一轮要跑很久。
        return (int) requireInRange(raw, KEY_BATCH_SIZE, 1, 10000);
    }

    public static long requireIntervalMs(@Nullable Object raw) {
        long v = requireInRange(raw, KEY_INTERVAL_MS, 1000, 24L * 60 * 60 * 1000);
        return v;
    }

    private static long requireInRange(@Nullable Object raw, String key, long min, long max) {
        if (raw == null) {
            throw new IllegalStateException(
                    "保留期配置缺失（fail-closed，禁止退回默认值）：" + key
                            + "。缺失的后果是「数据永久留存」或「立即清空」，"
                            + "两者都属未成年人数据违规，故必须在启动期拒绝。");
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "保留期配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "保留期配置超出允许区间（fail-closed）：" + key + " = " + value
                            + "，允许区间 [" + min + ", " + max + "]");
        }
        return value;
    }
}
