package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 发布策略持久化对象（W2 迁移，对应 {@code cap_publish_policy} 表）。
 *
 * <p>⚠️ 这是 persistence 模块<b>内部</b>的 DO，不与 core 的 {@code PublishPolicy} record 混用。
 * 适配层 {@code MybatisPublishPolicyRepository} 负责 DO ↔ record 的转换。
 *
 * <p>⚠️ 主键 DB 默认 gen_random_uuid()，但原 JDBC 显式绑定 policyId，故用 {@code IdType.INPUT}。
 */
@TableName("cap_publish_policy")
public class PublishPolicyDO {

    @TableId(value = "policy_id", type = IdType.INPUT)
    private UUID policyId;

    @TableField("scope")
    private String scope;

    /** PLATFORM scope 为 null（DB CHECK：PLATFORM<->null / TENANT<->非null）。 */
    @TableField("tenant_id")
    private @Nullable UUID tenantId;

    @TableField("policy_level")
    private String policyLevel;

    @TableField("resource_kind")
    private @Nullable String resourceKind;

    @TableField("resource_id")
    private @Nullable String resourceId;

    @TableField("publish_mode")
    private String publishMode;

    @TableField("enabled")
    private boolean enabled;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    @TableField("updated_at")
    private @Nullable Instant updatedAt;

    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public @Nullable UUID getTenantId() { return tenantId; }
    public void setTenantId(@Nullable UUID tenantId) { this.tenantId = tenantId; }
    public String getPolicyLevel() { return policyLevel; }
    public void setPolicyLevel(String policyLevel) { this.policyLevel = policyLevel; }
    public @Nullable String getResourceKind() { return resourceKind; }
    public void setResourceKind(@Nullable String resourceKind) { this.resourceKind = resourceKind; }
    public @Nullable String getResourceId() { return resourceId; }
    public void setResourceId(@Nullable String resourceId) { this.resourceId = resourceId; }
    public String getPublishMode() { return publishMode; }
    public void setPublishMode(String publishMode) { this.publishMode = publishMode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
