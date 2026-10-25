package cn.studykid.multiagent.application.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort.HeldReservation;

/**
 * 委派预算对账服务的单元测试（第二十二批 · C22-1 · A2）。
 *
 * <p>⚠️ <b>本类要钉死的是「哪些持有中的预留<b>不许</b>被自动处置」</b>：
 * 额度泄漏与「额度被凭空释放」都是严重事故，而两者都长得很像 ——
 * 都表现为「一笔预留不见了」。所以除了「该结算的要结算」，
 * 更要紧的是「<b>不该动的绝对不动</b>」。
 */
class DelegationBudgetReconciliationServiceTest {

    private static final String TENANT = "t-1";

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 记录 settle / release 调用的假预算端口。 */
    private static final class FakeBudget implements BudgetPort {

        final List<String> settled = new ArrayList<>();
        final List<String> released = new ArrayList<>();

        @Override
        public @NonNull ReserveOutcome reserve(@NonNull String tenantId, @NonNull String runId,
                                               String operationId, @NonNull BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }

        @Override
        public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                              @NonNull BigDecimal actualAmount, String modelId) {
            settled.add(reservationId + "@" + actualAmount);
            return true;
        }

        @Override
        public boolean release(@NonNull String tenantId, @NonNull String reservationId,
                               @NonNull String reason) {
            released.add(reservationId);
            return true;
        }

