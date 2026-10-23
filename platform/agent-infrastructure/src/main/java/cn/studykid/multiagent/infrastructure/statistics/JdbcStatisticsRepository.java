package cn.studykid.multiagent.infrastructure.statistics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.statistics.StatisticsPort;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.DailyStatistics;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.KnowledgePointRank;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.PeriodSummary;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.RunDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.StudentDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.WeaknessDayStat;

/**
 * 工作台统计的 JDBC 实现（报表中心后端）。
 *
 * <p><b>⚠️ 为什么必须经 {@link TransactionalConnectionHolder#inTransaction}</b>：
 * 平台侧所有租户表都是 {@code ENABLE + FORCE ROW LEVEL SECURITY}，
 * 策略为 {@code tenant_id = current_tenant_id()}，而
 * {@code current_tenant_id()} 读的是<b>会话变量</b> {@code app.tenant_id}
 * （{@code set_config(..., is_local=true)}，仅当前事务有效）。
 * 少了这一步，RLS 会把全部行过滤掉——表现为「报表全是 0」且<b>不报错</b>。
 * 这正是 {@code JdbcRunListQueryRepository} 用 {@code TenantConnectionLease}
 * 的同一原因：连接必须先绑租户，再执行任何业务 SQL。
 *
 * <p><b>⚠️ 双层隔离</b>：SQL 显式 {@code tenant_id = ?::uuid}（第一道）
 * + 表上 RLS FORCE（第二道，由上面的会话变量激活）。任一层被误改，另一层仍能挡住。
 * ⚠️ 但第二道只在应用角色<b>没有 BYPASSRLS 属性</b>时才真正生效——
 * 本仓 dev 环境用的 {@code ai_agent} 角色带 BYPASSRLS（实测 2026-10-23），
 * 此时只有第一道在起作用。故生产必须用 NOBYPASSRLS 的应用角色。
 *
 * <p><b>⚠️ 连续日期填充</b>：统计图表需要「缺数据的日子也为 0」而非断点，
 * 故以 Java 侧生成的连续 UTC 日期轴为基准，把各聚合查询的结果<b>归并</b>进去，
 * 缺失的日子补 0。避免依赖 {@code generate_series} 的占位参数绑定。
 *
 * <p><b>⚠️ 日期分组统一用 UTC</b>：Java 侧 {@code LocalDate.now(ZoneOffset.UTC)}
 * 与 SQL 侧 {@code created_at AT TIME ZONE 'UTC'} 必须同源，
 * 否则跨午夜时出现「今天/昨天」错位。
 *
 * <p><b>⚠️ 环比的两个窗口必须严格不重叠</b>：本区间 {@code [start, start+days)}
 * 与上一区间 {@code [start-days, start)} 用<b>左闭右开</b>的同一套边界表达，
 * 少一个上界就会把当天数据算进两个区间，环比变成「自己跟自己比」。
 *
 * <p><b>⚠️ 不返回任何学生可识别信息</b>：聚合维度仅为日期、知识点、错因类型，
 * 不触及 student_id / 题目原文 / 姓名（DEC-6 白名单 + P0-D00 §4 合规红线）。
 * 注意 {@code count(DISTINCT student_id)} 只产出<b>数量</b>，
 * 随机标识本身不出现在结果里。
 */
@Repository
public class JdbcStatisticsRepository implements StatisticsPort {

    /**
     * 薄弱知识点排行榜容量。
     *
     * <p>⚠️ 在应用侧截断而非 SQL {@code LIMIT}：SQL 若按
     * {@code (knowledge_point, error_type)} 组排序后取前 N 行，
     * 前 N 行可能全是同一个知识点的不同错因（该知识点「吃满」榜单），
     * 必须按知识点聚合完整后再排序。
     */
    private static final int TOP_KNOWLEDGE_POINTS = 10;

    /** 天数钳制下界。 */
    private static final long MIN_DAYS = 1L;

    /** 天数钳制上界（防止一个报表页拖垮全表聚合）。 */
    private static final long MAX_DAYS = 90L;

    private final DataSource dataSource;

