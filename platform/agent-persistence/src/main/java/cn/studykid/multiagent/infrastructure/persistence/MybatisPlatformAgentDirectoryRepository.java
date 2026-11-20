package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformAgentDirectoryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort.PlatformAgentRow;

/** PlatformAgentDirectoryPort MyBatis 实现（opsSqlSessionFactory + LATERAL JOIN）。 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisPlatformAgentDirectoryRepository implements PlatformAgentDirectoryPort {

    private final PlatformAgentDirectoryMapper mapper;
    private final DataSource dataSource;

    public MybatisPlatformAgentDirectoryRepository(@NonNull PlatformAgentDirectoryMapper mapper,
                                                    @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<PlatformAgentRow> listAgents(@Nullable String tenantIdFilter) {
        // ops 查询用哨兵 tenant（绕过 RLS tenant=target 限制）
        TenantScope.bind("00000000-0000-0000-0000-000000000000");
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext("00000000-0000-0000-0000-000000000000", "ops", Set.of(), 0L),
                    conn -> mapper.listAgents(tenantIdFilter).stream()
                            .map(r -> new PlatformAgentRow(r.tenantId(), r.agentId(), r.name(),
                                    r.description(), r.versionCount(), r.latestVersionNumber(),
                                    r.latestPublishedAt(), null, null,
                                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                                    false, null, java.util.List.of(), false, false, null, r.tenantCount()))
                            .toList());
        } finally { TenantScope.clear(); }
    }
}
