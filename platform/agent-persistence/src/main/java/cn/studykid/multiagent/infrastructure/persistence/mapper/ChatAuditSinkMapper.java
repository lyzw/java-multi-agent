package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** public.chat_audit_record / chat_audit_unattributed 写入。 */
public interface ChatAuditSinkMapper {
    int writeAttributed(@Param("auditId") String auditId, @Param("traceId") String traceId,
                        @Param("tenantId") String tenantId, @Param("domain") String domain,
                        @Param("endpoint") String endpoint, @Param("principalId") String principalId,
                        @Param("startedAt") Instant startedAt, @Param("completedAt") Instant completedAt,
                        @Param("recordJson") String recordJson);
    int writeUnattributed(@Param("auditId") String auditId, @Param("traceId") String traceId,
                           @Param("startedAt") Instant startedAt, @Param("endpoint") String endpoint,
                           @Param("httpMethod") String httpMethod, @Param("httpStatus") Integer httpStatus,
                           @Param("recordJson") String recordJson);
}
