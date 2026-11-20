package cn.studykid.multiagent.infrastructure.persistence;

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

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunEventQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort.RunEvent;

@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunEventQueryRepository implements RunEventQueryPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RunEventQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisRunEventQueryRepository(@NonNull RunEventQueryMapper mapper,
                                          @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<RunEvent> findAfter(@NonNull String tenantId, @NonNull String runId,
                                              long afterSequenceNo, int limit) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findAfter(tenantId, runId, (int) afterSequenceNo, Math.max(1, Math.min(limit, 200)))
                            .stream().map(this::toEvent).toList());
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @Nullable String findState(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.readRunState(tenantId, runId)
                            .map(RunEventQueryMapper.RunStateRow::getState).orElse(null));
        } finally {
            TenantScope.clear();
        }
    }

    private RunEvent toEvent(RunEventQueryMapper.RunEventRow r) {
        Map<String, Object> payload;
        try {
            payload = JSON.readValue(r.getPayload(), new TypeReference<>() {});
        } catch (Exception e) {
            payload = Map.of();
        }
        return new RunEvent(r.getSequenceNo(), r.getEventType(), payload, r.getCreatedAt());
    }
}
