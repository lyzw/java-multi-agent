package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

/** platform.platform_audit_event 平台审计（仅追加）。 */
public interface PlatformAuditMapper {

    int insert(@Param("id") UUID id, @Param("actorId") UUID actorId,
               @Param("actorType") String actorType, @Param("action") String action,
               @Param("targetType") String targetType, @Param("targetId") UUID targetId,
               @Param("decision") String decision, @Param("reasonCode") String reasonCode,
               @Param("correlationId") UUID correlationId, @Param("ip") String ip,
               @Param("occurredAt") Instant occurredAt, @Param("ticketScope") String ticketScope,
               @Param("capability") String capability, @Param("channel") String channel);

    List<AuditEventRow> query(@Param("actorId") String actorId, @Param("action") String action,
                              @Param("targetType") String targetType, @Param("targetId") String targetId,
                              @Param("from") Instant from, @Param("to") Instant to,
                              @Param("limit") int limit);

    record AuditEventRow(UUID id, UUID actorId, String actorType, String action,
                         String targetType, UUID targetId, String decision, String reasonCode,
                         UUID correlationId, String ip, Instant occurredAt,
                         String ticketScope, String capability, String channel) {}
}
