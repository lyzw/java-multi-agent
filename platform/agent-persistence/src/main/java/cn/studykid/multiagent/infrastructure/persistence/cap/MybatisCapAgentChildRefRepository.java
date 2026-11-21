package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapAgentChildRefMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapAgentChildRefDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;

/**
 * {@link CapAgentChildRefPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapAgentChildRefRepository}）。
 *
 * <p>Port 签名零改动；五条 SQL 逐字搬迁到 {@code CapAgentChildRefMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 幂等插入（ON CONFLICT DO NOTHING）、租户 ACTIVE 绑定过滤、影子比对/逐行染色全部与原实现一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * 全局表写入绑定零 UUID 哨兵，租户查询绑定该租户（与原实现一致）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapAgentChildRefRepository implements CapAgentChildRefPort {

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_agent_child_ref";

    private static final String GLOBAL_CONTEXT_TENANT = "00000000-0000-0000-0000-000000000000";

    private final CapAgentChildRefMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（默认 null / false ⇒ 现有行为 100% 不变）──
    // 非 final：由 Spring 启动期通过 bindAuthzShadow 一次性绑定，之后只读。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    @Autowired
    public MybatisCapAgentChildRefRepository(@NonNull CapAgentChildRefMapper mapper,
                                             @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisCapAgentChildRefRepository(@NonNull CapAgentChildRefMapper mapper,
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
    public boolean insert(@NonNull CapAgentChildRef ref) {
        return inTenantScope(GLOBAL_CONTEXT_TENANT, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT), conn ->
                        mapper.insert(ref.parentAgentId(), ref.parentReleaseVersion(),
                                ref.childAgentId(), ref.childReleaseVersion(),
                                ref.ordinal(), ref.required(), ref.createdAt()) > 0));
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentRelease(@NonNull String tenantId,
                                                                @NonNull String parentAgentId,
                                                                int parentReleaseVersion) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    for (CapAgentChildRefDO dbRow : mapper.listByParentRelease(
                            parentAgentId, parentReleaseVersion, tenantId)) {
                        rows.add(toRecord(dbRow));
                        shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                    }
                    return rows;
                }));
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentAgent(@NonNull String tenantId,
                                                              @NonNull String parentAgentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    for (CapAgentChildRefDO dbRow : mapper.listByParentAgent(parentAgentId, tenantId)) {
                        rows.add(toRecord(dbRow));
                        shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                    }
                    return rows;
                }));
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByChildAgent(@NonNull String tenantId,
                                                              @NonNull String childAgentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    for (CapAgentChildRefDO dbRow : mapper.listByChildAgent(childAgentId, tenantId)) {
                        rows.add(toRecord(dbRow));
                        shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                    }
                    return rows;
                }));
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByTenant(@NonNull String tenantId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    for (CapAgentChildRefDO dbRow : mapper.listByTenant(tenantId)) {
                        rows.add(toRecord(dbRow));
                        shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                    }
                    return rows;
                }));
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理（try-finally）。 */
    private <T> T inTenantScope(@NonNull String tenantId, Supplier<T> action) {
        TenantScope.bind(tenantId); // 全局写入绑哨兵，租户查询绑认证上下文 tenantId
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapAgentChildRef toRecord(CapAgentChildRefDO d) {
        return new CapAgentChildRef(
                d.getParentAgentId(), d.getParentReleaseVersion(),
                d.getChildAgentId(), d.getChildReleaseVersion(),
                d.getOrdinal(), d.isRequired(),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt());
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口：容器里存在 {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>用 setter 而不是可选构造参数：既有构造签名被单测与 {@code @Bean} 装配依赖。
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

    void shadowCompare(@NonNull String resourceType, @NonNull String tenantId, boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

    void shadowTaint(@NonNull String resourceType,
                     @NonNull String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                SubjectContext.forTenant(subjectTenantId), rowTenantId, isShared);
    }
}