        @Override
        public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                              @NonNull String operationId) {
            return Optional.empty();
        }

        @Override
        public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
            return List.of();
        }

        @Override
        public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                                  @NonNull String currency) {
            return Optional.empty();
        }

        @Override
        public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
            return true;
        }
    }

    /** 假反查端口：直接给出「持有中」的预留清单。 */
    private static final class FakeDelegationBudget implements DelegationBudgetPort {

        final List<HeldReservation> held = new ArrayList<>();

        @Override
        public @NonNull List<HeldReservation> findHeldReservations(@NonNull String tenantId) {
            return List.copyOf(held);
        }

        @Override
        public @NonNull Optional<HeldReservation> findHeldReservation(@NonNull String tenantId,
                                                                      @NonNull String operationId) {
            return held.stream().filter(r -> r.operationId().equals(operationId)).findFirst();
        }
    }

    private static @NonNull HeldReservation held(
            String reservationId, String operationId, AgentInvocationStatus status) {
        return new HeldReservation(
                reservationId, operationId, new BigDecimal("1.00"), status, "run-1",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("M-1 ✅ 反查按委派调用状态把持有中的预留分成四类")
    void scanClassifiesHeldReservationsByInvocationStatus() {
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-inflight", "i-1", AgentInvocationStatus.RUNNING));
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));
        delegation.held.add(held("r-terminal", "i-3", AgentInvocationStatus.SUCCEEDED));
        delegation.held.add(held("r-orphan", "i-4", null));

        var service = new DelegationBudgetReconciliationService(new FakeBudget(), delegation);
        var scan = service.scan(TENANT);

        assertThat(scan.heldCount()).as("持有总数").isEqualTo(4);
        assertThat(scan.inFlight()).as("在途（子执行可能还在跑）").hasSize(1);
        assertThat(scan.unknown()).as("结果不确定（R-007）").hasSize(1);
        assertThat(scan.terminal()).as("已终态").hasSize(1);
        assertThat(scan.unanchored()).as("连委派记录都没有").hasSize(1);
    }

    @Test
    @DisplayName("M-2 ⛔⚠️ UNKNOWN 的委派预留不得被自动处置（R-007：成本未知既不能结算也不能释放）")
    void unknownHeldIsNeverAutoDisposed() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        int settled = service.settleTerminalHeld(TENANT);

        assertThat(settled).as("UNKNOWN 不得被结算").isZero();
        assertThat(budget.settled).as("UNKNOWN 不得被结算").isEmpty();
        assertThat(budget.released).as("UNKNOWN 不得被释放（释放 = 白送额度）").isEmpty();
    }

    @Test
    @DisplayName("M-3 ⛔⚠️ 无锚点的预留不得被自动处置（查无实据 → 不自动改账）")
    void unanchoredHeldIsNeverAutoDisposed() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-orphan", "i-4", null));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        int settled = service.settleTerminalHeld(TENANT);

        assertThat(settled).as("无锚点不得被结算").isZero();
        assertThat(budget.settled).isEmpty();
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-4 ⛔⚠️ 在途的委派预留不得被自动处置（子执行可能还在跑，动了就是凭空吞额度）")
    void inFlightHeldIsNeverAutoDisposed() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-inflight", "i-1", AgentInvocationStatus.RUNNING));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        int settled = service.settleTerminalHeld(TENANT);

        assertThat(settled).as("在途不得被结算").isZero();
        assertThat(budget.settled).isEmpty();
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-5 ✅ 终态的委派预留被自动结算（子执行已启动 → 成本可能已发生，不能退额度）")
    void terminalHeldIsSettled() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-terminal", "i-3", AgentInvocationStatus.SUCCEEDED));
        delegation.held.add(held("r-terminal2", "i-5", AgentInvocationStatus.FAILED));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        int settled = service.settleTerminalHeld(TENANT);

        assertThat(settled).as("终态委派应被结算（不得照抄工具侧的「其余释放」）").isEqualTo(2);
        assertThat(budget.settled).containsExactlyInAnyOrder("r-terminal@1.00", "r-terminal2@1.00");
        assertThat(budget.released).as("终态委派不得被释放").isEmpty();
    }

    @Test
    @DisplayName("M-6 ✅ 人工收敛结论「成本已发生」→ 结算")
    void disposeWithSucceededConclusionSettles() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        boolean done = service.disposeHeld(TENANT, "i-2",
                ToolOperationState.RECONCILED_SUCCEEDED, new BigDecimal("0.80"));

        assertThat(done).as("收敛结论为「已发生」→ 结算").isTrue();
        assertThat(budget.settled).containsExactly("r-unknown@0.80");
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-7 ✅ 人工收敛结论「确认未发生」→ 释放（额度原样退回）")
    void disposeWithAbsentConclusionReleases() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        boolean done = service.disposeHeld(TENANT, "i-2",
                ToolOperationState.RECONCILED_ABSENT, null);

        assertThat(done).as("收敛结论为「未发生」→ 释放").isTrue();
        assertThat(budget.released).containsExactly("r-unknown");
        assertThat(budget.settled).isEmpty();
    }

    @Test
    @DisplayName("M-8 ⛔⚠️ 非收敛状态不得触发处置（只有「对账已收敛」才允许改账）")
    void nonReconciliationOutcomeIsRejected() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));

        var service = new DelegationBudgetReconciliationService(budget, delegation);

        assertThatThrownBy(() -> service.disposeHeld(TENANT, "i-2",
                ToolOperationState.UNKNOWN, null))
                .as("拿「本来就未知」当结论 = 没有任何新信息")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.disposeHeld(TENANT, "i-2",
                ToolOperationState.SUCCEEDED, null))
                .as("拿「执行成功」当对账结论 = 越过了对账这一步")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.settled).isEmpty();
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-9 ⛔⚠️ 终态预留拒绝人工处置（否则已计的费用会被再退一次）")
    void terminalHeldRefusesManualDisposal() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-terminal", "i-3", AgentInvocationStatus.SUCCEEDED));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        boolean done = service.disposeHeld(TENANT, "i-3",
                ToolOperationState.RECONCILED_ABSENT, null);

        assertThat(done).as("终态归自动结算路径管，人工结论不得覆盖").isFalse();
        assertThat(budget.released).as("不得释放已终态的预留").isEmpty();
        assertThat(budget.settled).isEmpty();
    }

    @Test
    @DisplayName("M-10 ⛔⚠️ 结算额超出预留 → fail-closed 不处置（转人工而不是让对账崩掉）")
    void oversizeSettleAmountIsRejected() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        boolean done = service.disposeHeld(TENANT, "i-2",
                ToolOperationState.RECONCILED_SUCCEEDED, new BigDecimal("5.00"));

        assertThat(done).as("结算额超预留必须被拒").isFalse();
        assertThat(budget.settled).isEmpty();
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-11 ✅ 找不到持有记录时返回 false 而不是抛异常（要能区分「无事可做」与「系统故障」）")
    void missingHeldReservationReturnsFalse() {
        FakeBudget budget = new FakeBudget();
        FakeDelegationBudget delegation = new FakeDelegationBudget();

        var service = new DelegationBudgetReconciliationService(budget, delegation);
        boolean done = service.disposeHeld(TENANT, "i-none",
                ToolOperationState.RECONCILED_ABSENT, null);

        assertThat(done).isFalse();
        assertThat(budget.settled).isEmpty();
        assertThat(budget.released).isEmpty();
    }

    @Test
    @DisplayName("M-12 ⚠️ needsAttention 必须把 UNKNOWN 与无锚点都算进去（在途与终态不算）")
    void needsAttentionCoversUnknownAndUnanchored() {
        FakeDelegationBudget delegation = new FakeDelegationBudget();
        delegation.held.add(held("r-inflight", "i-1", AgentInvocationStatus.RUNNING));
        delegation.held.add(held("r-terminal", "i-3", AgentInvocationStatus.SUCCEEDED));
        var service = new DelegationBudgetReconciliationService(new FakeBudget(), delegation);

        assertThat(service.scan(TENANT).needsAttention())
                .as("在途与终态都有明确归属，不该惊动人").isFalse();

        delegation.held.add(held("r-unknown", "i-2", AgentInvocationStatus.UNKNOWN));
        assertThat(service.scan(TENANT).needsAttention())
                .as("UNKNOWN 是「额度被占但没人能自动处置」").isTrue();

        delegation.held.removeIf(r -> r.reservationId().equals("r-unknown"));
        delegation.held.add(held("r-orphan", "i-4", null));
        assertThat(service.scan(TENANT).needsAttention())
                .as("无锚点同样需要人工看一眼").isTrue();
    }
}
