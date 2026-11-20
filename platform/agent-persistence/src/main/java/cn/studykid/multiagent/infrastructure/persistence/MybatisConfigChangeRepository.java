package cn.studykid.multiagent.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ConfigChangeMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ConfigChangeDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link ConfigChangePort} 的 MyBatis-Plus 实现（对应 {@code JdbcConfigChangeRepository}）。
 *
 * <p>Port 签名零改动；五条 SQL 逐字搬迁到 {@code ConfigChangeMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * 审计 fail-closed 顺序与原实现一致：写事务提交成功后才调 {@code AuditPort.recordRequired}；
 * 双人复核的应用层判断 + DB CHECK 第二道（SQLState 23514 -> {@link SelfApprovalException}）、
 * 状态机守卫（SQLState 23001 -> {@link IllegalStateConflictException}）、
 * apply 时重做收紧校验、影子比对/逐行染色——全部保留。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * 写路径的 {@code loadForUpdate} 仍带 FOR UPDATE（P1 不观测，隔离由 RLS 兜底）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisConfigChangeRepository implements ConfigChangePort {

    /** Postgres check_violation（CHECK 约束冲突）。 */
    private static final String CHECK_VIOLATION = "23514";
    /** Postgres restrict_violation（状态机守卫触发器）。 */
    private static final String RESTRICT_VIOLATION = "23001";

    /** 影子比对/染色的资源类型（与原实现同值）。 */
    private static final String RESOURCE_TYPE = "config_change_request";

    private final DataSource dataSource;
    private final AuditPort audit;
    private final ConfigOverridePort overrides;
    private final ConfigChangeMapper mapper;

    // ── P1 影子态接入（与原 Jdbc 实现同口径）────────────────────────────
    // 默认全部为 null / false => 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有 3 参构造 + Mapper，确保装配不变）。 */
    @Autowired
    public MybatisConfigChangeRepository(@Qualifier("dataSource") @NonNull DataSource dataSource,
                                         @NonNull AuditPort audit,
                                         @NonNull ConfigOverridePort overrides,
                                         @NonNull ConfigChangeMapper mapper) {
        this(dataSource, audit, overrides, mapper, null, null, false, "");
    }

    /** 影子态接线构造（测试用；生产由 {@link #bindAuthzShadow} 注入）。 */
    MybatisConfigChangeRepository(@NonNull DataSource dataSource,
                                  @NonNull AuditPort audit,
                                  @NonNull ConfigOverridePort overrides,
                                  @NonNull ConfigChangeMapper mapper,
                                  @Nullable ScopedDataAccess scoped,
                                  @Nullable AuthzShadowReporter reporter,
                                  boolean shadowEnabled,
                                  @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.overrides = overrides;
        this.mapper = mapper;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull ChangeItem request(@NonNull String tenantId,
                                       @NonNull String key,
                                       @NonNull String value,
                                       @NonNull String reason,
                                       @NonNull String requesterId) {
        if (!ConfigOverridePort.WRITABLE.containsKey(key)) {
            throw new ConfigOverridePort.UnknownConfigKeyException(key);
        }
        String valueType = valueTypeOf(key);
        try {
            ChangeItem created = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        ConfigChangeDO inserted = mapper.insertChange(
                                tenantId, key, value, valueType, reason, requesterId)
                                .orElseThrow(() -> new IllegalStateException("变更申请插入未返回 change_id"));
                        return new ChangeItem(inserted.getChangeId(), key, value, valueType, reason,
                                requesterId, inserted.getRequestedAt(), ChangeState.PENDING,
                                null, null, null, null);
                    }));
            // ⚠️ 明细只放**系统常量**（键/类型/分级），不放 value 与 reason——
            //    二者是用户自由文本，PIPL 敏感信息扫描（fail-closed）会拒写。
            audit.recordRequired(tenantId, requesterId,
                    AuditPort.Actions.CONFIG_CHANGE_REQUEST, "config_change", created.changeId(),
                    Map.of("configKey", key,
                            "valueType", valueType,
                            "tier", ConfigOverridePort.tierOf(key).name()));
            return created;
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("创建变更申请失败", e);
            }
            throw e;
        }
    }

    @Override
    public @NonNull Page<ChangeItem> list(@NonNull String tenantId,
                                          @Nullable ChangeState state,
                                          @Nullable String cursor,
                                          int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);
        try {
            List<ChangeItem> rows = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        List<ChangeItem> out = new ArrayList<>();
                        for (ConfigChangeDO d : mapper.listByCursor(
                                tenantId,
                                state == null ? null : state.name(),
                                after == null ? null : after.createdAt(),
                                after == null ? null : after.id(),
                                limit + 1)) {
                            // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                            shadowTaint(RESOURCE_TYPE, tenantId, d.getTenantId());
                            out.add(toChangeItem(d));
                        }
                        return out;
                    }));

            // 多取的那一行只用于判定，不返回（与 P4-D13 一致）
            String next = null;
            if (rows.size() > limit) {
                ChangeItem last = rows.get(limit - 1);
                rows = new ArrayList<>(rows.subList(0, limit));
                next = new PageCursor(last.requestedAt(), last.changeId()).encode();
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
    public @NonNull ChangeItem decide(@NonNull String tenantId,
                                      @NonNull String changeId,
                                      @NonNull Decision decision,
                                      @Nullable String note,
                                      @NonNull String approverId) {
        ChangeState target = decision == Decision.APPROVE
                ? ChangeState.APPROVED : ChangeState.REJECTED;
        try {
            ChangeItem updated = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        ConfigChangeDO existing = mapper.loadForUpdate(tenantId, changeId)
                                .orElseThrow(() -> new ChangeNotFoundException(changeId));
                        // ⚛️ **双人复核第一道**：应用层比对身份
                        if (existing.getRequesterId().equals(approverId)) {
                            throw new SelfApprovalException();
                        }
                        if (!"PENDING".equals(existing.getState())) {
                            throw new IllegalStateConflictException(
                                    "申请已处于 " + existing.getState() + "，不可再决定");
                        }
                        return mapper.updateDecision(tenantId, changeId, target.name(), note, approverId)
                                .map(MybatisConfigChangeRepository::toChangeItem)
                                .orElseThrow(() -> new ChangeNotFoundException(changeId));
                    }));
            // 审计在事务提交后执行（与原实现顺序一致）：审计失败即抛（fail-closed）。
            audit.recordRequired(tenantId, approverId,
                    decision == Decision.APPROVE
                            ? AuditPort.Actions.CONFIG_CHANGE_APPROVE
                            : AuditPort.Actions.CONFIG_CHANGE_REJECT,
                    "config_change", changeId,
                    Map.of("configKey", updated.key(), "state", target.name()));
            return updated;
        } catch (RuntimeException e) {
            if (e instanceof SelfApprovalException || e instanceof IllegalStateConflictException
                    || e instanceof ChangeNotFoundException) {
                throw e;
            }
            if (isSqlFailure(e)) {
                String sqlState = sqlStateOf(e);
                if (CHECK_VIOLATION.equals(sqlState)) {
                    // ⚛️ **双人复核第二道**：DB CHECK 拦截（应用层被绕过时生效）
                    throw new SelfApprovalException();
                }
                if (RESTRICT_VIOLATION.equals(sqlState)) {
                    throw new IllegalStateConflictException("申请状态已变更，请刷新后重试");
                }
                throw new IllegalStateException("审批失败", e);
            }
            throw e;
        }
    }

    @Override
    public @NonNull ChangeItem apply(@NonNull String tenantId,
                                     @NonNull String changeId,
                                     @NonNull String actorId) {
        try {
            ChangeItem applied = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        ConfigChangeDO db = mapper.loadForUpdate(tenantId, changeId)
                                .orElseThrow(() -> new ChangeNotFoundException(changeId));
                        ChangeItem existing = toChangeItem(db);
                        if (existing.state() != ChangeState.APPROVED) {
                            throw new IllegalStateConflictException(
                                    "只有 APPROVED 状态可应用，当前为 " + existing.state());
                        }
                        // ⚠️ **apply 时重做收紧校验**：申请提交后配置可能已被他人改过，
                        //    此时的「收紧」相对新基线可能已是放松。
                        revalidateTightening(tenantId, existing);

                        ChangeItem updated = mapper.markApplied(tenantId, changeId)
                                .map(MybatisConfigChangeRepository::toChangeItem)
                                .orElseThrow(() -> new ChangeNotFoundException(changeId));
                        // ⚠️ **先写配置表，再置 APPLIED**：同一事务内，任一失败全回滚。
                        overrides.apply(tenantId, existing.key(), existing.proposedValue(),
                                existing.reason(), actorId);
                        return updated;
                    }));
            // 审计在事务提交后执行（与原实现顺序一致）：审计失败即抛（fail-closed）。
            audit.recordRequired(tenantId, actorId,
                    AuditPort.Actions.CONFIG_CHANGE_APPLY, "config_change", changeId,
                    Map.of("configKey", applied.key()));
            return applied;
        } catch (RuntimeException e) {
            if (e instanceof SelfApprovalException || e instanceof IllegalStateConflictException
                    || e instanceof ChangeNotFoundException) {
                throw e;
            }
            if (isSqlFailure(e)) {
                if (RESTRICT_VIOLATION.equals(sqlStateOf(e))) {
                    throw new IllegalStateConflictException("申请状态已变更，请刷新后重试");
                }
                throw new IllegalStateException("应用变更失败", e);
            }
            throw e;
        }
    }

    /**
     * 重跑收紧校验（与原实现逐字一致）：读的是当前生效值（DB 覆盖 + yml 默认），
     * current 为 null 时 policy 只做格式校验（首次写入不受限）。
     */
    private void revalidateTightening(String tenantId, ChangeItem existing) {
        String current = null;
        Map<String, OverrideEntry> existingOverrides = overrides.overridesFor(tenantId);
        OverrideEntry entry = existingOverrides.get(existing.key());
        if (entry != null) {
            current = entry.value();
        }
        cn.studykid.multiagent.application.config.ConfigTighteningPolicy.validate(
                existing.key(), existing.proposedValue(), current,
                existing.reason(), valueTypeToPolicyType(existing.valueType()));
    }

    /** 配置键 → V12 的 value_type（必须与 JdbcConfigOverrideRepository.valueTypeOf 一致）。 */
    private static String valueTypeOf(String key) {
        return switch (key) {
            case "agent.run.max-cost-per-run" -> "DECIMAL";
            case "agent.approval.required-by-risk" -> "ENUM";
            case "agent.approval.ttl" -> "DURATION";
            default -> "INTEGER";
        };
    }

    private static cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ValueType
            valueTypeToPolicyType(String stored) {
        return switch (stored) {
            case "DECIMAL" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.DECIMAL;
            case "ENUM" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.ENUM;
            case "DURATION" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.DURATION;
            default -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.INTEGER;
        };
    }

    /** MyBatis 把 SQLException 包进 PersistenceException；沿 cause 链识别以免误伤业务异常。 */
    private static boolean isSqlFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }

    /** 取 cause 链中第一个 SQLException 的 SQLState（双人复核的 DB CHECK 需要它）。 */
    private static @Nullable String sqlStateOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) {
                return se.getSQLState();
            }
        }
        return null;
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /** 构造租户上下文（与原 Jdbc 实现逐字一致）。 */
    private static TenantContext context(@NonNull String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    private static ChangeItem toChangeItem(ConfigChangeDO d) {
        return new ChangeItem(
                d.getChangeId(),
                d.getConfigKey(),
                d.getProposedValue(),
                d.getValueType(),
                d.getReason(),
                d.getRequesterId(),
                d.getRequestedAt(),
                ChangeState.valueOf(d.getState()),
                d.getApproverId(),
                d.getDecidedAt(),
                d.getDecisionNote(),
                d.getAppliedAt());
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）—— 与原实现逐字一致
    // ══════════════════════════════════════════════════════════════

    /** 影子态装配入口：容器里存在 {@code AuthzShadowBinding} 即开启，不存在即完全关闭。 */
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

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /** 逐行染色：结果行的真实 tenant_id 与 Gate 算出的 ScopeFilter 比对（主体与行归属分开）。 */
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
