package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.AuthzVersionMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuthzVersionDO;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuthzWatermarkDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;

/**
 * {@link AuthzVersionPort} 的 MyBatis-Plus 实现（W2 迁移，对应 {@code JdbcAuthzVersionRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code AuthzVersionMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * 递增仍是 DB 单条原子 upsert（禁止「读版本 -> Java 加一 -> 写回」），
 * {@code ON CONFLICT DO UPDATE} 的表名限定、影子钩子顺序、fail-closed 异常与消息全部保留。
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
public class MybatisAuthzVersionRepository implements AuthzVersionPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisAuthzVersionRepository.class);

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_authz_version";

    private final AuthzVersionMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2；阶段一 P0-1b 第 1 批）────────────────
    // 默认全部为 null / false => 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    @Autowired
    public MybatisAuthzVersionRepository(@NonNull AuthzVersionMapper mapper,
                                         @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled    是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    MybatisAuthzVersionRepository(@NonNull AuthzVersionMapper mapper,
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
    public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                 @NonNull String principalId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<AuthzVersion> row = mapper.find(tenantId, principalId)
                            .map(MybatisAuthzVersionRepository::toRecord);
                    row.ifPresent(v -> shadowTaint(RESOURCE_TYPE, tenantId, v.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull AuthzVersion require(@NonNull String tenantId, @NonNull String principalId) {
        Optional<AuthzVersion> found = find(tenantId, principalId);
        if (found.isPresent()) {
            return found.get();
        }
        // ⚠️ fail-closed：无记录 = 无法确认版本新鲜度 -> 拒绝，绝不降级放行
        //    （06 §1.5 第 6 条 / 04 §A9）。此时调用方须以 AUTHZ_UNAVAILABLE 拒绝。
        throw new AuthzUnavailableException(tenantId, principalId,
                "授权版本记录不存在，无法确认版本新鲜度（fail-closed 拒绝）", null);
    }

    @Override
    public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                            @NonNull String principalId) {
        return inTenantScope(tenantId, () -> {
            TransactionalConnectionHolder.inTransaction(dataSource, tenantContext(tenantId), conn -> {
                mapper.initialize(tenantId, principalId);
                return null;
            });
            return require(tenantId, principalId);
        });
    }

    @Override
    public long bump(@NonNull String tenantId, @NonNull String principalId) {
        long version = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.bump(tenantId, principalId)
                        .map(AuthzVersionDO::getAuthzVersion)
                        // ⚠️ fail-closed：递增未返回结果 = 存储层异常，绝不静默返回旧值
                        .orElseThrow(() -> new IllegalStateException("授权版本递增未返回结果"))));
        if (log.isDebugEnabled()) {
            log.debug("授权版本已递增: tenant={}, principal={}, authzVersion={}",
                    tenantId, principalId, version);
        }
        return version;
    }

    /**
     * 租户级授权版本水位聚合（管理面 P4 运行监控）。
     *
     * <p>⚠️ 单条 SQL 保证 count(*) / count FILTER / max / max(updated_at) 在同一快照上求值。
     *
     * <p>⚠️ 无记录时返回零值快照而不是空：该租户还没有任何授权版本记录是合法的初始状态。
     */
    @Override
    public @NonNull AuthzWatermark watermark(@NonNull String tenantId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    AuthzWatermarkDO row = mapper.watermark(tenantId);
                    if (row == null) {
                        // ⚠️ 聚合查询无匹配行时返回 0 值行；
                        //    但本 SQL 无 GROUP BY，恒有一行 —— 保留兜底以防 SQL 变更
                        return new AuthzWatermark(
                                tenantId, 0L, 0L, 0L, null, Instant.now());
                    }
                    return new AuthzWatermark(
                            tenantId,
                            row.getSubjectCount(),
                            row.getRevokedSubjects(),
                            row.getMaxAuthzVersion(),
                            row.getLastBumpAt(),
                            Instant.now());
                }));
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖。
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

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    private static AuthzVersion toRecord(AuthzVersionDO d) {
        return new AuthzVersion(
                d.getTenantId(),
                d.getPrincipalId(),
                d.getAuthzVersion(),
                d.getUpdatedAt() == null ? Instant.EPOCH : d.getUpdatedAt());
    }

    /**
     * 构造仅用于绑定租户上下文的上下文。
     *
     * <p>⚠️ {@code principalId} 用固定占位值：本仓储的操作<b>按调用方显式传入的
     * principalId</b> 定位记录，不依赖上下文的 principal。
     */
    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
