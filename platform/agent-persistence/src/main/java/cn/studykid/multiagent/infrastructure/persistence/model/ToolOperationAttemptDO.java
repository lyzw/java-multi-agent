package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** tool_operation_attempt 对账表 DO（attempt 行）。 */
@TableName(value = "tool_operation_attempt", autoResultMap = true)
public class ToolOperationAttemptDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("operation_id")
    private String operationId;

    @TableField("attempt_number")
    private int attemptNumber;

    @TableField("state")
    private String state;

    @TableField("fencing_token")
    private String fencingToken;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("error_class")
    private String errorClass;

    @TableField("error_message")
    private String errorMessage;

    @TableField("result_unknown")
    private boolean resultUnknown;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String v) { this.operationId = v; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int v) { this.attemptNumber = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getFencingToken() { return fencingToken; }
    public void setFencingToken(String v) { this.fencingToken = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String v) { this.errorClass = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public boolean isResultUnknown() { return resultUnknown; }
    public void setResultUnknown(boolean v) { this.resultUnknown = v; }
}
