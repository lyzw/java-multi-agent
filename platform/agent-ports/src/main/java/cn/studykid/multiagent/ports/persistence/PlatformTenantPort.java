package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 平台租户管理仓储端口（重规划 §6 / §8 / §9）。
 *
 * <p><b>为什么独立端口</b>：平台对租户的管理（生命周期/配额/能力授权）与租户内部
 * 业务数据是两条访问路径。平台管理 API 经本端口访问 {@code platform.tenant}
 * 等平台域表（<b>无 RLS</b>），并<b>同步</b>维护 {@code public.tenant}
 * （业务租户行，供 RLS 与共享执行基础设施识别租户）。
 *
 * <p>⚠️ 安全性完全由<b>应用层平台授权闸门</b>（{@code PlatformSecurity}）保证，
 * 本端口只负责数据访问，不做权限判断（与 {@code AdminTenantPort} 的纪律一致）。
 *
 * <p>⚠️ <b>创建租户不自动建租户管理员</b>：{@link #provisionTenant} 仅创建租户主体
 * 与默认配额，进入 {@link TenantLifecycle#PENDING_SETUP}；首位 {@code TenantUser}
 * 由独立邀请/初始化流程建立（重规划 §6.1）。
 */
public interface PlatformTenantPort {

    /** 列出租户（平台级），按创建时间倒序。 */
    @NonNull
    List<PlatformTenantRow> listTenants(@Nullable String statusFilter, int limit);

    /** 按标识读取租户；不存在返回 {@code null}。 */
    @Nullable
    PlatformTenantRow findTenant(@NonNull String tenantId);

    /** 读取租户配额；不存在返回 {@code null}。 */
    @Nullable
    QuotaRow findQuota(@NonNull String tenantId);

    /**
     * 开通新租户（仅平台域登记 + 业务租户行 + 默认配额）。
     *
     * @return 新建租户行（lifecycle_state = PENDING_SETUP）
     * @throws IllegalStateException slug 已存在（调用方映射为 409）
     */
    @NonNull
    PlatformTenantRow provisionTenant(@NonNull String slug, @NonNull String name,
                                      @Nullable String plan);

    /**
     * 迁移租户生命周期状态（须由调用方先经 {@link TenantLifecycle#canTransition} 校验）。
     *
     * @return 更新后的租户行；租户不存在返回 {@code null}
     */
    @Nullable
    PlatformTenantRow transitionLifecycle(@NonNull String tenantId, @NonNull String newState);

    /** 更新租户配额。 */
    @Nullable
    QuotaRow updateQuota(@NonNull String tenantId, @NonNull QuotaRow quota);

    /** 授予租户能力（平台资源进入租户可用范围；不替代租户内调用授权）。 */
    @NonNull
    CapabilityGrantRow grantCapability(@NonNull String tenantId,
                                        @NonNull String resourceCode, @NonNull String scope);

    /** 撤销租户能力。返回是否实际撤销。 */
    boolean revokeCapability(@NonNull String tenantId, @NonNull String resourceCode);

    /** 列出租户能力授权。 */
    @NonNull
    List<CapabilityGrantRow> listCapabilities(@NonNull String tenantId);

    /** 平台租户行（含生命周期状态）。 */
    record PlatformTenantRow(
            @NonNull String tenantId,
            @NonNull String slug,
            @NonNull String name,
            @NonNull String lifecycleState,
            @Nullable String plan,
            long effectivePolicyVersion,
            long tenantAuthzVersion,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt
    ) {
    }

    /** 租户配额。 */
    record QuotaRow(
            int agentLimit,
            int runConcurrencyLimit,
            @Nullable BigDecimal budgetLimit
    ) {
    }

    /** 租户能力授权（平台资源进入租户可用范围）。 */
    record CapabilityGrantRow(
            @NonNull String id,
            @NonNull String tenantId,
            @NonNull String resourceCode,
            @NonNull String scope,
            @NonNull String status,
            long version
    ) {
    }
}
