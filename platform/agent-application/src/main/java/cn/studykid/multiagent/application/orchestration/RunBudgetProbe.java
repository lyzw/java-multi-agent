package cn.studykid.multiagent.application.orchestration;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.RunBudgetPort;

/**
 * Run 级预算探针（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>它解决的是什么</b>：不可妥协约束之一是
 * 「全调用树共享预算上限，<b>不能通过嵌套重置额度</b>」。
 * 此前系统只有<b>租户级</b>预算账户（租户 + 币种），
 * 于是「这一棵调用树最多花多少」没有独立口径 ——
 * 一个 Run 可以把租户余额烧光，而没有任何机制拦下它。
 *
 * <p>⚠️ <b>「共享」为何天然成立</b>：所有工具调用（无论多深、属于哪个 Agent）
 * 都记在<b>同一个 {@code run_id}</b> 下（{@code budget_reservation.run_id}），
 * 因此按 Run 聚合出的消耗就是整棵树的消耗 —— 嵌套 Agent 重置不了它。
 *
 * <p>⚠️ <b>三态语义</b>（混淆任一者都会出事）：
 * <ul>
 *   <li><b>未声明额度</b>（{@code run.budget_amount IS NULL}）→ <b>放行</b>。
 *       「没配预算」≠「预算为 0」；</li>
 *   <li><b>还有余额</b> → 放行；</li>
 *   <li><b>额度用尽</b> → <b>拒绝</b>；</li>
 *   <li><b>读不到</b> → <b>fail-closed</b>（预算系统故障时无限花钱 = Sev-0）。</li>
 * </ul>
 */
public class RunBudgetProbe {

    private static final Logger log = LoggerFactory.getLogger(RunBudgetProbe.class);

    private final RunBudgetPort runBudgets;

    public RunBudgetProbe(@NonNull RunBudgetPort runBudgets) {
        this.runBudgets = runBudgets;
    }

    /**
     * 判断该 Run 是否还有剩余预算。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 还有余额（true）/ 已用尽或读不到（false）
     */
    public boolean hasRemaining(@NonNull String tenantId, @NonNull String runId) {
        RunBudgetPort.RunBudget budget;
        try {
            var found = runBudgets.find(tenantId, runId);
            if (found.isEmpty()) {
                // ⚠️ 未声明 Run 级额度 = 不额外限制（不是「余额为 0」）
                return true;
            }
            budget = found.get();
        } catch (RuntimeException e) {
            // ⚠️ 预算系统故障 → fail-closed（不得当作「还有预算」）
            log.error("Run 级预算读取失败，fail-closed: tenant={}, run={}", tenantId, runId, e);
            return false;
        }
        var available = budget.available();
        if (available.signum() <= 0) {
            log.info("Run 级预算已用尽: run={}, 额度={}, 已预留={}, 已结算={}",
                    runId, budget.budgetAmount(), budget.reservedAmount(),
                    budget.settledAmount());
            return false;
        }
        return true;
    }
}
