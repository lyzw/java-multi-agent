package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName(value = "tenant_agent_binding", autoResultMap = true)
public class TenantAgentBindingDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("agent_id")
    private String agentId;

    @TableField("status")
    private String status;

    /** 可空：未固定版本为 NULL。 */
    @TableField("pinned_version")
    private Integer pinnedVersion;

    @TableField("config")
    private String config;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getPinnedVersion() { return pinnedVersion; }
    public void setPinnedVersion(Integer v) { this.pinnedVersion = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
