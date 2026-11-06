package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public JdbcRunBudgetRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<RunBudget> find(@NonNull String tenantId, @NonNull String runId) {
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
                            SELECT r.budget_amount,
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
                            BigDecimal amount = rs.getBigDecimal("budget_amount");
                            if (amount == null) {
                                // ⚠️ 未声明额度 = 不额外限制（≠ 余额为 0）
                                return Optional.empty();
                            }
                            return Optional.of(new RunBudget(
                                    runId,
                                    amount,
                                    nonNull(rs.getBigDecimal("reserved_amount")),
                                    nonNull(rs.getBigDecimal("settled_amount"))));
                        }
                    }
                });
    }

    /** 聚合结果不得为 null（COALESCE 已兜底，这里再兜一层以防驱动差异）。 */
    private static @NonNull BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
