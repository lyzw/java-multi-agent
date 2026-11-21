package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapabilitySnapshotMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapabilitySnapshotDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * {@link CapabilitySnapshotPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapabilitySnapshotRepository}）。
 *
 * <p><b>快照不可变</b>：写入用 {@code ON CONFLICT (run_id) DO NOTHING}，
 * 同一 runId 的重复保存<b>不覆盖</b>既有快照（06 §2「历史可追溯」）。
 * 快照被改写 = 历史失真，事后无法解释「当时到底按哪版配置跑的」。
 *
 * <p>⚠️ 全部走 {@link TransactionalConnectionHolder} + {@link TenantScope}：该表启用了
 * {@code ENABLE + FORCE ROW LEVEL SECURITY}，未绑定 {@code app.tenant_id}
 * 时可见 <b>0 行</b> —— 症状是「快照明明写了却读不出来」，
 * 与「Run 真的没建快照」症状完全相同，极易把排查方向带偏。
 *
 * <p>⚠️ <b>P1 影子态：只观测读路径</b>：{@code find} 已接影子，SQL 自带显式
 * {@code tenant_id = #{tenantId}::uuid} 谓词；写路径（save）属 P2 范围，不观测。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapabilitySnapshotRepository implements CapabilitySnapshotPort {

    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "execution_capability_snapshot";

    private final CapabilitySnapshotMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public MybatisCapabilitySnapshotRepository(@NonNull CapabilitySnapshotMapper mapper,
                                               @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisCapabilitySnapshotRepository(@NonNull CapabilitySnapshotMapper mapper,
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
    public boolean save(@NonNull CapabilitySnapshot snapshot) {
        return inTenantScope(snapshot.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(snapshot.tenantId()), conn ->
                        mapper.insertIfAbsent(
                                snapshot.runId(),
                                snapshot.tenantId(),
                                snapshot.agentId(),
                                snapshot.effectiveClosure(),
                                snapshot.agentVersion(),
                                snapshot.configVersion(),
                                snapshot.configFingerprint(),
                                snapshot.authzVersion(),
                                snapshot.createdAt()) > 0));
    }

    @Override
    public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                      @NonNull String runId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = #{tenantId}::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<CapabilitySnapshot> row = mapper.find(tenantId, runId)
                            .map(MybatisCapabilitySnapshotRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    // ── DO -> record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapabilitySnapshot toRecord(CapabilitySnapshotDO d) {
        return new CapabilitySnapshot(
                d.getRunId(),
                d.getTenantId(),
                d.getAgentId(),
                d.getEffectiveClosure(),
                d.getAgentVersion(),
                d.getConfigVersion(),
                d.getConfigFingerprint(),
                d.getAuthzVersion(),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
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

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
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

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。
     *
     * @param hasTenantPredicate 该查询的 SQL 是否真的带了显式租户谓词（必须如实填写）。
     */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     */
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
