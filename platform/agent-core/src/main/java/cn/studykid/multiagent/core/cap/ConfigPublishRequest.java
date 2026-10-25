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
 * <p>⚠️ <b>第三十四批起本表亦承载 PLATFORM 作用域的申请单</b>：
 * 平台级对象（Tool / Skill / MCP Server）无 {@code tenant_id}，
 * 其发布必须写 PLATFORM 作用域的发布头与当前指针 ——
 * 而那两张表的写策略只放行本租户的 TENANT 行，
 * 平台行**只能**由平台运维角色（BYPASSRLS）写入。
 * ⚠️ **平台级变更同样走申请单**（而不是「一次请求带 requester + approver」）：
 * 否则双人复核退化成自证。
 * ⚠️ **平台申请单对应用角色连接不可见**（本表的 RLS 读写策略是严格相等，
 * 平台行的 {@code tenant_id IS NULL} ≠ {@code current_tenant_id()}）——
 * 平台申请单只能经**平台运维角色连接**读写。
 *
 * @param requestId     申请单标识
 * @param tenantId      租户标识；<b>PLATFORM 作用域为 {@code null}</b>
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
 * @param scope         变更作用域（第三十四批 · PLATFORM 作用域的申请单）
 */
public record ConfigPublishRequest(
        @NonNull String requestId,
        @Nullable String tenantId,
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
        @Nullable String failureReason,
        ConfigRelease.@NonNull Scope scope) {

    /**
     * 兼容构造器：<b>租户级</b>申请单（第三十四批之前的形态）。
     *
     * <p>⚠️ 缺省 = {@code scope=TENANT}，<b>不是</b>「作用域随意」——
     * 缺省就是「本申请单属于某个租户」，与旧行为完全一致。
     */
    public ConfigPublishRequest(@NonNull String requestId,
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
        this(requestId, tenantId, operation, payload, reason, requesterId, state, approverId,
                decisionNote, requestedAt, decidedAt, appliedAt, failureReason,
                ConfigRelease.Scope.TENANT);
    }

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
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        // ⚠️ **scope 与 tenantId 必须一致**（与 DB 层 CHECK 同款）：
        //    TENANT 必须带租户，PLATFORM 必须**不带**租户。
        //    「PLATFORM 却带着 tenantId」会让这条申请单在两种作用域之间含糊，
        //    而申请单的作用域决定了它**走哪条通道**（应用角色 vs 平台运维角色）——
        //    含糊就等于「可能被送进错误的通道」。
        if (scope == ConfigRelease.Scope.TENANT && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT 作用域的申请单必须携带 tenantId");
        }
        if (scope == ConfigRelease.Scope.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM 作用域的申请单不得携带 tenantId");
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

    /**
     * @return 是否为平台级申请单（第三十四批）
     */
    public boolean isPlatformScope() {
        return scope == ConfigRelease.Scope.PLATFORM;
    }
}
