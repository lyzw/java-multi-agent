package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 租户配置覆盖持久化对象（对应 {@code JdbcConfigOverrideRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core / ports 的 record 混用；
 * 适配层 {@code MybatisConfigOverrideRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构：{@code tenant_config_override}（主键为复合键 (tenant_id, config_key)，
 * 本适配器只用自定义 SQL，不调用 BaseMapper 通用 CRUD，故不声明单一 @TableId）。
 */
@TableName("tenant_config_override")
public class ConfigOverrideDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("config_key")
    private String configKey;

    @TableField("value_text")
    private String valueText;

    @TableField("value_type")
    private String valueType;

    @TableField("reason")
    private @Nullable String reason;

    @TableField("changed_by")
    private @Nullable String changedBy;

    @TableField("updated_at")
    private @Nullable Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getValueText() { return valueText; }
    public void setValueText(String valueText) { this.valueText = valueText; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public @Nullable String getReason() { return reason; }
    public void setReason(@Nullable String reason) { this.reason = reason; }
    public @Nullable String getChangedBy() { return changedBy; }
    public void setChangedBy(@Nullable String changedBy) { this.changedBy = changedBy; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
