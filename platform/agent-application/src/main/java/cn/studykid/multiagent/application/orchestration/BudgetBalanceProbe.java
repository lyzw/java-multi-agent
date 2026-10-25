package cn.studykid.multiagent.application.orchestration;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.BudgetPort;

/**
 * 委派预算探针（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>双口径（缺一不可）</b>：
 * <ol>
 *   <li><b>租户级余额</b>：{@code budget_account}（租户 + 币种）——
 *       「这个租户整体还有多少钱」；</li>
 *   <li><b>Run 级额度</b>：{@code run.budget_amount} 按 {@code run_id} 聚合 ——
 *       「<b>这一棵调用树</b>还能花多少」。</li>
 * </ol>
 * 二者<b>都</b>通过才放行：只看租户级，一个 Run 就能把租户余额烧光；
 * 只看 Run 级，则绕开租户总额度的调用（不经委派的工具调用）不受约束。
 *
 * <p>⚠️ <b>读不到余额 → fail-closed</b>：与既有预算闸门口径一致
 * （「预算系统故障时无限花钱」是 Sev-0 类问题）。
 * 放行的代价远大于拒绝的代价。
 *
 * <p>⚠️ <b>未开通预算账户 → 不额外限制</b>：没有账户意味着该租户没有配预算，
 * 这不是「余额为 0」。真正的守门人是<b>工具调用链路的预算闸门</b>
 * （{@code BudgetPort.reserve}）—— 子 Agent 的工具调用仍会被它拦住，
 * 所以委派层面放行不会造成「无上限花钱」。
 */
public class BudgetBalanceProbe implements DelegationGuard.BudgetProbe {

    private static final Logger log = LoggerFactory.getLogger(BudgetBalanceProbe.class);

    private final BudgetPort budget;
    private final String currency;
    /**
     * Run 级预算探针（第七批）。
     *
     * <p>⚠️ <b>必填</b>：没有它，「全调用树共享预算上限」在委派层就是空的
     * （只能看到租户总额度，看不到这一棵树花了多少）。
     */
    private final RunBudgetProbe runBudget;

    /**
     * @param budget    租户级预算端口
     * @param currency  币种（与预算账户一致）
     * @param runBudget Run 级预算探针
     */
    public BudgetBalanceProbe(@NonNull BudgetPort budget,
                               @NonNull String currency,
                               @NonNull RunBudgetProbe runBudget) {
        this.budget = budget;
        this.currency = currency;
        this.runBudget = runBudget;
    }

    @Override
    public boolean hasRemaining(@NonNull String tenantId, @NonNull String runId) {
        Optional<BudgetPort.AccountSnapshot> snapshot;
        try {
            snapshot = budget.accountSnapshot(tenantId, currency);
        } catch (RuntimeException e) {
            // ⚠️ 预算系统故障 → fail-closed（不得当作「还有预算」）
            log.error("预算余额读取失败，委派 fail-closed: tenant={}, run={}", tenantId, runId, e);
            return false;
        }
        if (snapshot.isPresent() && snapshot.get().available().signum() <= 0) {
            // ⚠️ 租户级余额已耗尽 → 拒绝
            return false;
        }
        // ⚠️ 未开通预算账户 = 没配预算 ≠ 余额为 0 → 租户级不额外限制，
        //    但 **Run 级口径仍然要判**（它与租户账户是否存在无关）。
        return runBudget.hasRemaining(tenantId, runId);
    }
}
