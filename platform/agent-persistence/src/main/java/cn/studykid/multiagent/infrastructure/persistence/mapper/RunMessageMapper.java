package cn.studykid.multiagent.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/** run_message 写入（ON CONFLICT 幂等覆盖）。 */
public interface RunMessageMapper {
    int insertUpsert(@Param("tenantId") String tenantId,
                     @Param("runId") String runId,
                     @Param("stepNumber") int stepNumber,
                     @Param("role") String role,
                     @Param("contentJson") String contentJson,
                     @Param("toolOperationId") String toolOperationId,
                     @Param("modelId") String modelId,
                     @Param("inputTokens") Long inputTokens,
                     @Param("outputTokens") Long outputTokens,
                     @Param("latencyMs") Long latencyMs,
                     @Param("fencingToken") long fencingToken,
                     @Param("idemKey") String idemKey);
}
