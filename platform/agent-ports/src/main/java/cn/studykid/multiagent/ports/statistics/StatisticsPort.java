package cn.studykid.multiagent.ports.statistics;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 工作台统计端口（报表中心后端数据源）。
 *
 * <p><b>⚠️ 统计口径与隐私边界</b>：
 * 本端口只产出<b>租户级聚合</b>——按天计数的辅导量、错因记录、学生活跃度，
 * 以及错因薄弱点排行。<b>不返回任何学生可识别信息</b>（不返回 student_id、
 * 姓名、题目原文），符合 P0-D00 §4 合规红线与 DEC-6 数据白名单。
 *
 * <p><b>⚠️ 租户隔离是双层的</b>（与 {@code JdbcRunListQueryRepository} 同源铁律）：
 * SQL 显式携带 {@code tenant_id = ?::uuid}（第一道）+ 表上强制 RLS（第二道）。
 *
 * <p><b>⚠️ 日期分组统一用 UTC</b>：Java 侧 {@code LocalDate.now(UTC)} 与
 * SQL 侧 {@code created_at AT TIME ZONE 'UTC'} 必须同源，
 * 否则会在跨午夜时出现「今天/昨天」错位。
 */
public interface StatisticsPort {

    /** 取最近 {@code days} 天的聚合统计。 */
    @NonNull DailyStatistics daily(long days, @NonNull String tenantId);

    /**
     * 单日辅导量（含终态拆分）。
     *
     * <p>⚠️ <b>「发起量」与「终态量」是两个口径，不可混用</b>：
     * <ul>
     *   <li>{@code total} = 当日<b>发起</b>的辅导总数（<b>含在途</b>：排队中 / 分析中）。
     *       它回答「今天有多少学生来求助」——把在途排除掉会<b>少算</b>当日辅导量，
     *       让人误以为需求变少了。</li>
     *   <li>{@code terminal} = 当日发起且<b>已有结论</b>的数量
     *       （成功 + 失败 + 超时 + 取消）。它才是<b>成功率的分母</b>——
     *       在途 Run 尚无结论，计入会把「还没出结果」算成未成功，让成功率失真。</li>
     * </ul>
     * 两个数字必须分别呈现，合并成一个就必然说谎。
     *
     * @param date        UTC 日期，格式 {@code yyyy-MM-dd}
     * @param total       当日发起的辅导总数（含在途）
     * @param terminal    当日终态数（成功 + 失败 + 超时 + 取消）＝ 成功率分母
     * @param succeeded   成功终态数
     * @param failed      失败终态数
     * @param timedOut    超时终态数
     * @param cancelled   取消终态数
     */
    record RunDayStat(
            @NonNull String date,
            long total,
            long terminal,
            long succeeded,
            long failed,
            long timedOut,
            long cancelled
    ) {
    }

    /** 单日错题分析数（错题产生的错因记录条数）。 */
    record WeaknessDayStat(@NonNull String date, long count) {
    }

    /**
     * 单日学生活跃 / 新增。
     *
     * @param date             UTC 日期
     * @param activeStudents   当日有错因记录（参与分析）的去重学生数
     * @param newStudents      当日首次建档（首次出现于画像表）的学生数
     */
    record StudentDayStat(@NonNull String date, long activeStudents, long newStudents) {
    }

    /**
     * 薄弱知识点排行项。
     *
     * @param knowledgePoint 知识点（枚举化的 canonical 名称）
     * @param count          命中次数
     * @param errorTypes     该知识点下的错因分布（error_type → 次数）
     */
    record KnowledgePointRank(
            @NonNull String knowledgePoint,
            long count,
            @NonNull Map<String, Long> errorTypes
    ) {
    }

    /**
     * 一个统计窗口的汇总值（用于「本区间 vs 上一区间」的环比）。
     *
     * <p>⚠️ <b>为什么后端必须给汇总值，而不能让前端自己加</b>：
     * 「上一区间」的数据根本不在响应里（否则要传两倍天数），
     * 前端无从算起；而「本区间」若由前端对逐日数组求和，
     * 就出现了<b>两份口径</b>——一旦后端的归日规则与前端的求和规则
     * 分歧，同一个报表上会出现两个不一样的总数。
     * 故两个窗口都由后端给出汇总值，前端只做展示与对比。
     *
     * @param total            发起量（含在途）
     * @param terminal         终态量（成功率分母）
     * @param succeeded        成功终态数
     * @param failed           失败终态数
     * @param timedOut         超时终态数
     * @param cancelled        取消终态数
     * @param weaknessCount    错因记录条数
     * @param activeStudentDays 活跃<b>人次</b>（逐日去重后累加；不是去重人数）
     * @param newStudents      新增学生数（首次建档）
     */
    record PeriodSummary(
            long total,
            long terminal,
            long succeeded,
            long failed,
            long timedOut,
            long cancelled,
            long weaknessCount,
            long activeStudentDays,
            long newStudents
    ) {
    }

    /**
     * 报表中心聚合响应。
     *
     * @param runsByDay                每日辅导量（连续日期，缺数据填 0）
     * @param weaknessesByDay          每日错题分析数
     * @param studentsByDay            每日学生活跃 / 新增
     * @param topKnowledgePoints       薄弱知识点 Top N（<b>全历史累计</b>）
     * @param topKnowledgePointsInRange 薄弱知识点 Top N（<b>所选区间内</b>）
     * @param generatedDays            实际生成的连续天数
     * @param currentPeriod            本区间汇总（与逐日数组同源）
     * @param previousPeriod           紧邻的上一等长区间汇总（环比基线）
     */
    record DailyStatistics(
            @NonNull List<RunDayStat> runsByDay,
            @NonNull List<WeaknessDayStat> weaknessesByDay,
            @NonNull List<StudentDayStat> studentsByDay,
            @NonNull List<KnowledgePointRank> topKnowledgePoints,
            @NonNull List<KnowledgePointRank> topKnowledgePointsInRange,
            int generatedDays,
            @NonNull PeriodSummary currentPeriod,
            @NonNull PeriodSummary previousPeriod
    ) {
    }
}
