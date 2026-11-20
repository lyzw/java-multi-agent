package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** AgentChat 审计专用写入（run / run_event / run_step，fencing CAS）。 */
public interface AgentChatAuditMapper {
    int insertRun(@Param("runId") String runId, @Param("tenantId") String tenantId,
                   @Param("snapshotId") String snapshotId, @Param("idempotencyKey") String idempotencyKey,
                   @Param("requestDigest") String requestDigest, @Param("fencingToken") long fencingToken,
                   @Param("leaseOwner") String leaseOwner, @Param("leaseExpiresAt") Instant leaseExpiresAt,
                   @Param("inputPayloadJson") String inputPayloadJson,
                   @Param("correlationId") String correlationId);
    int insertRunEvent(@Param("tenantId") String tenantId, @Param("runId") String runId,
                        @Param("sequenceNo") int sequenceNo, @Param("eventType") String eventType,
                        @Param("payloadJson") String payloadJson);
    int finishRun(@Param("tenantId") String tenantId, @Param("runId") String runId,
                   @Param("state") String state, @Param("completedAt") Instant completedAt,
                   @Param("resultPayloadJson") String resultPayloadJson, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage, @Param("fencingToken") long fencingToken);
    int insertRunStep(@Param("tenantId") String tenantId, @Param("runId") String runId,
                       @Param("stepNumber") int stepNumber, @Param("stepType") String stepType,
                       @Param("fencingToken") long fencingToken, @Param("startedAt") Instant startedAt,
                       @Param("completedAt") Instant completedAt);
}
