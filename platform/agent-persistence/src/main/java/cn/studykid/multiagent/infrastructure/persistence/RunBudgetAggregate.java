package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import cn.studykid.multiagent.ports.persistence.BudgetPort;

/**
 * Run 级预算的<b>占用口径</b>（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>为什么必须是全仓唯一实现</b>：
 * 「这个 Run 已经花了多少」这个口径会被<b>两处</b>用到 ——
 * 一处是判定（预留时能不能再花），一处是观测（探针读剩余额度）。
 * 两处各写一遍聚合 SQL，等于两处可能对同一批预留给出不同解读：
 * 判定说「还剩 100」、观测说「已用 120」，于是「上限」到底是什么说不清，
 * 而这类矛盾<b>只在金额上体现</b>，排查成本极高。
 *
 * <p>⚠️ <b>口径本身</b>：
 * <ul>
 *   <li>{@code RESERVED}：记<b>预留额</b>（钱已经占住，尚未结算）；</li>
 *   <li>{@code SETTLED}：记<b>结算额</b>（实际花掉的，{@code settled ≤ reserved}）；</li>
 *   <li>{@code RELEASED}：<b>不计</b>（钱已退回，不再占用）。</li>
 * </ul>
 *
 * <p>⚠️ <b>天然单币种</b>：预留行本身不带币种（币种在 {@code budget_account} 上），
 * 但所有预留都记在<b>结算币种</b>账户上
 * （{@code JdbcBudgetRepository} 的条件 UPDATE 按
 * {@link BudgetPort#SETTLEMENT_CURRENCY} 过滤），
 * 故本聚合不会跨币种求和。
 * ⚠️ 若将来支持多币种，<b>必须</b>在此显式按币种过滤 ——
 * 否则「CNY 预留 + USD 预留」会被当成同一个数相加。
 */
public final class RunBudgetAggregate {

    /**
     * 未结算预留的聚合表达式（配合别名 {@code b} 使用）。
     */
    public static final String RESERVED_AMOUNT_EXPRESSION =
            "COALESCE(SUM(CASE WHEN b.state = 'RESERVED' THEN b.reserved_amount ELSE 0 END), 0)";

    /**
     * 已结算消耗的聚合表达式（配合别名 {@code b} 使用）。
     */
    public static final String SETTLED_AMOUNT_EXPRESSION =
            "COALESCE(SUM(CASE WHEN b.state = 'SETTLED' THEN b.settled_amount ELSE 0 END), 0)";

    private RunBudgetAggregate() {
    }

    /**
     * 读取某个 Run 当前已占用的额度。
     *
     * <p>⚠️ <b>必须在能串行化同一 Run 预留的上下文里调用</b>
     * （即已持有 {@code run} 行锁的事务内）：
     * 否则本查询与随后的扣减之间存在 check-then-act 窗口，
     * 并发预留会同时读到同一份旧聚合而全部通过判定。
     *
     * @param conn     连接（须处于事务内）
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 已占用额度（无预留时为 0）
     * @throws SQLException 查询失败
     */
    public static BigDecimal occupied(Connection conn, String tenantId, String runId)
            throws SQLException {
        String sql = """
                SELECT %s + %s
                FROM budget_reservation b
                WHERE b.tenant_id = ?::uuid AND b.run_id = ?::uuid
                """.formatted(RESERVED_AMOUNT_EXPRESSION, SETTLED_AMOUNT_EXPRESSION);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BigDecimal.ZERO;
                }
                BigDecimal value = rs.getBigDecimal(1);
                return value == null ? BigDecimal.ZERO : value;
            }
        }
    }
}
