package cn.studykid.multiagent.application.orchestration;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort;

/**
 * 委派预算预留的对账服务（多级 Agent v1.1 · 第二十二批 · C22-1 · A2）。
 *
 * <p>⚠️ <b>它补的是一个真实的额度泄漏</b>：
 * 委派的预算预留落在 {@code budget_reservation} 上，但它的
 * {@code operation_id} 是<b>委派调用标识</b>，委派记在 {@code agent_invocation} 里，
 * <b>没有</b>对应的 {@code tool_operation} 行 ——
 * 而工具侧的两条对账查询都用 <b>INNER JOIN tool_operation</b>，
 * 于是委派的预留<b>对它们完全不可见</b>：
 * 卡在 UNKNOWN 的委派，其额度被长期占用，却<b>连转人工清单都进不去</b>
 * （{@code unknownHeld} 统计恒为 0 → 运维看不到任何异常）。
 *
 * <p>⚠️ <b>处置矩阵（按委派调用的状态分四类）</b>：
 * <table border="1">
 *   <caption>持有中的委派预留如何处置</caption>
 *   <tr><th>委派调用状态</th><th>含义</th><th>处置</th></tr>
 *   <tr><td>QUEUED / RUNNING</td><td>子执行可能还在跑</td>
 *       <td>⚠️ <b>不动</b>：可能仍在执行，动了就是凭空吞额度</td></tr>
 *   <tr><td>UNKNOWN</td><td>结果不确定（R-007）</td>
 *       <td>⚠️ <b>不动</b>，只统计并转人工；须有明确的收敛结论才可处置</td></tr>
 *   <tr><td>终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT）</td><td>子执行已启动并结束</td>
 *       <td><b>结算</b>：子执行跑过 → 成本可能已发生</td></tr>
 *   <tr><td>无锚点（连 agent_invocation 行都没有）</td><td>委派终态提交报 NOT_FOUND</td>
 *       <td>⚠️ <b>不动</b>，只统计；查无实据 → 不自动改账</td></tr>
 * </table>
 *
 * <p>⚠️ <b>为什么终态一律「结算」而不是照抄工具侧的「成功结算、其余释放」</b>：
 * 闸门对委派的既有口径是「<b>子执行从未启动 → 释放；子执行已启动但失败 → 结算</b>」
 * （子 Agent 已经跑过，模型调用的钱已经花出去）。
 * 而「委派调用有终态」这件事<b>本身就等价于「子执行已启动」</b> ——
 * {@code agent_invocation} 行是在<b>调度子 Agent 之前</b>落下的
 * （{@code DelegationExecutor} 第 6 步插记录、第 7 步才调度），
 * 所以凡是<b>有记录行</b>的委派，子执行都已被调度过。
 * ⚠️ 照抄工具侧的「其余释放」会把这笔钱<b>白送</b>出去。
 *
 * <p>⚠️ <b>UNKNOWN 一律不自动处置</b>（R-007 红线）：
 * 成本是否发生<b>未知</b> → 既不能认定已花（结算），也不能当没花（释放）。
 * 只有拿到<b>明确的收敛结论</b>（{@code RECONCILED_SUCCEEDED} /
 * {@code RECONCILED_ABSENT}）才可处置。
 */
