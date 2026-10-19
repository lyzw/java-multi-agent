package cn.studykid.multiagent.core.run;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * ToolOperation（工具逻辑操作）状态。
 *
 * <p><b>三层 Attempt 必须区分</b>（P0-D03 §4 红线 4、ADR-004）：
 * <pre>
 *   Run attempt        : Run 的第 n 次整体执行尝试
 *   ToolOperation      : 一个<em>逻辑</em>工具操作（对账与幂等的最小单位）
 *   ToolOperationAttempt: 该逻辑操作的第 m 次<em>调用</em>尝试
 * </pre>
 * UNKNOWN 归属 <b>ToolOperation</b> 而非 attempt：它表达「这个逻辑操作的结果未知」，
 * 与「尝试了几次」正交。
 *
 * <p><b>UNKNOWN 语义（本平台最重要的安全约束之一）</b>：
 * 当请求已发出但响应丢失（超时、断网、下游崩溃），结果不可知。此时
 * <ul>
 *   <li><b>不得</b>当作普通 FAILED 盲目重试——可能造成重复副作用（R-003/Sev-0）；</li>
 *   <li><b>不得</b>直接释放预算预留——成本实际可能已发生（R-007）；</li>
 *   <li>必须进入对账流程：下游可查则收敛，不可查则转人工队列。</li>
 * </ul>
 */
public enum ToolOperationState {

    /** 已登记幂等键，尚未发出外部调用。 */
    PENDING,

    /** 已发出调用，等待结果。 */
    IN_FLIGHT,

    /** 调用成功且结果已持久化。终态。 */
    SUCCEEDED,

    /** 调用明确失败（下游明确拒绝、参数错误等），可安全重试。终态。 */
    FAILED,

    /** <b>结果未知</b>：已发出但无响应。须对账，<b>不得盲目重试</b>。 */
    UNKNOWN,

    /** 已通过对账确认成功。终态。 */
    RECONCILED_SUCCEEDED,

    /** 已通过对账确认未发生。终态。 */
    RECONCILED_ABSENT,

    /** 人工判定无法确定，登记责任人与处置结论。终态。 */
    MANUAL_DISPOSED;

    private static final Map<ToolOperationState, Set<ToolOperationState>> LEGAL = build();

    private static Map<ToolOperationState, Set<ToolOperationState>> build() {
        Map<ToolOperationState, Set<ToolOperationState>> m = new EnumMap<>(ToolOperationState.class);
        m.put(PENDING, EnumSet.of(IN_FLIGHT, FAILED));
        m.put(IN_FLIGHT, EnumSet.of(SUCCEEDED, FAILED, UNKNOWN));
        // UNKNOWN 只能通过对账或人工处置离开，不能直接重试
        m.put(UNKNOWN, EnumSet.of(RECONCILED_SUCCEEDED, RECONCILED_ABSENT, MANUAL_DISPOSED));
        m.put(SUCCEEDED, EnumSet.noneOf(ToolOperationState.class));
        m.put(FAILED, EnumSet.of(PENDING));
        m.put(RECONCILED_SUCCEEDED, EnumSet.noneOf(ToolOperationState.class));
        m.put(RECONCILED_ABSENT, EnumSet.noneOf(ToolOperationState.class));
        m.put(MANUAL_DISPOSED, EnumSet.noneOf(ToolOperationState.class));
        return Collections.unmodifiableMap(m);
    }

    public @NonNull Set<ToolOperationState> legalTargets() {
        return LEGAL.get(this);
    }

    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }

    public boolean canTransitionTo(@NonNull ToolOperationState target) {
        return legalTargets().contains(target);
    }

    public void requireTransitionTo(@NonNull ToolOperationState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException("ToolOperation", name(), target.name(), legalTargets());
        }
    }

    /**
     * @return 是否需要人工或自动对账处置。
     *         UNKNOWN 与 MANUAL_DISPOSED 之外均为 false。
     */
    public boolean requiresReconciliation() {
        return this == UNKNOWN || this == MANUAL_DISPOSED;
    }
}
