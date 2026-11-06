package cn.studykid.multiagent.infrastructure.budget;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.orchestration.DelegationBudgetReconciliationService;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.ports.persistence.BudgetPort;

/**
 * 预算对账服务（P3-D04 §4.3 / FR-012「预留有唯一记录」+「重复结算不重复计费」）。
 *
 * <p><b>对账三类差异，三种处置</b>：
 * <table border="1">
 *   <caption>处置矩阵</caption>
 *   <tr><th>差异</th><th>处置</th></tr>
 *   <tr><td>悬挂预留（RESERVED 但操作已终态且成功）</td><td>结算</td></tr>
 *   <tr><td>悬挂预留（操作已终态且未成功）</td><td>释放</td></tr>
 *   <tr><td>UNKNOWN 预留</td><td>⚠️ <b>不处置</b>，只统计并转人工</td></tr>
 *   <tr><td>账实不符</td><td>⚠️ <b>只报不改</b></td></tr>
 * </table>
 *
 * <p><b>⚠️ 为什么账实不符不自动修正</b>：
 * 自动「修正」账户余额 = 把「钱账不符」变成「看起来钱账相符」，
 * 差异被<b>永久掩盖</b>，且掩盖的正是「有人手工改过数据」这类线索。
 * 正确做法是告警 + 人工介入（归属 D7 审计链路）。
 *
 * <p><b>⚠️ 为什么 UNKNOWN 不处置</b>：
 * 成本可能已发生（R-007）。自动释放 = 白送额度；
 * 自动结算 = 凭空多计费。只有对账收敛后才有依据。
 *
 * <p>对账是<b>幂等</b>的：可反复扫描，已处置的不会被再碰
 * （{@code reserve/settle/release} 均以状态为前置条件）。
 */
