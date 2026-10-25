package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;

/**
 * 委派预算预留的<b>反查</b>端口（多级 Agent v1.1 · 第二十二批 · C22-1 · A2）。
 *
 * <p>⚠️ <b>为什么需要这个端口（这是一个真实的额度泄漏）</b>：
 * 委派的预算预留落在 {@code budget_reservation} 上，
 * 但它的 {@code operation_id} 是<b>委派调用标识</b>（{@code agent_invocation.invocation_id}），
 * 而<b>不是</b> {@code tool_operation.operation_id} ——
 * 委派记在 {@code agent_invocation} 里，<b>没有</b>对应的 {@code tool_operation} 行。
 * 而既有的两条对账查询（{@code findOrphaned} / {@code findUnknownHeld}）
 * 都用 <b>INNER JOIN tool_operation</b> →
 * <b>委派的预留对它们完全不可见</b>：
 * <ul>
 *   <li>卡在 {@code UNKNOWN} 的委派 → 预留永远停在 {@code RESERVED}，
 *       额度被长期占用，<b>而且连「转人工清单」都进不去</b>
 *       （{@code unknownHeld} 统计恒为 0 → {@code needsAttention()} 恒为 false
 *       → 运维看不到任何异常）；</li>
 *   <li>闸门在「预留成功」与「结算/释放」之间崩溃的委派 →
 *       同样是一笔看不见的悬挂额度。</li>
 * </ul>
 * 换句话说：<b>额度确实被占了，但没有任何一条查询能找到它</b>。
 *
 * <p>⚠️ <b>本端口的语义边界</b>：只返回<b>仍在 RESERVED</b> 且
 * <b>在 {@code tool_operation} 里找不到锚点</b>的预留 ——
 * 即「非工具侧」的预留。这与工具侧的两条查询构成<b>互补</b>而非重叠的划分：
 * <ul>
 *   <li>工具侧：{@code tool_operation} 有锚点 → {@code findOrphaned} / {@code findUnknownHeld} 负责；</li>
 *   <li>委派侧：本端口负责（{@code agent_invocation} 有锚点，或两侧都没有锚点）。</li>
 * </ul>
 *
 * <p>⚠️ <b>「没有锚点」也是一种结论</b>：委派终态提交时若报
 * {@code NOT_FOUND}（记录不存在），闸门会判 UNKNOWN 并<b>持有</b>预留 ——
 * 此时这条预留<b>连 {@code agent_invocation} 行都没有</b>，
 * 它只能靠「工具侧无锚点」这一条件被反查出来。
 * 把它一并暴露出来，是为了让「额度被占但查无实据」这件事<b>至少可见</b>。
 */
public interface DelegationBudgetPort {

    /**
     * 反查该租户<b>仍被持有</b>的委派侧预留（含无锚点者）。
     *
     * <p>⚠️ 返回的是<b>持有</b>，不是<b>悬挂</b>：
     * 持有可能因为「子执行还在跑」「结果不确定」「闸门崩溃」——
     * 三者的处置动作<b>完全不同</b>，判定在应用层做（见
     * {@code DelegationBudgetReconciliationService}）。
     *
     * @param tenantId 租户标识
     * @return 持有中的预留（按创建时间升序）；无则为空列表
     */
    @NonNull List<HeldReservation> findHeldReservations(@NonNull String tenantId);

    /**
     * 按操作标识反查<b>单条</b>持有中的预留。
     *
     * <p>⚠️ 只返回<b>仍在 RESERVED</b> 的：已结算/已释放的<b>不返回</b> ——
     * 处置入口必须能区分「没找到」与「已经处置过了」，否则重复处置会被
     * 误判成「什么都没发生」。
     *
     * @param tenantId   租户标识
     * @param operationId 操作标识（委派侧即委派调用标识）
     * @return 持有中的预留；不存在或已非 RESERVED 时为 {@link Optional#empty()}
     */
    @NonNull Optional<HeldReservation> findHeldReservation(@NonNull String tenantId,
                                                          @NonNull String operationId);

    /**
     * 一条被持有的委派侧预留。
     *
     * @param reservationId   预留标识
     * @param operationId     操作标识（委派侧即委派调用标识）
     * @param reservedAmount  预留额
     * @param invocationStatus 对应的委派调用状态；<b>可空</b> ——
     *                        空表示<b>连 {@code agent_invocation} 行都没有</b>
     *                        （委派终态提交报 NOT_FOUND 的情形）
     * @param runId           所属 Run 标识；无锚点时可能为空
     * @param createdAt       预留创建时间
     */
    record HeldReservation(@NonNull String reservationId,
                           @NonNull String operationId,
                           @NonNull BigDecimal reservedAmount,
                           @Nullable AgentInvocationStatus invocationStatus,
                           @Nullable String runId,
                           @NonNull Instant createdAt) {

        /**
         * @return 是否有委派调用记录作锚点
         */
        public boolean anchored() {
            return invocationStatus != null;
        }

        /**
         * @return 是否为「结果不确定」的持有（R-007：<b>禁止</b>自动释放/结算）
         */
        public boolean unknownHeld() {
            return invocationStatus == AgentInvocationStatus.UNKNOWN;
        }

        /**
         * @return 委派调用是否已进入终态
         *
         * <p>⚠️ {@code UNKNOWN} <b>也是</b>终态（它是「不再变化」，
         * 而不是「结果已定」）—— 故判断「结果是否已定」要用
         * {@link #definitelyTerminal()}。
         */
        public boolean invocationTerminal() {
            return invocationStatus != null && invocationStatus.isTerminal();
        }

        /**
         * @return 委派调用是否已进入<b>确定</b>终态（<b>不含</b> UNKNOWN）
         *
         * <p>⚠️ <b>为什么要把 UNKNOWN 单列</b>：
         * {@code UNKNOWN} 表示「结果不确定、进入对账」，
         * 它是<b>待收敛</b>而不是<b>已定论</b> ——
         * 处置动作必须区分「已经知道结果了」与「等对账给结论」，
         * 否则 UNKNOWN 会被误当成「已结束」而自动结算（R-007 红线）。
         */
        public boolean definitelyTerminal() {
            return invocationTerminal() && invocationStatus != AgentInvocationStatus.UNKNOWN;
        }

        /**
         * @return 委派调用是否<b>仍在途</b>（QUEUED / RUNNING）
         */
        public boolean inFlight() {
            return invocationStatus != null && !invocationStatus.isTerminal();
        }
    }
}
