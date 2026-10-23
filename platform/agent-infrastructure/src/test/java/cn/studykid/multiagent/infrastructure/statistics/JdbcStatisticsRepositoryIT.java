package cn.studykid.multiagent.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.statistics.StatisticsPort.DailyStatistics;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.KnowledgePointRank;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.RunDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.StudentDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.WeaknessDayStat;

/**
 * 工作台统计集成测试（报表中心 · 平台侧）。
 *
 * <p><b>⚠️ 必须跑在真实 PG 上</b>：本片全部命题都依赖数据库特性——
 * 强制 RLS、{@code timestamptz} 的时区换算、{@code count(DISTINCT ...)}。
 * 用内存替身验证等于没验证（替身验证的是替身）。
 *
 * <p><b>⚠️ 用非特权角色</b>：超级用户绕过 RLS，会得出「隔离失效」
 * 或「写进去读不到」的虚假结论。
 *
 * <p><b>⚠️ 运行前置条件（缺了会报 permission denied，不是本用例的锅）</b>：
 * <ol>
 *   <li>迁移已应用（V1~V18）；</li>
 *   <li>应用角色对被查表<b>有 SELECT 授权</b>：
 *       {@code GRANT SELECT ON ALL TABLES IN SCHEMA public TO app_agent}
 *       —— 迁移文件只对新表做条件式 GRANT，{@code run} / {@code weakness_record}
 *       这类基线表靠 CI 迁移后的全表 GRANT 覆盖；
 *       少了它，报错是 {@code permission denied for table run}，
 *       现象与「统计逻辑坏了」相距很远。</li>
 *   <li>应用角色<b>不得带 BYPASSRLS</b>：否则第二道隔离（表上强制 RLS）
 *       形同虚设，「跨租户隔离」用例会变成假绿。</li>
 *   <li>IT 库已应用 {@code migration/verify/V1_seed.sql}：
 *       本 IT 需要种子租户 {@code t-a} / {@code t-b} 及其执行快照。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么复用种子租户、不自建随机租户</b>（2026-10-23 实测修正）：
 * {@code execution_snapshot} 上挂着<b>禁止 UPDATE/DELETE 的不可变触发器</b>
 * （{@code forbid_snapshot_mutation}，V1 §4）。而 {@code run.snapshot_id}
 * 必填且带复合外键 {@code (tenant_id, snapshot_id)} →
 * <b>每新建一个租户就必须新建一个快照，而快照永远删不掉</b>，
 * 连带该租户（外键级联也会被触发器挡住）也删不掉。
 * 早期实现「每用例建随机租户 + 用完删除」实际是<b>永远删不掉</b>：
 * 清理异常被 catch 吞掉，IT 照样报绿，而库里每轮残留一个租户 + 一个快照。
 * 残留的快照还有连带伤害：别的 IT 用
 * {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 取快照时，
 * 会取到本 IT 留下的**别的租户**的快照 → 复合外键报错 → 别的 IT 整片变红
 * （实测 {@code RunEventAppendIT} 9 项 ERROR，现象与「本 IT 的数据」相距很远）。
 * <p>现改为：<b>复用种子租户 t-a / t-b</b>（快照已在种子里，永久存在是设计如此），
 * 断言一律用<b>增量</b>（先取基线，再造数，再比对差值），
 * 每例结束只删本例造的行（{@code run} / {@code weakness_record} / {@code student_profile}
 * 都可删），<b>零残留</b>。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcStatisticsRepositoryIT {

    /** 本 IT 造的 Run 的幂等键前缀（清理时按前缀删）。 */
    private static final String RUN_KEY_PREFIX = "stat-it-";

    /** 本 IT 造的学生标识前缀（形如 stu_statxxxxxxxx，命中 student_id 的格式约束）。 */
    private static final String STUDENT_PREFIX = "stu_stat";

    /** 本 IT 造的知识点的固定前缀（保证断言不受别的 IT 残留影响）。 */
    private static final String KP_PREFIX = "统计IT知识点";

    private static HikariDataSource pool;
    private static JdbcStatisticsRepository repository;

    /** 种子租户 A（持有执行快照，可造 Run）。 */
    private static String tenantA;

    /** 种子租户 B（无快照无数据，用作隔离对照）。 */
    private static String tenantB;

    /** 租户 A 的执行快照 id。 */
    private static String snapshotA;

    // ── 每例开始前的基线（断言用增量，不用绝对值）─────────────────────────

    /** 租户 A 当日 Run 总数。 */
    private static long baselineRuns;

    /** 租户 A 当日终态 Run 数。 */
    private static long baselineTerminal;

    /** 租户 A 当日成功 Run 数。 */
    private static long baselineSucceeded;

    /** 租户 A 当日失败 Run 数。 */
    private static long baselineFailed;

    /** 租户 A 当日错因记录数。 */
    private static long baselineWeakness;

    /** 租户 A 当日活跃学生数（去重）。 */
    private static long baselineActive;

    /** 租户 B 当日 Run 总数（隔离对照的基线）。 */
    private static long baselineRunsB;

    /** 租户 B 当日错因记录数（隔离对照的基线）。 */
    private static long baselineWeaknessB;

    /** 本区间（近 7 天）内租户 A 的 Run 数。 */
    private static long baselineWindowRuns;

    /** 上一等长区间内租户 A 的 Run 数。 */
    private static long baselinePreviousWindowRuns;

    /** 本区间（近 7 天）内租户 A 的终态 Run 数。 */
    private static long baselineWindowTerminal;

    @BeforeAll
    static void setUp() {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv().getOrDefault("IT_PG_PASSWORD", "");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (!pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        pool = new HikariDataSource(cfg);
        repository = new JdbcStatisticsRepository(pool);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug = " + q("t-a"));
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug = " + q("t-b"));
        if (tenantA == null || tenantB == null) {
            throw new IllegalStateException(
                    "IT 库缺少种子租户 t-a / t-b：请先应用 migration/verify/V1_seed.sql");
        }
        snapshotA = adminQuery("SELECT snapshot_id::text FROM execution_snapshot WHERE tenant_id = "
                + q(tenantA) + "::uuid LIMIT 1");
        if (snapshotA == null) {
            throw new IllegalStateException(
                    "IT 库缺少 t-a 的执行快照：请先应用 migration/verify/V1_seed.sql");
        }
    }

    /**
     * 关闭连接池。
     *
     * <p>⚠️ 必须关闭：各 IT 类累积的连接会打满 PostgreSQL 的 max_connections，
     * 导致**后续模块**的上下文加载以「too many clients」失败——
     * 失败现场在别的模块，离真因极远。
     */
    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    /**
     * 每例开始前记录基线。
     *
     * <p>⚠️ **必须用增量而不是绝对值**：种子租户里本来就有 Run
     * （{@code verify/V1_seed.sql} 造的 SUCCEEDED Run），
     * 别的 IT 也可能在本 IT 前后留下行。断言绝对值就是断言环境，
     * 换个库、换个顺序就飘。
     */
    @BeforeEach
    void captureBaseline() {
        baselineRuns = countRuns(tenantA);
        baselineTerminal = countTerminalRuns(tenantA);
        baselineSucceeded = countRunsByState(tenantA, "SUCCEEDED");
        baselineFailed = countRunsByState(tenantA, "FAILED");
        baselineWeakness = countWeakness(tenantA);
        baselineActive = countActiveStudents(tenantA);
        baselineRunsB = countRuns(tenantB);
        baselineWeaknessB = countWeakness(tenantB);
        baselineWindowRuns = countRunsInWindow(tenantA, 7, -1);
        baselineWindowTerminal = countTerminalRunsInWindow(tenantA, 7, -1);
        baselinePreviousWindowRuns = countRunsInWindow(tenantA, 7, 6);
    }

    /**
     * 每例结束清掉本例造的行。
     *
     * <p>⚠️ **只删能删的**：{@code execution_snapshot} 被不可变触发器挡住，
     * 所以本 IT 绝不新建快照（见类注释）。这里删的
     * {@code run} / {@code weakness_record} / {@code student_profile} 都可删，
     * 删完种子租户回到种子状态，不给后续 IT 留污染。
     */
    @AfterEach
    void cleanUp() {
        adminExec("DELETE FROM weakness_record WHERE tenant_id = " + q(tenantA)
                + "::uuid AND student_id LIKE " + q(STUDENT_PREFIX + "%"));
        adminExec("DELETE FROM run WHERE tenant_id = " + q(tenantA)
                + "::uuid AND idempotency_key LIKE " + q(RUN_KEY_PREFIX + "%"));
        adminExec("DELETE FROM student_profile WHERE tenant_id = " + q(tenantA)
                + "::uuid AND student_id LIKE " + q(STUDENT_PREFIX + "%"));
    }

    // -----------------------------------------------------------------
    // 用例
    // -----------------------------------------------------------------

    @Test
    @DisplayName("✅ 辅导量含在途，但成功率分母只算终态（两个口径必须分开）")
    void runDayStatSeparatesStartedFromTerminal() {
        seedRun("SUCCEEDED");
        seedRun("SUCCEEDED");
        seedRun("FAILED");
        seedRun("QUEUED");   // 在途
        seedRun("RUNNING");  // 在途

        DailyStatistics stats = repository.daily(7, tenantA);
        RunDayStat today = today(stats.runsByDay());

        assertThat(today.total() - baselineRuns)
                .as("⛔ 发起量必须含在途 Run —— 排除会让人以为当日需求变少")
                .isEqualTo(5L);
        assertThat(today.terminal() - baselineTerminal)
                .as("⛔ 终态量（成功率分母）不得含在途 —— 否则成功率失真")
                .isEqualTo(3L);
        // ⚠️ 成功/失败同样用**增量**：别的 IT 会在库里留下当天创建的 Run
        //   （实测残留 171 行），绝对值断言会把别人的数据算成自己的。
        assertThat(today.succeeded() - baselineSucceeded)
                .as("本例造了 2 条成功")
                .isEqualTo(2L);
        assertThat(today.failed() - baselineFailed)
                .as("本例造了 1 条失败")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 连续日期补零：区间内每一天都有一行，缺数据的日子为 0")
    void dailyFillsMissingDaysWithZero() {
        DailyStatistics stats = repository.daily(7, tenantA);

        assertThat(stats.runsByDay()).hasSize(7);
        assertThat(stats.weaknessesByDay()).hasSize(7);
        assertThat(stats.studentsByDay()).hasSize(7);
        assertThat(stats.generatedDays()).isEqualTo(7);

        // 日期轴必须升序、连续、截止今天
        List<String> dates = stats.runsByDay().stream().map(RunDayStat::date).toList();
        assertThat(dates).isSorted();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(dates.get(0)).isEqualTo(today.minusDays(6).toString());
        assertThat(dates.get(6)).isEqualTo(today.toString());
    }

    @Test
    @DisplayName("✅ 每日错题分析按 created_at 归日，知识点排行按命中次数降序")
    void weaknessDailyAndKnowledgePointRanking() {
        String runId = seedRun("SUCCEEDED");
        // 同一知识点 3 次、另一知识点 1 次
        // ⚠️ 知识点用**本 IT 专属名字**：断言它的绝对次数不受别的 IT 残留影响
        seedWeakness(runId, "甲", "计算失误");
        seedWeakness(runId, "甲", "计算失误");
        seedWeakness(runId, "甲", "概念不清");
        seedWeakness(runId, "乙", "审题偏差");

        DailyStatistics stats = repository.daily(7, tenantA);

        WeaknessDayStat todayStat = stats.weaknessesByDay().stream()
                .filter(d -> d.date().equals(LocalDate.now(ZoneOffset.UTC).toString()))
                .findFirst().orElseThrow();
        assertThat(todayStat.count() - baselineWeakness).isEqualTo(4L);

        List<KnowledgePointRank> top = stats.topKnowledgePoints();
        KnowledgePointRank jia = top.stream()
                .filter(k -> k.knowledgePoint().equals(kp("甲")))
                .findFirst().orElseThrow();
        KnowledgePointRank yi = top.stream()
                .filter(k -> k.knowledgePoint().equals(kp("乙")))
                .findFirst().orElseThrow();
        assertThat(jia.count()).isEqualTo(3L);
        assertThat(jia.errorTypes()).containsEntry("计算失误", 2L);
        assertThat(jia.errorTypes()).containsEntry("概念不清", 1L);
        assertThat(yi.count()).isEqualTo(1L);
        // 降序
        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i - 1).count()).isGreaterThanOrEqualTo(top.get(i).count());
        }
    }

    @Test
    @DisplayName("✅ 活跃学生按日去重（同一学生当天多次错题只计 1 人）")
    void activeStudentsAreDeduplicatedPerDay() {
        String runId = seedRun("SUCCEEDED");
        String student = STUDENT_PREFIX + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        // ⚠️ 三条必须落在**不同知识点**上：
        //    weakness_record 的幂等唯一键是 (tenant, student, run, knowledge_point)，
        //    同键再插会被唯一约束拒绝（实测 INSERT 直接失败）。
        seedWeakness(runId, "丙一", "计算失误", student);
        seedWeakness(runId, "丙二", "审题偏差", student);
        seedWeakness(runId, "丙三", "审题偏差", student);

        DailyStatistics stats = repository.daily(7, tenantA);
        StudentDayStat todayStat = stats.studentsByDay().stream()
                .filter(d -> d.date().equals(LocalDate.now(ZoneOffset.UTC).toString()))
                .findFirst().orElseThrow();

        // ⚠️ 断言的是**本例新增的活跃学生数**（本例只造了 1 个学生）
        long mine = todayStat.activeStudents() - baselineActive;
        assertThat(mine)
                .as("⛔ 同一学生当天的多条错因只能计 1 人")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("⛔ 跨租户隔离：租户 A 的数据不能出现在租户 B 的报表里")
    void statisticsAreIsolatedByTenant() {
        String runId = seedRun("SUCCEEDED");
        seedWeakness(runId, "丁", "计算失误");

        DailyStatistics statsA = repository.daily(7, tenantA);
        DailyStatistics statsB = repository.daily(7, tenantB);

        // ⚠️ 断言「增量」：B 租户可能有别的 IT 留下的历史数据，
        //    但**本例造的数据**一行都不许出现在 B 的报表里。
        long bRuns = today(statsB.runsByDay()).total();
        long bWeakness = statsB.weaknessesByDay().stream()
                .filter(d -> d.date().equals(LocalDate.now(ZoneOffset.UTC).toString()))
                .mapToLong(WeaknessDayStat::count)
                .findFirst().orElse(0L);

        assertThat(bRuns - baselineRunsB)
                .as("⛔ 租户 B 不得看到租户 A 的辅导量")
                .isEqualTo(0L);
        assertThat(bWeakness - baselineWeaknessB)
                .as("⛔ 租户 B 不得看到租户 A 的错题分析量")
                .isEqualTo(0L);
        assertThat(statsB.topKnowledgePoints().stream()
                .noneMatch(k -> k.knowledgePoint().startsWith(KP_PREFIX)))
                .as("⛔ 租户 B 不得看到租户 A 的知识点排行")
                .isTrue();
        // A 自己看得见（否则上面几条会变成「什么都没查到」的假绿）
        assertThat(statsA.topKnowledgePoints().stream()
                .anyMatch(k -> k.knowledgePoint().equals(kp("丁"))))
                .isTrue();
    }

    @Test
    @DisplayName("⛔ 天数钳制在 1～90（前端乱传不得触发全表扫描）")
    void daysAreClamped() {
        assertThat(repository.daily(0, tenantA).generatedDays()).isEqualTo(1);
        assertThat(repository.daily(-5, tenantA).generatedDays()).isEqualTo(1);
        assertThat(repository.daily(500, tenantA).generatedDays()).isEqualTo(90);
    }

    @Test
    @DisplayName("✅ 环比：上一区间只统计上一段，且与本区间严格不重叠")
    void previousPeriodIsAdjacentAndDisjoint() {
        // ⚠️ 窗口 = 7 天 → 本区间 [今天-6, 今天+1)，上一区间 [今天-13, 今天-6)
        //    两个区间**首尾相接**：上一区间的最后一天是今天-7，
        //    本区间的第一天是今天-6。卡住这两天，边界写错立刻暴露：
        //      · 上一区间上界写成闭区间 → 今天-6 被算两遍
        //      · 本区间下界写成开区间   → 今天-6 丢失
        seedRun("SUCCEEDED", 8);   // 上一区间
        seedRun("SUCCEEDED", 7);   // 上一区间最后一天
        seedRun("SUCCEEDED", 6);   // 本区间第一天
        seedRun("SUCCEEDED", 2);   // 本区间
        seedRun("SUCCEEDED", 0);   // 本区间（今天）

        DailyStatistics stats = repository.daily(7, tenantA);

        long currentRuns = stats.currentPeriod().total() - baselineWindowRuns;
        long previousRuns = stats.previousPeriod().total() - baselinePreviousWindowRuns;

        assertThat(currentRuns)
                .as("本区间 = 今天-6 ~ 今天，含 3 条")
                .isEqualTo(3L);
        assertThat(previousRuns)
                .as("⛔ 上一区间 = 今天-13 ~ 今天-7，含 2 条；"
                        + "边界重复计入会让环比失真")
                .isEqualTo(2L);
        // ⚠️ 两个窗口不许相交：合计必须等于落在两窗口内的总数（5 条）
        assertThat(currentRuns + previousRuns)
                .as("两窗口合计必须等于落在窗口内的总数（5 条），不得重复计数")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("✅ 汇总值与逐日数组同源（前端不再自行求和）")
    void periodSummaryMatchesDailyArrays() {
        seedRun("SUCCEEDED", 1);
        seedRun("FAILED", 1);
        seedRun("QUEUED", 0);
        String runId = seedRun("SUCCEEDED", 0);
        seedWeakness(runId, "戊", "计算失误", null, 1);
        seedWeakness(runId, "己", "审题偏差", null, 1);

        DailyStatistics stats = repository.daily(7, tenantA);

        long totalFromDays = stats.runsByDay().stream().mapToLong(RunDayStat::total).sum();
        long terminalFromDays = stats.runsByDay().stream().mapToLong(RunDayStat::terminal).sum();
        long weaknessFromDays =
                stats.weaknessesByDay().stream().mapToLong(WeaknessDayStat::count).sum();

        assertThat(stats.currentPeriod().total()).isEqualTo(totalFromDays);
        assertThat(stats.currentPeriod().terminal()).isEqualTo(terminalFromDays);
        assertThat(stats.currentPeriod().weaknessCount()).isEqualTo(weaknessFromDays);
        // ⚠️ 断言的是**区间汇总**，基线必须也是**区间**基线：
        //    `currentPeriod` 覆盖近 7 天，而「当日基线」只扣掉今天的行——
        //    本例特意造了 2 条「昨天」的 Run，用当日基线扣会多算。
        //    本例新增 4 条 Run：3 条终态 + 1 条在途
        assertThat(stats.currentPeriod().terminal() - baselineWindowTerminal).isEqualTo(3L);
        assertThat(stats.currentPeriod().total() - baselineWindowRuns).isEqualTo(4L);
    }

    @Test
    @DisplayName("✅ 知识点排行同时给「全历史」与「区间内」两个口径")
    void knowledgePointRankingHasBothScopes() {
        // 区间内命中 2 次
        String runId = seedRun("SUCCEEDED", 0);
        seedWeakness(runId, "庚", "计算失误", null, 0);
        seedWeakness(runId, "庚", "计算失误", null, 0);
        // 40 天前命中 5 次（落在 7 天窗口之外）
        String oldRun = seedRun("SUCCEEDED", 40);
        for (int i = 0; i < 5; i++) {
            seedWeakness(oldRun, "辛", "审题偏差", null, 40);
        }

        DailyStatistics stats = repository.daily(7, tenantA);

        // 全历史：辛（5 次）> 庚（2 次）
        KnowledgePointRank xin = stats.topKnowledgePoints().stream()
                .filter(k -> k.knowledgePoint().equals(kp("辛")))
                .findFirst().orElseThrow();
        KnowledgePointRank geng = stats.topKnowledgePoints().stream()
                .filter(k -> k.knowledgePoint().equals(kp("庚")))
                .findFirst().orElseThrow();
        assertThat(xin.count()).isEqualTo(5L);
        assertThat(geng.count()).isEqualTo(2L);

        // 区间内：辛一次都没有，只能看到庚
        KnowledgePointRank gengInRange = stats.topKnowledgePointsInRange().stream()
                .filter(k -> k.knowledgePoint().equals(kp("庚")))
                .findFirst().orElseThrow();
        assertThat(gengInRange.count()).isEqualTo(2L);
        assertThat(stats.topKnowledgePointsInRange().stream()
                .noneMatch(k -> k.knowledgePoint().equals(kp("辛"))))
                .as("⛔ 区间内排行不得混入窗口外的历史命中")
                .isTrue();
    }

    // -----------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------

    /** 取今天那一行。 */
    private static RunDayStat today(List<RunDayStat> rows) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return rows.stream().filter(r -> r.date().equals(today)).findFirst().orElseThrow();
    }

    /** 本 IT 专属的知识点名（带统一前缀，便于断言与清理）。 */
    private static String kp(String suffix) {
        return KP_PREFIX + suffix;
    }

    /** 造一条 Run（默认今天）。 */
    private static String seedRun(String state) {
        return seedRun(state, 0);
    }

    /**
     * 造一条 Run（指定状态与「创建于几天前」）。
     *
     * <p>⚠️ {@code created_at} 必须可控：统计按 {@code created_at} 归日，
     * 若夹具全落在今天，就无法验证「上一区间」「窗口之外」这两个命题。
     */
    private static String seedRun(String state, int daysAgo) {
        String runId = UUID.randomUUID().toString();
        // ⚠️ completed_at 必须与状态一致（DB CHECK run_completed_at_consistent）：
        //    终态必须有 completed_at，在途态必须为 NULL。
        // ⚠️ SUCCEEDED 还必须有 result_payload（DB CHECK run_result_consistent）。
        boolean terminal = !"QUEUED".equals(state) && !"RUNNING".equals(state);
        String completedAt = terminal ? ago(daysAgo) : "NULL";
        String resultPayload = "SUCCEEDED".equals(state)
                ? q("{\"answer\":\"ok\"}") + "::jsonb" : "NULL";
        adminExec("INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, request_digest, "
                + "state, attempt, input_payload, result_payload, correlation_id, created_at, updated_at, completed_at) "
                + "VALUES (" + q(runId) + "::uuid, " + q(tenantA) + "::uuid, " + q(snapshotA) + "::uuid, "
                + q(RUN_KEY_PREFIX + UUID.randomUUID()) + ", " + q("digest-" + UUID.randomUUID()) + ", "
                + q(state) + ", 1, " + q("{}") + "::jsonb, " + resultPayload
                + ", " + q("corr-" + UUID.randomUUID()) + ", " + ago(daysAgo)
                + ", " + ago(daysAgo) + ", " + completedAt + ")");
        // ⚠️ 自检：插入后立刻回读，确认这行**真的在库里**。
        //   （整类运行时曾出现「刚插入的 Run 随后被外键说成不存在」，
        //     若不立刻回读，失败现场会落在下游的 weakness 外键上，
        //     与真因（这行去哪了）相距很远。）
        String visible = adminQuery("SELECT run_id::text FROM run WHERE run_id = " + q(runId) + "::uuid");
        if (visible == null || visible.isBlank()) {
            throw new IllegalStateException("Run 插入后立刻回读不到（run_id=" + runId + "）");
        }
        return runId;
    }

    /** 造一条错因记录（今天、随机学生）。 */
    private static void seedWeakness(String runId, String kpSuffix, String errorType) {
        seedWeakness(runId, kpSuffix, errorType, null, 0);
    }

    /** 造一条错因记录（指定学生）。 */
    private static void seedWeakness(String runId, String kpSuffix, String errorType, String studentId) {
        seedWeakness(runId, kpSuffix, errorType, studentId, 0);
    }

    /** 造一条错因记录（指定「创建于几天前」）。 */
    private static void seedWeakness(String runId, String kpSuffix, String errorType,
                                     String studentId, int daysAgo) {
        String student = studentId != null
                ? studentId
                : STUDENT_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // ⚠️ weakness_record 有外键指向 student_profile（tenant_id, student_id），
        //    故必须先建画像——否则插入直接被 FK 拒绝。
        ensureStudentProfile(student, daysAgo);
        adminExec("INSERT INTO weakness_record (weakness_id, tenant_id, student_id, run_id, "
                + "knowledge_point, error_type, created_at, updated_at) VALUES ("
                + q(UUID.randomUUID().toString()) + "::uuid, " + q(tenantA) + "::uuid, " + q(student)
                + ", " + q(runId) + "::uuid, " + q(kp(kpSuffix)) + ", " + q(errorType)
                + ", " + ago(daysAgo) + ", " + ago(daysAgo) + ")");
    }

    /** 确保学生画像存在（weakness_record 的外键依赖）。 */
    private static void ensureStudentProfile(String studentId, int daysAgo) {
        String exists = adminQuery("SELECT student_id FROM student_profile WHERE tenant_id = "
                + q(tenantA) + "::uuid AND student_id = " + q(studentId));
        if (exists != null && !exists.isBlank()) {
            return;
        }
        adminExec("INSERT INTO student_profile (tenant_id, student_id, grade, subject, "
                + "created_at, updated_at) VALUES (" + q(tenantA) + "::uuid, " + q(studentId)
                + ", " + q("初二") + ", " + q("数学")
                + ", " + ago(daysAgo) + ", " + ago(daysAgo) + ")");
    }

    // -----------------------------------------------------------------
    // 基线统计（断言用增量，不用绝对值）
    // -----------------------------------------------------------------

    /** 某租户当日的 Run 总数。 */
    private static long countRuns(String tenantId) {
        return adminCount("SELECT count(*) FROM run WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC") + " >= " + utcToday() + " AND created_at AT TIME ZONE " + q("UTC") + " < " + utcToday() + " + interval " + q("1 day"));
    }

    /** 某租户当日的终态 Run 数。 */
    private static long countTerminalRuns(String tenantId) {
        return adminCount("SELECT count(*) FROM run WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC") + " >= " + utcToday() + " AND created_at AT TIME ZONE " + q("UTC") + " < " + utcToday() + " + interval " + q("1 day")
                + " AND state IN (" + q("SUCCEEDED") + ", " + q("FAILED") + ", "
                + q("TIMED_OUT") + ", " + q("CANCELLED") + ")");
    }

    /** 某租户当日的错因记录数。 */
    private static long countWeakness(String tenantId) {
        return adminCount("SELECT count(*) FROM weakness_record WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC") + " >= " + utcToday() + " AND created_at AT TIME ZONE " + q("UTC") + " < " + utcToday() + " + interval " + q("1 day"));
    }

    /** 某租户当日处于指定状态的 Run 数。 */
    private static long countRunsByState(String tenantId, String state) {
        return adminCount("SELECT count(*) FROM run WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC") + " >= " + utcToday()
                + " AND created_at AT TIME ZONE " + q("UTC") + " < " + utcToday()
                + " + interval " + q("1 day") + " AND state = " + q(state) + "::run_state");
    }

    /** 某租户当日的活跃学生数（去重）。 */
    private static long countActiveStudents(String tenantId) {
        return adminCount("SELECT count(DISTINCT student_id) FROM weakness_record WHERE tenant_id = "
                + q(tenantId) + "::uuid AND created_at AT TIME ZONE " + q("UTC") + " >= " + utcToday() + " AND created_at AT TIME ZONE " + q("UTC") + " < " + utcToday() + " + interval " + q("1 day"));
    }

    /** 统计窗口内的终态 Run 数。 */
    private static long countTerminalRunsInWindow(String tenantId, int days, int endOffsetDays) {
        long startOffset = (long) days + endOffsetDays;
        return adminCount("SELECT count(*) FROM run WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC")
                + " >= (" + utcToday() + " - interval " + q(startOffset + " days") + ")"
                + " AND created_at AT TIME ZONE " + q("UTC")
                + " < (" + utcToday() + " - interval " + q(endOffsetDays + " days") + ")"
                + " AND state IN (" + q("SUCCEEDED") + ", " + q("FAILED") + ", "
                + q("TIMED_OUT") + ", " + q("CANCELLED") + ")");
    }

    /**
     * 统计窗口内的 Run 数（用于环比基线）。
     *
     * @param tenantId      租户
     * @param days          窗口长度（天）
     * @param endOffsetDays 窗口**右边界**距今天的天数（左闭右开，负数表示未来）
     *                      · -1 → [今天-6, 今天+1) = 本区间
     *                      ·  6 → [今天-13, 今天-6) = 上一等长区间
     *
     * <p>⚠️ 基线必须在造数**之前**取：造数之后取的基线已含新数据。
     */
    private static long countRunsInWindow(String tenantId, int days, int endOffsetDays) {
        long startOffset = (long) days + endOffsetDays;
        return adminCount("SELECT count(*) FROM run WHERE tenant_id = " + q(tenantId)
                + "::uuid AND created_at AT TIME ZONE " + q("UTC")
                + " >= (" + utcToday() + " - interval " + q(startOffset + " days") + ")"
                + " AND created_at AT TIME ZONE " + q("UTC")
                + " < (" + utcToday() + " - interval " + q(endOffsetDays + " days") + ")");
    }

    // -----------------------------------------------------------------
    // 直连（绕过 RLS）的管理通道
    // -----------------------------------------------------------------

    /**
     * 把值包成 SQL 字符串字面量。
     *
     * <p>⚠️ **为什么要有这个辅助方法**：夹具 SQL 里全是单引号，
     * 与 Java 字符串的双引号紧挨着时极易写错落位
     * （写成 {@code " days'"} 就会变成「字符字面量未闭合」的**语法错误**，
     * 而不是一条能跑出断言失败的 SQL）。集中到一处后，SQL 里再不会出现裸引号。
     */
    private static String q(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 今日（UTC）的 SQL 表达式。
     *
     * <p>⚠️ **不能用 {@code current_date}**：那是 DB 会话所在时区的日期
     * （实测的远端 RDS 是 +08），而统计口径是 UTC——
     * 两者错开 8 小时，基线会把「昨天 UTC 16:00 到今天 UTC 16:00」
     * 整段算进来，断言全线漂移（实测 expected 5 but was 133）。
     * 故基线查询与生产查询**必须用同一个日期表达式**。
     */
    /**
     * 「今日（UTC）」的 SQL 条件片段。
     *
     * <p>⚠️ **必须是区间，不能用 {@code =}**：{@code created_at AT TIME ZONE 'UTC'}
     * 是 timestamp（无时区），与 date 比较时会**按「恰好等于当天零点」**判定，
     * 而非「落在当天」——实测基线恒为 0，断言全线漂移
     * （expected 5 but was 176：176 是全量，基线却是 0）。
     *
     * <p>⚠️ 也**不能用 {@code current_date}**：那是 DB 会话所在时区的日期
     * （实测的远端 RDS 是 +08），与统计口径的 UTC 错开 8 小时。
     * 故基线查询与生产查询**必须用同一个日期表达式**。
     */
    private static String utcToday() {
        return "(now() AT TIME ZONE " + q("UTC") + ")::date";
    }

    /** 生成「几天前」的时间表达式。 */
    private static String ago(int daysAgo) {
        return "now() - interval " + q(daysAgo + " days");
    }

    /**
     * 管理员连接配置（造夹具用）。
     *
     * <p>⚠️ <b>必须是独立的管理员账号</b>：应用账号（app_agent）受
     * <b>强制 RLS</b> 约束，{@code run} / {@code weakness_record} 等表的
     * 插入会被 {@code WITH CHECK (tenant_id = current_tenant_id())} 拒绝
     * ——而管理员通道没有会话变量，{@code current_tenant_id()} 返回 NULL，
     * 插入被**静默丢弃**（不报错）。
     * 实测（2026-10-23）：用应用账号造夹具时，种了 3 条 Run 只查得到 2 条，
     * 断言以「expected 3 but was 2」失败，现象与「统计逻辑漏算」高度相似，
     * 极易误判为产品缺陷。故夹具写入一律走管理员通道。
     *
     * <p>⚠️ 口令：本地 trust 认证无需口令；密口认证的库（RDS / CI）必须
     * 显式给 {@code IT_PG_ADMIN_PASSWORD}，否则连接失败
     * （作用域级隔离可能被误读为「失效」）。
     */
    private static HikariConfig adminConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        return cfg;
    }

    /**
     * 执行一条管理通道的写语句。
     *
     * <p>⚠️ **显式事务 + 显式提交**（2026-10-23 实测修正）：
     * 原实现依赖连接的自动提交，而连接来自「每次调用新建一个连接池」的临时池，
     * 连接被 close 时**未提交的事务会被回滚** → 夹具「写了但没写进去」。
     * 症状极难定位：插入不报错、随后外键却说「这行不存在」
     * （实测整类运行失败、单跑却通过——典型时序型偶发）。
     * 改成 {@code autoCommit=false} + 显式 {@code commit()} 后行为确定。
     */
    private static void adminExec(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    private static String adminQuery(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out.isEmpty() ? null : out.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    /** 取单值计数。 */
    private static long adminCount(String sql) {
        String value = adminQuery(sql);
        return value == null ? 0L : Long.parseLong(value.trim());
    }
}
