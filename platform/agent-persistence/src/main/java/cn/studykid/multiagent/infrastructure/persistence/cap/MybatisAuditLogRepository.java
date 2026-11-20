package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.ibatis.exceptions.PersistenceException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.AuditLog;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.AuditLogMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuditLogDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.AuditLogPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link AuditLogPort} 的 MyBatis-Plus 实现（对应 {@code JdbcAuditLogRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code AuditLogMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 审计写失败不吞异常（fail-closed）；{@code INSERT ... RETURNING created_at} 的时间戳由 DB 回填。
 * 游标分页（limit+1 探底、全序排序、非法游标从头查）与原实现逐字一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAuditLogRepository implements AuditLogPort {

    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final AuditLogMapper mapper;
    private final DataSource dataSource;

    public MybatisAuditLogRepository(@NonNull AuditLogMapper mapper,
                                     @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull AuditLog record(@NonNull AuditLog audit) {
        return inTenantScope(audit.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(audit.tenantId()), conn -> {
                    AuditLogDO dob = mapper.insertRecord(
                            audit.auditId(), audit.scope(), uuid(audit.tenantId()),
                            audit.resourceKind(), audit.resourceId(), audit.resourceName(),
                            audit.eventType(), audit.fromVersion(), audit.toVersion(),
                            audit.fromState(), audit.toState(),
                            audit.beforeConfig(), audit.afterConfig(), audit.diffSummary(),
                            audit.requestId(), audit.releaseId(),
                            audit.operatorId(), audit.reason(), audit.createdAt())
                            // ⚠️ 与原 JDBC 同款 fail-closed：RETURNING 没时间戳就明确失败
                            .orElseThrow(() -> new IllegalStateException("cap_audit_log INSERT 未返回"));
                    return withCreatedAt(audit,
                            dob.getCreatedAt() == null ? Instant.EPOCH : dob.getCreatedAt());
                }));
    }

    @Override
    public @NonNull Optional<AuditLog> findById(@NonNull String scope,
                                                 @Nullable String tenantId,
                                                 @NonNull UUID auditId) {
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId),
                    conn -> mapper.findById(scope, uuid(tenantId), auditId)
                            .map(MybatisAuditLogRepository::toRecord)));
        } catch (PersistenceException e) {
            // ⚠️ 旧实现 catch(SQLException) -> IllegalStateException("查询审计日志失败", e)，逐字保留。
            throw new IllegalStateException("查询审计日志失败", e);
        }
    }

    @Override
    public @NonNull Page<AuditLog> list(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @Nullable String resourceKind,
                                         @Nullable String resourceId,
                                         @Nullable String eventType,
                                         @Nullable String cursor,
                                         int limit) {
        return queryInternal(scope, tenantId, resourceKind, resourceId, eventType,
                null, null, null, cursor, limit);
    }

    @Override
    public @NonNull Page<AuditLog> query(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @Nullable String eventType,
                                          @Nullable String operatorId,
                                          @Nullable String fromTime,
                                          @Nullable String toTime,
                                          @Nullable String cursor,
                                          int limit) {
        return queryInternal(scope, tenantId, null, null, eventType,
                operatorId, fromTime, toTime, cursor, limit);
    }

    private Page<AuditLog> queryInternal(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @Nullable String resourceKind,
                                          @Nullable String resourceId,
                                          @Nullable String eventType,
                                          @Nullable String operatorId,
                                          @Nullable String fromTime,
                                          @Nullable String toTime,
                                          @Nullable String cursor,
                                          int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit 必须为正数");

        Instant parsedTime = null;
        UUID parsedId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split(",", 2);
                parsedTime = Instant.parse(parts[0]);
                parsedId = UUID.fromString(parts[1]);
            } catch (Exception ignored) { /* 兼容旧语义：非法游标从头查 */ }
        }
        final Instant afterTime = parsedTime;
        final UUID afterId = parsedId;

        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId), conn -> {
                        List<AuditLogDO> rows = mapper.query(
                                scope, uuid(tenantId), resourceKind, resourceId, eventType,
                                operatorId, fromTime, toTime,
                                // 与旧实现一致：把 Instant 的 ISO 文本交给 SQL 的 ::timestamptz cast
                                afterTime == null ? null : afterTime.toString(),
                                afterId, limit + 1);
                        List<AuditLog> mapped = rows.stream()
                                .map(MybatisAuditLogRepository::toRecord).toList();
                        if (mapped.size() <= limit) {
                            return Page.of(mapped, null);
                        }
                        AuditLog last = mapped.get(limit - 1);
                        List<AuditLog> pageItems = new ArrayList<>(mapped.subList(0, limit));
                        return Page.of(pageItems, last.createdAt().toString() + "," + last.auditId());
                    }));
        } catch (PersistenceException e) {
            // ⚠️ 旧实现 catch(SQLException) -> IllegalStateException("查询业务审计日志失败", e)，逐字保留。
            throw new IllegalStateException("查询业务审计日志失败", e);
        }
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理（try-finally）。 */
    private <T> T inTenantScope(@Nullable String tenantId, Supplier<T> action) {
        TenantScope.bind(effectiveTenant(tenantId)); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    /** 与原 {@code tenantContext} 同款：null / 空白 -> 平台零 UUID 哨兵。 */
    private static String effectiveTenant(@Nullable String tenantId) {
        return tenantId == null || tenantId.isBlank() ? PLATFORM_SCOPE_TENANT : tenantId;
    }

    private static TenantContext tenantContext(@Nullable String tenantId) {
        return new TenantContext(effectiveTenant(tenantId), "system", Set.of(), 0L);
    }

    /** 与原 {@code bindUuid} 同口径：null / 空白 -> null（SQL 侧 ::uuid cast 不变）。 */
    private static UUID uuid(@Nullable String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : UUID.fromString(tenantId);
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 read() 对齐）──

    /** 审计写入只回读 DB 时间戳，其余字段沿用入参（与原实现 return new AuditLog(...) 一致）。 */
    private static AuditLog withCreatedAt(AuditLog audit, Instant createdAt) {
        return new AuditLog(
                audit.auditId(), audit.scope(), audit.tenantId(),
                audit.resourceKind(), audit.resourceId(), audit.resourceName(),
                audit.eventType(), audit.fromVersion(), audit.toVersion(),
                audit.fromState(), audit.toState(),
                audit.beforeConfig(), audit.afterConfig(), audit.diffSummary(),
                audit.requestId(), audit.releaseId(),
                audit.operatorId(), audit.reason(), createdAt);
    }

    private static AuditLog toRecord(AuditLogDO d) {
        return new AuditLog(
                d.getAuditId(), d.getScope(),
                d.getTenantId() == null ? null : d.getTenantId().toString(),
                d.getResourceKind(), d.getResourceId(), d.getResourceName(),
                d.getEventType(), d.getFromVersion(), d.getToVersion(),
                d.getFromState(), d.getToState(),
                d.getBeforeConfig(), d.getAfterConfig(), d.getDiffSummary(),
                d.getRequestId(), d.getReleaseId(), d.getOperatorId(), d.getReason(),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt());
    }
}
