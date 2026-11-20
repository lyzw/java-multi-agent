package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** cap_approval 审批（21 列 + CAS 状态机）。 */
public interface CapApprovalMapper {

    Optional<ApprovalRow> findById(@Param("tenantId") String tenantId,
                                    @Param("approvalId") String approvalId);
    List<ApprovalRow> findByTool(@Param("tenantId") String tenantId, @Param("toolId") String toolId);
    List<ApprovalRow> findByInvocation(@Param("tenantId") String tenantId,
                                        @Param("invocationId") String invocationId);

    Optional<String> insertPending(@Param("tenantId") String tenantId, @Param("principalId") String principalId,
                                    @Param("toolId") String toolId, @Param("targetResource") String targetResource,
                                    @Param("approvalScopeHash") String approvalScopeHash,
                                    @Param("scopeDefinition") String scopeDefinition,
                                    @Param("effectiveArgsHash") String effectiveArgsHash,
                                    @Param("closureFingerprint") String closureFingerprint,
                                    @Param("authzVersion") long authzVersion,
                                    @Param("approverId") String approverId,
                                    @Param("approverScope") String approverScope,
                                    @Param("decidedAt") Instant decidedAt,
                                    @Param("expiresAt") Instant expiresAt,
                                    @Param("createdAt") Instant createdAt,
                                    @Param("updatedAt") Instant updatedAt);

    Optional<String> insertAwaitingHuman(@Param("tenantId") String tenantId,
                                          @Param("principalId") String principalId,
                                          @Param("toolId") String toolId,
                                          @Param("targetResource") String targetResource,
                                          @Param("approvalScopeHash") String approvalScopeHash,
                                          @Param("scopeDefinition") String scopeDefinition,
                                          @Param("effectiveArgsHash") String effectiveArgsHash,
                                          @Param("closureFingerprint") String closureFingerprint,
                                          @Param("authzVersion") long authzVersion,
                                          @Param("approverScope") String approverScope,
                                          @Param("expiresAt") Instant expiresAt,
                                          @Param("createdAt") Instant createdAt,
                                          @Param("updatedAt") Instant updatedAt);

    int reserve(@Param("invocationId") String invocationId, @Param("operationId") String operationId,
                 @Param("runId") String runId, @Param("tenantId") String tenantId,
                 @Param("approvalId") String approvalId, @Param("effectiveArgsHash") String effectiveArgsHash,
                 @Param("closureFingerprint") String closureFingerprint,
                 @Param("authzVersion") long authzVersion);

    int markIntent(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                    @Param("invocationId") String invocationId);
    int markIntentIdempotent(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                              @Param("invocationId") String invocationId);
    int consume(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                 @Param("invocationId") String invocationId, @Param("terminalStatus") String terminalStatus);
    int consumeIdempotent(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                           @Param("invocationId") String invocationId);
    int cancel(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                @Param("invocationId") String invocationId, @Param("reason") String reason);

    record ApprovalRow(String approvalId, String tenantId, String principalId, String runId,
                       String invocationId, String operationId, String toolId, String targetResource,
                       String approvalScopeHash, String scopeDefinition, String effectiveArgsHash,
                       String closureFingerprint, long authzVersion, String approverId,
                       String approverScope, Instant decidedAt, Instant expiresAt, String approvalState,
                       String terminalStatus, String cancelReason, Instant createdAt, Instant updatedAt) {}
}
