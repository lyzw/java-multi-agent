package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;

/**
 * 配置变更申请单仓储（P6R4 · 管理端点）。
 *
 * <p>⚠️ <b>本端口只承载租户级申请</b>：平台级对象（Tool / Skill / MCP Server）的发布
 * 走平台运维通道（BYPASSRLS 凭据 + 带外运维工具），**不经租户身份暴露**
 * —— 平台身份表 {@code principal} 是租户内的，没有跨租户主体，
 * 若把平台级发布挂到租户身份上，任一租户管理员自授角色即可改全平台配置。
 *
 * <p>⚠️ <b>状态迁移由端口保证</b>：{@code decide} 只允许 {@code PENDING → APPROVED/REJECTED}，
 * {@code markApplied} 只允许 {@code APPROVED → APPLIED}，
 * {@code markFailed} 只允许 {@code APPROVED → FAILED}。
 * 越级迁移（如 PENDING → APPLIED）必须**失败**，否则「没审批就生效」会从应用层漏到存储层。
 */
public interface ConfigPublishRequestPort {

    /** 审批决定。 */
    enum Decision {
        APPROVE,
        REJECT
    }

    /**
     * 登记一条申请单。
     *
     * @param tenantId   租户标识
     * @param operation  变更类型
     * @param payload    变更方案原文（JSON 文本）
     * @param reason     变更理由
     * @param requesterId 申请人主体标识
     * @return 落库后的申请单（{@code requestId} 由存储层生成）
     */
    @NonNull ConfigPublishRequest insert(@NonNull String tenantId,
                                         ConfigPublishRequest.@NonNull Operation operation,
                                         @NonNull String payload,
                                         @NonNull String reason,
                                         @NonNull String requesterId);

    /**
     * 读取一条申请单。
     *
     * @param tenantId  租户标识
     * @param requestId 申请单标识
     * @return 申请单；不存在或不属于本租户时为空
     */
    @NonNull Optional<ConfigPublishRequest> find(@NonNull String tenantId,
                                                 @NonNull String requestId);

    /**
     * 列出申请单（游标分页）。
     *
     * @param tenantId 租户标识
     * @param state    状态过滤；{@code null} = 不过滤
     * @param cursor   游标；{@code null} = 从头开始
     * @param limit    页大小
     * @return 分页结果
     */
    @NonNull Page<ConfigPublishRequest> list(@NonNull String tenantId,
                                             ConfigPublishRequest.@Nullable State state,
                                             @Nullable String cursor,
                                             int limit);

    /**
     * 审批（{@code PENDING → APPROVED / REJECTED}）。
     *
     * @param tenantId   租户标识
     * @param requestId  申请单标识
     * @param decision   审批决定
     * @param note       审批意见
     * @param approverId 审批人主体标识（**不得等于申请人**）
     * @return 审批后的申请单
     * @throws SelfApprovalException   审批人等于申请人
     * @throws StateConflictException  状态不是 {@code PENDING}
     * @throws NotFoundException       申请单不存在
     */
    @NonNull ConfigPublishRequest decide(@NonNull String tenantId,
                                         @NonNull String requestId,
                                         @NonNull Decision decision,
                                         @Nullable String note,
                                         @NonNull String approverId);

    /**
     * 标记执行成功（{@code APPROVED → APPLIED}）。
     *
     * @param tenantId  租户标识
     * @param requestId 申请单标识
     * @param actorId   执行人主体标识
     * @param appliedAt 执行完成时间
     * @return 更新后的申请单
     * @throws StateConflictException 状态不是 {@code APPROVED}
     * @throws NotFoundException      申请单不存在
     */
    @NonNull ConfigPublishRequest markApplied(@NonNull String tenantId,
                                              @NonNull String requestId,
                                              @NonNull String actorId,
                                              @NonNull Instant appliedAt);

    /**
     * 标记执行失败（{@code APPROVED → FAILED}，**终态、不可自动重试**）。
     *
     * @param tenantId  租户标识
     * @param requestId 申请单标识
     * @param reason    失败原因
     * @return 更新后的申请单
     * @throws StateConflictException 状态不是 {@code APPROVED}
     * @throws NotFoundException      申请单不存在
     */
    @NonNull ConfigPublishRequest markFailed(@NonNull String tenantId,
                                              @NonNull String requestId,
                                              @NonNull String reason);

    /** 申请单不存在。 */
    class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String requestId;

        public NotFoundException(@NonNull String requestId) {
            super("配置变更申请不存在: " + requestId);
            this.requestId = requestId;
        }

        public @NonNull String requestId() {
            return requestId;
        }
    }

    /** 状态冲突（越级迁移 / 重复审批）。 */
    class StateConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StateConflictException(@NonNull String message) {
            super(message);
        }
    }

    /** 审批人等于申请人（双人复核被违反）。 */
    class SelfApprovalException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SelfApprovalException() {
            super("不得审批自己提交的变更申请（双人复核要求）");
        }
    }
}
