package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.AuditLog;
import cn.studykid.multiagent.core.cap.AuditLog.EventType;
import cn.studykid.multiagent.ports.persistence.AuditLogPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 业务审计日志服务（cap_audit_log）。
 *
 * <p>⚠️ <b>所有写入失败必须 fail-closed</b>：调用方（草稿服务 / 发布工作流 / 生命周期服务）
 * 应将审计写与业务操作放在**同一事务**内 —— 若审计写失败必须让事务回滚。
 * 无审计的高危变更比变更失败更危险。
 *
 * <p>⚠️ <b>与系统审计 AuditPort 区分</b>：本服务写入的是资产配置级业务审计
 * （含 before/after 完整 JSON 快照 + diff_summary），仅 cap_audit_log；
 * AuditPort 写入系统通用审计。
 */
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogPort auditLog;
    private final Clock clock;

    public AuditLogService(@NonNull AuditLogPort auditLog, @NonNull Clock clock) {
        this.auditLog = auditLog;
        this.clock = clock;
    }

    // ── 便捷写入方法 ───────────────────────────────────────────────

    public AuditLog logCreate(@NonNull String scope,
                               @Nullable String tenantId,
                               @NonNull String resourceKind,
                               @NonNull String resourceId,
                               @Nullable String resourceName,
                               @NonNull String afterConfig,
                               @Nullable Integer toVersion,
                               @NonNull String operatorId,
                               @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.CREATE, null, toVersion,
                null, "NEW",
                null, afterConfig, null, null, operatorId, reason);
    }

    public AuditLog logSaveDraft(@NonNull String scope,
                                  @Nullable String tenantId,
                                  @NonNull String resourceKind,
                                  @NonNull String resourceId,
                                  @Nullable String resourceName,
                                  @NonNull String beforeConfig,
                                  @NonNull String afterConfig,
                                  @Nullable Integer fromVersion,
                                  @Nullable Integer toVersion,
                                  @NonNull String operatorId) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.SAVE_DRAFT, fromVersion, toVersion,
                null, null,
                beforeConfig, afterConfig,
                diffJson(beforeConfig, afterConfig),
                null, operatorId, null);
    }

    public AuditLog logSubmitApproval(@NonNull String scope,
                                       @Nullable String tenantId,
                                       @NonNull String resourceKind,
                                       @NonNull String resourceId,
                                       @Nullable String resourceName,
                                       @NonNull String operatorId,
                                       @Nullable UUID requestId,
                                       @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.SUBMIT_APPROVAL, null, null,
                null, "PENDING_APPROVAL",
                null, null, null, requestId, operatorId, reason);
    }

    public AuditLog logApprove(@NonNull String scope,
                                @Nullable String tenantId,
                                @NonNull String resourceKind,
                                @NonNull String resourceId,
                                @Nullable String resourceName,
                                @NonNull String operatorId,
                                @Nullable UUID requestId,
                                @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.APPROVE, null, null,
                "PENDING_APPROVAL", "PENDING_PUBLISH",
                null, null, null, requestId, operatorId, reason);
    }

    public AuditLog logReject(@NonNull String scope,
                               @Nullable String tenantId,
                               @NonNull String resourceKind,
                               @NonNull String resourceId,
                               @Nullable String resourceName,
                               @NonNull String operatorId,
                               @Nullable UUID requestId,
                               @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.REJECT, null, null,
                "PENDING_APPROVAL", "APPROVAL_REJECTED",
                null, null, null, requestId, operatorId, reason);
    }

    public AuditLog logPublishSuccess(@NonNull String scope,
                                        @Nullable String tenantId,
                                        @NonNull String resourceKind,
                                        @NonNull String resourceId,
                                        @Nullable String resourceName,
                                        @Nullable String beforeConfig,
                                        @NonNull String afterConfig,
                                        @Nullable Integer fromVersion,
                                        @NonNull Integer toVersion,
                                        @NonNull String operatorId,
                                        @Nullable UUID requestId,
                                        @Nullable Long releaseId) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.PUBLISH_SUCCESS, fromVersion, toVersion,
                null, "PUBLISHED",
                beforeConfig, afterConfig,
                diffJson(beforeConfig, afterConfig),
                requestId, operatorId, null, releaseId);
    }

    public AuditLog logPublishFailed(@NonNull String scope,
                                      @Nullable String tenantId,
                                      @NonNull String resourceKind,
                                      @NonNull String resourceId,
                                      @Nullable String resourceName,
                                      @NonNull String operatorId,
                                      @NonNull String failStage,
                                      @Nullable UUID requestId,
                                      @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.PUBLISH_FAILED, null, null,
                "PENDING_PUBLISH", "PUBLISH_FAILED",
                null, null, null, requestId, operatorId,
                reason == null ? failStage : failStage + ": " + reason);
    }

    public AuditLog logRollback(@NonNull String scope,
                                 @Nullable String tenantId,
                                 @NonNull String resourceKind,
                                 @NonNull String resourceId,
                                 @Nullable String resourceName,
                                 @NonNull String beforeConfig,
                                 @NonNull String afterConfig,
                                 @Nullable Integer fromVersion,
                                 @NonNull Integer toVersion,
                                 @NonNull String operatorId,
                                 @Nullable UUID requestId,
                                 @Nullable Long releaseId,
                                 @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.ROLLBACK, fromVersion, toVersion,
                null, "PUBLISHED",
                beforeConfig, afterConfig,
                diffJson(beforeConfig, afterConfig),
                requestId, operatorId, reason, releaseId);
    }

    public AuditLog logDiscard(@NonNull String scope,
                                @Nullable String tenantId,
                                @NonNull String resourceKind,
                                @NonNull String resourceId,
                                @Nullable String resourceName,
                                @NonNull String operatorId,
                                @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                EventType.DISCARD, null, null,
                null, null,
                null, null, null, null, operatorId, reason);
    }

    // ── 查询 ──────────────────────────────────────────────────────

    public @NonNull Page<AuditLog> list(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @Nullable String resourceKind,
                                         @Nullable String resourceId,
                                         @Nullable String eventType,
                                         @Nullable String cursor,
                                         int limit) {
        return auditLog.list(scope, tenantId, resourceKind, resourceId, eventType, cursor, limit);
    }

    public @NonNull Page<AuditLog> query(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @Nullable String eventType,
                                          @Nullable String operatorId,
                                          @Nullable String fromTime,
                                          @Nullable String toTime,
                                          @Nullable String cursor,
                                          int limit) {
        return auditLog.query(scope, tenantId, eventType, operatorId, fromTime, toTime, cursor, limit);
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private AuditLog write(@NonNull String scope,
                           @Nullable String tenantId,
                           @NonNull String resourceKind,
                           @NonNull String resourceId,
                           @Nullable String resourceName,
                           @NonNull EventType eventType,
                           @Nullable Integer fromVersion,
                           @Nullable Integer toVersion,
                           @Nullable String fromState,
                           @Nullable String toState,
                           @Nullable String beforeConfig,
                           @Nullable String afterConfig,
                           @Nullable String diffSummary,
                           @Nullable UUID requestId,
                           @NonNull String operatorId,
                           @Nullable String reason) {
        return write(scope, tenantId, resourceKind, resourceId, resourceName,
                eventType, fromVersion, toVersion, fromState, toState,
                beforeConfig, afterConfig, diffSummary, requestId, operatorId, reason, null);
    }

    private AuditLog write(@NonNull String scope,
                           @Nullable String tenantId,
                           @NonNull String resourceKind,
                           @NonNull String resourceId,
                           @Nullable String resourceName,
                           @NonNull EventType eventType,
                           @Nullable Integer fromVersion,
                           @Nullable Integer toVersion,
                           @Nullable String fromState,
                           @Nullable String toState,
                           @Nullable String beforeConfig,
                           @Nullable String afterConfig,
                           @Nullable String diffSummary,
                           @Nullable UUID requestId,
                           @NonNull String operatorId,
                           @Nullable String reason,
                           @Nullable Long releaseId) {
        Instant now = clock.instant();
        AuditLog audit = new AuditLog(
                UUID.randomUUID(), scope, tenantId, resourceKind, resourceId,
                resourceName, eventType.name(),
                fromVersion, toVersion, fromState, toState,
                beforeConfig, afterConfig, diffSummary,
                requestId, releaseId, operatorId, reason, now
        );
        try {
            return auditLog.record(audit);
        } catch (Exception e) {
            log.error("⚠️ 业务审计日志写入失败 (event={}, resource={}/{}): {}",
                    eventType, resourceKind, resourceId, e.getMessage());
            throw e; // fail-closed
        }
    }

    /** 极简 diff 摘要：仅比较两个 JSON 字符串是否相等、以及顶层 key 的变化。
     * 完整 diff 可由前端使用 JSONDiff 库实现，后端只提供"变了什么 key"摘要。 */
    private static String diffJson(@Nullable String before, @Nullable String after) {
        if (after == null) return "{}";
        if (before == null) return """
                {"added": true}""".trim();
        if (before.equals(after)) return """
                {"changed": false}""".trim();
        // 粗略：顶层 key
        String beforeKeys = extractTopKeys(before);
        String afterKeys = extractTopKeys(after);
        return """
                {"changed": true, "beforeKeys": [%s], "afterKeys": [%s]}""".formatted(beforeKeys, afterKeys);
    }

    private static String extractTopKeys(String json) {
        if (json == null) return "";
        int i = json.indexOf('{');
        int j = json.lastIndexOf('}');
        if (i < 0 || j < 0) return "";
        // 粗略提取所有 "key"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:");
        java.util.regex.Matcher m = p.matcher(json);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (m.find()) {
            if (!first) sb.append(",");
            sb.append('"').append(m.group(1)).append('"');
            first = false;
        }
        return sb.toString();
    }
}
