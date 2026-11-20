package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AuditQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;

/** AuditQueryPort MyBatis 实现（opsSqlSessionFactory）。 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAuditQueryRepository implements AuditQueryPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisAuditQueryRepository(@NonNull AuditQueryMapper mapper,
                                       @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<AuditEntry> query(@NonNull String tenantId,
                                             @Nullable String action, @Nullable String resourceType,
                                             @Nullable String resourceId,
                                             @Nullable Instant from, @Nullable Instant to,
                                             @Nullable String cursor, int limit) {
        TenantScope.bind(tenantId);
        try {
            List<AuditQueryMapper.AuditEntryRow> rows = TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.query(tenantId, action, resourceType, resourceId, from, to));
            List<AuditEntry> items = rows.stream().limit(limit).map(this::toEntry).toList();
            String nextCursor = null;
            if (rows.size() > limit && !items.isEmpty()) {
                nextCursor = new PageCursor(items.get(items.size()-1).createdAt(),
                        items.get(items.size()-1).auditId()).encode();
            }
            return new Page<>(items, nextCursor);
        } finally { TenantScope.clear(); }
    }

    private AuditEntry toEntry(AuditQueryMapper.AuditEntryRow r) {
        Map<String, Object> detailMap;
        try {
            detailMap = JSON.readValue(r.detail(), new TypeReference<>() {});
        } catch (Exception e) {
            detailMap = Map.of();
        }
        return new AuditEntry(r.auditId(), r.actorId(), r.action(), r.resourceType(),
                r.resourceId(), r.createdAt(), r.correlationId(), detailMap);
    }
}
