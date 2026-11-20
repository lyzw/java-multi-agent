package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** run 主表 DO（租户域 + FORCE RLS）。 */
@TableName(value = "run", autoResultMap = true)
public class RunDO {
    @TableField("tenant_id")      private String tenantId;
    @TableField("run_id")         private String runId;
    @TableField("snapshot_id")     private String snapshotId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_digest")  private String requestDigest;
    @TableField("state")           private String state;
    @TableField("attempt")         private int attempt;
    @TableField("fencing_token")   private long fencingToken;
    @TableField("input_payload")   private String inputPayload;
    @TableField("correlation_id")  private String correlationId;
    @TableField("deadline_at")     private Instant deadlineAt;
    @TableField("budget_amount")   private int budgetAmount;
    @TableField("requester_id")    private String requesterId;
    @TableField("lease_owner")     private String leaseOwner;
    @TableField("lease_expires_at") private Instant leaseExpiresAt;
    @TableField("error_code")      private String errorCode;
    @TableField("created_at")      private Instant createdAt;
    @TableField("completed_at")    private Instant completedAt;
    @TableField("updated_at")      private Instant updatedAt;
    // getters/setters
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getRunId() { return runId; }
    public void setRunId(String v) { this.runId = v; }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String v) { this.snapshotId = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String v) { this.requestDigest = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int v) { this.attempt = v; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long v) { this.fencingToken = v; }
    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String v) { this.inputPayload = v; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant v) { this.deadlineAt = v; }
    public int getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(int v) { this.budgetAmount = v; }
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String v) { this.requesterId = v; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String v) { this.leaseOwner = v; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant v) { this.leaseExpiresAt = v; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
