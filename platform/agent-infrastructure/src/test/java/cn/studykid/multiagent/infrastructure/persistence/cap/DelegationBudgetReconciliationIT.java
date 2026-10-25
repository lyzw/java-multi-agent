package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.orchestration.DelegationBudgetReconciliationService;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcToolOperationLedger;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort;

/**
 * 委派预算预留对账通道的 <b>DB 层</b>集成测试（第二十二批 · C22-1 · A2）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：
 * 这条通道的价值<b>全部</b>在于那条反查 SQL ——
 * 「{@code budget_reservation} ⋈ {@code agent_invocation}」能不能在真实 PG 上跑通、
 * 能不能真的把那笔<b>工具侧永远看不见</b>的额度捞出来。
 * 单元级证据（基于假端口）只能证明「调用了正确的方法」，
 * 证不了「这段 SQL 在 PG 上真的能跑」（RLS 策略缺失、列名不匹配、
 * LEFT JOIN 语义被写错成 INNER JOIN……）。
 * <b>生产上第一次用就会炸</b>的典型风险，必须在 IT 里钉死。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>D-1：委派侧预留（{@code agent_invocation} 为 UNKNOWN）<b>能被反查出来</b>
 *       —— 这正是修复前「额度被占却查无实据」的那一笔；</li>
 *   <li>D-2：工具侧预留<b>不被本通道反查</b>（与工具侧两条查询互补、不重叠）；</li>
 *   <li>D-3：连 {@code agent_invocation} 行都没有的预留<b>也能被反查出来</b>
 *       （委派终态提交报 NOT_FOUND 的情形）；</li>
 *   <li>D-4：终态委派被自动结算、UNKNOWN 与无锚点<b>绝不被自动处置</b>（R-007）；</li>
 *   <li>D-5：人工收敛结论能处置 UNKNOWN 的预留；</li>
 *   <li>D-6：跨租户读不到别人的委派预留（RLS）；</li>
 *   <li>D-7：已结算 / 已释放的预留不再出现在反查结果里。</li>
 * </ul>
 */
class DelegationBudgetReconciliationIT extends CapITBase {

    private static final String CURRENCY = "CNY";

    private JdbcBudgetRepository budget;
    private JdbcAgentInvocationRepository invocations;
    private JdbcToolOperationLedger ledger;
    private JdbcDelegationBudgetRepository delegationBudget;
    private DelegationBudgetReconciliationService service;

