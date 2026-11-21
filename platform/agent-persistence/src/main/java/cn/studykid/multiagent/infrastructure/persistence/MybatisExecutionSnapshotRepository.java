package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ExecutionSnapshotMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort.SnapshotRef;

/** ExecutionSnapshotPort MyBatis 实现（单 SQL JOIN 6 表）。 */
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisExecutionSnapshotRepository implements ExecutionSnapshotPort {

    private final ExecutionSnapshotMapper mapper;
    private final DataSource dataSource;

    public MybatisExecutionSnapshotRepository(@NonNull ExecutionSnapshotMapper mapper,
                                              @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<SnapshotRef> resolveSnapshot(@NonNull String tenantId, @NonNull String agentId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.resolve(tenantId, agentId).map(
                            r -> new SnapshotRef(r.snapshotId(), r.versionNumber())));
        } finally { TenantScope.clear(); }
    }
}
