package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformTenantMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.CapabilityGrantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.QuotaRow;

/** PlatformTenantPort MyBatis 实现（opsSqlSessionFactory + platform schema）。 */
// 由 ApplicationConfiguration 显式 @Bean 注册（不挂 @Repository，避免组件扫描重复）
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisPlatformTenantRepository implements PlatformTenantPort {

    private final PlatformTenantMapper mapper;
    private final DataSource dataSource;

    public MybatisPlatformTenantRepository(@NonNull PlatformTenantMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<PlatformTenantRow> listTenants(@Nullable String statusFilter, int limit) {
        return inOpsTx(() -> mapper.listTenants(statusFilter, limit).stream()
                .map(MybatisPlatformTenantRepository::toPortRow)
                .toList());
    }

    @Override
    public @Nullable PlatformTenantRow findTenant(@NonNull String tenantId) {
        return inOpsTx(() -> mapper.listTenants(null, 100).stream()
                .filter(r -> r.tenantId().equals(tenantId))
                .map(MybatisPlatformTenantRepository::toPortRow)
                .findFirst().orElse(null));
    }

    @Override
    public @Nullable QuotaRow findQuota(@NonNull String tenantId) {
        return inOpsTx(() -> mapper.findQuota(tenantId)
                .map(q -> new QuotaRow(q.agentLimit(), q.runConcurrencyLimit(), q.budgetLimit()))
                .orElse(null));
    }

    @Override
    public @NonNull PlatformTenantRow provisionTenant(@NonNull String slug, @NonNull String name,
                                                        @Nullable String plan) {
        return inOpsTx(() -> {
            var row = mapper.provisionTenant(slug, name, plan != null ? plan : "FREE")
                    .orElseThrow(() -> new IllegalStateException("provisionTenant 失败"));
            mapper.insertQuota(row.tenantId(), 5, 2, BigDecimal.valueOf(100.00));
            return toPortRow(row);
        });
    }

    @Override
    public @Nullable PlatformTenantRow transitionLifecycle(@NonNull String tenantId,
                                                             @NonNull String expectedCurrentState,
                                                             @NonNull String newState) {
        return inOpsTx(() -> {
            int updated = mapper.updateLifecycle(tenantId, expectedCurrentState, newState, newState);
            if (updated == 0) {
                return null;
            }
            return toPortRow(mapper.listTenants(null, 1000).stream()
                    .filter(r -> r.tenantId().equals(tenantId))
                    .findFirst().orElse(null));
        });
    }

    @Override
    public @NonNull CapabilityGrantRow grantCapability(@NonNull String tenantId,
                                                         @NonNull String resourceCode, @NonNull String scope) {
        return inOpsTx(() -> {
            mapper.grantCapability(tenantId, resourceCode, scope);
            return new CapabilityGrantRow(tenantId + ":" + resourceCode,
                    tenantId, resourceCode, scope, "ACTIVE", 1L);
        });
    }

    @Override
    public boolean revokeCapability(@NonNull String tenantId, @NonNull String resourceCode) {
        return inOpsTx(() -> mapper.revokeCapability(tenantId, resourceCode) > 0);
    }

    @Override
    public @NonNull List<CapabilityGrantRow> listCapabilities(@NonNull String tenantId) {
        return inOpsTx(() -> mapper.listCapabilities(tenantId).stream()
                .map(r -> new CapabilityGrantRow(r.tenantId() + ":" + r.resourceCode(),
                        r.tenantId(), r.resourceCode(), r.scope(), r.status(), (long) r.version()))
                .toList());
    }

    @Override
    public @NonNull QuotaRow updateQuota(@NonNull String tenantId, @NonNull QuotaRow quota) {
        return inOpsTx(() -> {
            mapper.insertQuota(tenantId, quota.agentLimit(), quota.runConcurrencyLimit(), quota.budgetLimit());
            return quota;
        });
    }

    private static PlatformTenantRow toPortRow(PlatformTenantMapper.PlatformTenantRow r) {
        if (r == null) {
            return null;
        }
        return new PlatformTenantRow(r.tenantId(), r.slug(), r.name(), r.lifecycleState(),
                r.plan(), r.effectivePolicyVersion(), r.tenantAuthzVersion(),
                r.createdAt(), r.updatedAt());
    }

    private <T> T inOpsTx(ThrowingSupplier<T> fn) {
        TenantScope.bind("00000000-0000-0000-0000-000000000000");
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext("00000000-0000-0000-0000-000000000000", "ops", Set.of(), 0L),
                    conn -> {
                        try {
                            return fn.get();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
