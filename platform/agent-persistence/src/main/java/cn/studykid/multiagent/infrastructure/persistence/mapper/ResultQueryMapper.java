package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** Run 结果聚合查询。 */
public interface ResultQueryMapper {
    RunRow readRun(@Param("tenantId") String tenantId, @Param("runId") String runId);
    List<WeaknessRow> weaknesses(@Param("tenantId") String tenantId, @Param("runId") String runId);
    List<StepRow> steps(@Param("tenantId") String tenantId, @Param("runId") String runId);
    List<ToolOpRow> unknownTools(@Param("tenantId") String tenantId, @Param("runId") String runId);

    record RunRow(String runId, String tenantId, String state,
                  Instant completedAt, String errorCode, String explanation) {}
    record WeaknessRow(String knowledgePoint, String errorType, double confidence) {}
    record StepRow(int stepNumber, String state, String stepType, String output) {}
    record ToolOpRow(String operationId, String toolName, String state) {}
}
