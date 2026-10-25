package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 级预算端口（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>为什么需要独立于 {@link BudgetPort} 的端口</b>：
 * {@code BudgetPort} 的语义是「<b>租户级</b>预算账户」（租户 + 币种维度），
 * 而本端口的语义是「<b>单个 Run</b>能花多少」。
 * 两者<b>不是同一件事</b>：租户有余额，不代表这一棵调用树还有额度 ——
 * 一个 Run 可以把租户余额烧光。
 *
 * <p>⚠️ <b>「全调用树共享」天然成立</b>：所有工具调用（无论多深、属于哪个 Agent）
 * 都记在<b>同一个 {@code run_id}</b> 下（{@code budget_reservation.run_id}），
 * 所以按 Run 聚合出来的消耗就是「这一整棵树的消耗」——
 * 嵌套 Agent <b>无法</b>通过重置额度来绕过上限。
 *
 * <p>⚠️ <b>为何是独立端口而非 {@code BudgetPort} 的 default 方法</b>：
 * default 方法返回「无限制」会让「实现方忘了实现」变成<b>静默失效</b> ——
 * 那正是「看起来有防线、实际不存在」。独立端口要求实现方<b>必须</b>实现，
 * 漏接线在启动/构造期就会暴露。
 */
public interface RunBudgetPort {

    /**
     * 读取某个 Run 的预算额度与已用量。
     *
     * <p>⚠️ <b>三态语义</b>（必须严格区分，混淆会直接变成 Sev-0）：
     * <ul>
     *   <li><b>返回快照</b> → 该 Run <b>声明了</b> Run 级预算额度，可据剩余量判定；</li>
     *   <li><b>返回 {@link Optional#empty()}</b> → 该 Run <b>未声明</b> Run 级额度
     *       （{@code run.budget_amount IS NULL}）= <b>不额外限制</b>，
     *       <b>不是</b>「余额为 0」；</li>
     *   <li><b>抛异常</b> → 读不到权威数据 = <b>fail-closed</b>
     *       （不得当作「还有额度」）。</li>
     * </ul>
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 预算快照；该 Run 未声明额度时为空
     */
    @NonNull Optional<RunBudget> find(@NonNull String tenantId, @NonNull String runId);

    /**
     * Run 级预算快照。
     *
     * @param runId          Run 标识
     * @param budgetAmount   该 Run 的总预算额度
     * @param reservedAmount 尚未结算的预留额（仍占用额度）
     * @param settledAmount  已结算的消耗额
     */
    record RunBudget(@NonNull String runId,
                     @NonNull BigDecimal budgetAmount,
                     @NonNull BigDecimal reservedAmount,
                     @NonNull BigDecimal settledAmount) {

        public RunBudget {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId 不能为空");
            }
            if (budgetAmount == null || reservedAmount == null || settledAmount == null) {
                throw new IllegalArgumentException("预算金额不能为 null");
            }
        }

        /**
         * @return 剩余可花额度（{@code 额度 - 未结算预留 - 已结算消耗}）；
         *         负数表示<b>已超支</b>（不截断为 0 —— 截断会掩盖超支事实）
         */
        @NonNull
        public BigDecimal available() {
            return budgetAmount.subtract(reservedAmount).subtract(settledAmount);
        }
    }
}
