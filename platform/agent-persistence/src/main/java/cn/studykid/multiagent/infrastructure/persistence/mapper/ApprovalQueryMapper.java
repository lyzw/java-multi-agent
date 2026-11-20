package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** approval 查询 + 决定。 */
public interface ApprovalQueryMapper {
    List<ApprovalRow> list(@Param("tenantId") String tenantId,
                           @Param("state") String state,
                           @Param("cursorApprovalId") String cursorApprovalId,
                           @Param("limit") int limit);
    Optional<ApprovalRow> find(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId);
    int decide(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
               @Param("newState") String newState, @Param("approverId") String approverId);

    record ApprovalRow(String approvalId, String operationId, String state,
                       String requirement, String argumentDigest, String targetResource,
                       String approverScope, String approverId, String tenantId, String runId,
                       java.time.Instant expiresAt, java.time.Instant decidedAt,
                       java.time.Instant createdAt) {}
}
