package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ConfigOverrideMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ConfigOverrideDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;

/**
 * {@link ConfigOverridePort} 的 MyBatis-Plus 实现（对应 {@code JdbcConfigOverrideRepository}）。
 *
 * <p>Port 签名零改动；三条 SQL 逐字搬迁到 {@code ConfigOverrideMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * 审计 fail-closed 顺序与原实现一致：写/删事务提交成功后才调 {@code AuditPort.recordRequired}；
 * 影子比对 / 逐行染色语义与原实现完全一致（默认全部 null / false => 行为 100% 不变）。
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
public class MybatisConfigOverrideRepository implements ConfigOverridePort {

    /** 影子比对/染色的资源类型（与原实现同值）。 */
    private static final String RESOURCE_TYPE = "tenant_config_override";

    private final ConfigOverrideMapper mapper;
    private final DataSource dataSource;
    private final AuditPort audit;

    // ── P1 影子态接入（与原 Jdbc 实现同口径）────────────────────────────
    // 默认全部为 null / false => 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有 2 参构造，确保装配不变）。 */
    @Autowired
    public MybatisConfigOverrideRepository(@NonNull ConfigOverrideMapper mapper,
                                           @Qualifier("dataSource") @NonNull DataSource dataSource,
                                           @NonNull AuditPort audit) {
        this(mapper, dataSource, audit, null, null, false, "");
    }

    /** 影子态接线构造（测试用；生产由 {@link #bindAuthzShadow} 注入）。 */
    MybatisConfigOverrideRepository(@NonNull ConfigOverrideMapper mapper,
                                    @NonNull DataSource dataSource,
                                    @NonNull AuditPort audit,
                                    @Nullable ScopedDataAccess scoped,
                                    @Nullable AuthzShadowReporter reporter,
                                    boolean shadowEnabled,
                                    @NonNull String sentinelTenantId) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.audit = audit;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Map<String, OverrideEntry> overridesFor(@NonNull String tenantId) {
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        Map<String, OverrideEntry> out = new LinkedHashMap<>();
                        for (ConfigOverrideDO d : mapper.findAllByTenant(tenantId)) {
                            // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                            shadowTaint(RESOURCE_TYPE, tenantId, d.getTenantId());
                            String key = d.getConfigKey();
                            Tightening tightening = ConfigOverridePort.WRITABLE.get(key);
                            out.put(key, new OverrideEntry(
                                    key,
                                    d.getValueText(),
                                    d.getValueType(),
                                    // ⚠️ 目录里没有的键（历史脏数据 / 目录变更后遗留）
                                    //    一律按 NEUTRAL 呈现，绝不因此阻断读取。
                                    tightening == null ? Tightening.NEUTRAL : tightening,
                                    d.getReason(),
                                    d.getChangedBy(),
                                    d.getUpdatedAt()));
                        }
                        return out;
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("读取配置覆盖失败", e);
            }
            throw e;
        }
    }

    @Override
    public @NonNull OverrideEntry apply(@NonNull String tenantId,
                                        @NonNull String key,
                                        @NonNull String value,
                                        @NonNull String reason,
                                        @NonNull String actorId)
            throws UnknownConfigKeyException {
        Tightening tightening = ConfigOverridePort.WRITABLE.get(key);
        if (tightening == null) {
            throw new UnknownConfigKeyException(key);
        }
        String valueType = valueTypeOf(key);
        try {
            Instant updatedAt = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> mapper.upsert(
                            tenantId, key, value, valueType, reason, actorId)
                            .orElseThrow(() -> new IllegalStateException("配置覆盖写入未返回 updated_at"))
                            .getUpdatedAt()));
            // ⚠️ 明细只放系统常量（键名/类型/方向），不放 value_text —— 见类注释。
            //    审计失败即抛（fail-closed）；顺序与原实现一致：事务提交后再审计。
            audit.recordRequired(tenantId, actorId,
                    AuditPort.Actions.CONFIG_UPDATE, "config", key,
                    Map.of("valueType", valueType,
                            "tightening", tightening.name()));
            return new OverrideEntry(key, value, valueType, tightening,
                    reason, actorId, updatedAt);
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("写入配置覆盖失败", e);
            }
            throw e;
        }
    }

    @Override
    public void clear(@NonNull String tenantId,
                      @NonNull String key,
                      @NonNull String actorId)
            throws UnknownConfigKeyException {
        if (!ConfigOverridePort.WRITABLE.containsKey(key)) {
            throw new UnknownConfigKeyException(key);
        }
        try {
            inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn ->
                            mapper.deleteByKey(tenantId, key)));
            // 审计失败即抛（fail-closed）；顺序与原实现一致：事务提交后再审计。
            audit.recordRequired(tenantId, actorId,
                    AuditPort.Actions.CONFIG_UPDATE, "config", key,
                    Map.of("valueType", "", "tightening", "CLEARED"));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("清除配置覆盖失败", e);
            }
            throw e;
        }
    }

    /**
     * 配置键 → V11 的 {@code value_type}（与原实现逐字一致：显式映射而非按值猜）。
     */
    private static String valueTypeOf(String key) {
        return switch (key) {
            case "agent.run.max-cost-per-run" -> "DECIMAL";
            case "agent.approval.required-by-risk" -> "ENUM";
            case "agent.approval.ttl" -> "DURATION";
            default -> "INTEGER";
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

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    /** 构造租户上下文（与原 Jdbc 实现逐字一致）。 */
    private static TenantContext context(@NonNull String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
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
