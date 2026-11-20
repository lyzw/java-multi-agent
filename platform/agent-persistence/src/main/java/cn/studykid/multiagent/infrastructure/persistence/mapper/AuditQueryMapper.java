package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** audit_record 审计查询（opsSqlSessionFactory）。 */
public interface AuditQueryMapper {
    List<AuditEntryRow> query(@Param("tenantId") String tenantId,
                              @Param("action") String action,
                              @Param("resourceType") String resourceType,
                              @Param("resourceId") String resourceId,
                              @Param("from") Instant from,
                              @Param("to") Instant to);

    record AuditEntryRow(String tenantId, String auditId, String actorId, String action,
                         String resourceType, String resourceId, String correlationId,
                         Instant createdAt, String detail) {}
}
