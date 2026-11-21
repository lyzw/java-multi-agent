package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformAuditMapper;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;

/**
 * PlatformAuditPort MyBatis 实现（platform schema，仅追加）。
 *
 * <p>⚠️ Mapper 绑定在 {@code opsSqlSessionFactory}，只有「MyBatis + 平台运维数据源」
 * 双开关同时打开时才生效；否则必须退回 Jdbc 实现。
 */
@Repository
@ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
        + "&& '${agent.platform-ops.datasource.enabled:false}'.equals('true')")
public class MybatisPlatformAuditRepository implements PlatformAuditPort {

    private final PlatformAuditMapper mapper;

    public MybatisPlatformAuditRepository(@NonNull PlatformAuditMapper mapper) {
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
