package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapApproval;

/**
 * CAP 审批仓储端口（W-PERM-P3 · §5.3.1 审批绑定契约 · §5.3.3 审批状态机）。
 *
 * <p>⚠️ <b>与既有 {@link ApprovalPort} 是两套模型</b>，理由见
 * {@link CapApproval} 的类注释（状态语义与终态幂等语义都不同，
 * 混在一张表里无法同时表达两套状态守卫）。
 *
 * <p>⚠️ <b>预占必须独占</b>（§5.3.3「RESERVED：预占，绑定 invocationId」）：
 * 同一审批只能被一个调用预占。他调用抢占 → {@code APPROVAL_RESERVED_BY_OTHER}。
 */
public interface CapApprovalPort {

    /**
     * 授予审批（由审批权威调用）。
     *
     * <p>⚠️ <b>授予的是「已批准但未预占」</b>（{@code PENDING} 态）：
     * 人工决策与调用预占是两件事，混在一起会让「审批被谁占用」无法回答。
     *
     * @param grant 授予内容（含 §5.3.1 的双哈希绑定）
     * @return 新审批 id
     */
    @NonNull String grant(@NonNull CapApprovalGrant grant);

    /**
     * 预占审批（步骤 10）。
     *
     * <p>⚠️ <b>必须原子</b>：并发预占同一审批时只能有一个成功。
     *
     * <p>⚠️ <b>绑定项是预占的前置条件</b>：实现必须把
     * {@code effectiveArgsHash} / {@code closureFingerprint} / {@code authzVersion}
     * 一并放进 CAS 条件里 —— 否则「预占时才发现绑定不符」与「预占成功后才校验」
     * 之间存在窗口，攻击者可在窗口内抢占审批。
     *
     * @param tenantId          租户
     * @param approvalId        审批 id
     * @param invocationId      调用标识（预占方）
     * @param operationId       关联的 ToolOperation
     * @param runId             关联 Run
     * @param effectiveArgsHash 本次调用的有效参数摘要（须与授予时一致）
     * @param closureFingerprint 本 Run 的闭包指纹（须与授予时一致）
     * @param authzVersion      当前授权版本（须与授予时一致）
     * @return 预占结论
     */
    @NonNull ReservationOutcome reserve(@NonNull String tenantId,
                                         @NonNull String approvalId,
                                         @NonNull String invocationId,
                                         @NonNull String operationId,
                                         @Nullable String runId,
                                         @NonNull String effectiveArgsHash,
                                         @NonNull String closureFingerprint,
                                         long authzVersion);

    /**
     * 标记「执行意图已提交」（步骤 13b · {@code RESERVED → INTENT_SUBMITTED}）。
     *
     * @return 是否迁移成功（已迁移过视为幂等成功）
     */
    boolean markIntentSubmitted(@NonNull String tenantId, @NonNull String approvalId,
                                 @NonNull String invocationId);

    /**
     * 确认消费（步骤 15 · {@code INTENT_SUBMITTED → CONSUMED}）。
     *
     * @param terminalStatus 关联的终态结果（SUCCEEDED / FAILED / UNKNOWN）
     * @return 是否迁移成功
     */
    boolean consume(@NonNull String tenantId, @NonNull String approvalId,
                    @NonNull String invocationId, @NonNull String terminalStatus);

    /**
     * 取消审批（补偿 · 步骤 11/12/13 失败后）。
     *
     * <p>⚠️ <b>幂等</b>：重复取消不产生二次效果，也不报错。
     *
     * @param reason 取消原因
     * @return 是否发生状态迁移（已取消返回 {@code false}）
     */
    boolean cancel(@NonNull String tenantId, @NonNull String approvalId,
                   @NonNull String invocationId, @NonNull String reason);

    /** @return 按 id 读取审批。 */
    @NonNull Optional<CapApproval> findById(@NonNull String tenantId, @NonNull String approvalId);

    /**
     * 按工具读取候选审批（消费时定位）。
     *
     * <p>⚠️ <b>可能返回多条</b>：同一工具可能有多份不同范围的审批。
     * 调用方须逐条校验绑定，取第一条匹配者。
     *
     * @param tenantId 租户
     * @param toolId   工具标识
     * @return 候选审批（按创建时间升序）
     */
    @NonNull List<CapApproval> findByTool(@NonNull String tenantId, @NonNull String toolId);

    /** @return 按调用标识读取审批（预占方视角）。 */
    @NonNull Optional<CapApproval> findByInvocation(@NonNull String tenantId,
                                                     @NonNull String invocationId);

    /**
     * 预占结论。
     */
    enum ReservationOutcome {
        /** 预占成功。 */
        RESERVED,
        /** 审批不存在。 */
        NOT_FOUND,
        /** 已被其他调用预占。 */
        ALREADY_RESERVED_BY_OTHER,
        /** 已被同一调用预占（幂等命中）。 */
        ALREADY_RESERVED_BY_SAME,
        /** 已提交执行意图（预占之后的阶段）。 */
        INTENT_SUBMITTED,
        /** 已消费（重放）。 */
        ALREADY_CONSUMED,
        /** 已过期。 */
        EXPIRED,
        /** 已取消。 */
        CANCELLED
    }

    /**
     * 审批授予内容（§5.3.1 绑定项）。
     *
     * @param tenantId           租户
     * @param principalId        被授权主体（§5.3.1 绑定项「tenantId / principalId」）
     * @param toolId             目标 Tool
     * @param targetResource     目标资源
     * @param approvalScopeHash  范围哈希
     * @param scopeDefinition    范围定义全文（JSON）——
     *                           §5.3.1「记录保存完整范围 JSON + hash」，
     *                           消费时据此重验范围
     * @param effectiveArgsHash  有效参数哈希
     * @param closureFingerprint 闭包指纹
     * @param authzVersion       授予时的授权版本
     * @param approverId         审批人
     * @param approverScope      审批人范围
     * @param expiresAt          有效期截止
     */
    record CapApprovalGrant(
            @NonNull String tenantId,
            @NonNull String principalId,
            @NonNull String toolId,
            @NonNull String targetResource,
            @NonNull String approvalScopeHash,
            @NonNull String scopeDefinition,
            @NonNull String effectiveArgsHash,
            @NonNull String closureFingerprint,
            long authzVersion,
            @NonNull String approverId,
            @NonNull String approverScope,
            @NonNull Instant expiresAt) {
    }
}
