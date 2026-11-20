package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** run_step 写入（ON CONFLICT 幂等覆盖）。 */
public interface RunStepMapper {
    int insertUpsert(@Param("tenantId") String tenantId,
                     @Param("runId") String runId,
                     @Param("stepNumber") int stepNumber,
                     @Param("stepType") String stepType,
                     @Param("fencingToken") long fencingToken,
                     @Param("startedAt") Instant startedAt,
                     @Param("completedAt") Instant completedAt);
}
