package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 业务审计日志持久化对象（对应 {@code JdbcAuditLogRepository}）。
 *
 * <p>persistence 模块内部 DO，不与 core 的 {@code AuditLog} record 混用；
 * 适配层 {@code MybatisAuditLogRepository} 负责 DO ↔ record 的转换。
 * 表结构来自 {@code cap_audit_log}（只读约束：RLS 仅放行 INSERT + SELECT）。
 *
 * <p>⚠️ 写入走自定义 {@code INSERT ... RETURNING created_at}（DB 时间戳回填），
 * 不调用 BaseMapper.insert；{@code @TableId(INPUT)} 仅作元数据标注。
 *
 * <p>⚠️ JSONB 列（before_config / after_config / diff_summary）用 {@link JsonbTypeHandler}
 * 按 String 读写，SQL 中保留显式 {@code ::jsonb} / {@code ::text} cast（与原实现逐字一致）。
 */
@TableName("cap_audit_log")
public class AuditLogDO {

    @TableId(value = "audit_id", type = IdType.INPUT)
    private UUID auditId;

    @TableField("scope")
    private String scope;

    /** PLATFORM scope 为 null（DB CHECK 约束：PLATFORM<->null / TENANT<->非null）。 */
    @TableField("tenant_id")
    private @Nullable UUID tenantId;

    @TableField("resource_kind")
    private String resourceKind;

    @TableField("resource_id")
    private String resourceId;

    @TableField("resource_name")
    private @Nullable String resourceName;

    @TableField("event_type")
    private String eventType;

    @TableField("from_version")
    private @Nullable Integer fromVersion;

    @TableField("to_version")
    private @Nullable Integer toVersion;

    @TableField("from_state")
    private @Nullable String fromState;

    @TableField("to_state")
    private @Nullable String toState;

    @TableField(value = "before_config", typeHandler = JsonbTypeHandler.class)
    private @Nullable String beforeConfig;

    @TableField(value = "after_config", typeHandler = JsonbTypeHandler.class)
    private @Nullable String afterConfig;

    @TableField(value = "diff_summary", typeHandler = JsonbTypeHandler.class)
    private @Nullable String diffSummary;

    @TableField("request_id")
    private @Nullable UUID requestId;

    @TableField("release_id")
    private @Nullable Long releaseId;

    @TableField("operator_id")
    private String operatorId;

    @TableField("reason")
    private @Nullable String reason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private @Nullable Instant createdAt;

    public UUID getAuditId() { return auditId; }
    public void setAuditId(UUID auditId) { this.auditId = auditId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public @Nullable UUID getTenantId() { return tenantId; }
    public void setTenantId(@Nullable UUID tenantId) { this.tenantId = tenantId; }
    public String getResourceKind() { return resourceKind; }
    public void setResourceKind(String resourceKind) { this.resourceKind = resourceKind; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public @Nullable String getResourceName() { return resourceName; }
    public void setResourceName(@Nullable String resourceName) { this.resourceName = resourceName; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public @Nullable Integer getFromVersion() { return fromVersion; }
    public void setFromVersion(@Nullable Integer fromVersion) { this.fromVersion = fromVersion; }
    public @Nullable Integer getToVersion() { return toVersion; }
    public void setToVersion(@Nullable Integer toVersion) { this.toVersion = toVersion; }
    public @Nullable String getFromState() { return fromState; }
    public void setFromState(@Nullable String fromState) { this.fromState = fromState; }
    public @Nullable String getToState() { return toState; }
    public void setToState(@Nullable String toState) { this.toState = toState; }
    public @Nullable String getBeforeConfig() { return beforeConfig; }
    public void setBeforeConfig(@Nullable String beforeConfig) { this.beforeConfig = beforeConfig; }
    public @Nullable String getAfterConfig() { return afterConfig; }
    public void setAfterConfig(@Nullable String afterConfig) { this.afterConfig = afterConfig; }
    public @Nullable String getDiffSummary() { return diffSummary; }
    public void setDiffSummary(@Nullable String diffSummary) { this.diffSummary = diffSummary; }
    public @Nullable UUID getRequestId() { return requestId; }
    public void setRequestId(@Nullable UUID requestId) { this.requestId = requestId; }
    public @Nullable Long getReleaseId() { return releaseId; }
    public void setReleaseId(@Nullable Long releaseId) { this.releaseId = releaseId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public @Nullable String getReason() { return reason; }
    public void setReason(@Nullable String reason) { this.reason = reason; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
}
