package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 业务审计日志 —— 全量记录 Agent / Skill / Tool / MCP_SERVER / RESULT_CONTRACT /
 * ROUTING_POLICY 的配置变更与发布/回滚轨迹（需求文档 §二.4）。
 *
 * <p>⚠️ <b>与 {@code AuditPort}（系统审计）区分</b>：
 * <ul>
 *   <li>{@code AuditPort} 是通用操作审计（谁在什么时间对什么资源做了什么），面向平台治理；</li>
 *   <li>本 record 对应的 {@code cap_audit_log} 是<b>资产配置级业务审计</b>，包含
 *       before_config / after_config / diff_summary 三个 JSON 快照 ——
 *       面向"某个 Agent 的配置是怎么一步步变成现在这样"的完整还原。</li>
 * </ul>
 * 二者并行写入，互不替代。
 *
 * <p>⚠️ <b>只读约束</b>：本表应禁止 UPDATE / DELETE（RLS 只放行 INSERT + SELECT）。
 *
 * @param auditId       审计日志标识
 * @param scope         作用域
 * @param tenantId      租户标识；PLATFORM 作用域为 {@code null}
 * @param resourceKind  资源类型
 * @param resourceId    资源 ID
 * @param resourceName  资源名称（便于列表快速展示）
 * @param eventType     事件类型（见 {@link EventType}）
 * @param fromVersion   变更前版本
 * @param toVersion     变更后版本
 * @param fromState     变更前状态（配置对象生命周期状态 / 草稿状态 / 发布状态）
 * @param toState       变更后状态
 * @param beforeConfig  变更前完整配置快照（JSON 文本，新建时为 {@code null}）
 * @param afterConfig   变更后完整配置快照（JSON 文本，删除/废弃时为 {@code null}）
 * @param diffSummary   字段级变更摘要（JSON 文本）
 * @param requestId     关联发布申请单 ID
 * @param releaseId     关联发布头 ID
 * @param operatorId    操作人
 * @param reason        变更原因说明
 * @param createdAt     审计时间
 */
public record AuditLog(
        @NonNull UUID auditId,
        @NonNull String scope,
        @Nullable String tenantId,
        @NonNull String resourceKind,
        @NonNull String resourceId,
        @Nullable String resourceName,
        @NonNull String eventType,
        @Nullable Integer fromVersion,
        @Nullable Integer toVersion,
        @Nullable String fromState,
        @Nullable String toState,
        @Nullable String beforeConfig,
        @Nullable String afterConfig,
        @Nullable String diffSummary,
        @Nullable UUID requestId,
        @Nullable Long releaseId,
        @NonNull String operatorId,
        @Nullable String reason,
        @NonNull Instant createdAt
) {

    /** 审计事件类型。 */
    public enum EventType {
        /** 新建资产。 */
        CREATE,
        /** 编辑保存草稿。 */
        SAVE_DRAFT,
        /** 提交审核（APPROVAL_* 模式）。 */
        SUBMIT_APPROVAL,
        /** 审批通过。 */
        APPROVE,
        /** 审批驳回。 */
        REJECT,
        /** 发布成功。 */
        PUBLISH_SUCCESS,
        /** 发布失败。 */
        PUBLISH_FAILED,
        /** 回滚到历史版本。 */
        ROLLBACK,
        /** 放弃未发布修改。 */
        DISCARD,
        /** 生命周期迁移（停用/废弃）。 */
        LIFECYCLE;

        /** 是否产生新版本。 */
        public boolean advancesVersion() {
            return this == PUBLISH_SUCCESS || this == ROLLBACK;
        }
    }

    public AuditLog {
        if (auditId == null) throw new IllegalArgumentException("auditId 不能为空");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope 不能为空");
        if (resourceKind == null || resourceKind.isBlank()) throw new IllegalArgumentException("resourceKind 不能为空");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId 不能为空");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType 不能为空");
        if (operatorId == null || operatorId.isBlank()) throw new IllegalArgumentException("operatorId 不能为空");
        if (createdAt == null) createdAt = Instant.EPOCH;

        if ("PLATFORM".equals(scope) && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM scope 不得携带 tenantId");
        }
        if ("TENANT".equals(scope) && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT scope 必须携带 tenantId");
        }
    }
}
