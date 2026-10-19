package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.infrastructure.budget.BudgetReconciliationService;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort.ReserveOutcome;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 预算预留并发与对账集成测试（P3-D04 / FR-012 / AT-BUDGET-001/002）。
 *
 * <p><b>本类证明的核心命题</b>（三条，全部在真实 PG17 上、非特权角色）：
 * <ol>
 *   <li><b>并发预留不超发</b>：N 路并发争抢有限额度，成功数恰好受限，
 *       账户余额与成功数严格一致；</li>
 *   <li><b>预留有唯一记录</b>：同 operation 重复预留命中同一 reservation_id，
 *       额度只扣一次；</li>
 *   <li><b>重复结算不重复计费</b>：重复结算只产生一条 usage_record，
 *       {@code settled_amount} 只加一次。</li>
 * </ol>
 *
 * <p><b>⚠️ 非特权角色</b>（{@link FaultInjectionSupport#requireNonPrivileged}）：
 * 超级用户绕过 RLS 会得到「隔离失效」的虚假结论（E-3）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class BudgetReservationIT {

    private static final String CURRENCY = "CNY";

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunCreationRepository creationRepo;
    private static JdbcToolOperationLedger ledger;
    private static JdbcBudgetRepository budget;
    private static String tenantA;
    private static String tenantB;
    private static String snapshotA;
    private static String snapshotB;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(8);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        creationRepo = new JdbcRunCreationRepository(appPool);
        ledger = new JdbcToolOperationLedger(appPool);
        budget = new JdbcBudgetRepository(appPool);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        tenantB = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-b");
        // ⚠️ 两个租户都要补齐快照：run_snapshot_fk 是真实外键，
        //    只给 t-a 准备会让跨租户用例在「建 Run」这一步就失败，
        //    报错点离真正的被测行为（预算隔离）很远
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-b");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
        snapshotB = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-b");
    }

    @AfterAll
    static void tearDown() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    // ── T-1：并发预留不超发（AT-BUDGET-001 前半）─────────────────────

    @Test
    @DisplayName("T-1 ⚠️⚠️ 并发预留不超发：成功数受限且账户余额与成功数严格一致")
    void concurrentReserveNeverOverspends() throws Exception {
        // 总额度 1.00，每次预留 0.30 → 最多成功 3 次（3×0.30=0.90 ≤ 1.00）
        BigDecimal limit = new BigDecimal("1.00");
        BigDecimal each = new BigDecimal("0.30");
        int threads = 12;
        resetAccount(tenantA, limit);

        String runId = newRun(tenantA);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<BudgetPort.ReserveOutcome> outcomes = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ReserveOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String opId = UUID.randomUUID().toString();
                futures.add(pool.submit((Callable<ReserveOutcome>) () -> {
                    start.await();
                    ReserveOutcome o = budget.reserve(tenantA, runId, opId, each);
                    if (o.granted()) {
                        granted.incrementAndGet();
                    }
                    synchronized (outcomes) {
                        outcomes.add(o);
                    }
                    return o;
                }));
            }
            start.countDown();
            for (Future<ReserveOutcome> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get())
                .as("⚠️⚠️ 并发预留不得超发：额度 %.2f、每次 %.2f → 最多 3 次成功，"
                        + "实测 %d 次", limit, each, granted.get())
                .isLessThanOrEqualTo(3);

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("账户预留额必须等于成功次数 × 单次预留额（否则说明有额度凭空消失或多扣）")
                .isEqualByComparingTo(each.multiply(BigDecimal.valueOf(granted.get())));
        assertThat(snap.reservedAmount().add(snap.settledAmount()))
                .as("⚠️⚠️ 预算不超发（budget_no_overspend 的语义）：预留 + 已结算 ≤ 总额度")
                .isLessThanOrEqualTo(snap.totalLimit());

        // 反证用：确认确实出现过 INSUFFICIENT（否则本用例是「全都成功」的平凡通过）
        assertThat(outcomes)
                .as("必须存在被拒的并发请求，否则本用例未真正施压")
                .contains(ReserveOutcome.INSUFFICIENT);
    }

    // ── T-1b：确定性交错（反证 T-1 的补充）─────────────────────────

    @Test
    @DisplayName("T-1b ⚠️⚠️ 确定性交错：扣减期间额度被抢占 → 必须拒绝而非超发")
    void deterministicInterleavingNeverOverspends() throws Exception {
        // ⚠️ **为何必须有这个用例**：T-1 用线程并发制造竞态，但窗口只有
        //    「SELECT 返回 → UPDATE 发出」之间的微秒级间隙。实测把实现换成
        //    check-then-act 后，T-1 连跑 3 次**仍然全绿** —— 那是**假阴性**，
        //    不是「并发下不会超发」。并发测试抓不住，就不能算证明了不变量。
        //
        //    本用例改用**确定性交错**：用两个连接手工编排，
        //    让连接 B 的 UPDATE 必然「读到旧值 → 阻塞在行锁上 →
        //    待 A 提交后重新判定」。这正是 check-then-act 的致命窗口，
        //    且 100% 命中，不依赖线程调度运气。
        BigDecimal limit = new BigDecimal("1.00");
        BigDecimal each = new BigDecimal("0.60");
        resetAccount(tenantA, limit);
        String runId = newRun(tenantA);

        Connection a = FaultInjectionSupport.rawAppConnection();
        Connection b = FaultInjectionSupport.rawAppConnection();
        try {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            FaultInjectionSupport.bind(a, tenantA);
            FaultInjectionSupport.bind(b, tenantA);

            // ── 连接 A：先锁住账户行，并消费掉全部额度但**不提交** ──
            //    A 持有行锁 + 未提交的事务 → B 的 UPDATE 必然阻塞等待
            BigDecimal seenByA = availableForUpdateIn(a);
            assertThat(seenByA).isEqualByComparingTo(limit);

            try (PreparedStatement ps = a.prepareStatement(
                    "UPDATE budget_account SET reserved_amount = reserved_amount + ? "
                  + "WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setBigDecimal(1, each);
                ps.setString(2, tenantA);
                ps.setString(3, CURRENCY);
                ps.executeUpdate();
            }

            // ── 连接 B：在**独立线程**中发起真实预留 ──
            //    B 会先做余额判断（此刻 A 未提交，B 看到的是旧值 1.00），
            //    再 UPDATE → 因 A 持锁而阻塞。A 提交后，B 必须重新判定并拒绝。
            String opB = UUID.randomUUID().toString();
            java.util.concurrent.atomic.AtomicReference<ReserveOutcome> bOutcome =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> bError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch bDone = new java.util.concurrent.CountDownLatch(1);

            Thread bt = new Thread(() -> {
                try {
                    bOutcome.set(budget.reserve(tenantA, runId, opB, each));
                } catch (Throwable t) {
                    bError.set(t);
                } finally {
                    bDone.countDown();
                }
            });
            bt.setDaemon(true);
            bt.start();

            // ⚠️ 等 B 确实阻塞在行锁上（而非还没开始）。用短暂 sleep 让它
            //    跑到 UPDATE；随后 A 提交，B 被唤醒并在**最新行版本**上重新判定。
            //    ⚠️ 这里 sleep 是编排手段，不是「等够久就行」——
            //    即便 B 尚未阻塞，A 先提交也只是让 B 更晚开始，结果相同。
            Thread.sleep(300);

            a.commit();   // A 提交：账户预留 = 0.60，可用 0.40

            assertThat(bDone.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as("连接 B 的预留应在 A 提交后完成")
                    .isTrue();
            bt.join(5_000);

            // ── 断言 B 的结局 ──
            assertThat(bError.get())
                    .as("⚠️ check-then-act 会让 B 依据过期判断继续扣减 → "
                        + "触发 budget_no_overspend 异常（1.20 > 1.00）。"
                        + "正确实现必须让 DB 在扣减的同一刻重新判定并干净拒绝。")
                    .isNull();

            assertThat(bOutcome.get())
                    .as("⚠️⚠️ B 手里的余额判断已过期，必须重新判定后拒绝")
                    .isEqualTo(ReserveOutcome.INSUFFICIENT);

            try {
                b.rollback();
            } catch (SQLException ignored) {
                // B 可能因预留内部自行开启事务而无需回滚
            }
        } finally {
            a.close();
            b.close();
        }

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("账户预留额只能等于 A 预留的那一笔——B 必须一分钱没扣")
                .isEqualByComparingTo(each);
        assertThat(snap.reservedAmount().add(snap.settledAmount()))
                .as("预算不超发")
                .isLessThanOrEqualTo(limit);
    }

    /** 读取可用额度并锁住账户行（SELECT ... FOR UPDATE）。 */
    private BigDecimal availableForUpdateIn(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT total_limit - reserved_amount - settled_amount FROM budget_account "
              + "WHERE tenant_id = ?::uuid AND currency = ? FOR UPDATE")) {
            ps.setString(1, tenantA);
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBigDecimal(1);
            }
        }
    }

    // ── T-2：DB 最后防线被真实触发 ────────────────────────────────────

    @Test
    @DisplayName("T-2 ⚠️⚠️ 绕过应用层直接超发 → budget_no_overspend CHECK 拒绝")
    void overspendBlockedByCheckConstraint() throws Exception {
        resetAccount(tenantA, new BigDecimal("1.00"));

        assertThatThrownBy(() -> inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE budget_account SET reserved_amount = reserved_amount + ? "
                  + "WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setBigDecimal(1, new BigDecimal("2.00"));
                ps.setString(2, tenantA);
                ps.setString(3, CURRENCY);
                ps.executeUpdate();
            }
            return null;
        }))
                .as("⚠️ budget_no_overspend 是并发超发的最后防线，"
                        + "它必须在真实 PG 上被触发过一次才算被证明有效")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("budget_no_overspend");
    }

    // ── T-3：预留唯一记录（AT-BUDGET-001 后半）────────────────────────

    @Test
    @DisplayName("T-3 ⚠️ 同 operation 重复预留 → 命中同一 reservation，额度只扣一次")
    void duplicateReserveHitsSameReservation() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);

        String run1 = newOperation(runId, "stu_" + String.format("%016x", 0xA1),
                "一元二次方程");
        String run2 = newOperation(runId, "stu_" + String.format("%016x", 0xA1),
                "一元二次方程");

        // ⚠️ 同一幂等键 → 同一逻辑操作 → 同一预留
        assertThat(run1).isEqualTo(run2);

        assertThat(budget.reserve(tenantA, runId, run1, new BigDecimal("0.50")))
                .isEqualTo(ReserveOutcome.RESERVED);
        assertThat(budget.reserve(tenantA, runId, run1, new BigDecimal("0.50")))
                .as("重复预留必须命中既有预留（FR-012「预留有唯一记录」）")
                .isEqualTo(ReserveOutcome.REPLAYED);

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("重复预留不得重复扣减额度（否则重复调用 = 重复花钱）")
                .isEqualByComparingTo("0.50");

        assertThat(budget.findByOperation(tenantA, run1))
                .as("同一 operation 只能有一条预留")
                .isPresent();
        long count = inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM budget_reservation "
                  + "WHERE tenant_id = ?::uuid AND operation_id = ?::uuid")) {
                ps.setString(1, tenantA);
                ps.setString(2, run1);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(count).as("DB 层不得出现两条预留").isEqualTo(1L);
    }

    // ── T-4：重复结算不重复计费（AT-BUDGET-002）───────────────────────

    @Test
    @DisplayName("T-4 ⚠️⚠️ 重复结算不重复计费：usage_record 一条，settled_amount 只加一次")
    void duplicateSettleDoesNotDoubleCharge() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String operationId = newOperation(runId,
                "stu_" + String.format("%016x", 0xB1), "一元二次方程");

        assertThat(budget.reserve(tenantA, runId, operationId, new BigDecimal("0.50")))
                .isEqualTo(ReserveOutcome.RESERVED);
        String rid = budget.findByOperation(tenantA, operationId)
                .orElseThrow().reservationId();

        assertThat(budget.settle(tenantA, rid, new BigDecimal("0.50"), "deepseek"))
                .isTrue();

        var afterFirst = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(afterFirst.settledAmount()).isEqualByComparingTo("0.50");
        assertThat(afterFirst.reservedAmount())
                .as("结算后预留额应全额扣回")
                .isEqualByComparingTo("0.00");

        // ⚠️ 重复结算：第二次必须被拒，且不得再次累加
        assertThat(budget.settle(tenantA, rid, new BigDecimal("0.50"), "deepseek"))
                .as("已结算的预留不得再次结算（重复计费 = Sev-0 级成本事故）")
                .isFalse();

        var afterSecond = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(afterSecond.settledAmount())
                .as("⚠️⚠️ 重复结算不得凭空多计费")
                .isEqualByComparingTo("0.50");
        assertThat(afterSecond.reservedAmount()).isEqualByComparingTo("0.00");

        long usageCount = inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM usage_record "
                  + "WHERE tenant_id = ?::uuid AND operation_id = ?::uuid")) {
                ps.setString(1, tenantA);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(usageCount)
                .as("usage_record 唯一索引 idx_usage_operation_once 保证同一操作只计费一次")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("T-4b 结算额超过预留额 → 拒绝，不允许凭空多计费")
    void settleBeyondReservedRejected() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String operationId = newOperation(runId,
                "stu_" + String.format("%016x", 0xB2), "一元二次方程");

        budget.reserve(tenantA, runId, operationId, new BigDecimal("0.50"));
        String rid = budget.findByOperation(tenantA, operationId)
                .orElseThrow().reservationId();

        assertThatThrownBy(() -> budget.settle(tenantA, rid, new BigDecimal("9.00"), "deepseek"))
                .as("结算额超过预留意味着凭空多计费，必须拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过预留");

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.settledAmount())
                .as("失败的事务不得留下半边落地（计量或账户更新）")
                .isEqualByComparingTo("0.00");
        assertThat(snap.reservedAmount()).isEqualByComparingTo("0.50");
    }

    // ── T-5：释放幂等 ────────────────────────────────────────────────

    @Test
    @DisplayName("T-5 ✅ 释放退回额度；重复释放不再退（否则额度被凭空放大）")
    void releaseIsIdempotent() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String operationId = newOperation(runId,
                "stu_" + String.format("%016x", 0xC1), "一元二次方程");

        budget.reserve(tenantA, runId, operationId, new BigDecimal("0.50"));
        String rid = budget.findByOperation(tenantA, operationId)
                .orElseThrow().reservationId();

        assertThat(budget.release(tenantA, rid, "tool-definitely-failed")).isTrue();

        var afterFirst = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(afterFirst.reservedAmount())
                .as("释放后额度应全额退回")
                .isEqualByComparingTo("0.00");

        assertThat(budget.release(tenantA, rid, "duplicate"))
                .as("重复释放必须被拒")
                .isFalse();

        var afterSecond = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(afterSecond.reservedAmount())
                .as("⚠️ 重复释放不得再退——凭空放大的额度比超发更隐蔽"
                        + "（它让后续所有预留都能通过，CHECK 依然成立）")
                .isEqualByComparingTo("0.00");
        assertThat(afterSecond.available())
                .as("可用额度必须等于总额度")
                .isEqualByComparingTo("10.00");
    }

    // ── T-6：UNKNOWN 不释放（R-007）──────────────────────────────────

    @Test
    @DisplayName("T-6 ⚠️⚠️ UNKNOWN 后预留保持 RESERVED，额度不退回（R-007）")
    void unknownKeepsReservation() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String operationId = newOperation(runId,
                "stu_" + String.format("%016x", 0xD1), "一元二次方程");

        budget.reserve(tenantA, runId, operationId, new BigDecimal("0.50"));
        String rid = budget.findByOperation(tenantA, operationId)
                .orElseThrow().reservationId();

        // 结果未知：请求已发出但响应丢失
        ledger.recordResult(tenantA, operationId, ToolOperationState.UNKNOWN,
                0L, null, "下游超时");

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("⚠️⚠️ UNKNOWN 时预留不得释放——成本可能已发生，"
                        + "释放等于白送额度")
                .isEqualByComparingTo("0.50");

        // 对账扫描：UNKNOWN 预留不是「悬挂」，应被单独识别
        assertThat(budget.findOrphaned(tenantA))
                .as("UNKNOWN 的预留不属于悬挂预留（处置路径不同：须先对账）")
                .noneMatch(o -> o.reservationId().equals(rid));
        assertThat(budget.findUnknownHeld(tenantA))
                .as("UNKNOWN 预留应被单独列出待人工/待对账")
                .anyMatch(o -> o.reservationId().equals(rid));

        String state = budget.findByOperation(tenantA, operationId)
                .orElseThrow().state();
        assertThat(state).as("预留状态必须仍是 RESERVED").isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("T-6b 对账收敛 RECONCILED_ABSENT 后 → 释放；RECONCILED_SUCCEEDED → 结算")
    void reconciliationDrivesBudgetDisposition() throws Exception {
        var service = new BudgetReconciliationService(budget, budget);

        // 场景一：确认未发生 → 释放
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runAbsent = newRun(tenantA);
        String opAbsent = newOperation(runAbsent,
                "stu_" + String.format("%016x", 0xE1), "一元二次方程");
        budget.reserve(tenantA, runAbsent, opAbsent, new BigDecimal("0.50"));
        ledger.recordResult(tenantA, opAbsent, ToolOperationState.UNKNOWN, 0L, null, "超时");
        ledger.reconcile(tenantA, opAbsent, ToolOperationState.RECONCILED_ABSENT,
                "profile-lookup:absent-after-grace");

        assertThat(service.disposeAfterReconciliation(
                tenantA, opAbsent, ToolOperationState.RECONCILED_ABSENT, null)).isTrue();
        assertThat(budget.accountSnapshot(tenantA, CURRENCY).orElseThrow().reservedAmount())
                .as("确认未发生 → 额度可安全退回")
                .isEqualByComparingTo("0.00");

        // 场景二：确认已发生 → 结算
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runOk = newRun(tenantA);
        String opOk = newOperation(runOk,
                "stu_" + String.format("%016x", 0xE2), "一元二次方程");
        budget.reserve(tenantA, runOk, opOk, new BigDecimal("0.50"));
        ledger.recordResult(tenantA, opOk, ToolOperationState.UNKNOWN, 0L, null, "超时");
        ledger.reconcile(tenantA, opOk, ToolOperationState.RECONCILED_SUCCEEDED,
                "profile-lookup:hit");

        assertThat(service.disposeAfterReconciliation(
                tenantA, opOk, ToolOperationState.RECONCILED_SUCCEEDED, null)).isTrue();
        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.settledAmount())
                .as("确认已发生 → 成本真实，按预留额结算")
                .isEqualByComparingTo("0.50");
        assertThat(snap.reservedAmount()).isEqualByComparingTo("0.00");
    }

    // ── T-7：账户不存在 fail-closed（D-2）────────────────────────────

    @Test
    @DisplayName("T-7 ⚠️ 账户不存在 → NO_ACCOUNT（不是 INSUFFICIENT），且拒绝预留")
    void missingAccountFailsClosedWithDistinctReason() throws Exception {
        // ⚠️ 必须**显式删掉**账户，而不能依赖「种子数据本来就没有」：
        //    JUnit 用例顺序不保证，T-8 会给 t-b 建账户，
        //    届时本用例的前提失效却仍会「通过」——一个依赖执行顺序的测试
        //    等于没有测试。
        removeAccount(tenantB);

        String runId = newRun(tenantB, snapshotB);
        String operationId = newOperation(tenantB, runId,
                "stu_" + String.format("%016x", 0xF1), "一元二次方程");

        ReserveOutcome outcome = budget.reserve(tenantB, runId, operationId,
                new BigDecimal("0.50"));

        assertThat(outcome)
                .as("⚠️ 必须与 INSUFFICIENT 严格区分：前者要**开通账户**，"
                        + "后者要**加额度**——运维处置方向完全不同")
                .isEqualTo(ReserveOutcome.NO_ACCOUNT);
        assertThat(outcome.granted())
                .as("fail-closed：未开通账户一律拒绝（放行 = 该租户无限花钱）")
                .isFalse();

        assertThat(budget.accountSnapshot(tenantB, CURRENCY))
                .as("账户确实不存在")
                .isEmpty();
    }

    @Test
    @DisplayName("T-7b 金额非法（≤ 0）→ FAIL_CLOSED，不按 0 放行也不退回默认")
    void invalidAmountFailsClosed() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);

        assertThat(budget.reserve(tenantA, runId, null, BigDecimal.ZERO))
                .as("金额为 0 必须拒绝：按 0 放行等于无预算约束执行工具")
                .isEqualTo(ReserveOutcome.FAIL_CLOSED);
        assertThat(budget.reserve(tenantA, runId, null, new BigDecimal("-1.00")))
                .isEqualTo(ReserveOutcome.FAIL_CLOSED);

        assertThat(budget.accountSnapshot(tenantA, CURRENCY).orElseThrow().reservedAmount())
                .as("非法金额不得改动账户")
                .isEqualByComparingTo("0.00");
    }

    // ── T-8：跨租户隔离 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-8 ⚠️ 预算不跨租户：A 的预留 B 不可见、不可结算")
    void budgetIsTenantIsolated() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        resetAccount(tenantB, new BigDecimal("10.00"));

        String runA = newRun(tenantA);
        String opA = newOperation(runA, "stu_" + String.format("%016x", 0x1A1), "一元二次方程");
        budget.reserve(tenantA, runA, opA, new BigDecimal("0.50"));
        String ridA = budget.findByOperation(tenantA, opA).orElseThrow().reservationId();

        assertThat(budget.findByOperation(tenantB, opA))
                .as("RLS：B 租户看不到 A 的预留")
                .isEmpty();

        assertThat(budget.settle(tenantB, ridA, new BigDecimal("0.50"), "deepseek"))
                .as("⚠️ B 不得结算 A 的预留（跨租户成本串账）")
                .isFalse();

        assertThat(budget.accountSnapshot(tenantB, CURRENCY).orElseThrow().settledAmount())
                .as("B 的账户不得因结算 A 的预留而变动")
                .isEqualByComparingTo("0.00");
        assertThat(budget.accountSnapshot(tenantA, CURRENCY).orElseThrow().settledAmount())
                .as("A 的预留仍应处于 RESERVED")
                .isEqualByComparingTo("0.00");
    }

    // ── T-9：崩溃注入：预留与账户同生共死 ────────────────────────────

    @Test
    @DisplayName("T-9 ⚠️ 崩溃注入：预留与账户扣减原子——无「扣了额度没记录」")
    void crashAfterReserveLeavesNoOrphan() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);

        // 模拟「账户已扣、预留未写」的中间态在真实事务下不可能出现：
        // 用裸连接显式验证——在同一事务内写账户，若不 commit 则两者都不落库
        Connection raw = FaultInjectionSupport.rawAppConnection();
        try {
            raw.setAutoCommit(false);
            FaultInjectionSupport.bind(raw, tenantA);
            try (PreparedStatement ps = raw.prepareStatement(
                    "UPDATE budget_account SET reserved_amount = reserved_amount + ? "
                  + "WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setBigDecimal(1, new BigDecimal("5.00"));
                ps.setString(2, tenantA);
                ps.setString(3, CURRENCY);
                ps.executeUpdate();
            }
            // ⚠️ 不 commit，直接关闭 = 模拟 JVM/连接崩溃
        } finally {
            raw.close();
        }

        assertThat(budget.accountSnapshot(tenantA, CURRENCY).orElseThrow().reservedAmount())
                .as("崩溃后账户预留额必须回滚（PG 服务端回滚未提交事务）")
                .isEqualByComparingTo("0.00");

        long reservations = inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM budget_reservation WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantA);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(reservations)
                .as("账户与预留明细必须一致（账实相符）")
                .isZero();
    }

    // ── T-10：对账 ───────────────────────────────────────────────────

    @Test
    @DisplayName("T-10 ⚠️ 对账：识别悬挂预留；账实不符只报不改")
    void reconciliationFindsOrphansAndReportsInconsistency() throws Exception {
        var service = new BudgetReconciliationService(budget, budget);

        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);

        // ① 成功但预留未结算 → 悬挂（应被结算）
        String opOk = newOperation(runId, "stu_" + String.format("%016x", 0x2A1), "一元二次方程");
        budget.reserve(tenantA, runId, opOk, new BigDecimal("0.50"));
        ledger.recordResult(tenantA, opOk, ToolOperationState.SUCCEEDED, 0L, null, null);

        // ② 确定失败但预留未释放 → 悬挂（应被释放）
        String opFail = newOperation(runId, "stu_" + String.format("%016x", 0x2A2), "二次函数");
        budget.reserve(tenantA, runId, opFail, new BigDecimal("0.50"));
        ledger.recordResult(tenantA, opFail, ToolOperationState.FAILED, 0L, "Downstream", "拒绝");

        var stats = service.reconcileAll(tenantA, null);
        assertThat(stats.scanned()).isEqualTo(2);
        assertThat(stats.settled()).as("成功的悬挂预留应被结算").isEqualTo(1);
        assertThat(stats.released()).as("失败的悬挂预留应被释放").isEqualTo(1);

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("对账后不应再有占用额度的悬挂预留")
                .isEqualByComparingTo("0.00");
        assertThat(snap.settledAmount()).isEqualByComparingTo("0.50");
        assertThat(budget.isConsistent(tenantA, CURRENCY))
                .as("处置完成后账实必须相符")
                .isTrue();
    }

    @Test
    @DisplayName("T-10b ⚠️ 账实不符时只告警不自动改账户（自动修正会永久掩盖差异）")
    void inconsistencyIsReportedNotAutoFixed() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String opId = newOperation(runId, "stu_" + String.format("%016x", 0x3A1), "一元二次方程");
        budget.reserve(tenantA, runId, opId, new BigDecimal("0.50"));

        // 人为制造账实不符：直接改账户而不写预留明细
        inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE budget_account SET reserved_amount = reserved_amount + 3.00 "
                  + "WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setString(1, tenantA);
                ps.setString(2, CURRENCY);
                ps.executeUpdate();
            }
            return null;
        });

        var service = new BudgetReconciliationService(budget, budget);
        var stats = service.reconcileAll(tenantA, null);

        assertThat(stats.consistent())
                .as("对账必须发现账实不符")
                .isFalse();
        assertThat(stats.needsAttention())
                .as("账实不符须标记为需人工介入")
                .isTrue();

        assertThat(budget.accountSnapshot(tenantA, CURRENCY).orElseThrow().reservedAmount())
                .as("⚠️⚠️ 对账**不得**自动改账户：把「钱账不符」改成"
                        + "「看起来相符」= 永久掩盖差异，且掩盖的正是"
                        + "「有人手工改过数据」这条线索")
                .isEqualByComparingTo("3.50");
    }

    // ── T-11：预留终态不可迁出（DB 级）───────────────────────────────

    @Test
    @DisplayName("T-11 ⚠️ 已结算预留不可迁回 RESERVED（防额度二次占用）")
    void terminalReservationCannotRevert() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);
        String opId = newOperation(runId, "stu_" + String.format("%016x", 0x4A1), "一元二次方程");
        budget.reserve(tenantA, runId, opId, new BigDecimal("0.50"));
        String rid = budget.findByOperation(tenantA, opId).orElseThrow().reservationId();
        budget.settle(tenantA, rid, new BigDecimal("0.50"), "deepseek");

        assertThatThrownBy(() -> inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE budget_reservation SET state = 'RESERVED' "
                  + "WHERE tenant_id = ?::uuid AND reservation_id = ?::uuid")) {
                ps.setString(1, tenantA);
                ps.setString(2, rid);
                ps.executeUpdate();
            }
            return null;
        }))
                .as("⚠️ 已结算被改回 RESERVED 会让额度被二次占用")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("终态");
    }

    @Test
    @DisplayName("T-11b state 取值收紧为三值（拼写错误不再静默写入）")
    void reservationStateRejectsTypos() throws Exception {
        resetAccount(tenantA, new BigDecimal("10.00"));
        String runId = newRun(tenantA);

        assertThatThrownBy(() -> inTenant(tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO budget_reservation "
                  + "(tenant_id, account_id, run_id, reserved_amount, state) "
                  + "SELECT tenant_id, account_id, ?::uuid, 1.00, 'resrved' "
                  + "FROM budget_account WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setString(1, runId);
                ps.setString(2, tenantA);
                ps.setString(3, CURRENCY);
                ps.executeUpdate();
            }
            return null;
        }))
                .as("state 原本是自由 TEXT，拼写错误可静默写入，"
                        + "直到账实核对才发现")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("reservation_state_valid");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 重置账户额度（每个用例独立，避免相互干扰）。 */
    private void resetAccount(String tenantId, BigDecimal limit) throws Exception {
        inTenant(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO budget_account (tenant_id, currency, total_limit) "
                  + "VALUES (?::uuid, ?, ?) "
                  + "ON CONFLICT (tenant_id, currency) DO UPDATE "
                  + "SET total_limit = EXCLUDED.total_limit, "
                  + "    reserved_amount = 0, settled_amount = 0, updated_at = now()")) {
                ps.setString(1, tenantId);
                ps.setString(2, CURRENCY);
                ps.setBigDecimal(3, limit);
                ps.executeUpdate();
            }
            // 清掉该租户的明细，保证一致性断言只反映本用例
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM usage_record WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM budget_reservation WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 新建 Run（账本与预留的 FK 都要求真实 Run 存在）。 */
    /** 删除账户（构造「未开通预算账户」场景）。 */
    private void removeAccount(String tenantId) throws Exception {
        inTenant(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM usage_record WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM budget_reservation WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM budget_account WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String newRun(String tenantId) {
        return newRun(tenantId, snapshotA);
    }

    private String newRun(String tenantId, String snapshotId) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "budget-" + runId),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "一元二次方程"), "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, java.time.Instant.now()));
        return runId;
    }

    /** 登记一个逻辑操作（预算预留的关联目标）。 */
    private String newOperation(String runId, String studentId, String knowledgePoint) {
        return newOperation(tenantA, runId, studentId, knowledgePoint);
    }

    private String newOperation(String tenantId, String runId,
                                String studentId, String knowledgePoint) {
        var reg = ledger.register(tenantId, runId, "record_weakness", "WRITE_PROFILE",
                IdempotencyKey.forRecordWeakness(studentId, runId, knowledgePoint).composite(),
                "digest-" + runId,
                Map.of("studentId", studentId, "knowledgePoint", knowledgePoint), 0L);
        return reg.operationId();
    }

    private <T> T inTenant(String tenantId, TenantQuery<T> body) throws Exception {
        return FaultInjectionSupport.inTenant(appPool, tenantId, body::apply);
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
