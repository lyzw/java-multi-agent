package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ExecutionIntent;

/**
 * 执行意图仓储端口（§6.1 · W-PERM-P0-T5）。
 *
 * <p>⚠️ <b>写入必须先于外部副作用</b>（P2 步骤 13a / 07 §2）。
 * 崩溃恢复据此区分「未执行」（可安全重试）与「结果未知」（须对账）。
 *
 * <p>读取方在 P3（审批状态机 / 补偿）与崩溃恢复流程。
 */
public interface ExecutionIntentPort {

    /**
     * 记录执行意图。
     *
     * <p>幂等：同一 {@code (tenantId, invocationId)} 已存在意图时返回既有记录，
     * <b>不</b>新建、不覆盖 —— 意图是「已打算执行」的证据，重复写没有意义。
     *
     * @return 生效的执行意图（新建或既有）
     */
    @NonNull ExecutionIntent record(@NonNull ExecutionIntent intent);

    /**
     * 按调用标识读取执行意图。
     */
    @NonNull Optional<ExecutionIntent> findByInvocation(@NonNull String tenantId,
                                                        @NonNull String invocationId);

    /**
     * <b>CAS 式</b>状态迁移：仅当当前状态等于 {@code expectedFrom} 时才迁移。
     *
     * <p>⚠️ 状态<b>只能前进</b>（RESERVED → INTENT_SUBMITTED → CONSUMED，
     * 旁路 CANCELLED / EXPIRED）。用「期望原状态」做前置条件，
     * 是为了防止并发下同一意图被消费两次（P3 审批一次性消费）。
     *
     * @return 迁移成功返回 {@code true}；当前状态不匹配返回 {@code false}
     */
    boolean transitionState(@NonNull String tenantId,
                            @NonNull String invocationId,
                            ExecutionIntent.@NonNull IntentState expectedFrom,
                            ExecutionIntent.@NonNull IntentState to);
}
