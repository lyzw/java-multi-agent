package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** platform.review_task DO（平台域，走 opsSqlSessionFactory）。 */
@TableName(value = "platform.review_task", autoResultMap = true)
public class ReviewTaskDO {

    @TableField("task_id")
    private String taskId;
    @TableField("trace_id")
    private String traceId;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("run_id")
    private String runId;
    @TableField("source")
    private String source;
    @TableField("priority")
    private String priority;
    @TableField("status")
    private String status;
    @TableField("assigned_to")
    private String assignedTo;
    @TableField("root_cause")
    private String rootCause;
    @TableField("note")
    private String note;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("resolved_at")
    private Instant resolvedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String v) { this.taskId = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getRunId() { return runId; }
    public void setRunId(String v) { this.runId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getPriority() { return priority; }
    public void setPriority(String v) { this.priority = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String v) { this.assignedTo = v; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String v) { this.rootCause = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
}
