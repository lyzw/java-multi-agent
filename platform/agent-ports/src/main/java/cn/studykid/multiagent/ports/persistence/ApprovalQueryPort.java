package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.config.ApprovalRequirement;

/**
 * 审批查询与决策端口（P4-D13 / G8 · FE-01 §P8 审批台 · FR-014）。
 *
 * <p><b>与 {@link ApprovalPort} 的分工</b>：后者是<b>执行侧</b>的
 * 「消费审批」（Worker 闸门调用），本端口是<b>人类审批台</b>的
 * 「查看待批 + 记录决定」。二者操作同一张表，但调用方与语义完全不同。
 *
 * <p>⚠️ <b>为什么决定要走端口而不是直接 UPDATE</b>：
 * {@code approval} 表上有触发器 {@code enforce_approval_state_guard}
 * 强制状态跃迁规则，且 {@code ApprovalPort} 已封装了「写审计 + 校验」，
 * 绕过它意味着绕过守卫与留痕。
 */
public interface ApprovalQueryPort {

    /**
     * 查询审批列表（游标分页）。
     *
     * @param state 状态过滤；{@code null} 表示不限
     */
    @NonNull Page<ApprovalItem> list(@NonNull String tenantId,
                                     @Nullable String state,
                                     @Nullable String cursor,
                                     int limit);

    /**
     * 查询单条审批。
     *
     * @return 存在且属于该租户时返回；否则 {@code null}
     */
    @Nullable ApprovalItem find(@NonNull String tenantId, @NonNull String approvalId);

    /**
     * 记录审批决定（批准 / 拒绝）。
     *
     * <p>⚠️ <b>过期审批必须拒绝</b>（FE-01 §P8：「过期/撤销须拒绝而非静默通过」），
     * 抛 {@link ApprovalDecisionException}。
     *
     * @param approverId 审批人标识（来自认证上下文，<b>不得</b>取自请求体）
     * @param reason     拒绝理由；<b>批准时可为空，拒绝时必填</b>
     * @throws ApprovalDecisionException 过期 / 已被决定 / 已消费
     */
    @NonNull
    ApprovalDecision decide(@NonNull String tenantId,
                            @NonNull String approvalId,
                            @NonNull boolean approve,
                            @NonNull String approverId,
                            @Nullable String reason);

    /** 审批决定结果。 */
    record ApprovalDecision(@NonNull String approvalId, @NonNull String newState) {
    }

    /**
     * 审批视图。
     *
     * <p>⚠️ <b>{@code argumentDigest} 是摘要而非参数原文</b>（FR-014 要求审批人看到
     * 「参数摘要」，而非参数本身——参数里含学生提交的解答原文）。
     *
     * @param expired <b>服务端计算的</b>过期标志；前端不得自己比较时间
     */
    record ApprovalItem(
            @NonNull String approvalId,
            @NonNull String operationId,
            @NonNull String runId,
            @NonNull String state,
            @NonNull String requirement,
            @NonNull String argumentDigest,
            @NonNull String targetResource,
            @NonNull String approverScope,
            @NonNull Instant expiresAt,
            @Nullable String approverId,
            @Nullable Instant decidedAt,
            @NonNull Instant createdAt,
            boolean expired
    ) {
        /** @return 审批要求（枚举值），非枚举值时退化为字符串本身。 */
        public @NonNull ApprovalRequirement requirementEnum() {
            try {
                return ApprovalRequirement.valueOf(requirement);
            } catch (IllegalArgumentException e) {
                return ApprovalRequirement.NOT_REQUIRED;
            }
        }
    }

    /**
     * 审批决定被拒（过期 / 状态不允许）。
     *
     * <p>⚠️ <b>只用于「资源状态冲突」</b>（→ 409），
     * <b>不用于「请求体不合法」</b>（如拒绝未填理由，那是 400）——
     * 两者语义不同，混用会让前端给出错误的引导。
     */
    class ApprovalDecisionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String currentState;

        public ApprovalDecisionException(String message, String currentState) {
            super(message);
            this.currentState = currentState;
        }

        public @NonNull String currentState() {
            return currentState;
        }
    }

    /**
     * 审批不存在或不属于该租户。
     *
     * <p>⚠️ <b>刻意定义在端口而非基础设施实现里</b>：端点层（{@code agent-api}）
     * 需要区分「404 资源不存在」与「409 状态冲突」，
     * 但 {@code agent-api} <b>不得</b>依赖 {@code agent-infrastructure}
     * （否则依赖方向反转，下层反过来依赖上层的实现细节）。
     */
    class ApprovalNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ApprovalNotFoundException(String approvalId) {
            super("审批不存在: " + approvalId);
        }
    }
}