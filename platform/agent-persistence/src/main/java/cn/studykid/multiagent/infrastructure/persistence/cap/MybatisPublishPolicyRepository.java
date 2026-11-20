package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.PublishPolicy;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.PublishPolicyMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.PublishPolicyDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.PublishPolicyPort;

/**
 * 发布策略配置 MyBatis-Plus 仓储（W2 迁移，对应 {@code JdbcPublishPolicyRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code PublishPolicyMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * 优先级解析仍由应用层 {@code PublishPolicyService} 负责，本仓储只做存储 + 按 (scope, tenant_id) 列出。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * PLATFORM 作用域绑定零 UUID 哨兵，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisPublishPolicyRepository implements PublishPolicyPort {

    /** PLATFORM 作用域的租户哨兵（与原 Jdbc 实现同值）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final PublishPolicyMapper mapper;
    private final DataSource dataSource;

    public MybatisPublishPolicyRepository(@NonNull PublishPolicyMapper mapper,
                                          @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull PublishPolicy insert(@NonNull PublishPolicy policy) {
        return inTenantScope(policy.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(policy.tenantId()), conn -> {
                    PublishPolicyDO dob = mapper.insertPolicy(
                            policy.policyId(),
                            policy.scope(),
                            uuid(policy.tenantId()),
                            policy.policyLevel(),
                            policy.resourceKind(),
                            policy.resourceId(),
                            policy.publishMode(),
                            policy.enabled(),
                            policy.updatedBy())
                            // ⚠️ 与原 JDBC 同款 fail-closed：RETURNING 没时间戳就明确失败
                            .orElseThrow(() -> new IllegalStateException(
                                    "cap_publish_policy INSERT 未返回 created_at"));
                    return policy.withTimestamps(dob.getCreatedAt(), dob.getUpdatedAt());
                }));
    }

    @Override
    public @NonNull Optional<PublishPolicy> findById(@NonNull String scope,
                                                      @Nullable String tenantId,
                                                      @NonNull UUID policyId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.findById(scope, uuid(tenantId), policyId)
                        .map(MybatisPublishPolicyRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<PublishPolicy> update(@NonNull PublishPolicy policy) {
        return inTenantScope(policy.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(policy.tenantId()), conn -> mapper.updateByKey(
                        policy.publishMode(),
                        policy.enabled(),
                        policy.updatedBy(),
                        policy.policyId(),
                        policy.scope(),
                        uuid(policy.tenantId()))
                        .map(dob -> policy.withTimestamps(dob.getCreatedAt(), dob.getUpdatedAt()))));
    }

    @Override
    public boolean delete(@NonNull String scope,
                          @Nullable String tenantId,
                          @NonNull UUID policyId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.deleteByKey(policyId, scope, uuid(tenantId)) > 0));
    }

    @Override
    public @NonNull List<PublishPolicy> listEnabled(@NonNull String scope,
                                                     @Nullable String tenantId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.listEnabled(scope, uuid(tenantId)).stream()
                        .map(MybatisPublishPolicyRepository::toRecord)
                        .toList()));
    }

    @Override
    public @NonNull Optional<PublishPolicy> findByTarget(@NonNull String scope,
                                                          @Nullable String tenantId,
                                                          @NonNull String policyLevel,
                                                          @Nullable String resourceKind,
                                                          @Nullable String resourceId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.findByTarget(
                        scope,
                        uuid(tenantId),
                        policyLevel,
                        resourceKind == null ? "" : resourceKind,
                        resourceId == null ? "" : resourceId)
                        .map(MybatisPublishPolicyRepository::toRecord)));
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        // 与原 Jdbc 的 tenantContext 同款归一化：null / 空 / 非 UUID 哨兵 -> 平台零 UUID，
        // 否则绑非 UUID 文本会让 current_tenant_id() 的 ::UUID 转换直接报错。
        TenantScope.bind(tenantContext(tenantId).tenantId());
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /**
     * 构造租户上下文（与原 Jdbc 实现逐字一致）。
     *
     * <p>⚠️ 非 UUID 哨兵租户（如平台身份合成值）归一化到标准哨兵，与原 bindUuid 的降级口径一致。
     */
    private static TenantContext tenantContext(@Nullable String tenantId) {
        String normalized;
        if (tenantId == null || tenantId.isBlank()) {
            normalized = PLATFORM_SCOPE_TENANT;
        } else {
            try {
                UUID.fromString(tenantId);
                normalized = tenantId;
            } catch (IllegalArgumentException e) {
                // 非 UUID 哨兵 -> 视作平台侧，归一化到标准哨兵
                normalized = PLATFORM_SCOPE_TENANT;
            }
        }
        return new TenantContext(normalized, "system", java.util.Set.of(), 0L);
    }

    /** 与原 {@code bindUuid} 同口径：null / 空 / 非 UUID 哨兵降级为 NULL（SQL 侧 ::uuid cast 不变）。 */
    private static UUID uuid(@Nullable String uuidText) {
        if (uuidText == null || uuidText.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 read() 对齐）──

    private static PublishPolicy toRecord(PublishPolicyDO d) {
        return new PublishPolicy(
                d.getPolicyId(),
                d.getScope(),
                d.getTenantId() == null ? null : d.getTenantId().toString(),
                d.getPolicyLevel(),
                d.getResourceKind(),
                d.getResourceId(),
                d.getPublishMode(),
                d.isEnabled(),
                d.getUpdatedBy(),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt(),
                d.getUpdatedAt() == null ? Instant.EPOCH : d.getUpdatedAt());
    }
}
