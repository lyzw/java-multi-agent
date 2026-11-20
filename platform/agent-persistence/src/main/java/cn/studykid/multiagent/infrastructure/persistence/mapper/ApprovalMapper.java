package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** approval 审批写端 + 状态查询（8 SQL）。 */
public interface ApprovalMapper {
    /** INSERT approval (new) */
    int insertApproval(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                        @Param("requirement") String requirement, @Param("argumentDigest") String argumentDigest,
                        @Param("targetResource") String targetResource, @Param("approverScope") String approverScope,
                        @Param("approverId") String approverId, @Param("expiresAt") Instant expiresAt);
    /** INSERT approval (existing operation) —— 复用既有 approval_id */
    Optional<String> insertApprovalExistingOp(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                                               @Param("requirement") String requirement, @Param("argumentDigest") String argumentDigest,
                                               @Param("targetResource") String targetResource, @Param("approverScope") String approverScope,
                                               @Param("expiresAt") Instant expiresAt);
    /** UPDATE SET state='REJECTED', approver_id=?, decided_at=now() */
    int rejectApproval(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                         @Param("approverId") String approverId);
    /** SELECT FROM approval WHERE tenant_id + operation_id */
    Optional<ApprovalRow> findApproval(@Param("tenantId") String tenantId, @Param("operationId") String operationId);
    /** SELECT run state FOR UPDATE（lockRun） */
    Optional<RunStateRow> lockRunState(@Param("tenantId") String tenantId, @Param("runId") String runId);
    /** UPDATE SET state='CONSUMED', consumed_at=now() */
    int consumeApproval(@Param("tenantId") String tenantId, @Param("operationId") String operationId);
    /** UPDATE SET state='EXPIRED', decided_at=now() */
    int expireApproval(@Param("tenantId") String tenantId, @Param("operationId") String operationId);

    record ApprovalRow(String tenantId, String approvalId, String operationId, String state,
                       String requirement, String argumentDigest, String targetResource,
                       String approverScope, Instant expiresAt, String approverId,
                       Instant decidedAt, Instant createdAt) {}
    record RunStateRow(String state, Instant cancelRequestedAt) {}
}
