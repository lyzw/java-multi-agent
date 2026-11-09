package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunBudgetPort;

/**
 * {@link RunBudgetPort} 的 JDBC 实现（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>读口径</b>：Run 级额度来自 {@code run.budget_amount}，
 * 已用量来自 {@code budget_reservation} 按 {@code run_id} 聚合：
 * <ul>
 *   <li>未结算的预留（{@code state = 'RESERVED'}）仍占用额度；</li>
 *   <li>已结算的消耗（{@code state = 'SETTLED'}）已花掉额度。</li>
 * </ul>
 * 已释放的预留（{@code state = 'RELEASED'}）不计入 —— 钱已退回。
 *
 * <p>⚠️ <b>「未声明」与「读失败」必须可区分</b>：
 * {@code run.budget_amount IS NULL} → 返回 {@link Optional#empty()}
 * （= 不额外限制）；SQL/连接异常 → 抛出（调用方 fail-closed）。
 * 把两者混为一谈会双向出事：当成「不限制」则约束失效，
 * 当成「余额为 0」则所有调用被拒。
 *
 * <p>⚠️ <b>聚合口径与租户级预留的一致性</b>：本查询读的是预留行，
 * 而预留行由租户级原子预留写入。因此 Run 级判定与租户级判定
 * 看到的是<b>同一批预留</b> —— 不会出现「Run 级算了一份、租户级算另一份」。
 */
public class JdbcRunBudgetRepository implements RunBudgetPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunBudgetRepository.class);

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Bean 装配不变）。 */
    @Autowired
    public JdbcRunBudgetRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcRunBudgetRepository(@NonNull DataSource dataSource,
                            @Nullable ScopedDataAccess scoped,
                            @Nullable AuthzShadowReporter reporter,
                            boolean shadowEnabled,
                            @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Optional<RunBudget> find(@NonNull String tenantId, @NonNull String runId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("run", tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new cn.studykid.multiagent.core.tenant.TenantContext(
                        tenantId, "worker", java.util.Set.of(), 0L),
                conn -> {
                    // ⚠️ 单条 SQL 取齐额度与已用量：分两次查会看到**不一致**的两个快照
                    //    （额度读到了、预留还没算进去 → 误判「还有额度」）。
                    //    ⚠️ 占用口径来自 RunBudgetAggregate（全仓唯一实现）：
                    //    判定侧与观测侧必须用同一份定义，否则「上限」有两个解释。
                    String sql = """
                            SELECT r.tenant_id AS tenant_id,
                                   r.budget_amount,
                                   %s
                                       AS reserved_amount,
                                   %s
                                       AS settled_amount
                            FROM run r
                            LEFT JOIN budget_reservation b
                                   ON b.tenant_id = r.tenant_id AND b.run_id = r.run_id
                            WHERE r.tenant_id = ?::uuid AND r.run_id = ?::uuid
                            GROUP BY r.run_id, r.budget_amount
                            """.formatted(
                            RunBudgetAggregate.RESERVED_AMOUNT_EXPRESSION,
                            RunBudgetAggregate.SETTLED_AMOUNT_EXPRESSION);
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                // ⚠️ Run 不存在：不是「未声明额度」，而是数据不可信
                                //    → 抛异常，由调用方 fail-closed。
                                throw new IllegalStateException(
                                        "Run 不存在，无法读取 Run 级预算: run=" + runId);
                            }
                            // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                            String rowTenant = rs.getString("tenant_id");
                            BigDecimal amount = rs.getBigDecimal("budget_amount");
                            if (amount == null) {
                                // ⚠️ 未声明额度 = 不额外限制（≠ 余额为 0）
                                shadowTaint("run", tenantId, rowTenant);
                                return Optional.empty();
                            }
                            RunBudget budget = new RunBudget(
                                    runId,
                                    amount,
                                    nonNull(rs.getBigDecimal("reserved_amount")),
                                    nonNull(rs.getBigDecimal("settled_amount")));
                            shadowTaint("run", tenantId, rowTenant);
                            return Optional.of(budget);
                        }
                    }
                });
    }

    /** 聚合结果不得为 null（COALESCE 已兜底，这里再兜一层以防驱动差异）。 */
    private static @NonNull BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭。
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
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）。
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
