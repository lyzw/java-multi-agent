package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** cap_decision_log 决策审计（3 SQL）。 */
public interface DecisionAuditMapper {
    int insert(@Param("tenantId") String tenantId, @Param("runId") String runId,
                @Param("invocationId") String invocationId, @Param("authzDecision") String authzDecision,
                @Param("execDecision") String execDecision, @Param("execStatus") String execStatus,
                @Param("ledgerStatus") String ledgerStatus, @Param("reasonCode") String reasonCode,
                @Param("authzVersion") Long authzVersion, @Param("closureFingerprint") String closureFingerprint,
                @Param("configPublishSeq") Long configPublishSeq, @Param("releaseId") Long releaseId,
                @Param("principalId") String principalId, @Param("toolId") String toolId,
                @Param("decisionKind") String decisionKind, @Param("decisionAt") Instant decisionAt);
    AggregateRow aggregate(@Param("tenantId") String tenantId);
    List<DecisionLogRow> findByInvocation(@Param("tenantId") String tenantId,
                                          @Param("invocationId") String invocationId);
    List<DecisionLogRow> findByRun(@Param("tenantId") String tenantId, @Param("runId") String runId);
    List<DecisionLogRow> list(@Param("tenantId") String tenantId, @Param("limit") int limit);

    record AggregateRow(long total, long invocations, long blockedByAuthz, long blockedByQuota,
                        long reconciled, long errors) {}
    record DecisionLogRow(String decisionId, String tenantId, String runId, String invocationId,
                          String authzDecision, String execDecision, String execStatus, String ledgerId,
                          String decisionKind, Instant decisionAt) {}
}
