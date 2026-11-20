package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformAuditMapper;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;

/** PlatformAuditPort MyBatis 实现（platform schema，仅追加）。 */
@Repository
@Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisPlatformAuditRepository implements PlatformAuditPort {

    private final PlatformAuditMapper mapper;

    public MybatisPlatformAuditRepository(@NonNull PlatformAuditMapper mapper,
                                           @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
    }

    @Override
    public void record(@NonNull PlatformAuditEvent event) {
        mapper.insert(event.id(), toUuid(event.actorId(), "actorId"), event.actorType(), event.action(),
                event.targetType(), toUuid(event.targetId(), "targetId"), event.decision(),
                event.reasonCode(), event.correlationId(), event.ip(), event.occurredAt(),
                event.ticketScope(), event.capability(), event.channel());
    }

    @Override
    public @NonNull List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery q) {
        return mapper.query(q.actorId(), q.action(), q.targetType(), q.targetId(),
                        q.from(), q.to(), q.limit())
                .stream()
                .map(r -> new PlatformAuditEvent(r.id(), str(r.actorId()), r.actorType(), r.action(),
                        r.targetType(), str(r.targetId()), r.decision(), r.reasonCode(),
                        r.correlationId(), r.ip(), r.occurredAt(), r.ticketScope(),
                        r.capability(), r.channel()))
                .toList();
    }

    private static UUID toUuid(String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return UUID.fromString(v);
    }
    private static String str(UUID v) { return v == null ? null : v.toString(); }
}
