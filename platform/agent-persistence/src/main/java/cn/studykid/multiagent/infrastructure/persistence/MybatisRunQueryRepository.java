package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunQueryPort;
import cn.studykid.multiagent.ports.persistence.RunQueryPort.RunStatus;

@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunQueryRepository implements RunQueryPort {

    private final RunQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisRunQueryRepository(@NonNull RunQueryMapper mapper,
                                     @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @Nullable RunStatus findStatus(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findById(tenantId, runId).map(r -> new RunStatus(
                            r.getRunId(), r.getState(), r.getCreatedAt(), r.getCompletedAt(),
                            r.getAttempt(), r.getErrorCode(), r.getRequesterId())).orElse(null));
        } finally {
            TenantScope.clear();
        }
    }
}
