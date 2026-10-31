package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * CAP 审批记录（W-PERM-P3 · §5.3.1 审批绑定契约 · §5.3.3 审批状态机）。
 *
 * <p>⚠️ <b>与既有 {@code approval} 表是两套模型，不可混用</b>：
 * <ul>
 *   <li>既有模型：{@code PENDING → APPROVED → CONSUMED}，
 *       一个 operation 一条审批，<b>消费即终态</b>（幂等重放返回 CONSUMED）；</li>
 *   <li>本模型：{@code PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED}
 *       （旁路 {@code EXPIRED} / {@code CANCELLED}），
 *       审批可被<b>预占</b>，预占与消费<b>分离</b>，
 *       且带 §5.3.1 的<b>双哈希绑定</b>（范围哈希 + 有效参数哈希）。</li>
 * </ul>
 * 两者对「PENDING」的语义都不同（既有 = 未决议；本模型 = 已批准但未被预占），
 * 放在同一张表会让状态守卫无法同时表达两套规则，故独立成表
 * （{@code cap_approval}，P3 V16 迁移）。
 *
 * @param approvalId        审批标识
 * @param tenantId          租户
 * @param principalId       被授权主体（§5.3.1 绑定项：主体与租户）
 * @param runId             关联 Run（可空：审批可在 Run 创建前授予）
 * @param invocationId      预占方调用标识（未预占时为空）
 * @param operationId       关联的 ToolOperation（预占时绑定）
 * @param toolId            目标 Tool 标识
 * @param targetResource    目标资源
 * @param approvalScopeHash §5.3.1 范围哈希（范围定义摘要）
 * @param scopeDefinition   §5.3.1「记录保存完整范围 JSON + hash」——
 *                          消费时用它<b>重新验证</b>实际参数是否仍落在范围内
 * @param effectiveArgsHash §5.3.1 有效参数哈希（本次全部有效参数的规范化摘要）
 * @param closureFingerprint 本 Run 依赖的闭包指纹（不默认绑定全局 configVersion）
 * @param authzVersion      生成时的主体授权版本
 * @param approverId        审批人
 * @param approverScope     审批人范围
 * @param decidedAt         决策时刻
 * @param expiresAt         有效期截止
 * @param state             审批状态
 * @param terminalStatus    关联的终态结果（SUCCEEDED / FAILED / UNKNOWN）
 * @param cancelReason      取消原因（取消时填写）
 * @param createdAt         创建时间
 * @param updatedAt         最后更新时间
 */
public record CapApproval(
        @NonNull String approvalId,
        @NonNull String tenantId,
        @NonNull String principalId,
        @Nullable String runId,
        @Nullable String invocationId,
        @Nullable String operationId,
        @NonNull String toolId,
        @NonNull String targetResource,
        @NonNull String approvalScopeHash,
        @NonNull String scopeDefinition,
        @NonNull String effectiveArgsHash,
        @NonNull String closureFingerprint,
        long authzVersion,
        /**
         * 审批人。
         *
         * <p>⚠️ <b>仅 {@link ApprovalState#AWAITING_HUMAN} 允许为空</b>：
         * 人还没批，自然没有审批人。其余状态（已批准/已预占/已消费…）
         * 缺审批人 = 数据损坏，必须拒绝（见紧凑构造器）。
         */
        @Nullable String approverId,
        @NonNull String approverScope,
        /**
         * 决定时间。
         *
         * <p>⚠️ 同 {@link #approverId}：仅「待人工决策」状态允许为空。
         */
        @Nullable Instant decidedAt,
        @NonNull Instant expiresAt,
        @NonNull ApprovalState state,
        @Nullable String terminalStatus,
        @Nullable String cancelReason,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt) {

    public CapApproval {
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        // ⚠️ M3 / G1：审批人与决定时间**只在「待人工决策」状态下可空**。
        //    其他状态缺审批人 = 数据损坏（不是「还没批」）→ 明确拒绝，
        //    绝不放行 —— 宽容的构造器会把损坏数据变成「看起来正常的授权」。
        if (state != ApprovalState.AWAITING_HUMAN) {
            if (approverId == null || approverId.isBlank()) {
                throw new IllegalArgumentException(
                        "审批状态 " + state + " 的审批人不能为空（仅 AWAITING_HUMAN 可空）");
            }
            if (decidedAt == null) {
                throw new IllegalArgumentException(
                        "审批状态 " + state + " 的决定时间不能为空（仅 AWAITING_HUMAN 可空）");
            }
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        if (approvalScopeHash == null || approvalScopeHash.isBlank()) {
            throw new IllegalArgumentException("approvalScopeHash 不能为空");
        }
        if (scopeDefinition == null || scopeDefinition.isBlank()) {
            throw new IllegalArgumentException("scopeDefinition 不能为空");
        }
        if (effectiveArgsHash == null || effectiveArgsHash.isBlank()) {
            throw new IllegalArgumentException("effectiveArgsHash 不能为空");
        }
        if (closureFingerprint == null || closureFingerprint.isBlank()) {
            throw new IllegalArgumentException("closureFingerprint 不能为空");
        }
        if (decidedAt == null || expiresAt == null || createdAt == null || updatedAt == null) {
            // ⚠️ M3 / G1：「待人工决策」状态下 decidedAt 允许为空（人还没决定）。
            //    其余状态缺决定时间 = 数据损坏 → 明确拒绝。
            if (state != ApprovalState.AWAITING_HUMAN
                    || expiresAt == null || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("时间字段不能为空");
            }
        }
    }

    /**
     * §5.3.3 审批状态机。
     *
     * <p>⚠️ <b>状态只能前进</b>：{@code PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED}，
     * 旁路 {@code EXPIRED} / {@code CANCELLED}。
     * 不得回退到 {@code PENDING} —— 那会让已发生的副作用被误判为「尚未审批」而重新执行。
     */
    public enum ApprovalState {
        /**
         * ⚠️ 待人工决策（M3 / G1 · 运行时 HITL）。
         *
         * <p>与 {@link #PENDING} <b>语义相反，不可混用</b>：
         * <ul>
         *   <li>{@code PENDING} = <b>已批准</b>（人工或平台预授权已完成），
         *       尚未被任何调用预占 —— 它是「可直接消耗」的授权；</li>
         *   <li>{@code AWAITING_HUMAN} = <b>尚未有人决定</b>，
         *       运行期发现无可用预授权时登记的待办 —— 它<b>不可被消耗</b>，
         *       必须先有人批准（→ PENDING）才允许预占。</li>
         * </ul>
         * 混淆两者会导致「人还没批，工具就执行了」（= 无审批的副作用动作 = Sev-0）。
         *
         * <p>⚠️ 本状态下 {@code approver_id} 允许为空（人还没批），
         * {@code decided_at} 同理。
         */
        AWAITING_HUMAN,
        /** 已批准（人工决策完成），尚未被任何调用预占。 */
        PENDING,
        /** 已被某 invocation 预占（绑定 invocationId 与 operationId）。 */
        RESERVED,
        /** 执行意图已持久化，即将发起外部副作用。 */
        INTENT_SUBMITTED,
        /** 已与终态结果关联并确认。 */
        CONSUMED,
        /** 已过期（超过有效期）。 */
        EXPIRED,
        /** 已被补偿取消（预算/PRE/准入失败）。 */
        CANCELLED;

        /**
         * @return 是否为终态（不可再迁出）
         */
        public boolean isTerminal() {
            return this == CONSUMED || this == EXPIRED || this == CANCELLED;
        }
    }
}
