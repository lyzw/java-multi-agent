package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.DelegationBudgetMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.DelegationBudgetHeldDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort;

/**
 * {@link DelegationBudgetPort} 的 MyBatis-Plus 实现（对应 {@code JdbcDelegationBudgetRepository}）。
 *
 * <p>Port 签名零改动；两条反查 SQL 逐字搬迁到 {@code DelegationBudgetMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。{@code LEFT JOIN} + {@code NOT EXISTS tool_operation}
 * 的「非工具侧」互补判据、按创建时间升序、无锚点行（{@code status} 为空）一并保留。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisDelegationBudgetRepository implements DelegationBudgetPort {

    private static final Logger log =
            LoggerFactory.getLogger(MybatisDelegationBudgetRepository.class);

    private final DelegationBudgetMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参 DataSource 构造口径，额外注入 Mapper）。 */
    @Autowired
    public MybatisDelegationBudgetRepository(@NonNull DelegationBudgetMapper mapper,
                                             @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    MybatisDelegationBudgetRepository(@NonNull DelegationBudgetMapper mapper,
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
    public @NonNull List<HeldReservation> findHeldReservations(@NonNull String tenantId) {
        shadowCompare("budget_reservation", tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.findHeld(tenantId).stream()
                        .map(row -> {
                            HeldReservation held = readHeld(row);
                            shadowTaint("budget_reservation", tenantId, row.getTenantId());
                            // D13：LEFT JOIN 从表在无匹配时为 null → 跳过（不误判）
                            shadowTaint("agent_invocation", tenantId, row.getJoinTenantId());
                            return held;
                        })
                        .collect(Collectors.toCollection(ArrayList::new))));
    }

    @Override
    public @NonNull Optional<HeldReservation> findHeldReservation(@NonNull String tenantId,
                                                                   @NonNull String operationId) {
        shadowCompare("budget_reservation", tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> mapper.findHeldByOperation(tenantId, operationId).stream()
                        .findFirst()
                        .map(row -> {
                            shadowTaint("budget_reservation", tenantId, row.getTenantId());
                            shadowTaint("agent_invocation", tenantId, row.getJoinTenantId());
                            return readHeld(row);
                        })));
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖，
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

    /** 读一行持有中的预留（与原 {@code readHeld} 逐字段一致）。 */
    private static @NonNull HeldReservation readHeld(@NonNull DelegationBudgetHeldDO row) {
        return new HeldReservation(
                row.getReservationId(),
                row.getOperationId(),
                row.getReservedAmount(),
                parseStatus(row.getStatus()),
                row.getRunId(),
                row.getCreatedAt());
    }

    /**
     * 解析委派调用状态。
     *
     * <p>⚠️ <b>无法识别的状态按「无锚点」处理</b>（返回 {@code null}），
     * 而不是当成某个终态 —— 未知状态绝不能被当成「已结束」而自动结算。
     */
    private static @Nullable AgentInvocationStatus parseStatus(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AgentInvocationStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("未知的委派调用状态，按无锚点处理（不自动处置）: {}", raw);
            return null;
        }
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理（try-finally）。 */
    private <T> T inTenantScope(@NonNull String tenantId, Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    private static @NonNull TenantContext tenantContext(@NonNull String tenantId) {
        return new TenantContext(tenantId, "worker", Set.of(), 0L);
    }
}