    @BeforeEach
    void wire() {
        budget = new JdbcBudgetRepository(appPool);
        invocations = new JdbcAgentInvocationRepository(appPool);
        ledger = new JdbcToolOperationLedger(appPool);
        delegationBudget = new JdbcDelegationBudgetRepository(appPool);
        service = new DelegationBudgetReconciliationService(budget, delegationBudget);

        // ⚠️ 每例清理：残留的预留会让「反查只查得到这一笔」的断言失真
        adminExec("DELETE FROM budget_reservation WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM agent_invocation WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM tool_operation WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        resetAccount(tenantA, new BigDecimal("100.00"));
    }

    // ── D-1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-1 ✅ 委派预留（调用为 UNKNOWN）能被反查出来 —— 修复前这笔额度对账完全看不见")
    void delegationReservationIsFoundByReverseLookup() {
        String runId = newRun(tenantA);
        String invocationId = uuid();

        // 委派的预算预留：operation_id = 委派调用标识（**没有** tool_operation 行）
        assertThat(budget.reserve(tenantA, runId, invocationId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        // 对应的委派调用卡在 UNKNOWN
        insertInvocation(tenantA, runId, invocationId, AgentInvocationStatus.UNKNOWN);

        List<DelegationBudgetPort.HeldReservation> held =
                delegationBudget.findHeldReservations(tenantA);

        assertThat(held).as("卡在 UNKNOWN 的委派预留必须能被反查到").hasSize(1);
        DelegationBudgetPort.HeldReservation r = held.get(0);
        assertThat(r.operationId()).isEqualTo(invocationId);
        assertThat(r.invocationStatus()).isEqualTo(AgentInvocationStatus.UNKNOWN);
        assertThat(r.unknownHeld()).isTrue();
        assertThat(r.reservedAmount()).isEqualByComparingTo("1.00");
    }

    // ── D-2 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-2 ⛔ 工具侧预留不被本通道反查（与工具侧两条查询互补、不重叠）")
    void toolSideReservationIsNotFoundByDelegationChannel() {
        String runId = newRun(tenantA);
        // 工具调用：有 tool_operation 行 → 归工具侧的两条查询管
        var reg = ledger.register(tenantA, runId, "record_weakness", "WRITE_PROFILE",
                "it-key-" + uuid(), "digest-" + uuid(), java.util.Map.of("k", "v"), 0L);
        assertThat(budget.reserve(tenantA, runId, reg.operationId(), new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);

        assertThat(delegationBudget.findHeldReservations(tenantA))
                .as("工具侧的预留不该出现在委派通道里（否则会重复处置）")
                .isEmpty();

        // ⚠️ 更强的一条断言：把工具操作标成 UNKNOWN，
        //    验证**同一笔预留**只会出现在工具侧那一条通道里 ——
        //    两条通道是「互补」而非「重复」，否则一次对账会处置两遍。
        ledger.recordResult(tenantA, reg.operationId(), ToolOperationState.UNKNOWN,
                0L, null, "超时");
        assertThat(budget.findUnknownHeld(tenantA))
                .as("工具侧的 UNKNOWN 预留仍由工具侧查询负责（互补，不是取代）")
                .hasSize(1);
        assertThat(delegationBudget.findHeldReservations(tenantA))
                .as("同一笔预留不得同时出现在两条通道里")
                .isEmpty();
    }

    // ── D-3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-3 ✅ 连 agent_invocation 行都没有的预留也能被反查出来（委派终态提交报 NOT_FOUND）")
    void unanchoredReservationIsStillFound() {
        String runId = newRun(tenantA);
        String operationId = uuid();
        assertThat(budget.reserve(tenantA, runId, operationId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        // ⚠️ 故意<b>不</b>建 agent_invocation 行 —— 模拟「委派记录不存在」的终态提交

        List<DelegationBudgetPort.HeldReservation> held =
                delegationBudget.findHeldReservations(tenantA);

        assertThat(held).as("无锚点的预留必须能被反查（否则额度泄漏永远查无实据）").hasSize(1);
        assertThat(held.get(0).anchored()).isFalse();
        assertThat(held.get(0).invocationStatus()).isNull();
        assertThat(held.get(0).definitelyTerminal())
                .as("无锚点 ≠ 已定论，绝不能被当成终态自动结算").isFalse();
    }

    // ── D-4 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-4 ✅ 终态委派被自动结算；UNKNOWN 与无锚点绝不被自动处置（R-007）")
    void terminalIsSettledButUnknownAndUnanchoredAreNot() {
        String runId = newRun(tenantA);

        String terminalId = uuid();
        assertThat(budget.reserve(tenantA, runId, terminalId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runId, terminalId, AgentInvocationStatus.SUCCEEDED);

        String unknownId = uuid();
        assertThat(budget.reserve(tenantA, runId, unknownId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runId, unknownId, AgentInvocationStatus.UNKNOWN);

        String orphanId = uuid();
        assertThat(budget.reserve(tenantA, runId, orphanId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);

        int settled = service.settleTerminalHeld(tenantA);

        assertThat(settled).as("只有终态的那一笔被结算").isEqualTo(1);
        assertThat(reservationState(tenantA, unknownId))
                .as("UNKNOWN 的预留必须仍在 RESERVED（既不结算也不释放）").isEqualTo("RESERVED");
        assertThat(reservationState(tenantA, orphanId))
                .as("无锚点的预留必须仍在 RESERVED（查无实据不改账）").isEqualTo("RESERVED");
        assertThat(reservationState(tenantA, terminalId))
                .as("终态的预留已结算（额度不得白送）").isEqualTo("SETTLED");
    }

    // ── D-5 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-5 ✅ 人工收敛结论能处置 UNKNOWN 的委派预留（结算与释放两条路）")
    void manualConclusionDisposesUnknownReservation() {
        String runId = newRun(tenantA);

        String settleId = uuid();
        assertThat(budget.reserve(tenantA, runId, settleId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runId, settleId, AgentInvocationStatus.UNKNOWN);

        String releaseId = uuid();
        assertThat(budget.reserve(tenantA, runId, releaseId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runId, releaseId, AgentInvocationStatus.UNKNOWN);

        assertThat(service.disposeHeld(tenantA, settleId,
                ToolOperationState.RECONCILED_SUCCEEDED, new BigDecimal("0.80")))
                .as("收敛结论「成本已发生」→ 结算").isTrue();
        assertThat(service.disposeHeld(tenantA, releaseId,
                ToolOperationState.RECONCILED_ABSENT, null))
                .as("收敛结论「确认未发生」→ 释放").isTrue();

        assertThat(reservationState(tenantA, settleId)).isEqualTo("SETTLED");
        assertThat(reservationState(tenantA, releaseId)).isEqualTo("RELEASED");
    }

    // ── D-6 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-6 ✅ RLS：跨租户读不到别人的委派预留")
    void crossTenantReservationIsInvisible() {
        String runIdA = newRun(tenantA);
        String invocationId = uuid();
        assertThat(budget.reserve(tenantA, runIdA, invocationId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runIdA, invocationId, AgentInvocationStatus.UNKNOWN);

        assertThat(delegationBudget.findHeldReservations(tenantB))
                .as("B 租户读不到 A 租户的委派预留")
                .isEmpty();
        assertThat(delegationBudget.findHeldReservations(tenantA))
                .as("A 租户读得到自己那笔")
                .hasSize(1);
    }

    // ── D-7 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-7 ✅ 已结算 / 已释放的预留不再出现在反查结果里（不重复处置）")
    void disposedReservationsDisappearFromReverseLookup() {
        String runId = newRun(tenantA);
        String settledId = uuid();
        String releasedId = uuid();
        assertThat(budget.reserve(tenantA, runId, settledId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        assertThat(budget.reserve(tenantA, runId, releasedId, new BigDecimal("1.00")))
                .isEqualTo(BudgetPort.ReserveOutcome.RESERVED);
        insertInvocation(tenantA, runId, settledId, AgentInvocationStatus.SUCCEEDED);
        insertInvocation(tenantA, runId, releasedId, AgentInvocationStatus.UNKNOWN);

        assertThat(budget.settle(tenantA, reservationIdOf(tenantA, settledId),
                new BigDecimal("1.00"), "it")).isTrue();
        assertThat(budget.release(tenantA, reservationIdOf(tenantA, releasedId), "it")).isTrue();

        assertThat(delegationBudget.findHeldReservations(tenantA))
                .as("已处置的不再出现在持有清单里（否则会被重复处置）")
                .isEmpty();
        assertThat(service.disposeHeld(tenantA, settledId,
                ToolOperationState.RECONCILED_ABSENT, null))
                .as("已处置的预留不得再被人工处置（否则会把已计的费用再退一次）")
                .isFalse();
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 建一条委派调用记录。 */
    private void insertInvocation(String tenantId, String runId, String invocationId,
                                  AgentInvocationStatus status) {
        assertThat(invocations.insert(new AgentInvocation(
                invocationId, tenantId, runId, null, uuid(), 1, uuid(), 1,
                status, 1, "trace-" + invocationId, "in-digest", null,
                Instant.now(), null, null, 1L)))
                .as("委派记录应写入成功").isTrue();
    }

    /** 建一个可挂预算预留的 Run。 */
    private String newRun(String tenantId) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-deleg-agent"),
                "{}", true);
        String snapshotId = createExecutionSnapshot(tenantId, agentId);
        return createRun(tenantId, agentId, snapshotId);
    }

    /** 重置账户额度（每个用例独立）。 */
    private void resetAccount(String tenantId, BigDecimal limit) {
        adminExec("INSERT INTO budget_account (tenant_id, currency, total_limit) VALUES ('"
                + tenantId + "'::uuid, '" + CURRENCY + "', " + limit + ") "
                + "ON CONFLICT (tenant_id, currency) DO UPDATE SET total_limit = EXCLUDED.total_limit, "
                + "reserved_amount = 0, settled_amount = 0, updated_at = now()");
    }

    /** 读某笔预留的当前状态。 */
    private String reservationState(String tenantId, String operationId) {
        return adminQuery("SELECT state FROM budget_reservation WHERE tenant_id = '"
                + tenantId + "'::uuid AND operation_id = '" + operationId + "'::uuid");
    }

    /** 读某笔预留的预留标识。 */
    private String reservationIdOf(String tenantId, String operationId) {
        return adminQuery("SELECT reservation_id::text FROM budget_reservation WHERE tenant_id = '"
                + tenantId + "'::uuid AND operation_id = '" + operationId + "'::uuid");
    }
}
