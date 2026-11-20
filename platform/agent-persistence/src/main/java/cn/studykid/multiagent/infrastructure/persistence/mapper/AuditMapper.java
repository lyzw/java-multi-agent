package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** audit_record 写入 + 查询。 */
public interface AuditMapper {
    List<AuditViewRow> findByResource(@Param("tenantId") String tenantId,
                                       @Param("resourceType") String resourceType,
                                       @Param("resourceId") String resourceId);
    Optional<String> insert(@Param("tenantId") String tenantId,
                             @Param("actorId") String actorId,
                             @Param("action") String action,
                             @Param("resourceType") String resourceType,
                             @Param("resourceId") String resourceId,
                             @Param("detailJson") String detailJson);

    record AuditViewRow(String tenantId, String auditId, String actorId, String action,
                        String resourceType, String resourceId, java.time.Instant createdAt) {}
}
