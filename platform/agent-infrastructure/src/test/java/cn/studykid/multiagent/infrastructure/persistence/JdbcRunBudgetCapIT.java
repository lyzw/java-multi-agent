package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort.ReserveOutcome;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunLimits;

/**
 * Run 级预算上限的<b>原子强制</b>集成测试（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p><b>本类证明的核心命题</b>（全部在真实 PG 上、非特权角色）：
 * <ol>
 *   <li><b>单次预留不得突破 Run 上限</b>：剩余额度装不下本次预留 → 拒绝，
 *       且<b>不扣减租户额度</b>（拒绝不得留下「钱被占了但没干活」）；</li>
 *   <li><b>并发预留不得突破 Run 上限</b>：N 路并发争抢同一个 Run 的有限额度，
 *       成功数受限；</li>
 *   <li><b>确定性交错必须被拒绝</b>：连接 B 的余额判断在 A 提交前已过期，
 *       正确实现必须让 B <b>重新判定后拒绝</b>，而不是按过期判断继续扣减。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么第 3 条不可省</b>：并发测试的竞态窗口只有微秒级，
 * 实测把实现换成 check-then-act 后，并发用例连跑多次<b>仍然全绿</b>
 * —— 那是<b>假阴性</b>。确定性交错 100% 命中该窗口，
 * 是唯一能证明「行锁真的把同一 Run 的预留串行化了」的手段。
 *
 * <p><b>⚠️ 非特权角色</b>：超级用户绕过 RLS 会得到「隔离失效」的虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcRunBudgetCapIT {

    private static final String CURRENCY = "CNY";

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunCreationRepository creationRepo;
    private static JdbcBudgetRepository budget;
    private static JdbcRunBudgetRepository runBudget;
    private static String tenantA;
    private static String snapshotA;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(8);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        creationRepo = new JdbcRunCreationRepository(appPool);
        budget = new JdbcBudgetRepository(appPool);
        runBudget = new JdbcRunBudgetRepository(appPool);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
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

    // ── T-1：单次预留不得突破 Run 上限 ──────────────────────────────

    @Test
    @DisplayName("T-1 ⚠️⚠️ Run 上限装不下本次预留 → 拒绝，且不扣减租户额度")
    void reservationBeyondRunCapIsRejectedWithoutChargingTenant() throws Exception {
        BigDecimal tenantLimit = new BigDecimal("100.00");
        BigDecimal runCap = new BigDecimal("100.00");
        resetAccount(tenantA, tenantLimit);
        String runId = newRun(tenantA, runCap);

        ReserveOutcome first = budget.reserve(tenantA, runId, UUID.randomUUID().toString(),
                new BigDecimal("60"));
        assertThat(first)
                .as("60 < 100，应预留成功")
                .isEqualTo(ReserveOutcome.RESERVED);

        ReserveOutcome second = budget.reserve(tenantA, runId, UUID.randomUUID().toString(),
                new BigDecimal("50"));
        assertThat(second)
                .as("⚠️⚠️ 剩余 40 却要预留 50 → 必须拒绝；"
                        + "放行等于单次调用就突破 Run 上限")
                .isEqualTo(ReserveOutcome.RUN_BUDGET_EXHAUSTED);

        var snap = budget.accountSnapshot(tenantA, CURRENCY).orElseThrow();
        assertThat(snap.reservedAmount())
                .as("⚠️ 拒绝时**不得扣减**租户额度（否则出现「钱被占了但没干活」）")
                .isEqualByComparingTo(new BigDecimal("60"));
    }

    @Test
    @DisplayName("T-2 ✅ Run 未声明额度 → 不额外限制（≠「额度为 0」）")
    void undeclaredRunCapDoesNotRestrict() throws Exception {
        resetAccount(tenantA, new BigDecimal("100.00"));
        String runId = newRun(tenantA, null);

        ReserveOutcome outcome = budget.reserve(tenantA, runId, UUID.randomUUID().toString(),
                new BigDecimal("80"));

        assertThat(outcome)
                .as("没配预算 ≠ 预算为 0；未声明额度不得把调用全拦掉")
                .isEqualTo(ReserveOutcome.RESERVED);
    }

    @Test
    @DisplayName("T-3 ⚠️⚠️ 并发预留不得突破 Run 上限（同一 Run 串行化）")
    void concurrentReservationsNeverExceedRunCap() throws Exception {
        // 租户额度充足（1000），Run 上限 1.00 → 瓶颈必须是 Run 上限而非租户额度，
        // 否则本用例证明不了「Run 级封顶」而只是重复了租户级用例。
        BigDecimal tenantLimit = new BigDecimal("1000.00");
        BigDecimal runCap = new BigDecimal("1.00");
        BigDecimal each = new BigDecimal("0.30");
        int threads = 12;
        resetAccount(tenantA, tenantLimit);
        String runId = newRun(tenantA, runCap);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<ReserveOutcome> outcomes = new ArrayList<>();

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
                .as("⚠️⚠️ Run 上限 1.00、每次 0.30 → 最多成功 3 次，实测 %d 次",
                        granted.get())
                .isLessThanOrEqualTo(3);

        var run = runBudget.find(tenantA, runId).orElseThrow();
        assertThat(run.available())
                .as("⚠️⚠️ Run 已占用额度不得超过其上限（本次的核心不变量）")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // 反证用：确实出现过 Run 级拒绝，否则本用例是「全都成功」的平凡通过
        assertThat(outcomes)
                .as("必须存在被 Run 级上限拒绝的并发请求，否则本用例未真正施压")
                .contains(ReserveOutcome.RUN_BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("T-4 ⚠️⚠️⚠️ 确定性交错：过期判断必须重新判定后拒绝（不得超发）")
    void deterministicInterleavingNeverExceedsRunCap() throws Exception {
        BigDecimal tenantLimit = new BigDecimal("1000.00");
        BigDecimal runCap = new BigDecimal("100.00");
        BigDecimal each = new BigDecimal("60.00");
        resetAccount(tenantA, tenantLimit);
        String runId = newRun(tenantA, runCap);

        Connection a = FaultInjectionSupport.rawAppConnection();
        try {
            a.setAutoCommit(false);
            FaultInjectionSupport.bind(a, tenantA);

            // ── 连接 A：锁住 run 行，并占用 60（**不提交**）────────────────
            //    A 持有 run 行锁 → 同一 Run 的其他预留必然阻塞在行锁上，
            //    这是「同一 Run 的预留被串行化」的物理体现。
            try (PreparedStatement ps = a.prepareStatement(
                    "SELECT budget_amount FROM run WHERE tenant_id = ?::uuid "
                  + "AND run_id = ?::uuid FOR UPDATE")) {
                ps.setString(1, tenantA);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("Run 应存在").isTrue();
                    assertThat(rs.getBigDecimal("budget_amount"))
                            .isEqualByComparingTo(runCap);
                }
            }
            try (PreparedStatement ps = a.prepareStatement(
                    "INSERT INTO budget_reservation (tenant_id, account_id, run_id, "
                  + "operation_id, reserved_amount, state) "
                  + "SELECT tenant_id, account_id, ?::uuid, ?::uuid, ?, 'RESERVED' "
                  + "FROM budget_account WHERE tenant_id = ?::uuid AND currency = ?")) {
                ps.setString(1, runId);
                ps.setString(2, UUID.randomUUID().toString());
                ps.setBigDecimal(3, each);
                ps.setString(4, tenantA);
                ps.setString(5, CURRENCY);
                ps.executeUpdate();
            }

            // ── 连接 B：在独立线程中发起真实预留 ──────────────────────
            //    若实现是 check-then-act，B 会读到「未提交、看不见」的旧聚合，
            //    判定 0 + 60 ≤ 100 → 放行 → A 提交后总占用 120 > 100 → **超发**。
            //    正确实现必须阻塞在 run 行锁上，A 提交后重新判定 → 拒绝。
            String opB = UUID.randomUUID().toString();
            AtomicReference<ReserveOutcome> bOutcome = new AtomicReference<>();
            AtomicReference<Throwable> bError = new AtomicReference<>();
            CountDownLatch bDone = new CountDownLatch(1);
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

            // ⚠️ sleep 是**编排手段**：让 B 确实跑到「等 run 行锁」这一步。
            //    即便 B 还没阻塞，A 先提交也只是让 B 更晚开始，结果相同。
            Thread.sleep(300);

            a.commit();   // A 提交：Run 已占用 60

            assertThat(bDone.await(30, TimeUnit.SECONDS))
                    .as("连接 B 的预留应在 A 提交后完成")
                    .isTrue();
            bt.join(5_000);

            assertThat(bError.get())
                    .as("⚠️ B 的预留不应抛异常（应干净拒绝）")
                    .isNull();
            assertThat(bOutcome.get())
                    .as("⚠️⚠️⚠️ B 的余额判断已过期（A 刚提交了 60），"
                          + "必须重新判定后拒绝：60 + 60 > 100。"
                          + "若这里放行，Run 级上限就是一句空话")
                    .isEqualTo(ReserveOutcome.RUN_BUDGET_EXHAUSTED);

            var run = runBudget.find(tenantA, runId).orElseThrow();
            assertThat(run.available())
                    .as("⚠️⚠️ Run 剩余额度不得为负（实际超支）")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        } finally {
            try {
                a.rollback();
            } catch (SQLException ignored) {
                // 已提交时无需回滚
            }
            a.close();
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 重置账户额度并清空明细（每个用例独立，避免相互干扰）。 */
    private void resetAccount(String tenantId, BigDecimal limit) throws Exception {
        FaultInjectionSupport.inTenant(appPool, tenantId, conn -> {
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
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM budget_reservation WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM usage_record WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 新建 Run；{@code cap} 为 {@code null} 表示未声明 Run 级额度。 */
    private String newRun(String tenantId, BigDecimal cap) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotA,
                new IdempotencyKey("run.create", "runcap-" + runId),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "一元二次方程"), "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, java.time.Instant.now()),
                cap == null ? RunLimits.UNLIMITED : RunLimits.withBudget(cap));
        return runId;
    }
}
