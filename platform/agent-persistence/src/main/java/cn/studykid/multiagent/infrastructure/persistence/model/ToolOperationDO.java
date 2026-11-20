package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** tool_operation 主表 DO（租户域 + FORCE RLS）。 */
@TableName(value = "tool_operation", autoResultMap = true)
public class ToolOperationDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("operation_id")
    private String operationId;

    @TableField("run_id")
    private String runId;

    @TableField("tool_name")
    private String toolName;

    @TableField("risk_level")
    private String riskLevel;

    @TableField("state")
    private String state;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("request_digest")
    private String requestDigest;

    @TableField("tool_arguments")
    private String toolArguments;

    @TableField("fencing_token")
    private String fencingToken;

    @TableField("reconciliation_source")
    private String reconciliationSource;

    @TableField("manual_disposed_by")
    private String manualDisposedBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("reconciled_at")
    private Instant reconciledAt;

    @TableField("manual_disposed_at")
    private Instant manualDisposedAt;

    // getters & setters
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String v) { this.operationId = v; }
    public String getRunId() { return runId; }
    public void setRunId(String v) { this.runId = v; }
    public String getToolName() { return toolName; }
    public void setToolName(String v) { this.toolName = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String v) { this.requestDigest = v; }
    public String getToolArguments() { return toolArguments; }
    public void setToolArguments(String v) { this.toolArguments = v; }
    public String getFencingToken() { return fencingToken; }
    public void setFencingToken(String v) { this.fencingToken = v; }
    public String getReconciliationSource() { return reconciliationSource; }
    public void setReconciliationSource(String v) { this.reconciliationSource = v; }
    public String getManualDisposedBy() { return manualDisposedBy; }
    public void setManualDisposedBy(String v) { this.manualDisposedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Instant v) { this.reconciledAt = v; }
    public Instant getManualDisposedAt() { return manualDisposedAt; }
    public void setManualDisposedAt(Instant v) { this.manualDisposedAt = v; }
}
