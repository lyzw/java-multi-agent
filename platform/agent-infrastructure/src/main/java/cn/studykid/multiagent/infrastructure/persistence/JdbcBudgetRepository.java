package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.BudgetPort;

/**
 * {@link BudgetPort} 的 JDBC 实现（P3-D04 / FR-012）。
 *
 * <p><b>⚠️ 四条不变量</b>：
 * <ol>
 *   <li><b>预留是单条原子条件 UPDATE</b>（§2.1 D-1）——
 *       {@code WHERE reserved + settled + ? <= total_limit}。
 *       绝不「先 SELECT 检查再 UPDATE」：检查与扣减之间的窗口会让
 *       N 个并发同时读到余额充足而全部成功，直接超发；</li>
 *   <li><b>「账户不存在」与「额度不足」必须可区分</b>（§2.2 D-2）——
 *       两者都返回 0 行，故需先判定账户存在性再决定语义；</li>
 *   <li><b>计量插入与账户更新同事务</b>（§2.5 D-5）——
 *       半边落地会造成永久账实不符，且重复结算会凭空多计费；</li>
 *   <li><b>连接所有权</b>——全部经
 *       {@link TransactionalConnectionHolder#inTransaction}
 *       （P2-D5 铁律：禁止直接 {@code dataSource.getConnection()}）。</li>
 * </ol>
 *
 * <p><b>为何不用 {@code SELECT ... FOR UPDATE}</b>：行锁会把并发预留
 * 串行化，而条件更新在 PG 行级 MVCC 下已经原子，失败方无需等待即可返回。
 * 加锁只是把「并发拒绝」换成「并发排队」，在 DoS 场景下更糟（D-2 限流）。
 */
