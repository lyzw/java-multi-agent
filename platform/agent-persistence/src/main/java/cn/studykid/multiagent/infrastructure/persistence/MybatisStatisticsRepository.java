package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.StatisticsMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.statistics.StatisticsPort;

/** StatisticsPort MyBatis 实现（daily 聚合装配）。 */
@Repository
@Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisStatisticsRepository implements StatisticsPort {

    private final StatisticsMapper mapper;
    private final DataSource dataSource;

    public MybatisStatisticsRepository(@NonNull StatisticsMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull DailyStatistics daily(long days, @NonNull String tenantId) {
        Instant to = Instant.now();
        Instant from = to.minus(days, ChronoUnit.DAYS);
        return inTx(tenantId, () -> {
            // 按日聚合 → 按天归并 state 维度
            Map<String, List<StatisticsMapper.RunDayRow>> runByDay = mapper.runByDay(tenantId, from, to)
                    .stream().collect(Collectors.groupingBy(StatisticsMapper.RunDayRow::day));
            List<RunDayStat> runs = runByDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> {
                        long total = e.getValue().stream().mapToLong(StatisticsMapper.RunDayRow::count).sum();
                        long succeeded = sumState(e.getValue(), "SUCCEEDED");
                        long failed = sumState(e.getValue(), "FAILED");
                        long cancelled = sumState(e.getValue(), "CANCELLED");
                        long timedOut = sumState(e.getValue(), "TIMED_OUT");
                        long terminal = succeeded + failed + cancelled + timedOut;
                        return new RunDayStat(e.getKey(), total, terminal, succeeded, failed, timedOut, cancelled);
                    }).toList();

            List<WeaknessDayStat> weaknesses = mapper.weaknessByDay(tenantId, from, to).stream()
                    .map(r -> new WeaknessDayStat(r.day(), r.count())).toList();
            Map<String, Long> active = mapper.activeStudentsByDay(tenantId, from, to).stream()
                    .collect(Collectors.toMap(StatisticsMapper.DayCountRow::day, StatisticsMapper.DayCountRow::count));
            Map<String, Long> created = mapper.newStudentsByDay(tenantId, from, to).stream()
                    .collect(Collectors.toMap(StatisticsMapper.DayCountRow::day, StatisticsMapper.DayCountRow::count));
            List<StudentDayStat> students = active.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new StudentDayStat(e.getKey(), e.getValue(),
                            created.getOrDefault(e.getKey(), 0L))).toList();

            // 知识点 Top：按 knowledge_point 归并 errorTypes
            Map<String, Map<String, Long>> kp = new java.util.LinkedHashMap<>();
            mapper.knowledgePointRank(tenantId, from, to, 20).forEach(r ->
                    kp.computeIfAbsent(r.knowledgePoint(), k -> new java.util.LinkedHashMap<>())
                            .merge(r.errorType(), r.count(), Long::sum));
            List<KnowledgePointRank> top = kp.entrySet().stream()
                    .map(e -> new KnowledgePointRank(e.getKey(),
                            e.getValue().values().stream().mapToLong(Long::longValue).sum(), e.getValue()))
                    .sorted((a, b) -> Long.compare(b.count(), a.count()))
                    .toList();

            long totalRuns = runs.stream().mapToLong(RunDayStat::total).sum();
            long totalTerminal = runs.stream().mapToLong(RunDayStat::terminal).sum();
            long totalSucceeded = runs.stream().mapToLong(RunDayStat::succeeded).sum();
            long totalFailed = runs.stream().mapToLong(RunDayStat::failed).sum();
            long totalTimedOut = runs.stream().mapToLong(RunDayStat::timedOut).sum();
            long totalCancelled = runs.stream().mapToLong(RunDayStat::cancelled).sum();
            PeriodSummary current = new PeriodSummary(totalRuns, totalTerminal, totalSucceeded,
                    totalFailed, totalTimedOut, totalCancelled,
                    weaknesses.stream().mapToLong(WeaknessDayStat::count).sum(),
                    students.stream().mapToLong(StudentDayStat::activeStudents).sum(),
                    students.stream().mapToLong(StudentDayStat::newStudents).sum());
            PeriodSummary empty = new PeriodSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);

            return new DailyStatistics(runs, weaknesses, students, top, List.of(),
                    (int) days, current, empty);
        });
    }

    private static long sumState(List<StatisticsMapper.RunDayRow> rows, String state) {
        return rows.stream().filter(r -> state.equals(r.state()))
                .mapToLong(StatisticsMapper.RunDayRow::count).sum();
    }

    private <T> T inTx(String tenantId, java.util.function.Supplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L), conn -> fn.get());
        } finally { TenantScope.clear(); }
    }
}
