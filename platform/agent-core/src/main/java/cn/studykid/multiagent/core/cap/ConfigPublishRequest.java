package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 配置变更申请单（P6R4 · 管理端点 · §6.3 / §6.6）。
 *
 * <p>⚠️ <b>本记录承载的是「审批流程」的进度，不是配置对象自身的生命周期</b>：
 * <ul>
 *   <li>本记录：{@code PENDING → APPROVED → APPLIED}（另有 {@code REJECTED} / {@code FAILED}）；</li>
 *   <li>配置对象：{@code DRAFT → VALIDATED → PUBLISHED → DEPRECATED}（{@code cap_lifecycle_state}）。</li>
 * </ul>
 * 两套状态刻意分开 —— 混用会让「审批通过了但还没生效」的对象被误当成「已发布」，
 * 而「已发布」是**可被引用**的语义（§6.6）。
 *
 * <p>⚠️ <b>为什么要单独建这张表</b>：§6.3 的发布是「版本行 + 发布头 + 指针 CAS」的
 * <b>原子事务</b>，天然不可拆成两次调用；而双人复核要求申请人与审批人是两个不同主体。
 * 若把两个 id 放进一次请求由调用方填写，双人复核就退化成
 * 「调用方自己声明另一个人同意了」—— 那不是复核，是自证。
 *
 * @param requestId     申请单标识
 * @param tenantId      租户标识（**平台级发布不经本表**）
 * @param operation     变更类型
 * @param payload       变更方案原文（JSON 文本）—— 审批人批的是这份，执行时执行的也必须是这份
 * @param reason        变更理由（人工可读）
 * @param requesterId   申请人主体标识
 * @param state         审批流程状态
 * @param approverId    审批人主体标识（双人复核：不得等于申请人）
 * @param decisionNote  审批意见
 * @param requestedAt   申请时间
 * @param decidedAt     审批时间
 * @param appliedAt     执行完成时间
 * @param failureReason 执行失败原因（{@code state=FAILED} 时有值）
 */
public record ConfigPublishRequest(
        @NonNull String requestId,
        @NonNull String tenantId,
        @NonNull Operation operation,
        @NonNull String payload,
        @NonNull String reason,
        @NonNull String requesterId,
        @NonNull State state,
        @Nullable String approverId,
        @Nullable String decisionNote,
        @NonNull Instant requestedAt,
        @Nullable Instant decidedAt,
        @Nullable Instant appliedAt,
        @Nullable String failureReason) {

    /** 变更类型。 */
    public enum Operation {
        /** 发布新版本行 + 发布头 + 指针 CAS（§6.3 第 1/3/4 条）。 */
        PUBLISH,
        /** 回滚到指定的历史发布（§6.3 第 6 条）。 */
        ROLLBACK,
        /** 生命周期状态迁移（§6.6）。 */
        LIFECYCLE_TRANSITION
    }

    /** 审批流程状态（**不是**配置对象自身的生命周期）。 */
    public enum State {
        /** 已提交，等待审批。 */
        PENDING,
        /** 已批准，等待执行。 */
        APPROVED,
        /** 已拒绝，终态。 */
        REJECTED,
        /** 已执行成功，终态。 */
        APPLIED,
        /** 执行失败，终态（**不可自动重试**）。 */
        FAILED
    }

    public ConfigPublishRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (requesterId == null || requesterId.isBlank()) {
            throw new IllegalArgumentException("requesterId 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        // ⚠️ 双人复核的存储层兜底：与 DB CHECK 同款，应用层校验不是唯一防线
        if (approverId != null && !approverId.isBlank() && approverId.equals(requesterId)) {
            throw new IllegalArgumentException("审批人不得等于申请人（双人复核）");
        }
    }

    /**
     * @return 是否处于「可执行」状态（已批准且尚未执行）
     */
    public boolean isExecutable() {
        return state == State.APPROVED;
    }

    /**
     * @return 是否处于「待审批」状态
     */
    public boolean isPending() {
        return state == State.PENDING;
    }
}