public class JdbcBudgetRepository implements BudgetPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcBudgetRepository.class);

    /** 本平台只结算人民币；跨币种折算属 P4（L-6）。 */
    private static final String DEFAULT_CURRENCY = "CNY";

    private final DataSource dataSource;

    public JdbcBudgetRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── 预留 ────────────────────────────────────────────────────────

    @Override
    public @NonNull ReserveOutcome reserve(@NonNull String tenantId,
                                           @NonNull String runId,
                                           @Nullable String operationId,
                                           @NonNull BigDecimal amount) {
        // ⚠️ 金额非法一律 fail-closed：不退回默认值，不按 0 处理
        if (amount == null || amount.signum() <= 0) {
            log.warn("预算预留金额非法，fail-closed 拒绝: tenant={}, amount={}", tenantId, amount);
            return ReserveOutcome.FAIL_CLOSED;
        }

        return inTenantTx(tenantId, conn -> {
            // ① 幂等：同 operation 已有预留 → 直接复用（D-3）
            if (operationId != null) {
                Optional<Reservation> existing = findByOperation(conn, tenantId, operationId);
                if (existing.isPresent()) {
                    if (log.isDebugEnabled()) {
                        log.debug("预算预留幂等命中: operation={}, reservation={}",
                                operationId, existing.get().reservationId());
                    }
                    return ReserveOutcome.REPLAYED;
                }
            }

            // ② 账户存在性判定（D-2：与「额度不足」语义不同）
            if (!accountExists(conn, tenantId)) {
                log.warn("租户未开通预算账户，fail-closed 拒绝: tenant={}", tenantId);
                return ReserveOutcome.NO_ACCOUNT;
            }

            // ③ 原子条件更新（D-1）：判定与扣减在同一语句内
            boolean reserved = tryAtomicReserve(conn, tenantId, amount);
            if (!reserved) {
                log.warn("预算额度不足，拒绝预留: tenant={}, 请求={}", tenantId, amount);
                return ReserveOutcome.INSUFFICIENT;
            }

            // ④ 写预留行。并发下唯一索引可能让本行插入失败 →
            //    账户已扣的额度必须退回，否则「扣了额度没记录」= 永久泄漏
            String reservationId = insertReservation(conn, tenantId, runId, operationId, amount);
            if (reservationId == null) {
                // 唯一冲突（同 operation 并发预留）→ 退回本次扣减并复用既有预留
                rollbackSingleReserve(conn, tenantId, amount);
                if (log.isDebugEnabled()) {
                    log.debug("并发预留撞唯一索引，回退并复用: operation={}", operationId);
                }
                return ReserveOutcome.REPLAYED;
            }

            if (log.isInfoEnabled()) {
                log.info("预算预留成功: reservation={}, run={}, operation={}, amount={}",
                        reservationId, runId, operationId, amount);
            }
            return ReserveOutcome.RESERVED;
        });
    }

    /**
     * 单条原子条件更新（D-1 的核心）。
     *
     * <p>⚠️ 条件必须在 UPDATE 的 WHERE 内，而不是「先 SELECT 再判断」：
     * 只有这样，判定与扣减才在数据库的同一原子操作内完成。
     *
     * @return true=额度已扣；false=额度不足（账户存在但余额不够）
     */
    private boolean tryAtomicReserve(Connection conn, String tenantId, BigDecimal amount)
            throws SQLException {
        String sql = """
                UPDATE budget_account
                SET reserved_amount = reserved_amount + ?,
                    updated_at = now()
                WHERE tenant_id = ?::uuid
                  AND currency = ?
                  AND reserved_amount + settled_amount + ? <= total_limit
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, tenantId);
            ps.setString(3, DEFAULT_CURRENCY);
            ps.setBigDecimal(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * 写预留行。
     *
     * @return reservationId；<b>null 表示撞唯一索引</b>（并发同 operation 预留）
     */
    private @Nullable String insertReservation(Connection conn, String tenantId, String runId,
                                               @Nullable String operationId, BigDecimal amount)
            throws SQLException {
        String sql = """
                INSERT INTO budget_reservation
                    (tenant_id, account_id, run_id, operation_id, reserved_amount, state)
                SELECT tenant_id, account_id, ?::uuid, ?::uuid, ?, 'RESERVED'
                FROM budget_account
                WHERE tenant_id = ?::uuid AND currency = ?
                ON CONFLICT (tenant_id, operation_id) WHERE operation_id IS NOT NULL
                    DO NOTHING
                RETURNING reservation_id::text
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            if (operationId == null) {
                ps.setNull(2, java.sql.Types.OTHER);
            } else {
                ps.setString(2, operationId);
            }
            ps.setBigDecimal(3, amount);
            ps.setString(4, tenantId);
            ps.setString(5, DEFAULT_CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 退回一次预留扣减（插入撞唯一索引时调用）。 */
    private void rollbackSingleReserve(Connection conn, String tenantId, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE budget_account SET reserved_amount = reserved_amount - ?, "
              + "updated_at = now() WHERE tenant_id = ?::uuid AND currency = ? "
              + "AND reserved_amount >= ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, tenantId);
            ps.setString(3, DEFAULT_CURRENCY);
            ps.setBigDecimal(4, amount);
            ps.executeUpdate();
        }
    }

    private boolean accountExists(Connection conn, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM budget_account WHERE tenant_id = ?::uuid AND currency = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, DEFAULT_CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ── 结算 ────────────────────────────────────────────────────────

    @Override
    public boolean settle(@NonNull String tenantId,
                          @NonNull String reservationId,
                          @NonNull BigDecimal actualAmount,
                          @Nullable String modelId) {
        if (actualAmount.signum() < 0) {
            log.warn("结算金额为负，fail-closed 拒绝: reservation={}, amount={}",
                    reservationId, actualAmount);
            return false;
        }

        return inTenantTx(tenantId, conn -> {
            // ① 锁行读预留（D-3 状态机：只有 RESERVED 可离开）
            Reservation r = lockReservation(conn, tenantId, reservationId);
            if (r == null || !States.RESERVED.equals(r.state())) {
                // ⚠️ 已终态 → 重复结算。**不得**再次累加 settled_amount
                if (log.isDebugEnabled()) {
                    log.debug("结算被拒：预留不存在或已终态: reservation={}, state={}",
                            reservationId, r == null ? "N/A" : r.state());
                }
                return false;
            }
            if (actualAmount.compareTo(r.reservedAmount()) > 0) {
                // ⚠️ 结算额超过预留额：CHECK (settled <= reserved) 会拒绝，
                //    这里先给出可读错误，避免依赖 DB 报错文本
                throw new IllegalStateException(
                        "结算金额 " + actualAmount + " 超过预留额 " + r.reservedAmount()
                                + "（reservation=" + reservationId + "）。"
                                + "须先调整预留额，不允许凭空多计费。");
            }

            // ② 计量写入：ON CONFLICT DO NOTHING + RETURNING（D-5 的原子幂等）
            //    ⚠️ 与账户更新同事务：本方法整体在一个事务里，任一步失败整体回滚
            //    返回 false = 该操作已有计量记录（重复结算），此时**不得**重复累加
            boolean usageWritten = insertUsageOnce(conn, tenantId, r, actualAmount, modelId);

            // ③ 账户更新：预留额全额扣回，实际结算额计入
            moveReservedToSettled(conn, tenantId, r.reservedAmount(), actualAmount);

            // ④ 预留状态推进（RESERVED → SETTLED）
            markSettled(conn, tenantId, reservationId, actualAmount);

            if (log.isInfoEnabled()) {
                log.info("预算结算完成: reservation={}, 预留={}, 实结={}, 计量写入={}",
                        reservationId, r.reservedAmount(), actualAmount, usageWritten);
            }
            return true;
        });
    }

    /**
     * 计量幂等写入（D-5）。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而非「先查后插」：
     * 后者是 check-then-act，并发下可插入两行。
     *
     * @return true=本次新写入；false=已存在（重复结算）
     */
    private boolean insertUsageOnce(Connection conn, String tenantId, Reservation r,
                                    BigDecimal actualAmount, @Nullable String modelId)
            throws SQLException {
        String sql = """
                INSERT INTO usage_record
                    (tenant_id, run_id, operation_id, reservation_id,
                     model_id, cost_amount, currency)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?)
                ON CONFLICT (tenant_id, operation_id) WHERE operation_id IS NOT NULL
                    DO NOTHING
                RETURNING usage_id::text
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, r.runId());
            if (r.operationId() == null) {
                ps.setNull(3, java.sql.Types.OTHER);
            } else {
                ps.setString(3, r.operationId());
            }
            ps.setString(4, r.reservationId());
            ps.setString(5, modelId == null ? "unknown" : modelId);
            ps.setBigDecimal(6, actualAmount);
            ps.setString(7, DEFAULT_CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 账户金额迁移：预留额扣回，实际结算额计入。 */
    private void moveReservedToSettled(Connection conn, String tenantId,
                                        BigDecimal reserved, BigDecimal actual)
            throws SQLException {
        String sql = """
                UPDATE budget_account
                SET reserved_amount = reserved_amount - ?,
                    settled_amount = settled_amount + ?,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND currency = ?
                  AND reserved_amount >= ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, reserved);
            ps.setBigDecimal(2, actual);
            ps.setString(3, tenantId);
            ps.setString(4, DEFAULT_CURRENCY);
            ps.setBigDecimal(5, reserved);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "账户预留额不足以结转（tenant=" + tenantId
                                + ", 需要=" + reserved + "）。账实不符，中止结算。");
            }
        }
    }

    private void markSettled(Connection conn, String tenantId, String reservationId,
                             BigDecimal actualAmount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE budget_reservation SET state = 'SETTLED', "
              + "settled_amount = ?, settled_at = now() "
              + "WHERE tenant_id = ?::uuid AND reservation_id = ?::uuid")) {
            ps.setBigDecimal(1, actualAmount);
            ps.setString(2, tenantId);
            ps.setString(3, reservationId);
            ps.executeUpdate();
        }
    }

    // ── 释放 ────────────────────────────────────────────────────────

    @Override
    public boolean release(@NonNull String tenantId,
                           @NonNull String reservationId,
                           @NonNull String reason) {
        return inTenantTx(tenantId, conn -> {
            Reservation r = lockReservation(conn, tenantId, reservationId);
            if (r == null || !States.RESERVED.equals(r.state())) {
                // ⚠️ 重复释放：返回 false 且**不改账户**。
                //    若在此处仍退回额度，额度会被凭空放大 —— 那比超发更隐蔽，
                //    因为它让后续所有预留都能通过（CHECK 依然成立）。
                if (log.isDebugEnabled()) {
                    log.debug("释放被拒：预留不存在或已终态: reservation={}, state={}",
                            reservationId, r == null ? "N/A" : r.state());
                }
                return false;
            }

            String sql = """
                    UPDATE budget_account
                    SET reserved_amount = reserved_amount - ?, updated_at = now()
                    WHERE tenant_id = ?::uuid AND currency = ? AND reserved_amount >= ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBigDecimal(1, r.reservedAmount());
                ps.setString(2, tenantId);
                ps.setString(3, DEFAULT_CURRENCY);
                ps.setBigDecimal(4, r.reservedAmount());
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "释放时账户预留额不足（tenant=" + tenantId
                                    + ", 需退=" + r.reservedAmount() + "）。账实不符，中止释放。");
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE budget_reservation SET state = 'RELEASED', release_reason = ? "
                  + "WHERE tenant_id = ?::uuid AND reservation_id = ?::uuid")) {
                ps.setString(1, reason);
                ps.setString(2, tenantId);
                ps.setString(3, reservationId);
                ps.executeUpdate();
            }

            if (log.isInfoEnabled()) {
                log.info("预算预留已释放: reservation={}, 退回={}, 原因={}",
                        reservationId, r.reservedAmount(), reason);
            }
            return true;
        });
    }

    // ── 查询 ────────────────────────────────────────────────────────

    @Override
    public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                          @NonNull String operationId) {
        return inTenantTx(tenantId, conn -> findByOperation(conn, tenantId, operationId));
    }

    @Override
    public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
        return inTenantTx(tenantId, conn -> {
            // ⚠️ 只返回「关联操作已终态」的预留。
            //    UNKNOWN 不算悬挂——它是「等对账」，处置路径不同（须人工/D5）。
            String sql = """
                    SELECT r.reservation_id::text, r.operation_id::text, r.reserved_amount,
                           o.state::text, r.created_at
                    FROM budget_reservation r
                    JOIN tool_operation o
                      ON o.tenant_id = r.tenant_id
                     AND o.operation_id = r.operation_id
                    WHERE r.tenant_id = ?::uuid
                      AND r.state = 'RESERVED'
                      AND o.state IN ('SUCCEEDED', 'FAILED',
                                      'RECONCILED_SUCCEEDED', 'RECONCILED_ABSENT',
                                      'MANUAL_DISPOSED')
                    ORDER BY r.created_at
                    """;
            List<OrphanedReservation> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp created = rs.getTimestamp("created_at");
                        out.add(new OrphanedReservation(
                                rs.getString("reservation_id"),
                                rs.getString("operation_id"),
                                rs.getBigDecimal("reserved_amount"),
                                rs.getString("state"),
                                created.toInstant()));
                    }
                }
            }
            return out;
        });
    }

    /** @return 该租户仍为 RESERVED 且关联操作为 UNKNOWN 的预留（转人工清单）。 */
    public @NonNull List<OrphanedReservation> findUnknownHeld(@NonNull String tenantId) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT r.reservation_id::text, r.operation_id::text, r.reserved_amount,
                           o.state::text, r.created_at
                    FROM budget_reservation r
                    JOIN tool_operation o
                      ON o.tenant_id = r.tenant_id
                     AND o.operation_id = r.operation_id
                    WHERE r.tenant_id = ?::uuid
                      AND r.state = 'RESERVED'
                      AND o.state = 'UNKNOWN'
                    ORDER BY r.created_at
                    """;
            List<OrphanedReservation> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new OrphanedReservation(
                                rs.getString("reservation_id"),
                                rs.getString("operation_id"),
                                rs.getBigDecimal("reserved_amount"),
                                rs.getString("state"),
                                rs.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                              @NonNull String currency) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT account_id::text, currency, total_limit,
                           reserved_amount, settled_amount
                    FROM budget_account
                    WHERE tenant_id = ?::uuid AND currency = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AccountSnapshot(
                            rs.getString("account_id"),
                            rs.getString("currency"),
                            rs.getBigDecimal("total_limit"),
                            rs.getBigDecimal("reserved_amount"),
                            rs.getBigDecimal("settled_amount")));
                }
            }
        });
    }

    @Override
    public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT
                        (SELECT reserved_amount FROM budget_account
                          WHERE tenant_id = ?::uuid AND currency = ?) AS acct_reserved,
                        (SELECT COALESCE(SUM(reserved_amount - settled_amount), 0)
                           FROM budget_reservation
                          WHERE tenant_id = ?::uuid AND state = 'RESERVED') AS detail_reserved,
                        (SELECT settled_amount FROM budget_account
                          WHERE tenant_id = ?::uuid AND currency = ?) AS acct_settled,
                        (SELECT COALESCE(SUM(settled_amount), 0)
                           FROM budget_reservation
                          WHERE tenant_id = ?::uuid AND state = 'SETTLED') AS detail_settled
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // ⚠️ 占位符共 6 个：2 个账户查询各 2 个（tenant+currency），
                //    2 个明细查询各 1 个（tenant）
                ps.setString(1, tenantId);
                ps.setString(2, currency);
                ps.setString(3, tenantId);
                ps.setString(4, tenantId);
                ps.setString(5, currency);
                ps.setString(6, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return true;
                    }
                    BigDecimal acctReserved = nz(rs.getBigDecimal("acct_reserved"));
                    BigDecimal detailReserved = nz(rs.getBigDecimal("detail_reserved"));
                    BigDecimal acctSettled = nz(rs.getBigDecimal("acct_settled"));
                    BigDecimal detailSettled = nz(rs.getBigDecimal("detail_settled"));
                    return acctReserved.compareTo(detailReserved) == 0
                            && acctSettled.compareTo(detailSettled) == 0;
                }
            }
        });
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private Optional<Reservation> findByOperation(Connection conn, String tenantId,
                                                 String operationId) throws SQLException {
        String sql = """
                SELECT reservation_id::text, run_id::text, operation_id::text,
                       reserved_amount, settled_amount, state,
                       (SELECT currency FROM budget_account a
                         WHERE a.tenant_id = r.tenant_id AND a.account_id = r.account_id) AS currency
                FROM budget_reservation r
                WHERE r.tenant_id = ?::uuid AND r.operation_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readReservation(rs)) : Optional.empty();
            }
        }
    }

    /** FOR UPDATE 锁行读预留（与写同事务，防并发重复结算/释放）。 */
    private @Nullable Reservation lockReservation(Connection conn, String tenantId,
                                                  String reservationId) throws SQLException {
        String sql = """
                SELECT reservation_id::text, run_id::text, operation_id::text,
                       reserved_amount, settled_amount, state,
                       (SELECT currency FROM budget_account a
                         WHERE a.tenant_id = r.tenant_id AND a.account_id = r.account_id) AS currency
                FROM budget_reservation r
                WHERE tenant_id = ?::uuid AND reservation_id = ?::uuid
                FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readReservation(rs) : null;
            }
        }
    }

    private Reservation readReservation(ResultSet rs) throws SQLException {
        return new Reservation(
                rs.getString("reservation_id"),
                rs.getString("run_id"),
                rs.getString("operation_id"),
                rs.getBigDecimal("reserved_amount"),
                rs.getBigDecimal("settled_amount"),
                rs.getString("state"),
                rs.getString("currency"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 在租户事务中执行：复用外层连接或自开（P2-D5 连接所有权铁律）。
     */
    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "worker", Set.of(), 0L),
                action::run);
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T run(Connection conn) throws SQLException;
    }

    /** 仅供测试与运维读取当前时间基准。 */
    static Instant now() {
        return Instant.now();
    }
}
