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

import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ExecutionIntentMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ExecutionIntentDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ExecutionIntentPort;

/**
 * {@link ExecutionIntentPort} 的 MyBatis-Plus 实现（W2 迁移，对应 {@code JdbcExecutionIntentRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code ExecutionIntentMapper.xml}，仅 {@code ?} -> {@code #{param}}。
 * 状态迁移仍是「期望原状态」CAS（防同一意图被消费两次），写入后回读（ON CONFLICT DO NOTHING 时返回既有行），
 * 影子钩子顺序与「执行意图写入后读不到」消息全部保留。
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
public class MybatisExecutionIntentRepository implements ExecutionIntentPort {

    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_execution_intent";

    private final ExecutionIntentMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public MybatisExecutionIntentRepository(@NonNull ExecutionIntentMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisExecutionIntentRepository(@NonNull ExecutionIntentMapper mapper,
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
    public @NonNull ExecutionIntent record(@NonNull ExecutionIntent intent) {
        return inTenantScope(intent.tenantId(), () -> {
            TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(intent.tenantId()),
                    conn -> {
                        mapper.recordIntent(
                                intent.intentId(),
                                intent.tenantId(),
                                intent.runId(),
                                intent.invocationId(),
                                intent.toolId(),
                                intent.toolVersion(),
                                intent.argsFingerprint(),
                                intent.approvalId(),
                                intent.intentState().name(),
                                intent.createdAt(),
                                intent.updatedAt());
                        return null;
                    });
            // ⚠️ 回读而非直接返回入参：ON CONFLICT DO NOTHING 时本次可能<b>没有</b>写入，
            //    此时生效的是既有行。返回入参会掩盖「已被别人写过」这一事实，
            //    而调用方（P2 步骤 13a）需要知道意图是否由本次调用真正落盘。
            return findByInvocation(intent.tenantId(), intent.invocationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "执行意图写入后读不到（intent=" + intent.invocationId() + "）"));
        });
    }

    @Override
    public @NonNull Optional<ExecutionIntent> findByInvocation(@NonNull String tenantId,
                                                               @NonNull String invocationId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = #{tenantId}::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<ExecutionIntentDO> row = mapper.findByInvocation(tenantId, invocationId);
                    if (row.isEmpty()) {
                        return Optional.<ExecutionIntent>empty();
                    }
                    ExecutionIntentDO dob = row.get();
                    // ⚠️ hasTenantPredicate=true / 逐行染色：行归属从结果行的 tenant_id 读出。
                    shadowTaint(RESOURCE_TYPE, tenantId, dob.getTenantId());
                    return Optional.of(toRecord(dob));
                }));
    }

    @Override
    public boolean transitionState(@NonNull String tenantId,
                                   @NonNull String invocationId,
                                   ExecutionIntent.@NonNull IntentState expectedFrom,
                                   ExecutionIntent.@NonNull IntentState to) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.transitionState(
                        to.name(), tenantId, invocationId, expectedFrom.name()) > 0));
    }

    @Override
    public @NonNull List<ExecutionIntent> findPending(@NonNull String tenantId,
                                                         int limit) {
        // ⚠️ 只捞「尚未终结」的两态（RESERVED / INTENT_SUBMITTED）：
        //    CONSUMED 已终结、CANCELLED/EXPIRED 已旁路掉 —— 捞进来只会拖慢恢复扫描。
        // ⚠️ 按创建时间升序：恢复必须<b>按发生顺序</b>处理，
        //    否则先处理后发生的意图会让先发生的意图永远等不到它的结果。
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = #{tenantId}::uuid`。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    List<ExecutionIntentDO> rows = mapper.findPending(tenantId, Math.max(1, limit));
                    List<ExecutionIntent> out = new ArrayList<>();
                    for (ExecutionIntentDO dob : rows) {
                        shadowTaint(RESOURCE_TYPE, tenantId, dob.getTenantId());
                        out.add(toRecord(dob));
                    }
                    return out;
                }));
    }

    // ── DO ↔ record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static ExecutionIntent toRecord(ExecutionIntentDO d) {
        return new ExecutionIntent(
                d.getIntentId(),
                d.getTenantId(),
                d.getRunId(),
                d.getInvocationId(),
                d.getToolId(),
                d.getToolVersion(),
                d.getArgsFingerprint(),
                d.getApprovalId(),
                ExecutionIntent.IntentState.valueOf(d.getIntentState()),
                d.getCreatedAt() == null ? Instant.EPOCH : d.getCreatedAt(),
                d.getUpdatedAt() == null ? Instant.EPOCH : d.getUpdatedAt());
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
     * 逐行染色：结果行的真实 tenant_id 与 Gate 算出的 ScopeFilter 比对。
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

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }
}
