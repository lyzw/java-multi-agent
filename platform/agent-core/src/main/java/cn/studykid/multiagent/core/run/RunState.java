package cn.studykid.multiagent.core.run;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * Run 生命周期状态与合法迁移。
 *
 * <p>设计要点（P0-D03 §4 / FR-004）：
 * <ul>
 *   <li>状态迁移必须经由本类型校验，<b>禁止任意字段直接改状态</b>；</li>
 *   <li>终态不可再迁出，防止并发覆盖；</li>
 *   <li>领域层零框架依赖（v3.1 架构约束），故不使用任何 Spring 注解。</li>
 * </ul>
 *
 * <p>状态语义：
 * <pre>
 *   QUEUED ──claim──> RUNNING ──complete──> SUCCEEDED
 *      │                 │                      (终态)
 *      │                 ├──fail─────> FAILED   (终态)
 *      │                 ├──cancel───> CANCELLED(终态)
 *      │                 └──timeout──> TIMED_OUT(终态)
 *      └──cancel─────────> CANCELLED           (终态)
 * </pre>
 *
 * <p>UNKNOWN 不是 Run 状态，而是 <b>ToolOperation 状态</b>：外部副作用结果不确定时
 * 进入 UNKNOWN，须对账处置，不得当作普通失败盲目重试（P0-D03 §4 红线 7）。
 */
public enum RunState {

    /** 已创建并与初始事件、幂等记录、Outbox 在同一事务提交。 */
    QUEUED,

    /** Worker 已领取并持有有效租约。 */
    RUNNING,

    /**
     * 等待人工审批（M1 / G2 修复核心 · PARK 态）。
     *
     * <p>⚠️ <b>非终态</b>：工具调用需要审批、且已登记待办后，
     * Run 让出 worker 线程与租约进入本态；审批决定到达后由
     * {@code ApprovalDecidedWakeupConsumer} 唤醒并 {@code RESUME} 回 {@link #RUNNING}。
     * 此间不占用 worker 资源（不空转、不续租）。
     *
     * <p>⚠️ 仅允许迁向 {@code RUNNING}（恢复）/ {@code CANCELLED}（人撤）/ 
     * {@code TIMED_OUT}（审批超时兜底）/ {@code FAILED}（人拒或超时收敛），
     * 不允许从本态直接进入 {@code SUCCEEDED}（成功只能在恢复并重执行后判定）。
     */
    WAITING_APPROVAL,

    /** 协作式取消登记后停止启动新动作。终态。 */
    CANCELLED,

    /** 正常完成。终态。 */
    SUCCEEDED,

    /** 可归因失败。终态。 */
    FAILED,

    /** 超过时长上限。终态。 */
    TIMED_OUT;

    private static final Map<RunState, Set<RunState>> LEGAL_TRANSITIONS = buildTransitions();

    private static Map<RunState, Set<RunState>> buildTransitions() {
        Map<RunState, Set<RunState>> m = new EnumMap<>(RunState.class);
        m.put(QUEUED, EnumSet.of(RUNNING, CANCELLED));
        m.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, WAITING_APPROVAL));
        // ⚠️ M1-1：WAITING_APPROVAL 的恢复/收敛迁移（PARK → RESUME/终止）
        m.put(WAITING_APPROVAL, EnumSet.of(RUNNING, CANCELLED, TIMED_OUT, FAILED));
        m.put(CANCELLED, EnumSet.noneOf(RunState.class));
        m.put(SUCCEEDED, EnumSet.noneOf(RunState.class));
        m.put(FAILED, EnumSet.noneOf(RunState.class));
        m.put(TIMED_OUT, EnumSet.noneOf(RunState.class));
        return Collections.unmodifiableMap(m);
    }

    /** @return 允许迁移到的目标状态集合；终态返回空集。 */
    public @NonNull Set<RunState> legalTargets() {
        return LEGAL_TRANSITIONS.get(this);
    }

    /** @return 终态不可迁出。 */
    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }

    public boolean canTransitionTo(@NonNull RunState target) {
        return legalTargets().contains(target);
    }

    /**
     * 校验迁移合法性，非法即抛异常——<b>不静默忽略</b>，避免并发覆盖被掩盖。
     *
     * @throws IllegalStateTransitionException 迁移非法时
     */
    public void requireTransitionTo(@NonNull RunState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }
}
