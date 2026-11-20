package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigDraft;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ConfigDraftMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigDraftDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ConfigDraftPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 变更草稿仓储 —— MyBatis-Plus 适配层（W1 试点，对应主文档 §4.2 / §9.3）。
 *
 * <p><b>范式样板</b>：Port 签名与旧 {@code JdbcConfigDraftRepository} 完全一致，上层零改动；
 * 内部不再写 SQL，改为委托 {@code ConfigDraftMapper}。其余 Jdbc*Repository 按此模板替换。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效，实现灰度/一键回滚。开启时旧实现因互补条件不被注册。
 *
 * <p><b>RLS 保证</b>：每个 Mapper 调用前用认证上下文的 tenantId 绑定 {@link TenantScope}，
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * finally 无条件清理，防线程池串租户。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisConfigDraftRepository implements ConfigDraftPort {

    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final ConfigDraftMapper mapper;
    private final DataSource dataSource;

    public MybatisConfigDraftRepository(@NonNull ConfigDraftMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull ConfigDraft insert(@NonNull ConfigDraft draft) {
        return inTenantScope(draft.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(draft.tenantId()), conn -> {
                    ConfigDraftDO dob = toDO(draft);
                    mapper.insert(dob);
                    // created_at/updated_at 由 DB DEFAULT now() 生成，BaseMapper.insert 不回填 DO，
                    // 与原 JDBC 的 INSERT ... RETURNING 语义对齐：按主键重读取回。
                    return findById(draft.scope(), draft.tenantId(), draft.draftId()).orElseThrow();
                }));
    }

    @Override
    public @NonNull Optional<ConfigDraft> findById(@NonNull String scope,
                                                     @Nullable String tenantId,
                                                     @NonNull UUID draftId) {
        return inTenantScope(tenantId, () -> mapper.findById(scope, uuid(tenantId), draftId)
                .map(MybatisConfigDraftRepository::toRecord));
    }

    @Override
    public @NonNull Page<ConfigDraft> list(@NonNull String scope, @Nullable String tenantId,
                                            @Nullable String state, @Nullable String cursor,
                                            int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return inTenantScope(tenantId, () -> {
            // 游标格式与旧实现逐字一致："<Instant>,<UUID>"（非法游标从头查，吞异常）
            Instant afterTime = null;
            UUID afterId = null;
            if (cursor != null && !cursor.isBlank()) {
                String[] parts = cursor.split(",");
                try {
                    afterTime = Timestamp.from(Instant.parse(parts[0])).toInstant();
                    afterId = UUID.fromString(parts[1]);
                } catch (Exception ignored) { /* 兼容旧语义：非法游标从头查 */ }
            }

            List<ConfigDraftDO> rows = mapper.listByCursor(
                    scope, uuid(tenantId), state, afterTime, afterId, limit + 1);

            String nextCursor = null;
            if (rows.size() > limit) {
                ConfigDraftDO last = rows.get(limit - 1);
                rows = new ArrayList<>(rows.subList(0, limit));
                nextCursor = last.getUpdatedAt() + "," + last.getDraftId();
            }
            return Page.of(rows.stream().map(MybatisConfigDraftRepository::toRecord).toList(), nextCursor);
        });
    }

    @Override
    public @NonNull ConfigDraft transitionState(@NonNull String scope, @Nullable String tenantId,
                                                 @NonNull UUID draftId,
                                                 @NonNull String expectedCurrentState,
                                                 @NonNull String targetState,
                                                 @Nullable UUID linkedRequestId,
                                                 @Nullable String operatorId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    int rows = mapper.transitionState(scope, uuid(tenantId), draftId,
                            expectedCurrentState, targetState, linkedRequestId, operatorId, Instant.now());
                    if (rows != 1) {
                        // fail-closed：0 行 = 并发冲突或非法迁移，绝不静默成功
                        throw new IllegalStateException("状态迁移失败（并发冲突或非法迁移）: "
                                + expectedCurrentState + " → " + targetState);
                    }
                    return findById(scope, tenantId, draftId).orElseThrow();
                }));
    }

    @Override
    public @NonNull Optional<ConfigDraft> findActiveByResource(@NonNull String scope,
                                                                @Nullable String tenantId,
                                                                @NonNull String resourceKind,
                                                                @NonNull String resourceId) {
        return inTenantScope(tenantId, () -> mapper.findActiveByResource(
                scope, uuid(tenantId), resourceKind, resourceId)
                .map(MybatisConfigDraftRepository::toRecord));
    }

    @Override
    public @NonNull ConfigDraft saveContent(@NonNull String scope, @Nullable String tenantId,
                                             @NonNull UUID draftId, @NonNull String newConfig,
                                             @Nullable String newFingerprint, @NonNull String operatorId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    int rows = mapper.saveContent(scope, uuid(tenantId), draftId,
                            newConfig, newFingerprint, operatorId, Instant.now());
                    if (rows != 1) {
                        throw new IllegalStateException("保存草稿失败：草稿不存在或当前状态不允许保存");
                    }
                    return findById(scope, tenantId, draftId).orElseThrow();
                }));
    }

    @Override
    public @NonNull ConfigDraft markFailed(@NonNull String scope, @Nullable String tenantId,
                                            @NonNull UUID draftId, @NonNull String failStage,
                                            @NonNull String failDetailsJson, @NonNull String operatorId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    int rows = mapper.markFailed(scope, uuid(tenantId), draftId,
                            failStage, failDetailsJson, operatorId, Instant.now());
                    if (rows != 1) {
                        throw new IllegalStateException(
                                "发布失败仅允许 PENDING_PUBLISH/DRAFT 状态，迁移未生效: " + draftId);
                    }
                    return findById(scope, tenantId, draftId).orElseThrow();
                }));
    }

    @Override
    public @NonNull ConfigDraft markRejected(@NonNull String scope, @Nullable String tenantId,
                                              @NonNull UUID draftId, @Nullable UUID linkedRequestId,
                                              @NonNull String rejectionReason, @NonNull String operatorId) {
        // 与旧实现同口径：委托 PENDING_APPROVAL → APPROVAL_REJECTED 的状态迁移 CAS。
        return transitionState(scope, tenantId, draftId,
                "PENDING_APPROVAL", "APPROVAL_REJECTED", linkedRequestId, operatorId);
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        String effective = tenantId == null || tenantId.isBlank() ? PLATFORM_SCOPE_TENANT : tenantId;
        TenantScope.bind(effective); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    private static TenantContext context(@Nullable String tenantId) {
        String effective = tenantId == null || tenantId.isBlank() ? PLATFORM_SCOPE_TENANT : tenantId;
        return new TenantContext(effective, "system", java.util.Set.of(), 0L);
    }

    /** PLATFORM scope（tenantId=null）→ SQL 传 null（DO/XML 用 IS NOT DISTINCT FROM 兼容）。 */
    private static UUID uuid(@Nullable String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : UUID.fromString(tenantId);
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 read() 对齐）──

    private static ConfigDraftDO toDO(ConfigDraft r) {
        ConfigDraftDO d = new ConfigDraftDO();
        d.setDraftId(r.draftId());
        d.setScope(r.scope());
        d.setTenantId(uuid(r.tenantId()));
        d.setResourceKind(r.resourceKind());
        d.setResourceId(r.resourceId());
        d.setResourceName(r.resourceName());
        d.setBaseVersion(r.baseVersion());
        d.setDraftVersion(r.draftVersion());
        d.setDraftState(r.draftState());
        d.setDraftConfig(r.draftConfig());
        d.setConfigFingerprint(r.configFingerprint());
        d.setLatestRequestId(r.latestRequestId());
        d.setFailureStage(r.failureStage());
        d.setFailureDetails(r.failureDetails() == null ? "[]" : r.failureDetails());
        d.setRejectionReason(r.rejectionReason());
        d.setOperatorId(r.operatorId());
        return d;
    }

    private static ConfigDraft toRecord(ConfigDraftDO d) {
        return new ConfigDraft(
                d.getDraftId(), d.getScope(), d.getTenantId() == null ? null : d.getTenantId().toString(),
                d.getResourceKind(), d.getResourceId(), d.getResourceName(),
                d.getBaseVersion(), d.getDraftVersion(), d.getDraftState(),
                d.getDraftConfig(), d.getConfigFingerprint(), d.getLatestRequestId(),
                d.getFailureStage(), d.getFailureDetails(), d.getRejectionReason(), d.getOperatorId(),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt(),
                d.getUpdatedAt() == null ? Instant.EPOCH : d.getUpdatedAt());
    }
}
