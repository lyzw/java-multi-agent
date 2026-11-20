package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 执行意图持久化对象（W2 迁移，对应 {@code cap_execution_intent} 表）。
 *
 * <p>⚠️ 这是 persistence 模块<b>内部</b>的 DO，不与 core 的 {@code ExecutionIntent} record 混用。
 * 适配层 {@code MybatisExecutionIntentRepository} 负责 DO ↔ record 的转换。
 *
 * <p>⚠️ 业务主键是复合键 {@code (tenant_id, invocation_id)}（写入用 ON CONFLICT DO NOTHING），
 * 各 id 列在 SQL 中保留原 {@code ::uuid} cast，DO 侧按文本读写（与 ResultSet.getString 一致）。
 *
 * <p>⚠️ {@code intentState} 是 PG 枚举 {@code cap_intent_state}，SQL 侧保留显式
 * {@code ::cap_intent_state} cast；DO 只存枚举名文本。
 */
@TableName("cap_execution_intent")
public class ExecutionIntentDO {

    @TableField("intent_id")
    private String intentId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("run_id")
    private @Nullable String runId;

    @TableField("invocation_id")
    private String invocationId;

    @TableField("tool_id")
    private String toolId;

    @TableField("tool_version")
    private @Nullable Integer toolVersion;

    @TableField("args_fingerprint")
    private @Nullable String argsFingerprint;

    @TableField("approval_id")
    private @Nullable String approvalId;

    @TableField("intent_state")
    private String intentState;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public @Nullable String getRunId() { return runId; }
    public void setRunId(@Nullable String runId) { this.runId = runId; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }
    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public @Nullable Integer getToolVersion() { return toolVersion; }
    public void setToolVersion(@Nullable Integer toolVersion) { this.toolVersion = toolVersion; }
    public @Nullable String getArgsFingerprint() { return argsFingerprint; }
    public void setArgsFingerprint(@Nullable String argsFingerprint) { this.argsFingerprint = argsFingerprint; }
    public @Nullable String getApprovalId() { return approvalId; }
    public void setApprovalId(@Nullable String approvalId) { this.approvalId = approvalId; }
    public String getIntentState() { return intentState; }
    public void setIntentState(String intentState) { this.intentState = intentState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
