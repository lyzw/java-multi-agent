package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** 统计聚合（to_char AT TIME ZONE / count / count DISTINCT）。 */
public interface StatisticsMapper {

    List<RunDayRow> runByDay(@Param("tenantId") String tenantId,
                             @Param("from") Instant from, @Param("to") Instant to);
    List<DayCountRow> weaknessByDay(@Param("tenantId") String tenantId,
                                     @Param("from") Instant from, @Param("to") Instant to);
    List<DayCountRow> activeStudentsByDay(@Param("tenantId") String tenantId,
                                           @Param("from") Instant from, @Param("to") Instant to);
    List<DayCountRow> newStudentsByDay(@Param("tenantId") String tenantId,
                                        @Param("from") Instant from, @Param("to") Instant to);
    List<KnowledgePointRow> knowledgePointRank(@Param("tenantId") String tenantId,
                                                @Param("from") Instant from, @Param("to") Instant to,
                                                @Param("limit") int limit);

    record RunDayRow(String day, String state, long count) {}
    record DayCountRow(String day, long count) {}
    record KnowledgePointRow(String knowledgePoint, String errorType, long count) {}
}
