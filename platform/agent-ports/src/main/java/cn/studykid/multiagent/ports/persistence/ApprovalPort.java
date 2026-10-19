package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.config.ApprovalRequirement;

/**
 * 审批消费端口（P3-D05 / FR-013 / 红线 9 协同）。
 *
 * <p><b>为什么存在</b>：P2 建好了 {@code approval} 表（绑定 operation + 参数摘要 +
 * 目标资源 + 有效期），但<b>零 Java 实现</b>。闸门第 ⑤ 环此前对「需要审批」的工具
 * 一律 fail-closed 拒绝（审批流未实现）。本端口把「审批绑定 / 消费 / 撤销」做活，
 * 让 FR-013 的可证伪命题成立：
 * <ul>
 *   <li>审批<b>绑定</b>参数摘要与目标资源 —— 审批后参数被篡改则消费失败（防篡改）；</li>
 *   <li>过期 / 撤销 / 已消费的审批被<b>拒绝</b>；</li>
 *   <li>消费与<b>取消检查原子协调</b> —— 已取消的 Run 不得消费审批、不得产生副作用；</li>
 *   <li>崩溃后可恢复 —— 消费标记与事务原子提交，重启后幂等消费不双重阻塞、不双重计费。</li>
 * </ul>
 *
 * <p><b>⚠️ 谁授予审批</b>：本端口只负责「消费与校验」，不负责「决策是否该批准」。
 * 批准由审批权威（人类审批台 / 策略引擎）调用 {@link #grant} 完成。
 * 当前 Worker 路径没有人类审批台（E-2 流程待建），故需要审批的工具在权威接入前
 * 一律 fail-closed 拒绝——这与既有行为一致，只是路径从「恒拒」变为「查不到有效审批即拒」，
 * 且具备了被真实审批驱动的能力。
 */
public interface ApprovalPort {

    /**
     * 绑定并授予审批（APPROVED）。
     *
     * <p>⚠️ 由审批权威调用；测试可直接授予以构造「已批准」场景。
     * 写入必须带 {@code approver_id} 与 {@code decided_at}（DB 一致性 CHECK 强制）。
     *
     * @return 新审批 ID
     */
    @NonNull String grant(@NonNull String tenantId,
                          @NonNull String operationId,
                          @NonNull String argumentDigest,
                          @NonNull String targetResource,
                          @NonNull ApprovalRequirement requirement,
                          @NonNull String approverScope,
                          @NonNull Instant expiresAt,
                          @NonNull String approverId);

    /**
     * 原子消费审批。
     *
     * <p><b>单一事实源</b>：在<b>同一事务</b>内先锁 Run 校验取消、再锁审批校验状态/有效期/绑定，
     * 全部通过才标记 CONSUMED。任一不满足即按对应 {@link ApprovalOutcome} 拒绝，
     * <b>不标记消费、不抛异常</b>——调用方据此 fail-closed 拒绝工具执行。
     *
     * <p><b>幂等</b>：审批已 CONSUMED 时返回 {@code CONSUMED}（不报错），
     * 以支持「消费提交后崩溃、重启重放」的崩溃恢复场景。审批按 operation_id 1:1 绑定，
     * 不存在「两个不同操作抢同一审批」的双花可能（D-4）。
     *
     * @param argumentDigest 本次调用的参数摘要（须与审批绑定的摘要一致）
     * @param targetResource 本次调用的目标资源（须与审批绑定的目标一致）
     * @param runId          关联 Run（用于原子取消检查）
     */
    @NonNull ApprovalOutcome consume(@NonNull String tenantId,
                                     @NonNull String operationId,
                                     @NonNull String argumentDigest,
                                     @NonNull String targetResource,
                                     @NonNull String runId);

    /**
     * 撤销（APPROVED → REJECTED）。
     *
     * <p>⚠️ 撤销只作用于尚未消费的审批；已消费的审批不可撤销
     * （副作用可能已发生，撤销无意义，须走对账）。
     */
    void revoke(@NonNull String tenantId,
                @NonNull String operationId,
                @NonNull String approverId,
                @NonNull String reason);

    /** @return 该 operation 的有效（APPROVED/PENDING）审批视图；无则空。 */
    java.util.Optional<ApprovalView> findActive(@NonNull String tenantId,
                                               @NonNull String operationId);

    /** 消费结果。每个值都对应一个可被反证的不变量。 */
    enum ApprovalOutcome {
        /** 消费成功（含幂等命中已消费）。 */
        CONSUMED,
        /** 无对应审批记录。 */
        NO_APPROVAL,
        /** 审批被拒绝（state=REJECTED）。 */
        REJECTED,
        /** 审批已过期（state=EXPIRED 或 expires_at 已过）。 */
        EXPIRED,
        /** 审批尚未决议（state=PENDING）或处于其他非批准态。 */
        NOT_APPROVED,
        /** 参数摘要不符（防篡改：审批后参数被改）。 */
        ARGUMENT_MISMATCH,
        /** 目标资源不符。 */
        TARGET_MISMATCH,
        /** Run 已取消（原子取消检查）。 */
        RUN_CANCELLED
    }

    /** 审批视图（findActive 返回）。 */
    record ApprovalView(String approvalId,
                        String operationId,
                        ApprovalPort.ApprovalOutcome state,
                        String requirement,
                        Instant expiresAt,
                        @Nullable String approverId) {
    }
}
