package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 高危配置变更申请端口（P4-D16 / **C7**）。
 *
 * <p>⚠️ <b>不复用 {@link ApprovalPort} / approval 表</b>：
 * 后者 {@code operation_id UUID NOT NULL} 强绑定工具操作（FR-013），
 * 配置变更没有「工具操作」实体。硬塞会引入伪造的 operation_id
 * 或把它改可空——前者是数据造假，后者削弱既有约束。
 *
 * <p><b>⚠️ 为何 APPROVED 与 APPLIED 是两个状态</b>：
 * 「批准」与「配置真的变了」是两件事。合并会让审批人无法区分
 * 「我批了」与「生效了」——那正是 P4-D15 D15-B3 的
 * 「看起来生效了其实没生效」。分两步且各自留痕。
 *
 * <p><b>⚠️ 双人复核</b>：申请人不得审批自己的申请。
 * 应用层与 DB CHECK 双保险——应用层可被绕过，DB 不会。
 *
 * <p><b>⚠️ 审计 fail-closed</b>：请求 / 批准 / 拒绝 / 应用
 * 四步均走 {@link AuditPort#recordRequired}，失败即中止。
 */
public interface ConfigChangePort {

    /** 申请状态。 */
    enum ChangeState {
        PENDING, APPROVED, REJECTED, APPLIED
    }

    /** 审批决定。 */
    enum Decision {
        APPROVE, REJECT
    }

    /**
     * 创建变更申请（<b>不写入配置表</b>）。
     *
     * @param requesterId 申请人（来自认证上下文，<b>不得</b>取自请求体）
     * @throws ConfigOverridePort.UnknownConfigKeyException 键不可写 → 404
     * @throws IllegalArgumentException                     值/理由非法 → 400
     */
    @NonNull ChangeItem request(@NonNull String tenantId,
                                @NonNull String key,
                                @NonNull String value,
                                @NonNull String reason,
                                @NonNull String requesterId);

    /**
     * 列出变更申请（游标分页，全序）。
     *
     * @param state 仅看某状态；null = 全部
     */
    @NonNull Page<ChangeItem> list(@NonNull String tenantId,
                                   @Nullable ChangeState state,
                                   @Nullable String cursor,
                                   int limit);

    /**
     * 审批决定：批准或拒绝。
     *
     * <p>⚠️ <b>批准不写入配置表</b>——写入由 {@link #apply} 单独完成。
     *
     * @throws SelfApprovalException    审批自己的申请 → 409
     * @throws IllegalStateConflictException 对终态/已决定再决定 → 409
     * @throws ChangeNotFoundException  申请不存在/不属于本租户 → 404
     */
    @NonNull ChangeItem decide(@NonNull String tenantId,
                               @NonNull String changeId,
                               @NonNull Decision decision,
                               @Nullable String note,
                               @NonNull String approverId);

    /**
     * 应用已批准的变更：<b>实际写入配置表</b>并置为 APPLIED。
     *
     * <p>⚠️ 本步会做<b>完整的收紧校验</b>（{@code ConfigTighteningPolicy}）：
     * 申请提交后、审批之前，配置可能已被他人改过，
     * 此时的「收紧」相对新基线可能已是放松。
     *
     * @throws IllegalStateConflictException 非 APPROVED 状态 → 409
     */
    @NonNull ChangeItem apply(@NonNull String tenantId,
                              @NonNull String changeId,
                              @NonNull String actorId);

    /** 一条变更申请。 */
    record ChangeItem(
            @NonNull String changeId,
            @NonNull String key,
            @NonNull String proposedValue,
            @NonNull String valueType,
            @NonNull String reason,
            @NonNull String requesterId,
            @NonNull Instant requestedAt,
            @NonNull ChangeState state,
            @Nullable String approverId,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote,
            @Nullable Instant appliedAt) {
    }

    /** 审批自己的申请（→ 409；DB CHECK 为第二道防线）。 */
    class SelfApprovalException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SelfApprovalException() {
            super("不得审批自己提交的变更申请（双人复核要求）");
        }
    }

    /** 状态冲突（终态再决定、非 APPROVED 就应用）→ 409。 */
    class IllegalStateConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IllegalStateConflictException(String message) {
            super(message);
        }
    }

    /** 申请不存在 / 不属于本租户 → 404。 */
    class ChangeNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String changeId;

        public ChangeNotFoundException(String changeId) {
            super("变更申请不存在: " + changeId);
            this.changeId = changeId;
        }

        public String changeId() {
            return changeId;
        }
    }
}