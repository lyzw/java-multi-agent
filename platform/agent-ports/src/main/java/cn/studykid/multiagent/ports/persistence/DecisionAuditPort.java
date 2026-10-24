package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;

/**
 * 四字段决策日志端口（§5.1 / §7.4 / 08 §7 · W-PERM-P5）。
 *
 * <p>⚠️ <b>为什么不用既有的 {@link AuditPort}</b>：
 * {@code audit_record} 是<b>通用行为审计</b>（action / resource_type / detail JSONB），
 * 而 08 §7 要求的是可统计的「四字段 + ledger」：
 * <ul>
 *   <li>四字段完整率 <b>100%</b>；</li>
 *   <li>非笼统 DENY 占比 <b>100%</b>（按 reasonCode 归类）；</li>
 *   <li>每次调用可经 runId + invocationId 追踪。</li>
 * </ul>
 * 用通用审计表承载，只能靠 {@code detail} 里的约定 key 表达 ——
 * 「完整率 100%」就退化成一条无法用 SQL 断言的约定。
 * 故另立专表，以 NOT NULL 从存储层强制完整率。
 *
 * <p>⚠️ <b>写入语义是 fail-closed</b>：
 * 决策日志写不进去，说明「发生了什么」不可追责 ——
 * 那比这次调用失败更严重。故 {@link #append} 失败必须抛异常。
 */
public interface DecisionAuditPort {

    /**
     * 追加一条决策日志。
     *
     * <p>⚠️ 失败<b>抛异常</b>（fail-closed）：审计缺失比调用失败更严重。
     *
     * @param entry 决策条目（四字段 + ledger + 原因码 + 上下文 id）
     */
    void append(@NonNull DecisionAuditEntry entry);

    /**
     * 按调用标识读取决策日志（对账 / 追责）。
     *
     * @param tenantId     租户标识
     * @param invocationId 调用标识
     * @return 该调用的全部决策记录（按时间升序）
     */
    @NonNull List<DecisionAuditEntry> findByInvocation(@NonNull String tenantId,
                                                       @NonNull String invocationId);

    /**
     * 按 Run 读取决策日志。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 该 Run 的全部决策记录（按时间升序）
     */
    @NonNull List<DecisionAuditEntry> findByRun(@NonNull String tenantId, @NonNull String runId);

    /**
     * 统计决策日志的审计覆盖率（08 §7 的两条指标）。
     *
     * <p>⚠️ DB 层四字段已是 NOT NULL，故「不完整」这一项恒为 0；
     * 它的价值在于**把该不变量变成可执行的断言**，
     * 而不是靠「表结构如此」这一口头保证。
     *
     * @param tenantId 租户标识
     * @return 覆盖率统计
     */
    @NonNull
    DecisionAuditCoverage coverage(@NonNull String tenantId);

    /**
     * 读取<b>近期拒绝记录</b>（管理面 P4 运行监控）。
     *
     * <p>⚠️ <b>只返回 DENY</b>：ALLOW 行不构成告警素材。
     * 把允许记录一并返回会让调用方自行过滤 —— 那等于把
     * 「拒绝才算异常」这一口径分散到每个调用点，容易漏筛。
     *
     * <p>⚠️ <b>排序固定为发生时刻倒序</b>：监控视图看的是「最近发生了什么」，
     * 顺序若不确定则同一批数据在不同次调用里排列不同，无法做增量对比。
     *
     * <p>⚠️ <b>只读，不写入</b>：本方法不产生任何审计副作用。
     *
     * @param tenantId 租户标识
     * @param since    起始时刻（含）；<b>不得为 null</b> —— 监控必须有窗口，
     *                 无窗口的「近期」在数据量增长后等于全表扫描
     * @param limit    最大返回条数（调用方已校验上限）
     * @return 拒绝记录（按 {@code created_at} 倒序）
     */
    public @NonNull List<DecisionAuditEntry> findRecentDenies(
            @NonNull String tenantId,
            @NonNull Instant since,
            int limit);
}
