package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 配置变更申请单持久化对象（对应 {@code JdbcConfigPublishRequestRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code ConfigPublishRequest} record 混用；
 * 适配层 {@code MybatisConfigPublishRequestRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构：{@code cap_config_publish_request}。
 * {@code request_id} 由 DB 默认生成，写入用自定义 {@code INSERT ... RETURNING} 取回，
 * 故 {@code IdType.INPUT}。
 *
 * <p>⚠️ JSONB 列（payload / fail_details）用 {@link JsonbTypeHandler} 按 String 读写；
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 * PLATFORM 作用域的 {@code tenantId} 为 null（SQL 用 IS NOT DISTINCT FROM 兼容）。
 */
@TableName("cap_config_publish_request")
public class ConfigPublishRequestDO {

    @TableId(value = "request_id", type = IdType.INPUT)
    private String requestId;

    @TableField("tenant_id")
    private @Nullable String tenantId;

    @TableField("operation")
    private String operation;

    @TableField(value = "payload", typeHandler = JsonbTypeHandler.class)
    private String payload;

    @TableField("reason")
    private String reason;

    @TableField("requester_id")
    private String requesterId;

    @TableField("state")
    private String state;

    @TableField("approver_id")
    private @Nullable String approverId;

    @TableField("decision_note")
    private @Nullable String decisionNote;

    @TableField("requested_at")
    private @Nullable Instant requestedAt;

    @TableField("decided_at")
    private @Nullable Instant decidedAt;

    @TableField("applied_at")
    private @Nullable Instant appliedAt;

    @TableField("failure_reason")
    private @Nullable String failureReason;

    @TableField("scope")
    private String scope;

    @TableField("resource_kind")
    private @Nullable String resourceKind;

    @TableField("resource_id")
    private @Nullable String resourceId;

    @TableField("target_version")
    private @Nullable Integer targetVersion;

    @TableField("fail_stage")
    private @Nullable String failStage;

    @TableField(value = "fail_details", typeHandler = JsonbTypeHandler.class)
    private @Nullable String failDetails;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public @Nullable String getApproverId() { return approverId; }
    public void setApproverId(@Nullable String approverId) { this.approverId = approverId; }
    public @Nullable String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(@Nullable String decisionNote) { this.decisionNote = decisionNote; }
    public @Nullable Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(@Nullable Instant requestedAt) { this.requestedAt = requestedAt; }
    public @Nullable Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(@Nullable Instant decidedAt) { this.decidedAt = decidedAt; }
    public @Nullable Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(@Nullable Instant appliedAt) { this.appliedAt = appliedAt; }
    public @Nullable String getFailureReason() { return failureReason; }
    public void setFailureReason(@Nullable String failureReason) { this.failureReason = failureReason; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public @Nullable String getResourceKind() { return resourceKind; }
    public void setResourceKind(@Nullable String resourceKind) { this.resourceKind = resourceKind; }
    public @Nullable String getResourceId() { return resourceId; }
    public void setResourceId(@Nullable String resourceId) { this.resourceId = resourceId; }
    public @Nullable Integer getTargetVersion() { return targetVersion; }
    public void setTargetVersion(@Nullable Integer targetVersion) { this.targetVersion = targetVersion; }
    public @Nullable String getFailStage() { return failStage; }
    public void setFailStage(@Nullable String failStage) { this.failStage = failStage; }
    public @Nullable String getFailDetails() { return failDetails; }
    public void setFailDetails(@Nullable String failDetails) { this.failDetails = failDetails; }
}
