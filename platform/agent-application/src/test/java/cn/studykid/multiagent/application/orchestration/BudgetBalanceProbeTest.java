package cn.studykid.multiagent.application.orchestration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.persistence.BudgetPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派预算探针测试（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>双口径</b>（第七批起）：租户级余额 <b>与</b> Run 级额度
 * <b>都</b>通过才放行。
 * <ul>
 *   <li>只看租户级 → 一个 Run 就能把租户余额烧光；</li>
 *   <li>只看 Run 级 → 不经委派的工具调用不受约束。</li>
 * </ul>
 * 用例名里如实写明口径，避免后人误读。
 */
class BudgetBalanceProbeTest {

    private static final String TENANT = "tenant-1";
    private static final String RUN = "run-1";

    @Test
    @DisplayName("租户账户仍有余额 → 允许委派")
    void remainingBalanceAllowsDelegation() {
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "2", "1")));

        assertThat(new BudgetBalanceProbe(budget, "CNY", unlimitedRunBudget()).hasRemaining(TENANT, RUN)).isTrue();
    }

    @Test
    @DisplayName("租户账户余额耗尽 → 拒绝委派（MA-T17）")
    void exhaustedBalanceBlocksDelegation() {
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "6", "4")));

        assertThat(new BudgetBalanceProbe(budget, "CNY", unlimitedRunBudget()).hasRemaining(TENANT, RUN)).isFalse();
    }

    @Test
    @DisplayName("未开通预算账户 → 不额外限制（没配预算 ≠ 余额为 0）")
    void missingAccountDoesNotBlock() {
        BudgetPort budget = budgetWith(Optional.empty());

        assertThat(new BudgetBalanceProbe(budget, "CNY", unlimitedRunBudget()).hasRemaining(TENANT, RUN)).isTrue();
    }

    @Test
    @DisplayName("余额读不到 → fail-closed（不得当作「还有预算」）")
    void unreadableBalanceFailsClosed() {
        BudgetPort budget = new BudgetPort() {
            @Override
            public ReserveOutcome reserve(String t, String r, String o, BigDecimal a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean settle(String t, String r, BigDecimal a, String m) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean release(String t, String r, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Reservation> findByOperation(String t, String o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AccountSnapshot> accountSnapshot(String t, String c) {
                // ⚠️ 模拟预算系统故障
                throw new IllegalStateException("模拟预算服务不可用");
            }

            @Override
            public List<OrphanedReservation> findOrphaned(String t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isConsistent(String t, String c) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(new BudgetBalanceProbe(budget, "CNY", unlimitedRunBudget()).hasRemaining(TENANT, RUN))
                .as("预算系统故障时放行 = 无限花钱，与既有闸门口径相反")
                .isFalse();
    }

    // ── Run 级口径（第七批 · MA-T17 · 全调用树共享）───────────────

    @Test
    @DisplayName("✅ Run 级额度已用尽 → 拒绝委派（租户余额充足也不行）")
    void exhaustedRunBudgetBlocksDelegation() {
        // ⚠️ 租户还有 7 元，但**这一棵树**的额度已用尽 → 必须拒绝。
        //    这正是「全调用树共享预算上限」的含义：嵌套 Agent 无法另起额度。
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "2", "1")));

        assertThat(new BudgetBalanceProbe(budget, "CNY", runBudgetWith("10", "6", "4"))
                .hasRemaining(TENANT, RUN))
                .as("⚠️ 租户有余额不等于这棵树还有额度")
                .isFalse();
    }

    @Test
    @DisplayName("✅ Run 级额度未声明 → 不额外限制（不是「余额为 0」）")
    void undeclaredRunBudgetDoesNotBlock() {
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "2", "1")));

        assertThat(new BudgetBalanceProbe(budget, "CNY", unlimitedRunBudget())
                .hasRemaining(TENANT, RUN)).isTrue();
    }

    @Test
    @DisplayName("⛔ Run 级额度读不到 → fail-closed（预算故障不得当作「还有额度」）")
    void unreadableRunBudgetFailsClosed() {
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "2", "1")));
        RunBudgetProbe broken = new RunBudgetProbe((tenantId, runId) -> {
            throw new IllegalStateException("模拟 Run 级预算读取失败");
        });

        assertThat(new BudgetBalanceProbe(budget, "CNY", broken).hasRemaining(TENANT, RUN))
                .as("⚠️ 读不到权威额度 → fail-closed")
                .isFalse();
    }

    @Test
    @DisplayName("✅ Run 级额度尚有余额 → 放行")
    void remainingRunBudgetAllowsDelegation() {
        BudgetPort budget = budgetWith(Optional.of(snapshot("10", "2", "1")));

        assertThat(new BudgetBalanceProbe(budget, "CNY", runBudgetWith("10", "3", "2"))
                .hasRemaining(TENANT, RUN)).isTrue();
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static BudgetPort.AccountSnapshot snapshot(String total, String reserved,
                                                       String settled) {
        return new BudgetPort.AccountSnapshot("acc-1", "CNY",
                new BigDecimal(total), new BigDecimal(reserved), new BigDecimal(settled));
    }

    /** 「未声明 Run 级额度」的端口替身（= 不额外限制）。 */
    private static RunBudgetProbe unlimitedRunBudget() {
        return new RunBudgetProbe((tenantId, runId) -> java.util.Optional.empty());
    }

    /** 指定 Run 级额度与已用量的端口替身。 */
    private static RunBudgetProbe runBudgetWith(String amount, String reserved,
                                                 String settled) {
        return new RunBudgetProbe((tenantId, runId) -> java.util.Optional.of(
                new cn.studykid.multiagent.ports.persistence.RunBudgetPort.RunBudget(
                        runId, new BigDecimal(amount), new BigDecimal(reserved),
                        new BigDecimal(settled))));
    }

    private static BudgetPort budgetWith(Optional<BudgetPort.AccountSnapshot> snapshot) {
        return new BudgetPort() {
            @Override
            public ReserveOutcome reserve(String t, String r, String o, BigDecimal a) {
                return ReserveOutcome.RESERVED;
            }

            @Override
            public boolean settle(String t, String r, BigDecimal a, String m) {
                return true;
            }

            @Override
            public boolean release(String t, String r, String reason) {
                return true;
            }

            @Override
            public Optional<Reservation> findByOperation(String t, String o) {
                return Optional.empty();
            }

            @Override
            public Optional<AccountSnapshot> accountSnapshot(String t, String c) {
                return snapshot;
            }

            @Override
            public List<OrphanedReservation> findOrphaned(String t) {
                return List.of();
            }

            @Override
            public boolean isConsistent(String t, String c) {
                return true;
            }
        };
    }
}
