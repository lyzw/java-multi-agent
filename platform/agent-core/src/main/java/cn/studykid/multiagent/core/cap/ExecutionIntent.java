package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 执行意图记录（§6.1）。
 *
 * <p><b>存在理由</b>（§6.1 / P2 步骤 13a）：与 PRE/INTENT 合并或独立，
 * 但必须<b>可恢复</b>。
 *
 * <p>⚠️ <b>必须先于外部副作用写入</b>。这是崩溃安全的关键分界：
 * <ul>
 *   <li>崩溃发生在「意图已写、副作用未发」→ 恢复时可判定「未执行」，<b>安全重试</b>；</li>
 *   <li>崩溃发生在「副作用已发、意图未写」→ 恢复时<b>无从判断</b>，
 *       必须按 UNKNOWN 进对账。</li>
 * </ul>
 * 顺序反了就会产生<b>不可检测的重复副作用</b>（P3 COMP-6 硬门槛）。
 *
 * @param intentId        意图标识
 * @param tenantId        租户标识
 * @param runId           Run 标识
 * @param invocationId    调用标识（租户内唯一）
 * @param toolId          目标 Tool id
 * @param toolVersion     目标 Tool 版本
 * @param argsFingerprint 参数指纹（防参数替换重放，P3 双哈希之一）
 * @param approvalId      关联审批 id
 * @param intentState     意图状态
 * @param createdAt       创建时间
 * @param updatedAt       最后更新时间
 */
public record ExecutionIntent(
        @NonNull String intentId,
        @NonNull String tenantId,
        @Nullable String runId,
        @NonNull String invocationId,
        @NonNull String toolId,
        @Nullable Integer toolVersion,
        @Nullable String argsFingerprint,
        @Nullable String approvalId,
        @NonNull IntentState intentState,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
) {

    /**
     * 意图状态机。
     *
     * <p>与 P3 审批状态机对齐：{@code RESERVED → INTENT_SUBMITTED → CONSUMED}，
     * 旁路 {@code CANCELLED}（预算/PRE/准入失败后补偿）与 {@code EXPIRED}。
     *
     * <p>⚠️ 状态<b>只能前进</b>，不得回退到「尚未打算执行」——
     * 那会让已发生的副作用被误判为「未执行」而重复执行。
     */
    public enum IntentState {
        /** 已预留（预算/审批占位），尚未提交执行意图。 */
        RESERVED,
        /** 已持久化执行意图，<b>即将</b>发起外部副作用。 */
        INTENT_SUBMITTED,
        /** 副作用已终结（成功/失败/未知），意图被消费。 */
        CONSUMED,
        /** 已取消（补偿完成，幂等）。 */
        CANCELLED,
        /** 已过期（超时未终结）。 */
        EXPIRED
    }

    public ExecutionIntent {
        if (intentId == null || intentId.isBlank()) {
            throw new IllegalArgumentException("intentId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        if (intentState == null) {
            throw new IllegalArgumentException("intentState 不能为空");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("时间字段不能为空");
        }
    }

    /**
     * 判断是否处于「尚未终结」状态（恢复扫描用）。
     *
     * @return {@code true} 表示需要被崩溃恢复流程关注
     */
    public boolean isPending() {
        return intentState == IntentState.RESERVED
                || intentState == IntentState.INTENT_SUBMITTED;
    }
}
