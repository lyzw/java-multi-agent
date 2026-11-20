package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 变更草稿持久化对象（POC，对应主文档 §4.1）。
 *
 * <p>⚠️ 这是 persistence 模块<b>内部</b>的 DO，不与 core 的
 * {@code ConfigDraft} record 混用——后者必须保持零框架注解（纯度守卫）。
 * 适配层 {@code MybatisConfigDraftRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code deploy/sql/platform/09_cap_publish_workflow_upgrade.sql}。
 *
 * <p>⚠️ 主键：DB 默认 {@code gen_random_uuid()}，故用 {@code IdType.INPUT}
 * （insert 时由适配层显式 setObject，与原 JDBC 实现一致，RETURNING 时间戳的语义不变）。
 *
 * <p>⚠️ JSONB 列（draft_config / failure_details）用 {@link JsonbTypeHandler} 按 String 读写，
 * SQL 中保留显式 {@code ::jsonb} cast（与现有实现逐字一致）。
 */
@TableName("cap_config_draft")
public class ConfigDraftDO {

    @TableId(value = "draft_id", type = IdType.INPUT)
    private UUID draftId;

    @TableField("scope")
    private String scope;

    /** 租户标识；PLATFORM scope 为 null（DB CHECK 约束：PLATFORM↔null / TENANT↔非null）。 */
    @TableField("tenant_id")
    private @Nullable UUID tenantId;

    @TableField("resource_kind")
    private String resourceKind;

    @TableField("resource_id")
    private String resourceId;

    @TableField("resource_name")
    private @Nullable String resourceName;

    @TableField("base_version")
    private int baseVersion;

    @TableField("draft_version")
    private int draftVersion;

    @TableField("draft_state")
    private String draftState;

    @TableField(value = "draft_config", typeHandler = JsonbTypeHandler.class)
    private String draftConfig;

    @TableField("config_fingerprint")
    private @Nullable String configFingerprint;

    @TableField("latest_request_id")
    private @Nullable UUID latestRequestId;

    @TableField("failure_stage")
    private @Nullable String failureStage;

    @TableField(value = "failure_details", typeHandler = JsonbTypeHandler.class)
    private String failureDetails;

    @TableField("rejection_reason")
    private @Nullable String rejectionReason;

    @TableField("operator_id")
    private String operatorId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private @Nullable Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private @Nullable Instant updatedAt;

    // ── getters / setters（POJO 供 MP 反射；不引入 Lombok 以免与本模块注解处理纪律冲突）──

    public UUID getDraftId() { return draftId; }
    public void setDraftId(UUID draftId) { this.draftId = draftId; }
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
    public int getBaseVersion() { return baseVersion; }
    public void setBaseVersion(int baseVersion) { this.baseVersion = baseVersion; }
    public int getDraftVersion() { return draftVersion; }
    public void setDraftVersion(int draftVersion) { this.draftVersion = draftVersion; }
    public String getDraftState() { return draftState; }
    public void setDraftState(String draftState) { this.draftState = draftState; }
    public String getDraftConfig() { return draftConfig; }
    public void setDraftConfig(String draftConfig) { this.draftConfig = draftConfig; }
    public @Nullable String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(@Nullable String configFingerprint) { this.configFingerprint = configFingerprint; }
    public @Nullable UUID getLatestRequestId() { return latestRequestId; }
    public void setLatestRequestId(@Nullable UUID latestRequestId) { this.latestRequestId = latestRequestId; }
    public @Nullable String getFailureStage() { return failureStage; }
    public void setFailureStage(@Nullable String failureStage) { this.failureStage = failureStage; }
    public String getFailureDetails() { return failureDetails; }
    public void setFailureDetails(String failureDetails) { this.failureDetails = failureDetails; }
    public @Nullable String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(@Nullable String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
