package cn.studykid.multiagent.application.orchestration;

import java.math.BigDecimal;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 *   <li><b>剩余额度 ≥ 本次预留额</b> → 放行；</li>
 *   <li><b>剩余额度 &lt; 本次预留额</b> → <b>拒绝</b>；</li>
 *   <li><b>读不到</b> → <b>fail-closed</b>（预算系统故障时无限花钱 = Sev-0）。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么必须按「量」判，不能只按「方向」判</b>：
 * 只判「还有没有余额」时，剩余 10 元的 Run 依然能预留 50 元 ——
 * <b>单次调用就能突破上限</b>，所谓的「共享上限」名存实亡。
 * 上限是<b>量</b>，判定就必须是量级判定。
 * ⚠️ 本探针是<b>快检</b>（读一次聚合），<b>不是</b>权威：
 * 它与扣减之间存在 check-then-act 窗口，
 * 权威判定在 {@code BudgetPort.reserve} 的同一事务里（见端口契约）。
 */
public class RunBudgetProbe {

    private static final Logger log = LoggerFactory.getLogger(RunBudgetProbe.class);

    private final RunBudgetPort runBudgets;

    public RunBudgetProbe(@NonNull RunBudgetPort runBudgets) {
        this.runBudgets = runBudgets;
    }

    /**
     * 判断该 Run 的额度<b>是否已耗尽</b>（方向性判定）。
     *
     * <p>⚠️ <b>本方法只回答「还有没有余额」，不回答「这一笔装不装得下」</b>：
     * 它用于<b>委派层</b>（{@code DelegationGuard} 的预算探针）——
     * 那一次委派本身<b>不预留额度</b>（钱是子 Agent 的工具调用花的），
     * 此刻没有「本次金额」可判，只能问「这棵树是不是已经没钱了」。
     *
     * <p>⚠️ <b>工具调用链路必须用 {@link #check}</b>（量级判定）：
     * 用本方法代替它，剩余 10 元的 Run 仍能预留 50 元 —— 单次调用即突破上限。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 还有余额（true）/ 已耗尽或读不到（false）
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
        if (budget.available().signum() <= 0) {
            log.info("Run 级预算已用尽: run={}, 额度={}, 已预留={}, 已结算={}",
                    runId, budget.budgetAmount(), budget.reservedAmount(),
                    budget.settledAmount());
            return false;
        }
        return true;
    }

    /**
     * 判断本次预留金额是否落在该 Run 的额度内。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @param amount   本次预留金额（必须为正）
     * @return 判定结论（见 {@link Verdict}）
     */
    public @NonNull Verdict check(@NonNull String tenantId,
                                  @NonNull String runId,
                                  @NonNull BigDecimal amount) {
        // ⚠️ 非法金额一律拒绝：金额非法说明调用方（或配置）出了问题，
        //    此时放行等于「按未定义的额度放行」。
        if (amount.signum() <= 0) {
            log.warn("Run 级预算判定：预留金额非法，fail-closed 拒绝: run={}, amount={}",
                    runId, amount);
            return Verdict.deny(Denial.INVALID_AMOUNT, null);
        }
        RunBudgetPort.RunBudget budget;
        try {
            var found = runBudgets.find(tenantId, runId);
            if (found.isEmpty()) {
                // ⚠️ 未声明 Run 级额度 = 不额外限制（不是「余额为 0」）
                return Verdict.allowNoLimit();
            }
            budget = found.get();
        } catch (RuntimeException e) {
            // ⚠️ 预算系统故障 → fail-closed（不得当作「还有预算」）
            log.error("Run 级预算读取失败，fail-closed: tenant={}, run={}", tenantId, runId, e);
            return Verdict.deny(Denial.READ_FAILED, null);
        }
        var available = budget.available();
        if (available.compareTo(amount) < 0) {
            log.info("Run 级预算不足以容纳本次预留: run={}, 额度={}, 已预留={}, 已结算={}, 本次={}",
                    runId, budget.budgetAmount(), budget.reservedAmount(),
                    budget.settledAmount(), amount);
            return Verdict.deny(Denial.AMOUNT_EXCEEDS_REMAINING, available);
        }
        return Verdict.allow(available);
    }

    /**
     * 拒绝原因。
     *
     * <p>⚠️ <b>必须可区分</b>：「额度不够」是业务约束，
     * 「读不到」是基础设施故障 —— 两者的处置路径完全不同
     * （前者调额度，后者查预算服务）。
     */
    public enum Denial {

        /** 剩余额度不足以容纳本次预留（业务约束）。 */
        AMOUNT_EXCEEDS_REMAINING,

        /** 预算读不到（基础设施故障 → fail-closed）。 */
        READ_FAILED,

        /** 预留金额非法（调用方/配置缺陷 → fail-closed）。 */
        INVALID_AMOUNT
    }

    /**
     * 判定结论。
     *
     * @param allowed   是否放行
     * @param denial    拒绝原因（放行时为 {@code null}）
     * @param available 剩余额度（未声明额度或读不到时为 {@code null}）
     */
    public record Verdict(boolean allowed,
                           @Nullable Denial denial,
                           @Nullable BigDecimal available) {

        /** @return 放行（有限额且额度充足）。 */
        static @NonNull Verdict allow(@NonNull BigDecimal available) {
            return new Verdict(true, null, available);
        }

        /** @return 放行（未声明 Run 级额度 = 不额外限制）。 */
        static @NonNull Verdict allowNoLimit() {
            return new Verdict(true, null, null);
        }

        /** @return 拒绝。 */
        static @NonNull Verdict deny(@NonNull Denial denial, @Nullable BigDecimal available) {
            return new Verdict(false, denial, available);
        }

        /**
         * @return 用于账本/拒绝记录的稳定原因串（放行时为 {@code null}）
         */
        public @Nullable String reasonCode() {
            if (allowed) {
                return null;
            }
            return switch (denial) {
                case AMOUNT_EXCEEDS_REMAINING -> "run-budget-exhausted";
                case READ_FAILED -> "run-budget-unavailable";
                case INVALID_AMOUNT -> "run-budget-invalid-amount";
            };
        }
    }
}