public class BudgetReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(BudgetReconciliationService.class);

    private static final String CURRENCY = "CNY";

    private final BudgetPort budget;
    private final JdbcBudgetRepository repository;

    /**
     * 委派侧的预算对账通道（第二十二批 · C22-1）。
     *
     * <p>⚠️ <b>可空</b>：未装配时<b>显式告警</b>而非静默跳过 ——
     * 静默跳过等于让「委派额度泄漏」这件事<b>永远不被发现</b>。
     */
    private final DelegationBudgetReconciliationService delegationReconciliation;

    public BudgetReconciliationService(@NonNull BudgetPort budget,
                                       @NonNull JdbcBudgetRepository repository) {
        this(budget, repository, null);
    }

    public BudgetReconciliationService(@NonNull BudgetPort budget,
                                       @NonNull JdbcBudgetRepository repository,
                                       @Nullable DelegationBudgetReconciliationService
                                               delegationReconciliation) {
        this.budget = budget;
        this.repository = repository;
        this.delegationReconciliation = delegationReconciliation;
    }

    /** 对账结果统计。 */
    public record ReconcileStats(int scanned,
                                 int settled,
                                 int released,
                                 int unknownHeld,
                                 boolean consistent,
                                 int delegationHeld,
                                 int delegationUnknownHeld,
                                 int delegationUnanchored) {

        /**
         * @return 是否存在需要人工介入的项。
         *
         * <p>⚠️ <b>委派侧的 UNKNOWN 与无锚点也必须算进来</b>（第二十二批 · C22-1）：
         * 委派的预留记在 {@code agent_invocation} 里，<b>没有</b>对应的
         * {@code tool_operation} 行 → 工具侧的两条查询（{@code findOrphaned} /
         * {@code findUnknownHeld}）用 INNER JOIN，<b>看不见它们</b>。
         * 若这里也不计，运维看到的永远是「一切正常」，
         * 而实际上额度正被一批看不见的委派预留长期占着。
         */
        public boolean needsAttention() {
            return unknownHeld > 0 || !consistent
                    || delegationUnknownHeld > 0 || delegationUnanchored > 0;
        }
    }

    /**
     * 扫描并处置该租户的预算差异。
     *
     * @param settleAmount 对「悬挂且操作成功」的预留，结算为该金额
     *                    （调用方按实际 usage 提供；null 时按预留额全额结算）
     */
    public @NonNull ReconcileStats reconcileAll(@NonNull String tenantId,
                                                java.math.BigDecimal settleAmount) {
        List<BudgetPort.OrphanedReservation> orphaned = budget.findOrphaned(tenantId);

        int settled = 0;
        int released = 0;
        for (BudgetPort.OrphanedReservation r : orphaned) {
            // ⚠️ 终态只有一种：成功类结算，其余一律释放。
            //    「MANUAL_DISPOSED 视为成功」是错误推断——人工处置的结论
            //    不一定是副作用已发生，此处按「未确认成功」处理 = 释放额度，
            //    真实成本是否发生由 usage_record 独立记录，不依赖预留。
            boolean succeeded = "SUCCEEDED".equals(r.operationState())
                    || "RECONCILED_SUCCEEDED".equals(r.operationState());
            if (succeeded) {
                java.math.BigDecimal amount = settleAmount != null
                        ? settleAmount : r.reservedAmount();
                if (amount.compareTo(r.reservedAmount()) > 0) {
                    // ⚠️ 结算额超过预留：结转会被 CHECK 拒绝，
                    //    这里提前转人工而不是让整个对账崩掉
                    log.warn("对账结算额超预留，转人工: reservation={}, 结算={}, 预留={}",
                            r.reservationId(), amount, r.reservedAmount());
                    continue;
                }
                if (budget.settle(tenantId, r.reservationId(), amount, "reconciliation")) {
                    settled++;
                }
            } else if (budget.release(tenantId, r.reservationId(),
                    "reconcile:operation-terminal:" + r.operationState())) {
                released++;
            }
        }

        // ⚠️ UNKNOWN 预留只统计，绝不处置（R-007）
        int unknownHeld = repository.findUnknownHeld(tenantId).size();

        // ⚠️ 委派侧（第二十二批 · C22-1）：委派的预留记在 agent_invocation 里，
        //    工具侧的两条查询 INNER JOIN tool_operation → **看不见它们**。
        //    这里补上反查通道：终态的自动结算，UNKNOWN 与无锚点的只统计。
        int delegationHeld = 0;
        int delegationUnknownHeld = 0;
        int delegationUnanchored = 0;
        if (delegationReconciliation != null) {
            // ⚠️ 终态委派的悬挂预留：子执行已启动 → 成本可能已发生 → 结算
            delegationReconciliation.settleTerminalHeld(tenantId);
            DelegationBudgetReconciliationService.ScanResult delegationScan =
                    delegationReconciliation.scan(tenantId);
            delegationHeld = delegationScan.heldCount();
            delegationUnknownHeld = delegationScan.unknown().size();
            delegationUnanchored = delegationScan.unanchored().size();
        } else {
            // ⚠️ **未装配委派对账通道 → 显式告警，绝不静默跳过**：
            //    静默跳过 = 委派侧的额度泄漏永远不会被发现，
            //    而运维看到的仍然是「预算一切正常」。
            log.warn("⚠️ 委派预算对账通道未装配 → 委派侧的悬挂/UNKNOWN 预留"
                    + "**不可见**（额度可能长期被占用且无人发现）: tenant={}", tenantId);
        }

        boolean consistent = budget.isConsistent(tenantId, CURRENCY);
        if (!consistent) {
            log.error("⚠️⚠️ 预算账实不符（reserved/settled 与明细不符），"
                    + "只告警不自动修正: tenant={}", tenantId);
        }

        if (log.isInfoEnabled()) {
            log.info("预算对账完成: tenant={}, 扫描={}, 结算={}, 释放={}, UNKNOWN持有={}, "
                            + "委派持有={}, 委派UNKNOWN={}, 委派无锚点={}, 账实一致={}",
                    tenantId, orphaned.size(), settled, released, unknownHeld,
                    delegationHeld, delegationUnknownHeld, delegationUnanchored, consistent);
        }
        return new ReconcileStats(orphaned.size(), settled, released,
                unknownHeld, consistent,
                delegationHeld, delegationUnknownHeld, delegationUnanchored);
    }

    /**
     * 判定某个 UNKNOWN 操作的预留应如何处置。
     *
     * <p>供 D3 对账收敛后调用：{@code RECONCILED_SUCCEEDED} → 结算，
     * {@code RECONCILED_ABSENT} → 释放。
     *
     * @param settleAmount 实际成本；{@code null} 时按预留额全额结算
     * @return 是否执行了处置
     */
    public boolean disposeAfterReconciliation(
            @NonNull String tenantId,
            @NonNull String operationId,
            @NonNull ToolOperationState reconciled,
            java.math.BigDecimal settleAmount) {

        if (reconciled != ToolOperationState.RECONCILED_SUCCEEDED
                && reconciled != ToolOperationState.RECONCILED_ABSENT) {
            throw new IllegalArgumentException(
                    "只有对账收敛结果可触发预算处置，收到: " + reconciled);
        }

        var reservation = budget.findByOperation(tenantId, operationId);
        if (reservation.isEmpty()) {
            // 该操作从未预留预算（如纯只读工具），无需处置
            return false;
        }
        String rid = reservation.get().reservationId();

        if (reconciled == ToolOperationState.RECONCILED_SUCCEEDED) {
            java.math.BigDecimal amount = settleAmount != null
                    ? settleAmount : reservation.get().reservedAmount();
            return budget.settle(tenantId, rid, amount, "reconcile:succeeded");
        }
        // ⚠️ RECONCILED_ABSENT = 确认副作用**未发生** → 额度可安全退回
        return budget.release(tenantId, rid, "reconcile:absent");
    }
}
