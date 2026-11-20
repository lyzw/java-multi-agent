package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** chat_audit_record ∪ chat_audit_unattributed 联合查询。 */
public interface ChatAuditRecordQueryMapper {

    List<SummaryRow> list(@Param("tenantId") String tenantId, @Param("endpoint") String endpoint,
                           @Param("terminalState") String terminalState,
                           @Param("from") Instant from, @Param("to") Instant to,
                           @Param("includeRecord") boolean includeRecord,
                           @Param("includeUnattributed") boolean includeUnattributed,
                           @Param("limit") int limit);

    DetailRow detailRecord(@Param("auditId") String auditId);
    DetailRow detailUnattributed(@Param("auditId") String auditId);

    record SummaryRow(String auditId, String traceId, String source, String tenantId, String domain,
                      String principalId, String endpoint, Instant startedAt, Instant completedAt,
                      Long durationMs, String terminalState, String httpMethod, Integer httpStatus,
                      String messagePreview, String rejectionReason) {}
    record DetailRow(String auditId, String traceId, String tenantId, String domain,
                     String principalId, String endpoint, Instant startedAt, Instant completedAt,
                     Long durationMs, String terminalState, String httpMethod, Integer httpStatus,
                     Long configVersion, String rejectionReason, String payloadJson) {}
}
