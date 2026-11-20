package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** platform schema 租户管理（opsSqlSessionFactory）。 */
public interface PlatformTenantMapper {
    List<PlatformTenantRow> listTenants(@Param("statusFilter") String statusFilter, @Param("limit") int limit);
    Optional<QuotaRow> findQuota(@Param("tenantId") String tenantId);
    Optional<PlatformTenantRow> provisionTenant(@Param("slug") String slug, @Param("name") String name,
                                                 @Param("plan") String plan);
    int updateLifecycle(@Param("tenantId") String tenantId, @Param("fromState") String fromState,
                        @Param("toState") String toState, @Param("toStatus") String toStatus);
    int insertQuota(@Param("tenantId") String tenantId, @Param("agentLimit") int agentLimit,
                     @Param("runConcurrencyLimit") int runConcurrencyLimit, @Param("budgetLimit") BigDecimal budgetLimit);
    int grantCapability(@Param("tenantId") String tenantId, @Param("resourceCode") String resourceCode,
                         @Param("scope") String scope);
    int revokeCapability(@Param("tenantId") String tenantId, @Param("resourceCode") String resource);
    List<CapabilityGrantRow> listCapabilities(@Param("tenantId") String tenantId);

    record PlatformTenantRow(String tenantId, String slug, String name, String lifecycleState,
                             String plan, long effectivePolicyVersion, long tenantAuthzVersion,
                             java.time.Instant createdAt, java.time.Instant updatedAt) {}
    record QuotaRow(int agentLimit, int runConcurrencyLimit, BigDecimal budgetLimit) {}
    record CapabilityGrantRow(String tenantId, String resourceCode, String scope, String status, int version) {}
}
