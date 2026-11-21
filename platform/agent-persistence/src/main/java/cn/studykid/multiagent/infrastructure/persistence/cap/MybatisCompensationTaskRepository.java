package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
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

import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CompensationTaskMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CompensationTaskDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;

/**
 * {@link CompensationTaskPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCompensationTaskRepository}）。
 *
 * <p>⚠️ <b>入队幂等靠唯一约束，不靠「先查后插」</b>：
 * {@code UNIQUE (tenant_id, kind, target_id)} + {@code ON CONFLICT DO NOTHING}。
 * 先查后插存在 check-then-act 窗口 —— 并发补偿失败会落两条任务，
 * 而补偿必须幂等，多余的一条会让额度被释放两次（额度凭空放大）。
 *
 * <p>⚠️ <b>扫描只取「到期可重试」的任务</b>：{@code next_retry_at IS NULL OR next_retry_at <= now()}。
 * 无退避的直接重试会在下游持续故障时形成热循环。
 *
 * <p>⚠️ <b>P1 影子态：只观测读路径</b>：{@code findById} / {@code findPending} 已接影子，
 * 两者 SQL 都自带显式 {@code tenant_id = #{tenantId}::uuid} 谓词；
 * 写路径（enqueue / markCompleted / markFailed）属 P2 范围，不观测。
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
public class MybatisCompensationTaskRepository implements CompensationTaskPort {

    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_compensation_task";

    private final CompensationTaskMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public MybatisCompensationTaskRepository(@NonNull CompensationTaskMapper mapper,
                                             @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisCompensationTaskRepository(@NonNull CompensationTaskMapper mapper,
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
    public boolean enqueue(@NonNull CapCompensationTask task) {
        return inTenantScope(task.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(task.tenantId()), conn ->
                        mapper.insertIfAbsent(
                                task.taskId(),
                                task.tenantId(),
                                task.kind().name(),
                                task.targetId(),
                                task.invocationId(),
                                task.payload(),
                                task.state().name(),
                                task.attempts(),
                                task.lastError(),
                                task.createdAt(),
                                task.updatedAt(),
                                task.nextRetryAt()) > 0));
    }

    @Override
    public @NonNull Optional<CapCompensationTask> findById(@NonNull String tenantId,
                                                           @NonNull String taskId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = #{tenantId}::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<CapCompensationTask> row =
                            mapper.findById(tenantId, taskId).map(MybatisCompensationTaskRepository::toRecord);
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull List<CapCompensationTask> findPending(@NonNull String tenantId, int limit) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = #{tenantId}::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    // 与旧实现同口径：limit 下限夹到 1（负数/0 不得变成「取全部」）。
                    List<CompensationTaskDO> rows = mapper.findPending(tenantId, Math.max(1, limit));
                    List<CapCompensationTask> out = new ArrayList<>(rows.size());
                    for (CompensationTaskDO d : rows) {
                        CapCompensationTask r = toRecord(d);
                        shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId());
                        out.add(r);
                    }
                    return out;
                }));
    }

    @Override
    public boolean markCompleted(@NonNull String tenantId, @NonNull String taskId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn ->
                        mapper.markCompleted(tenantId, taskId) > 0));
    }

    @Override
    public boolean markFailed(@NonNull String tenantId,
                              @NonNull String taskId,
                              @NonNull String error,
                              @NonNull Instant nextRetryAt) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn ->
                        mapper.markFailed(tenantId, taskId, error, nextRetryAt) > 0));
    }

    // ── DO -> record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapCompensationTask toRecord(CompensationTaskDO d) {
        return new CapCompensationTask(
                d.getTaskId(),
                d.getTenantId(),
                CapCompensationTask.CompensationKind.valueOf(d.getKind()),
                d.getTargetId(),
                d.getInvocationId(),
                d.getPayload(),
                CapCompensationTask.TaskState.valueOf(d.getState()),
                d.getAttempts(),
                d.getLastError(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getNextRetryAt());
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
