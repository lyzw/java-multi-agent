package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapResultContractMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapResultContractDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;

/**
 * {@link CapResultContractPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapResultContractRepository}）。
 *
 * <p>⚠️ <b>写入是「插入不可变版本行」，不是更新</b>：同
 * {@code (tenantId, contractId, version)} 已存在时返回 {@code false} 且不覆盖。
 * 改契约只能发新版本 —— 否则在途 Run 按快照引用的旧契约会被就地改写，
 * 历史可追溯性就没了。
 *
 * <p>⚠️ <b>RLS 表</b>：必须经 {@code TenantScope.bind(tenantId)} 绑定租户上下文后查询
 * （外层 WHERE tenant_id 与 RLS 策略双保险）。
 *
 * <p>⚠️ <b>{@code contract_id} 是可读字符串，不是 UUID</b>（V23 修复）：
 * SQL 里只有 tenant_id 带 {@code ::uuid} cast。曾把该列声明为 {@code UUID} 并在 SQL 里写
 * {@code ?::uuid} → 非 UUID 形式的契约 id 会让<b>整个发布事务回滚</b>（PSQLException），
 * 运行期的契约解析也每次抛异常 → 委派全部 fail-closed。
 * ⚠️ 该转换在 mock 连接的单元测试里<b>从不真正执行</b>，故只有 DB 层 IT 才能发现它。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapResultContractRepository implements CapResultContractPort {
    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_result_contract";

    private final CapResultContractMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public MybatisCapResultContractRepository(@NonNull CapResultContractMapper mapper,
                                              @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisCapResultContractRepository(@NonNull CapResultContractMapper mapper,
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
    public boolean insert(@NonNull CapResultContractDef def) {
        return inTenantScope(def.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(def.tenantId()), conn ->
                        mapper.insertVersion(
                                def.tenantId(),
                                def.contractId(),
                                def.version(),
                                def.schemaJson(),
                                def.configFingerprint(),
                                def.schemaVersion(),
                                def.createdAt(),
                                def.publishedAt()) > 0));
    }

    @Override
    public @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                         @NonNull String contractId,
                                                         int version) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<CapResultContractDef> row = mapper.find(tenantId, contractId, version)
                            .map(MybatisCapResultContractRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull Optional<CapResultContractDef> findLatestPublished(
            @NonNull String tenantId, @NonNull String contractId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<CapResultContractDef> row =
                            mapper.findLatestPublished(tenantId, contractId)
                                    .map(MybatisCapResultContractRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull Optional<CapResultContractDef> findLatest(@NonNull String tenantId,
                                                              @NonNull String contractId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<CapResultContractDef> row = mapper.findLatest(tenantId, contractId)
                            .map(MybatisCapResultContractRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    // ── DO → record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapResultContractDef toRecord(CapResultContractDO d) {
        return new CapResultContractDef(
                d.getTenantId(),
                d.getContractId(),
                d.getVersion(),
                d.getSchemaJson(),
                d.getConfigFingerprint(),
                d.getSchemaVersion(),
                d.getCreatedAt(),
                d.getPublishedAt(),
                lifecycleState(d.getLifecycleState()));
    }

    /**
     * 读取生命周期状态（§6.6）。
     *
     * <p>⚠️ <b>「读不到状态」绝不能被降级成「没有停用」</b>：
     * 本方法<b>不吞异常</b> —— 查询语句显式选择了 {@code lifecycle_state::text}，
     * 列不存在（迁移 V26 未应用）时 SQL 直接失败，
     * 而不是返回 {@code null} 让调用方以为「这行没被停用」。
     * 后者会让「停用」这道防线<b>看起来在、其实不在</b>：
     * 发布期引用校验读不到 DEPRECATED 状态 → 停用过的契约照样能被新发布引用。
     * 数据层失败必须<b>显式炸出来</b>（fail-closed），由迁移纪律保证列一定存在。
     *
     * @param raw 生命周期状态原始文本
     * @return 生命周期状态；值为空时为 {@code null}（= 未记录）
     */
    private static @Nullable ConfigLifecycleState lifecycleState(@Nullable String raw) {
        return raw == null ? null : ConfigLifecycleState.valueOf(raw);
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
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

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }
}
