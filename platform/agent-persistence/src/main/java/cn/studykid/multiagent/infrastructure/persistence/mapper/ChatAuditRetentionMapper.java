package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** public.chat_audit_record 保留期清理（ctid 子查询批量删）。 */
public interface ChatAuditRetentionMapper {
    int purgeExpired(@Param("tenantId") String tenantId, @Param("cutoff") Instant cutoff,
                      @Param("limit") int limit);
}
