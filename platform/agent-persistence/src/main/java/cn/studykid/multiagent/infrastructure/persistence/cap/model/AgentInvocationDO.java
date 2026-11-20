package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 委派调用记录持久化对象（对应 {@code JdbcAgentInvocationRepository}）。
 *
 * <p>persistence 模块内部 DO，不与 core 的 {@code AgentInvocation} record 混用；
 * 适配层 {@code MybatisAgentInvocationRepository} 负责 DO ↔ record 的转换。
 *
 * <p>⚠️ 写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}（幂等），
 * 终态写入 / 接管提升均为带 CAS 条件的自定义 UPDATE，不调用 BaseMapper.insert/update。
 * {@code @TableId(INPUT)} 仅作元数据标注。
 *
 * <p>⚠️ {@code routing_decision} 为 JSONB 列：用 {@link JsonbTypeHandler} 按 String 读写，
 * SQL 中保留显式 {@code ::jsonb} / {@code ::text} cast（与原实现逐字一致）。
 * 其余 ID 列在 SQL 中逐字保留 {@code ::uuid} cast，DO 侧用 String（与原 record 对齐）。
 */
@TableName("agent_invocation")
public class AgentInvocationDO {

    @TableId(value = "invocation_id", type = IdType.INPUT)
    private String invocationId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("run_id")
    private String runId;

    @TableField("parent_invocation_id")
    private @Nullable String parentInvocationId;

    @TableField("parent_agent_id")
    private String parentAgentId;

    @TableField("parent_agent_version")
    private int parentAgentVersion;

    @TableField("target_agent_id")
    private String targetAgentId;

    @TableField("target_agent_version")
    private int targetAgentVersion;

    @TableField("requested_target_agent_id")
    private @Nullable String requestedTargetAgentId;

    @TableField("status")
    private String status;

    @TableField("depth")
    private int depth;

    @TableField("trace_id")
    private String traceId;

    @TableField("input_digest")
    private @Nullable String inputDigest;

    @TableField("output_digest")
    private @Nullable String outputDigest;

    @TableField("started_at")
    private @Nullable Instant startedAt;

    @TableField("finished_at")
    private @Nullable Instant finishedAt;

    @TableField("reason_code")
    private @Nullable String reasonCode;

    @TableField("fencing_token")
    private long fencingToken;

    @TableField(value = "routing_decision", typeHandler = JsonbTypeHandler.class)
    private @Nullable String routingDecision;

    @TableField("policy_ref")
    private @Nullable String policyRef;

    @TableField("policy_fingerprint")
    private @Nullable String policyFingerprint;

    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public @Nullable String getParentInvocationId() { return parentInvocationId; }
    public void setParentInvocationId(@Nullable String parentInvocationId) { this.parentInvocationId = parentInvocationId; }
    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String parentAgentId) { this.parentAgentId = parentAgentId; }
    public int getParentAgentVersion() { return parentAgentVersion; }
    public void setParentAgentVersion(int parentAgentVersion) { this.parentAgentVersion = parentAgentVersion; }
    public String getTargetAgentId() { return targetAgentId; }
    public void setTargetAgentId(String targetAgentId) { this.targetAgentId = targetAgentId; }
    public int getTargetAgentVersion() { return targetAgentVersion; }
    public void setTargetAgentVersion(int targetAgentVersion) { this.targetAgentVersion = targetAgentVersion; }
    public @Nullable String getRequestedTargetAgentId() { return requestedTargetAgentId; }
    public void setRequestedTargetAgentId(@Nullable String requestedTargetAgentId) { this.requestedTargetAgentId = requestedTargetAgentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public @Nullable String getInputDigest() { return inputDigest; }
    public void setInputDigest(@Nullable String inputDigest) { this.inputDigest = inputDigest; }
    public @Nullable String getOutputDigest() { return outputDigest; }
    public void setOutputDigest(@Nullable String outputDigest) { this.outputDigest = outputDigest; }
    public @Nullable Instant getStartedAt() { return startedAt; }
    public void setStartedAt(@Nullable Instant startedAt) { this.startedAt = startedAt; }
    public @Nullable Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(@Nullable Instant finishedAt) { this.finishedAt = finishedAt; }
    public @Nullable String getReasonCode() { return reasonCode; }
    public void setReasonCode(@Nullable String reasonCode) { this.reasonCode = reasonCode; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
    public @Nullable String getRoutingDecision() { return routingDecision; }
    public void setRoutingDecision(@Nullable String routingDecision) { this.routingDecision = routingDecision; }
    public @Nullable String getPolicyRef() { return policyRef; }
    public void setPolicyRef(@Nullable String policyRef) { this.policyRef = policyRef; }
    public @Nullable String getPolicyFingerprint() { return policyFingerprint; }
    public void setPolicyFingerprint(@Nullable String policyFingerprint) { this.policyFingerprint = policyFingerprint; }
}
