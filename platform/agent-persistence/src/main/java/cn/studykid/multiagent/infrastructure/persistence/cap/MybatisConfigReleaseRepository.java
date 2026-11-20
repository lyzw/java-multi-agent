package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ConfigReleaseMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigReleaseDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link ConfigReleasePort} 的 MyBatis-Plus 实现（W2 迁移，对应 {@code JdbcConfigReleaseRepository}）。
 *
 * <p>Port 签名零改动；六条 SQL 逐字搬迁到 {@code ConfigReleaseMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 影子比对 / 逐行染色 / 审计语义与原实现完全一致：默认全部为 null / false ⇒ 行为 100% 不变。
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
public class MybatisConfigReleaseRepository implements ConfigReleasePort {

    /**
     * PLATFORM 作用域的租户哨兵：零 UUID。
     *
     * <p>必须是合法 UUID——见 {@link #tenantContext(String)} 的说明：
     * 绑非 UUID 会让 {@code current_tenant_id()} 的 {@code ::UUID} 转换直接报错。
     */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_config_release";

    private final ConfigReleaseMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（与原 Jdbc 实现同口径）────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    public MybatisConfigReleaseRepository(@NonNull ConfigReleaseMapper mapper,
                                          @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试用；生产由 {@link #bindAuthzShadow} 注入）。 */
    MybatisConfigReleaseRepository(@NonNull ConfigReleaseMapper mapper,
                                   @NonNull DataSource dataSource,
                                   @Nullable ScopedDataAccess scoped,
                                   @Nullable AuthzShadowReporter reporter,
                                   boolean shadowEnabled,
                                   @NonNull String sentinelTenantId) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull ConfigRelease save(@NonNull ConfigRelease release) {
        return inTenantScope(release.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(release.tenantId()), conn -> {
                    ConfigReleaseDO inserted = mapper.insertRelease(
                            release.scope().name(),
                            release.tenantId(),
                            release.objectVersions(),
                            release.aggregateFingerprint(),
                            release.status().name(),
                            release.requesterId(),
                            release.approverId(),
                            release.effectiveAt(),
                            release.createdAt())
                            .orElseThrow(() -> new IllegalStateException("发布记录插入未返回结果"));
                    return new ConfigRelease(
                            inserted.getReleaseId(),
                            inserted.getConfigPublishSeq(),
                            release.scope(),
                            release.tenantId(),
                            release.objectVersions(),
                            release.aggregateFingerprint(),
                            release.status(),
                            release.requesterId(),
                            release.approverId(),
                            release.effectiveAt(),
                            inserted.getCreatedAt() == null ? release.createdAt() : inserted.getCreatedAt());
                }));
    }

    @Override
    public @NonNull Optional<ConfigRelease> findByReleaseId(@Nullable String tenantId, long releaseId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<ConfigRelease> row = mapper.findByReleaseId(releaseId)
                            .map(MybatisConfigReleaseRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public boolean markRolledBack(@Nullable String tenantId, long releaseId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn ->
                        mapper.markRolledBack(releaseId) > 0));
    }

    @Override
    public @NonNull Optional<ConfigRelease> findByPublishSeq(@NonNull String tenantId,
                                                              long configPublishSeq) {
        // ⚠️ 必须绑定**该租户**的上下文：
        //    平台级（零 UUID）上下文看不到租户行，读取会静默返回空。
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<ConfigRelease> row = mapper.findByPublishSeq(configPublishSeq)
                            .map(MybatisConfigReleaseRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull Optional<ConfigRelease> findLatestPublished(ConfigRelease.@NonNull Scope scope,
                                                                 String tenantId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<ConfigRelease> row = mapper.findLatestPublished(scope.name(), tenantId)
                            .map(MybatisConfigReleaseRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull Page<ConfigRelease> listReleases(@NonNull String tenantId,
                                                     @Nullable String cursor,
                                                     int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);

        List<ConfigRelease> rows = new ArrayList<>();
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId), conn -> {
                        List<ConfigReleaseDO> dbRows = mapper.listReleases(
                                tenantId,
                                after == null ? null : after.createdAt(),
                                after == null ? null : after.id(),
                                limit + 1);
                        for (ConfigReleaseDO dbRow : dbRows) {
                            ConfigRelease r = toRecord(dbRow);
                            rows.add(r);
                            shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId());
                        }
                        String next = null;
                        if (rows.size() > limit) {
                            ConfigRelease last = rows.get(limit - 1);
                            rows.subList(limit, rows.size()).clear();
                            next = new PageCursor(last.createdAt(),
                                    String.valueOf(last.releaseId())).encode();
                        }
                        return Page.of(rows, next);
                    }));
        } catch (RuntimeException e) {
            throw new IllegalStateException("查询发布历史失败", e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）—— 与原实现逐字一致
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：原仓既有构造签名被单测与 {@code @Bean} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
     */
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
                       @Nullable String tenantId,
                       boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                tenantId == null ? null : SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     * PLATFORM 作用域（主体/行租户为 {@code null}）不染色。
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, isShared);
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    private static ConfigRelease toRecord(ConfigReleaseDO d) {
        return new ConfigRelease(
                d.getReleaseId(),
                d.getConfigPublishSeq(),
                ConfigRelease.Scope.valueOf(d.getScope()),
                d.getTenantId(),
                d.getObjectVersions(),
                d.getAggregateFingerprint(),
                ConfigRelease.Status.valueOf(d.getStatus()),
                d.getRequesterId(),
                d.getApproverId(),
                d.getEffectiveAt(),
                d.getCreatedAt());
    }

    /**
     * 构造租户上下文（与原 Jdbc 实现逐字一致）。
     *
     * <p>⚠️ <b>PLATFORM 作用域必须绑定一个<b>合法 UUID</b>，不能绑 {@code null}</b>：
     * {@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}——
     * 绑非 UUID 文本会让 <b>类型转换直接报错</b>。
     */
    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", Set.of(), 0L);
    }
}