    public JdbcStatisticsRepository(@NonNull DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    @Override
    @NonNull
    public DailyStatistics daily(long rawDays, @NonNull String tenantId) {
        long days = Math.min(Math.max(rawDays, MIN_DAYS), MAX_DAYS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(days - 1L);
        // ⚠️ 左闭右开：本区间 [start, end)
        LocalDate end = start.plusDays(days);
        // ⚠️ 上一区间 [prevStart, start)——与本区间**严格不重叠**
        LocalDate prevStart = start.minusDays(days);

        // ⚠️ 全部查询必须在同一条已绑租户的连接上执行：
        //    顶层调用时 inTransaction 自开连接 + set_config + 提交（只读，空提交无害）；
        //    若已处于外层事务（如被服务层事务包裹），复用外层连接，不提交。
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                conn -> {
                    // 1) 本区间与上一区间的每日辅导量（发起量 + 终态拆分）
                    //    ⚠️ **两个口径必须分开**：
                    //    · total    = 当日发起的全部 Run（**含在途**）——回答「有多少学生来求助」
                    //    · terminal = 其中已有结论的数量 —— 是**成功率的分母**
                    //    早期实现把在途也从 total 里排除，导致「当日辅导量」被少算，
                    //    且成功率与辅导量共用一个分母，两个数字必然有一个是错的。
                    Map<String, long[]> currentRunByState = runStats(conn, tenantId, start, end);
                    Map<String, long[]> previousRunByState = runStats(conn, tenantId, prevStart, start);

                    // 2) 每日错题分析数（错因记录条数）
                    Map<String, Long> weaknessByDay =
                            weaknessCounts(conn, tenantId, start, end);

                    // 3a) 每日活跃学生（当日有错因记录，去重计数）
                    Map<String, Long> activeStudents =
                            activeStudentCounts(conn, tenantId, start, end);

                    // 3b) 每日新增学生（当日首次建档）
                    Map<String, Long> newStudents =
                            newStudentCounts(conn, tenantId, start, end);

                    // 4) 知识点排行：全历史累计 + 所选区间内
                    //    ⚠️ 两个口径都要给：全历史回答「长期哪些知识点反复出错」，
                    //    区间内回答「这段时间哪里最薄弱」——用区间筛长期薄弱点会让它消失，
                    //    只给全历史又会让「区间选择器」在这一张图上失效。
                    List<KnowledgePointRank> topKnowledgePoints =
                            knowledgeRanks(conn, tenantId, null, null);
                    List<KnowledgePointRank> topKnowledgePointsInRange =
                            knowledgeRanks(conn, tenantId, start, end);

                    // 5) 上一区间的其余三项（环比基线）
                    //    ⚠️ 与本区间**同形同界**：左闭右开，边界不重叠。
                    Map<String, Long> previousWeakness =
                            weaknessCounts(conn, tenantId, prevStart, start);
                    Map<String, Long> previousActive =
                            activeStudentCounts(conn, tenantId, prevStart, start);
                    Map<String, Long> previousNew =
                            newStudentCounts(conn, tenantId, prevStart, start);

                    // 归并成连续日期轴（升序，图表从左向右）
                    List<RunDayStat> runsByDay = new ArrayList<>();
                    List<WeaknessDayStat> weaknessesByDay = new ArrayList<>();
                    List<StudentDayStat> studentsByDay = new ArrayList<>();
                    for (int i = 0; i < days; i++) {
                        String day = start.plusDays(i).toString();
                        long[] r = currentRunByState.getOrDefault(day, new long[6]);
                        // total = 发起量（含在途）；terminal = 终态量（成功率分母）
                        runsByDay.add(new RunDayStat(day, r[0], r[5],
                                r[1], r[2], r[3], r[4]));
                        weaknessesByDay.add(new WeaknessDayStat(
                                day, weaknessByDay.getOrDefault(day, 0L)));
                        studentsByDay.add(new StudentDayStat(
                                day,
                                activeStudents.getOrDefault(day, 0L),
                                newStudents.getOrDefault(day, 0L)));
                    }

                    return new DailyStatistics(
                            runsByDay,
                            weaknessesByDay,
                            studentsByDay,
                            topKnowledgePoints,
                            topKnowledgePointsInRange,
                            (int) days,
                            summarize(currentRunByState, weaknessByDay, activeStudents, newStudents),
                            summarize(previousRunByState, previousWeakness,
                                    previousActive, previousNew));
                });
    }

    /**
     * 区间内每日 Run 状态计数。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（不含）
     * @return 日期 → [发起量, 成功, 失败, 超时, 取消, 终态量]
     */
    @NonNull
    private static Map<String, long[]> runStats(@NonNull Connection conn,
                                                @NonNull String tenantId,
                                                @NonNull LocalDate from,
                                                @NonNull LocalDate to) {
        Map<String, long[]> runByState = new LinkedHashMap<>();
        query(conn, """
                SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       state, count(*) AS c
                FROM run
                WHERE tenant_id = ?::uuid
                  AND created_at AT TIME ZONE 'UTC' >= ?::date
                  AND created_at AT TIME ZONE 'UTC' < ?::date
                GROUP BY day, state
                """, List.of(tenantId, from.toString(), to.toString()), rs -> {
            long[] acc = runByState.computeIfAbsent(
                    rs.getString("day"), k -> new long[6]);
            long c = rs.getLong("c");
            // ⚠️ 发起量：所有状态都算（含 QUEUED / RUNNING）
            acc[0] += c;
            switch (rs.getString("state")) {
                case "SUCCEEDED" -> acc[1] += c;
                case "FAILED" -> acc[2] += c;
                case "TIMED_OUT" -> acc[3] += c;
                case "CANCELLED" -> acc[4] += c;
                default -> { /* 在途态不计入终态拆分 */ }
            }
            // ⚠️ 终态量 = 四种终态之和（= 成功率分母）
            acc[5] = acc[1] + acc[2] + acc[3] + acc[4];
        });
        return runByState;
    }

    /**
     * 区间内每日错因记录数。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（不含）
     */
    @NonNull
    private static Map<String, Long> weaknessCounts(@NonNull Connection conn,
                                                    @NonNull String tenantId,
                                                    @NonNull LocalDate from,
                                                    @NonNull LocalDate to) {
        return groupCount(conn, """
                SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       count(*) AS c
                FROM weakness_record
                WHERE tenant_id = ?::uuid
                  AND created_at AT TIME ZONE 'UTC' >= ?::date
                  AND created_at AT TIME ZONE 'UTC' < ?::date
                GROUP BY day
                """, List.of(tenantId, from.toString(), to.toString()));
    }

    /**
     * 区间内每日活跃学生数（按日去重）。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（不含）
     */
    @NonNull
    private static Map<String, Long> activeStudentCounts(@NonNull Connection conn,
                                                          @NonNull String tenantId,
                                                          @NonNull LocalDate from,
                                                          @NonNull LocalDate to) {
        return groupCount(conn, """
                SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       count(DISTINCT student_id) AS c
                FROM weakness_record
                WHERE tenant_id = ?::uuid
                  AND created_at AT TIME ZONE 'UTC' >= ?::date
                  AND created_at AT TIME ZONE 'UTC' < ?::date
                GROUP BY day
                """, List.of(tenantId, from.toString(), to.toString()));
    }

    /**
     * 区间内每日新增学生数（首次建档）。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（不含）
     */
    @NonNull
    private static Map<String, Long> newStudentCounts(@NonNull Connection conn,
                                                       @NonNull String tenantId,
                                                       @NonNull LocalDate from,
                                                       @NonNull LocalDate to) {
        return groupCount(conn, """
                SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       count(*) AS c
                FROM student_profile
                WHERE tenant_id = ?::uuid
                  AND created_at AT TIME ZONE 'UTC' >= ?::date
                  AND created_at AT TIME ZONE 'UTC' < ?::date
                GROUP BY day
                """, List.of(tenantId, from.toString(), to.toString()));
    }

    /**
     * 知识点排行（按命中次数降序，取 Top N）。
     *
     * @param from 起始日期（含）；{@code null} 表示不限（全历史）
     * @param to   结束日期（不含）；{@code null} 表示不限（全历史）
     */
    @NonNull
    private static List<KnowledgePointRank> knowledgeRanks(@NonNull Connection conn,
                                                            @NonNull String tenantId,
                                                            LocalDate from,
                                                            LocalDate to) {
        Map<String, KnowledgePointRank> kpAccum = new LinkedHashMap<>();
        if (from == null || to == null) {
            query(conn, """
                    SELECT knowledge_point, error_type, count(*) AS c
                    FROM weakness_record
                    WHERE tenant_id = ?::uuid
                    GROUP BY knowledge_point, error_type
                    ORDER BY knowledge_point
                    """, List.of(tenantId), rs -> accumulateRank(kpAccum, rs));
        } else {
            query(conn, """
                    SELECT knowledge_point, error_type, count(*) AS c
                    FROM weakness_record
                    WHERE tenant_id = ?::uuid
                      AND created_at AT TIME ZONE 'UTC' >= ?::date
                      AND created_at AT TIME ZONE 'UTC' < ?::date
                    GROUP BY knowledge_point, error_type
                    ORDER BY knowledge_point
                    """, List.of(tenantId, from.toString(), to.toString()),
                    rs -> accumulateRank(kpAccum, rs));
        }
        List<KnowledgePointRank> ranks = new ArrayList<>(kpAccum.values());
        // ⚠️ 排序稳定：同次数按 SQL 的 knowledge_point 序（LinkedHashMap 插入序），
        //    保证同一份数据每次渲染顺序一致——否则榜单会在刷新间跳动。
        ranks.sort((a, b) -> Long.compare(b.count(), a.count()));
        if (ranks.size() > TOP_KNOWLEDGE_POINTS) {
            ranks = ranks.subList(0, TOP_KNOWLEDGE_POINTS);
        }
        return ranks;
    }

    /** 把一行 (知识点, 错因, 次数) 累加进排行累加器。 */
    private static void accumulateRank(Map<String, KnowledgePointRank> kpAccum, ResultSet rs)
            throws SQLException {
        String kp = rs.getString("knowledge_point");
        String et = rs.getString("error_type");
        long c = rs.getLong("c");
        KnowledgePointRank rank = kpAccum.computeIfAbsent(kp,
                k -> new KnowledgePointRank(k, 0L, new LinkedHashMap<>()));
        Map<String, Long> ets = new LinkedHashMap<>(rank.errorTypes());
        ets.merge(et, c, Long::sum);
        // ⚠️ 记录不可变，累计靠「读旧值 → 造新记录 → put」，
        //    而不是原地改 map——那会让同一引用被多处共享后遭到意外修改。
        kpAccum.put(kp, new KnowledgePointRank(kp, rank.count() + c, ets));
    }

    /**
     * 把逐日聚合归并成区间汇总。
     *
     * <p>⚠️ {@code activeStudentDays} 是<b>人次</b>（逐日去重后累加），
     * 不是<b>去重人数</b>——同一学生 3 天活跃记 3 人次。
     * 命名上刻意与「人数」区分，避免被读成「有多少学生」。
     */
    @NonNull
    private static PeriodSummary summarize(@NonNull Map<String, long[]> runByState,
                                           @NonNull Map<String, Long> weaknessByDay,
                                           @NonNull Map<String, Long> activeStudents,
                                           @NonNull Map<String, Long> newStudents) {
        long total = 0;
        long terminal = 0;
        long succeeded = 0;
        long failed = 0;
        long timedOut = 0;
        long cancelled = 0;
        for (long[] acc : runByState.values()) {
            total += acc[0];
            terminal += acc[5];
            succeeded += acc[1];
            failed += acc[2];
            timedOut += acc[3];
            cancelled += acc[4];
        }
        long weakness = weaknessByDay.values().stream().mapToLong(Long::longValue).sum();
        long activeDays = activeStudents.values().stream().mapToLong(Long::longValue).sum();
        long fresh = newStudents.values().stream().mapToLong(Long::longValue).sum();
        return new PeriodSummary(total, terminal, succeeded, failed, timedOut, cancelled,
                weakness, activeDays, fresh);
    }

    /**
     * 在已绑租户的连接上执行聚合查询，逐行消费。
     *
     * @param conn    已执行 {@code set_config('app.tenant_id', ...)} 的连接
     * @param sql     聚合 SQL（必须自带 {@code tenant_id = ?::uuid}）
     * @param params  绑定参数（按位序）
     * @param consumer 行消费者
     */
    private static void query(@NonNull Connection conn, @NonNull String sql,
                              @NonNull List<Object> params,
                              @NonNull SqlRowConsumer consumer) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计查询失败: " + sql, e);
        }
    }

    /**
     * 执行 {@code SELECT ... AS day, count(*) AS c} 形态的按天聚合。
     *
     * @return 日期 → 计数（插入序，即 SQL 的 GROUP BY 序）
     */
    @NonNull
    private static Map<String, Long> groupCount(@NonNull Connection conn, @NonNull String sql,
                                                @NonNull List<Object> params) {
        Map<String, Long> result = new LinkedHashMap<>();
        query(conn, sql, params, rs -> result.put(rs.getString("day"), rs.getLong("c")));
        return result;
    }

    /** 聚合查询的行消费者（受检异常由实现方兜住）。 */
    @FunctionalInterface
    private interface SqlRowConsumer {
        void accept(@NonNull ResultSet rs) throws SQLException;
    }
}