public class DelegationBudgetReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(DelegationBudgetReconciliationService.class);

    private final BudgetPort budget;
    private final DelegationBudgetPort delegationBudget;

    public DelegationBudgetReconciliationService(@NonNull BudgetPort budget,
                                                 @NonNull DelegationBudgetPort delegationBudget) {
        this.budget = budget;
        this.delegationBudget = delegationBudget;
    }

    /**
     * 扫描并分类该租户持有中的委派预留。
     *
     * @param tenantId 租户标识
     * @return 扫描结果
     */
    public @NonNull ScanResult scan(@NonNull String tenantId) {
        List<DelegationBudgetPort.HeldReservation> held =
                delegationBudget.findHeldReservations(tenantId);

        List<DelegationBudgetPort.HeldReservation> inFlight = List.of();
        List<DelegationBudgetPort.HeldReservation> unknown = List.of();
        List<DelegationBudgetPort.HeldReservation> terminal = List.of();
        List<DelegationBudgetPort.HeldReservation> unanchored = List.of();

        var inFlightBuf = new java.util.ArrayList<DelegationBudgetPort.HeldReservation>();
        var unknownBuf = new java.util.ArrayList<DelegationBudgetPort.HeldReservation>();
        var terminalBuf = new java.util.ArrayList<DelegationBudgetPort.HeldReservation>();
        var unanchoredBuf = new java.util.ArrayList<DelegationBudgetPort.HeldReservation>();
        for (DelegationBudgetPort.HeldReservation r : held) {
            if (!r.anchored()) {
                unanchoredBuf.add(r);
            } else if (r.unknownHeld()) {
                unknownBuf.add(r);
            } else if (r.inFlight()) {
                inFlightBuf.add(r);
            } else {
                terminalBuf.add(r);
            }
        }
        inFlight = inFlightBuf;
        unknown = unknownBuf;
        terminal = terminalBuf;
        unanchored = unanchoredBuf;

        ScanResult result = new ScanResult(held.size(), inFlight, unknown, terminal, unanchored);
        if (log.isInfoEnabled()) {
            log.info("委派预算预留扫描: tenant={}, 持有={}, 在途={}, UNKNOWN={}, 终态={}, 无锚点={}",
                    tenantId, result.heldCount(), result.inFlight().size(),
                    result.unknown().size(), result.terminal().size(),
                    result.unanchored().size());
        }
        return result;
    }

    /**
     * 自动处置「委派调用已终态」的悬挂预留（<b>结算</b>）。
     *
     * <p>⚠️ <b>只碰终态</b>：在途的（子执行可能还在跑）与 UNKNOWN 的
     * （成本未知）一律不动 —— 前者动了是凭空吞额度，后者动了违反 R-007。
     *
     * <p>⚠️ <b>与闸门不冲突</b>：闸门在委派返回后会自己结算同一笔预留；
     * 两者都受「RESERVED → SETTLED」的状态前置条件保护，
     * 先到者生效，后到者拿到 {@code false} —— <b>不会重复计费</b>。
     *
     * @param tenantId 租户标识
     * @return 实际结算笔数
     */
    public int settleTerminalHeld(@NonNull String tenantId) {
        ScanResult scan = scan(tenantId);
        int settled = 0;
        for (DelegationBudgetPort.HeldReservation r : scan.terminal()) {
            if (budget.settle(tenantId, r.reservationId(), r.reservedAmount(),
                    "reconcile:delegation-terminal")) {
                settled++;
            }
        }
        if (settled > 0 && log.isInfoEnabled()) {
            log.info("委派终态悬挂预留已结算: tenant={}, 笔数={}", tenantId, settled);
        }
        return settled;
    }

    /**
     * 按<b>明确的收敛结论</b>处置一条持有中的委派预留。
     *
     * <p>⚠️ <b>这是 UNKNOWN 预留唯一的出口</b>：自动处置被 R-007 禁止，
     * 因此「成本到底有没有发生」必须由外部对账给出<b>明确结论</b>，
     * 本方法只负责把结论落到账上。
     *
     * <p>⚠️ <b>拒绝处置「委派调用已终态」的预留</b>：那类预留归
     * {@link #settleTerminalHeld} 自动结算。若此处也放行，
     * 人工给出一个与事实相反的结论就会<b>把已结算的额度再退一次</b>。
     *
     * @param tenantId    租户标识
     * @param operationId 操作标识（委派侧即委派调用标识）
     * @param reconciled  收敛结论：{@code RECONCILED_SUCCEEDED}（成本已发生 → 结算）
     *                    或 {@code RECONCILED_ABSENT}（确认未发生 → 释放）
     * @param settleAmount 实际成本；{@code null} 时按预留额全额结算
     * @return 是否执行了处置
     */
    public boolean disposeHeld(@NonNull String tenantId,
                                @NonNull String operationId,
                                @NonNull ToolOperationState reconciled,
                                @Nullable BigDecimal settleAmount) {
        if (reconciled != ToolOperationState.RECONCILED_SUCCEEDED
                && reconciled != ToolOperationState.RECONCILED_ABSENT) {
            throw new IllegalArgumentException(
                    "只有对账收敛结果可触发预算处置，收到: " + reconciled);
        }

        var held = delegationBudget.findHeldReservation(tenantId, operationId);
        if (held.isEmpty()) {
            // ⚠️ 找不到 = 没有可处置的持有预留（不存在 / 已处置过）
            //    返回 false 而非抛异常：处置入口要能区分「无事可做」与「系统故障」。
            log.info("委派预留无可处置的持有记录: tenant={}, operation={}", tenantId, operationId);
            return false;
        }
        DelegationBudgetPort.HeldReservation r = held.get();

        if (r.definitelyTerminal()) {
            // ⚠️ 终态归自动结算路径管 —— 人工结论不得覆盖它（否则可能把已计的费用再退一次）
            // ⚠️ 注意判据是「**确定**终态」：UNKNOWN 虽是终态枚举值，
            //    但它的语义是「等对账给结论」，**正是本方法的输入**，不能被挡住。
            log.warn("委派预留的调用已是确定终态，拒绝人工处置（应由自动结算路径处理）: "
                    + "tenant={}, operation={}, status={}",
                    tenantId, operationId, r.invocationStatus());
            return false;
        }

        if (reconciled == ToolOperationState.RECONCILED_SUCCEEDED) {
            BigDecimal amount = settleAmount != null ? settleAmount : r.reservedAmount();
            if (amount.signum() < 0 || amount.compareTo(r.reservedAmount()) > 0) {
                // ⚠️ 结算额超出预留 → 提前转人工，而不是让整个对账崩掉
                log.warn("委派预留结算额非法，转人工: tenant={}, operation={}, 结算={}, 预留={}",
                        tenantId, operationId, amount, r.reservedAmount());
                return false;
            }
            return budget.settle(tenantId, r.reservationId(), amount, "reconcile:delegation-succeeded");
        }
        // ⚠️ RECONCILED_ABSENT = 确认成本**未发生** → 额度可安全退回
        return budget.release(tenantId, r.reservationId(), "reconcile:delegation-absent");
    }

    /**
     * 委派预留的扫描结果。
     *
     * @param heldCount  持有中的预留总数
     * @param inFlight   委派调用仍在途（QUEUED / RUNNING）—— <b>不动</b>
     * @param unknown    委派调用结果不确定（UNKNOWN）—— <b>不动</b>，待人工收敛
     * @param terminal   委派调用已终态 —— 可自动结算
     * @param unanchored 连 {@code agent_invocation} 行都没有 —— <b>不动</b>，查无实据
     */
    public record ScanResult(int heldCount,
                              @NonNull List<DelegationBudgetPort.HeldReservation> inFlight,
                              @NonNull List<DelegationBudgetPort.HeldReservation> unknown,
                              @NonNull List<DelegationBudgetPort.HeldReservation> terminal,
                              @NonNull List<DelegationBudgetPort.HeldReservation> unanchored) {

        /**
         * @return 是否存在需要人工介入的项
         *
         * <p>⚠️ <b>UNKNOWN 与无锚点都要算</b>：
         * 这两类是「额度被占但没人能自动处置」的项 ——
         * 它们不体现在工具侧的 {@code unknownHeld} 统计里，
         * 若不单独计入，运维看到的永远是「一切正常」。
         */
        public boolean needsAttention() {
            return !unknown.isEmpty() || !unanchored.isEmpty();
        }
    }
}
