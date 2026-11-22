package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ConfigPublishRequestMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ConfigPublishRequestDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link ConfigPublishRequestPort} 的 MyBatis-Plus 实现
 * （对应 {@code JdbcConfigPublishRequestRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code ConfigPublishRequestMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * PLATFORM 作用域（tenantId == null）的零 UUID 哨兵、{@code IS NOT DISTINCT FROM}、
 * JSONB cast、双人复核（应用层 + DB CHECK 23514）、状态机守卫、
 * <b>审计与状态变更同事务</b>（P1-15 修复）与影子比对/逐行染色全部保留。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisConfigPublishRequestRepository implements ConfigPublishRequestPort {

    /** Postgres check_violation（CHECK 约束冲突）。 */
    private static final String CHECK_VIOLATION = "23514";

    /**
     * PLATFORM 作用域的租户哨兵：零 UUID（与原 Jdbc 实现同值）。
     *
     * <p>⚠️ 必须是合法 UUID——{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错。
     */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    /** 影子比对/染色的资源类型（与原实现同值）。 */
    private static final String RESOURCE_TYPE = "cap_config_publish_request";

    private final DataSource dataSource;
    private final AuditPort audit;
    private final ConfigPublishRequestMapper mapper;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public MybatisConfigPublishRequestRepository(@Qualifier("dataSource") @NonNull DataSource dataSource,
                                                 @NonNull AuditPort audit,
                                                 @NonNull ConfigPublishRequestMapper mapper) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    @NonNull
    public ConfigPublishRequest insert(@Nullable String tenantId,
                                       ConfigPublishRequest.@NonNull Operation operation,
                                       @NonNull String payload,
                                       @NonNull String reason,
                                       @NonNull String requesterId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    ConfigPublishRequestDO inserted = mapper.insertRequest(
                            tenantId, scopeOf(tenantId).name(), operation.name(),
                            payload, reason, requesterId)
                            .orElseThrow(() -> new IllegalStateException("变更申请插入未返回 request_id"));
                    String requestId = inserted.getRequestId();
                    Instant requestedAt = inserted.getRequestedAt();
                    // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）：审计写不进去 -> 全部回滚。
                    // 审计明细只放系统常量，不放 payload / reason（PIPL fail-closed）。
                    audit.recordRequired(auditTenant(tenantId), requesterId,
                            AuditPort.Actions.CONFIG_PUBLISH_REQUEST, "config_publish_request",
                            requestId, Map.of("operation", operation.name()));
                    return new ConfigPublishRequest(requestId, tenantId,
                            operation, payload, reason,
                            requesterId, ConfigPublishRequest.State.PENDING,
                            null, null, requestedAt, null, null, null,
                            scopeOf(tenantId),
                            null, null, null, null, null);
                }));
    }

    @Override
    public ConfigPublishRequest insertWithBeforeSnapshot(
            @Nullable String tenantId,
            ConfigPublishRequest.@NonNull Operation operation,
            @NonNull String payload,
            @Nullable String beforePayload,
            @NonNull String reason,
            @NonNull String requesterId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    // ⚠️ before_payload 与 payload 同一条 INSERT：「变更前」是提交时刻的快照。
                    ConfigPublishRequestDO inserted = mapper.insertRequestWithBefore(
                            tenantId, scopeOf(tenantId).name(), operation.name(),
                            payload, beforePayload, reason, requesterId)
                            .orElseThrow(() -> new IllegalStateException("变更申请插入未返回 request_id"));
                    String requestId = inserted.getRequestId();
                    Instant requestedAt = inserted.getRequestedAt();
                    // 审计与状态变更同事务（同 insert 的约定）。
                    audit.recordRequired(auditTenant(tenantId), requesterId,
                            AuditPort.Actions.CONFIG_PUBLISH_REQUEST, "config_publish_request",
                            requestId, Map.of("operation", operation.name()));
                    return new ConfigPublishRequest(requestId, tenantId,
                            operation, payload, reason,
                            requesterId, ConfigPublishRequest.State.PENDING,
                            null, null, requestedAt, null, null, null,
                            scopeOf(tenantId),
                            null, null, null, null, null);
                }));
    }

    @Override
    @NonNull
    public Optional<ConfigPublishRequest> find(@Nullable String tenantId,
                                                @NonNull String requestId) {
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        if (tenantId != null) {
                            shadowCompare(RESOURCE_TYPE, tenantId, true);
                        }
                        Optional<ConfigPublishRequestDO> db = mapper.find(tenantId, requestId);
                        if (tenantId != null) {
                            db.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, d.getTenantId()));
                        }
                        return db.map(MybatisConfigPublishRequestRepository::toRecord);
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询变更申请失败", e);
            }
            throw e;
        }
    }

    @Override
    @NonNull
    public Page<ConfigPublishRequest> list(@Nullable String tenantId,
                                           ConfigPublishRequest.@Nullable State state,
                                           @Nullable String cursor,
                                           int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);
        try {
            List<ConfigPublishRequest> rows = inTenantScope(tenantId, () ->
                    TransactionalConnectionHolder.inTransaction(
                            dataSource, context(tenantId), conn -> {
                                if (tenantId != null) {
                                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                                }
                                List<ConfigPublishRequest> out = new ArrayList<>();
                                for (ConfigPublishRequestDO d : mapper.listByCursor(
                                        tenantId,
                                        state == null ? null : state.name(),
                                        after == null ? null : after.createdAt(),
                                        after == null ? null : after.id(),
                                        limit + 1)) {
                                    out.add(toRecord(d));
                                    if (tenantId != null) {
                                        shadowTaint(RESOURCE_TYPE, tenantId, d.getTenantId());
                                    }
                                }
                                return out;
                            }));

            String next = null;
            if (rows.size() > limit) {
                ConfigPublishRequest last = rows.get(limit - 1);
                rows = new ArrayList<>(rows.subList(0, limit));
                next = new PageCursor(last.requestedAt(), last.requestId()).encode();
            }
            return Page.of(rows, next);
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询变更申请失败", e);
            }
            throw e;
        }
    }

    @Override
    @NonNull
    public ConfigPublishRequest decide(@Nullable String tenantId,
                                       @NonNull String requestId,
                                       @NonNull Decision decision,
                                       @Nullable String note,
                                       @NonNull String approverId) {
        ConfigPublishRequest.State target = decision == Decision.APPROVE
                ? ConfigPublishRequest.State.APPROVED
                : ConfigPublishRequest.State.REJECTED;
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        ConfigPublishRequestDO existing = mapper.loadForUpdate(tenantId, requestId)
                                .orElseThrow(() -> new NotFoundException(requestId));
                        // ⚛️ **双人复核第一道**：应用层比对身份
                        if (existing.getRequesterId().equals(approverId)) {
                            throw new SelfApprovalException();
                        }
                        if (!"PENDING".equals(existing.getState())) {
                            throw new StateConflictException(
                                    "申请已处于 " + existing.getState() + "，不可再决定");
                        }
                        ConfigPublishRequestDO db = mapper.updateDecision(
                                tenantId, requestId, target.name(), note, approverId)
                                .orElseThrow(() -> new NotFoundException(requestId));
                        // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）：
                        //    审计写不进去 -> 状态变更一并回滚（无审计的批准不存在）。
                        audit.recordRequired(auditTenant(tenantId), approverId,
                                decision == Decision.APPROVE
                                        ? AuditPort.Actions.CONFIG_PUBLISH_APPROVE
                                        : AuditPort.Actions.CONFIG_PUBLISH_REJECT,
                                "config_publish_request", requestId,
                                Map.of("operation", existing.getOperation(),
                                        "state", target.name()));
                        return toRecord(db);
                    }));
        } catch (RuntimeException e) {
            if (e instanceof SelfApprovalException || e instanceof StateConflictException
                    || e instanceof NotFoundException) {
                throw e;
            }
            if (isSqlFailure(e)) {
                if (CHECK_VIOLATION.equals(sqlStateOf(e))) {
                    // ⚛️ **双人复核第二道**：DB CHECK 拦截（应用层被绕过时生效）
                    throw new SelfApprovalException();
                }
                throw new IllegalStateException("审批失败", causeOf(e));
            }
            throw e;
        }
    }

    @Override
    @NonNull
    public ConfigPublishRequest markApplied(@Nullable String tenantId,
                                            @NonNull String requestId,
                                            @NonNull String actorId,
                                            @NonNull Instant appliedAt) {
        return transitionTo(tenantId, requestId,
                ConfigPublishRequest.State.APPLIED, null, null, null, actorId, appliedAt);
    }

    @Override
    @NonNull
    public ConfigPublishRequest markFailed(@Nullable String tenantId,
                                           @NonNull String requestId,
                                           @NonNull String reason,
                                           @Nullable String failStage,
                                           @Nullable String failDetailsJson) {
        return transitionTo(tenantId, requestId,
                ConfigPublishRequest.State.FAILED, reason, failStage, failDetailsJson, null, null);
    }

    private ConfigPublishRequest transitionTo(String tenantId, String requestId,
                                              ConfigPublishRequest.State target,
                                              String failureReason, String failStage, String failDetailsJson,
                                              String actorId,
                                              Instant appliedAt) {
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        ConfigPublishRequestDO existing = mapper.loadForUpdate(tenantId, requestId)
                                .orElseThrow(() -> new NotFoundException(requestId));
                        if (!"APPROVED".equals(existing.getState())) {
                            throw new StateConflictException(
                                    "只有 APPROVED 状态可执行，当前为 " + existing.getState());
                        }
                        ConfigPublishRequestDO db = mapper.transition(
                                tenantId, requestId, target.name(),
                                failureReason, failStage, failDetailsJson, appliedAt)
                                .orElseThrow(() -> new NotFoundException(requestId));
                        // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）。
                        if (target == ConfigPublishRequest.State.APPLIED) {
                            audit.recordRequired(auditTenant(tenantId), actorId,
                                    AuditPort.Actions.CONFIG_PUBLISH_APPLY, "config_publish_request",
                                    requestId, Map.of("operation", existing.getOperation()));
                        } else {
                            // ⚠️ 失败也要留痕：一份「批过但没生效」的申请必须能被查到
                            audit.recordRequired(auditTenant(tenantId),
                                    actorId == null ? "system" : actorId,
                                    AuditPort.Actions.CONFIG_PUBLISH_FAILED, "config_publish_request",
                                    requestId, Map.of("operation", existing.getOperation()));
                        }
                        return toRecord(db);
                    }));
        } catch (RuntimeException e) {
            if (e instanceof SelfApprovalException || e instanceof StateConflictException
                    || e instanceof NotFoundException) {
                throw e;
            }
            if (isSqlFailure(e)) {
                throw new IllegalStateException("更新申请状态失败", causeOf(e));
            }
            throw e;
        }
    }

    private static ConfigPublishRequest toRecord(ConfigPublishRequestDO d) {
        return new ConfigPublishRequest(
                d.getRequestId(),
                d.getTenantId(),
                ConfigPublishRequest.Operation.valueOf(d.getOperation()),
                d.getPayload(),
                d.getReason(),
                d.getRequesterId(),
                ConfigPublishRequest.State.valueOf(d.getState()),
                d.getApproverId(),
                d.getDecisionNote(),
                d.getRequestedAt(),
                d.getDecidedAt(),
                d.getAppliedAt(),
                d.getFailureReason(),
                ConfigRelease.Scope.valueOf(d.getScope()),
                d.getResourceKind(),
                d.getResourceId(),
                d.getTargetVersion(),
                d.getFailStage(),
                d.getFailDetails());
    }

    /** MyBatis 把 SQLException 包进 PersistenceException；沿 cause 链取 SQLState / cause。 */
    private static @Nullable String sqlStateOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) {
                return se.getSQLState();
            }
        }
        return null;
    }

    private static boolean isSqlFailure(Throwable e) {
        return sqlStateOf(e) != null;
    }

    /** 与原 Jdbc 的 SqlStateCarrier 解包对齐：非 SQL 失败时回退为原异常。 */
    private static Throwable causeOf(RuntimeException e) {
        Throwable cause = e.getCause();
        return cause == null ? e : cause;
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /** 租户上下文：平台作用域绑定零 UUID 哨兵（与原 Jdbc 实现逐字一致）。 */
    private static TenantContext context(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /** 申请单的作用域由 tenantId 决定：null = PLATFORM。 */
    private static ConfigRelease.Scope scopeOf(@Nullable String tenantId) {
        return tenantId == null ? ConfigRelease.Scope.PLATFORM : ConfigRelease.Scope.TENANT;
    }

    /** 审计的租户维度：平台作用域用零 UUID 哨兵。 */
    private static String auditTenant(@Nullable String tenantId) {
        return tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId;
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）—— 与原实现逐字一致
    // ══════════════════════════════════════════════════════════════

    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}
