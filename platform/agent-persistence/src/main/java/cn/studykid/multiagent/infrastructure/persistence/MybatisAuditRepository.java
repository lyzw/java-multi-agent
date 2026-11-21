package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AuditMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/** AuditPort MyBatis 实现（INSERT RETURNING audit_id::text）。 */
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAuditRepository implements AuditPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisAuditRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditMapper mapper;
    private final DataSource dataSource;

    public MybatisAuditRepository(@NonNull AuditMapper mapper,
                                  @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public void recordRequired(@NonNull String tenantId, @NonNull String actorId,
                                @NonNull String action, @NonNull String resourceType,
                                @NonNull String resourceId, @NonNull Map<String, Object> detail) {
        write(tenantId, actorId, action, resourceType, resourceId, detail);
    }

    @Override
    public void recordAccess(@NonNull String tenantId, @NonNull String actorId,
                               @NonNull String action, @NonNull String resourceType,
                               @NonNull String resourceId, @NonNull Map<String, Object> detail) {
        write(tenantId, actorId, action, resourceType, resourceId, detail);
    }

    private void write(String tenantId, String actorId, String action, String resourceType,
                       String resourceId, Map<String, Object> detail) {
        try {
            TenantScope.bind(tenantId);
            try {
                String detailJson = JSON.writeValueAsString(detail);
                TransactionalConnectionHolder.inTransaction(
                        dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                        conn -> { mapper.insert(tenantId, actorId, action, resourceType, resourceId, detailJson); return null; });
            } finally { TenantScope.clear(); }
        } catch (Exception e) {
            log.warn("⚠️ 审计写入失败: tenant={}, action={}, cause={}", tenantId, action, e.toString());
        }
    }

    @Override
    public @NonNull List<AuditView> findByResource(@NonNull String tenantId, @NonNull String resourceType,
                                                     @Nullable String resourceId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findByResource(tenantId, resourceType, resourceId).stream()
                            .map(r -> new AuditView(r.auditId(), r.actorId(), r.action(),
                                    r.resourceType(), r.resourceId(), r.createdAt()))
                            .toList());
        } finally { TenantScope.clear(); }
    }
}
