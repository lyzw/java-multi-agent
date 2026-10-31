package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;

/**
 * 审批决定唤醒端口（M1-6 / G2 · PARK → RESUME）。
 *
 * <p>⚠️ <b>为什么需要独立端口</b>：审批台在 {@code agent-api}，
 * 而「拉回挂起 Run 并重入闸门」的执行能力在 {@code agent-worker}
 * （需要 {@code RunExecutionLoop} 与账本）。若 API 直接引用 Worker 的类，
 * 依赖方向就反了（API → Worker），破坏分层。
 * 本端口把「决定到达 → 唤醒挂起 Run」定义在中立的端口层，
 * 两侧各自依赖它。
 *
 * <p>⚠️ <b>语义</b>：实现方<b>不得</b>用「重跑模型」的方式恢复 ——
 * 必须用同一幂等键重入闸门，否则同一逻辑操作会被执行两次（重复副作用）。
 */
public interface ApprovalWakeupPort {

    /**
     * 审批决定落库后唤醒对应的挂起 Run。
     *
     * <p>⚠️ 实现方<b>不得抛异常</b>：唤醒失败属可观测事实，
     * 不是调用方的错误（决定已落库是不可回滚的既成事实）。
     *
     * @param tenantId   租户
     * @param approvalId 审批行标识
     * @return 唤醒结果
     */
    @NonNull Result wakeup(@NonNull String tenantId, @NonNull String approvalId);

    /**
     * 唤醒结果。
     *
     * <p>⚠️ 全部取值都是<b>可观测事实</b>，不表示「成功/失败」的隐式判断：
     * 调用方据此打点，不据此放行任何动作。
     */
    enum Result {
        /** 已唤醒并进入恢复执行。 */
        RESUMED,
        /** 审批行不可读（不存在或不属于该租户）。 */
        NOT_FOUND,
        /** 审批仍未决定（PENDING）。 */
        NOT_DECIDED,
        /** Run 不处于 WAITING_APPROVAL（已被唤醒 / 已终态 / 不存在）。 */
        NOT_PARKED,
        /** 无法反查原始调用 → Run 显式收敛 FAILED，工具未执行。 */
        INVOCATION_MISSING
    }
}
