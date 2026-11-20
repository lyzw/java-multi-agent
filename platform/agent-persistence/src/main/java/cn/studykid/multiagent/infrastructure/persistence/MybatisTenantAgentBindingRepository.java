package cn.studykid.multiagent.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort.BindingNotFoundException;
import cn.studykid.multiagent.infrastructure.persistence.mapper.TenantAgentBindingMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.TenantAgentBindingDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort;

/**
 * 租户-Agent 绑定 MyBatis-Plus 仓储（W2 迁移，对应 JdbcTenantAgentBindingRepository）。
 *
 * <p>tenant_agent_binding FORCE RLS：所有方法先 TenantScope.bind(tenantId)，
 * 拦截器保证 set_config 在业务 SQL 前；写操作走 TransactionalConnectionHolder.inTransaction
 * 复用外层连接，commit 由外层决定（TenantBoundTransactionFactory 借外层连接、no-op commit）。
 *
 * <p>⚠️ 审计的豁免键保留：agentId 作为不透明 UUID，审计里仍用 Map.of("resourceId", agentId)
 * 走 VALUE_SCAN_EXEMPT_KEYS，避免学号误判（原实现里的 PII 扫描缺陷）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisTenantAgentBindingRepository implements TenantAgentBindingPort {

    private final TenantAgentBindingMapper mapper;
    private final DataSource dataSource;
    private final AuditPort audit;

    public MybatisTenantAgentBindingRepository(@NonNull TenantAgentBindingMapper mapper,
                                               @Qualifier("dataSource") @NonNull DataSource dataSource,
                                               @NonNull AuditPort audit) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull List<BindingItem> listByTenant(@NonNull String tenantId) {
        return inTenant(tenantId, () -> mapper.listByTenant(tenantId).stream().map(this::toBinding).toList());
    }

    @Override
    public @NonNull List<BindingItem> listByAgent(@NonNull String agentId) {
        // 绑定表带 FORCE RLS：逐租户绑定上下文查询，避免绕过租户隔离
        List<String> tenantIds = new ArrayList<>();
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT tenant_id::text FROM tenant");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tenantIds.add(rs.getString(1));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("查询租户列表失败", e);
        }
        List<BindingItem> rows = new ArrayList<>();
        for (String tid : tenantIds) {
            rows.addAll(inTenant(tid, () -> mapper.find(tid, agentId).stream().map(this::toBinding).toList()));
        }
        return rows;
    }

    @Override
    public @NonNull Optional<BindingItem> find(@NonNull String tenantId, @NonNull String agentId) {
        return inTenant(tenantId, () -> mapper.find(tenantId, agentId).stream().findFirst().map(this::toBinding));
    }

    @Override
    public @NonNull BindingItem bind(@NonNull String tenantId, @NonNull String agentId,
                                     @Nullable Integer pinnedVersion, @Nullable String config,
                                     @NonNull String actorId) {
        String finalConfig = config == null || config.isBlank() ? "{}" : config;
        inTenant(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, new TenantContext(tenantId, actorId, Set.of(), 0L), conn -> {
                    TenantAgentBindingDO row = mapper.bind(tenantId, agentId, pinnedVersion, finalConfig);
                    if (row == null) throw new IllegalStateException("tenant_agent_binding INSERT RETURNING 空");
                    audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_BIND,
                            "tenant_agent_binding", tenantId + ":" + agentId,
                            Map.of("resourceId", agentId));
                    return null;
                }));
        return find(tenantId, agentId).orElseThrow();
    }

    @Override
    public @NonNull BindingItem update(@NonNull String tenantId, @NonNull String agentId,
                                       @Nullable Integer pinnedVersion, @Nullable String config,
                                       @NonNull String actorId) {
        String finalConfig = config == null || config.isBlank() ? "{}" : config;
        int n = inTenant(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, new TenantContext(tenantId, actorId, Set.of(), 0L), conn -> {
                    int updated = mapper.update(tenantId, agentId, pinnedVersion, finalConfig);
                    audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_UPDATE,
                            "tenant_agent_binding", tenantId + ":" + agentId,
                            Map.of("resourceId", agentId));
                    return updated;
                }));
        if (n == 0) throw new BindingNotFoundException(tenantId, agentId);
        return find(tenantId, agentId).orElseThrow();
    }

    @Override
    public void unbind(@NonNull String tenantId, @NonNull String agentId, @NonNull String actorId) {
        int n = inTenant(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, new TenantContext(tenantId, actorId, Set.of(), 0L), conn -> {
                    int updated = mapper.unbind(tenantId, agentId);
                    audit.recordRequired(tenantId, actorId, AuditPort.Actions.AGENT_BINDING_UNBIND,
                            "tenant_agent_binding", tenantId + ":" + agentId,
                            Map.of("resourceId", agentId));
                    return updated;
                }));
        if (n == 0) throw new BindingNotFoundException(tenantId, agentId);
    }

    private <T> T inTenant(String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId);
        try { return action.get(); } finally { TenantScope.clear(); }
    }

    private BindingItem toBinding(TenantAgentBindingDO d) {
        return new BindingItem(d.getTenantId(), d.getAgentId(),
                d.getStatus(), d.getPinnedVersion(), d.getConfig(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
